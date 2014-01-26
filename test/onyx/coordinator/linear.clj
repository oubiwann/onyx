(ns onyx.coordinator.linear
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan <!! >!! tap timeout]]
            [com.stuartsierra.component :as component]
            [simulant.sim :as sim]
            [simulant.util :as u]
            [datomic.api :as d]
            [onyx.system :as s]
            [onyx.coordinator.extensions :as extensions]
            [onyx.coordinator.log.datomic :as datomic]
            [onyx.coordinator.sim-test-utils :as sim-utils]
            [incanter.core :refer [view]]
            [incanter.charts :refer [line-chart]]))

(def cluster (atom {}))

(defn create-birth [executor t]
  [[{:db/id (d/tempid :test)
     :agent/_actions (u/e executor)
     :action/atTime t
     :action/type :action.type/register-peer}]])

(defn create-death [executor t]
  [[{:db/id (d/tempid :test)
     :agent/_actions (u/e executor)
     :action/atTime t
     :action/type :action.type/unregister-peer}]])

(defn generate-linear-scaling-data [test executor]
  (let [model (-> test :model/_tests first)
        limit (:test/duration test)
        rate (:model/peer-rate model)
        peers (:model/peek-peers model)
        births (mapcat (partial create-birth executor)
                       (range 0 (* peers rate) rate))
        deaths (mapcat (partial create-death executor)
                       (range (- limit (* peers rate)) limit rate))]
    (concat births deaths)))

(defn create-linear-cluster-test [conn model test]
  (u/require-keys test :db/id :test/duration)
  (-> @(d/transact conn [(assoc test
                           :test/type :test.type/linear-cluster
                           :model/_tests (u/e model))])
      (u/tx-ent (:db/id test))))

(defn create-executor [conn test]
  (let [tid (d/tempid :test)
        result @(d/transact conn
                            [{:db/id tid
                              :agent/type :agent.type/executor
                              :test/_agents (u/e test)}])]
    (d/resolve-tempid (d/db conn) (:tempids result) tid)))

(defmethod sim/create-test :model.type/linear-cluster
  [conn model test]
  (let [test (create-linear-cluster-test conn model test)
        executor (create-executor conn test)]
    (u/transact-batch conn (generate-linear-scaling-data test executor) 1000)
    (d/entity (d/db conn) (u/e test))))

(defmethod sim/create-sim :test.type/linear-cluster
  [sim-conn test sim]
  (-> @(d/transact sim-conn (sim/construct-basic-sim test sim))
      (u/tx-ent (:db/id sim))))

(def sim-uri (str "datomic:mem://" (d/squuid)))

(def sim-conn (sim-utils/reset-conn sim-uri))

(sim-utils/load-schema sim-conn "simulant/schema.edn")

(sim-utils/load-schema sim-conn "simulant/coordinator-sim.edn")

(def system (s/onyx-system {:sync :zookeeper :queue :hornetq :eviction-delay 500000}))

(def components (alter-var-root #'system component/start))

(def coordinator (:coordinator components))

(def log (:log components))

(def tx-queue (d/tx-report-queue (:conn log)))

(def offer-spy (chan 10000))

(def catalog
  [{:onyx/name :in
    :onyx/direction :input
    :onyx/consumption :sequential
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name "in-queue"}
   {:onyx/name :inc
    :onyx/type :transformer
    :onyx/consumption :sequential}
   {:onyx/name :out
    :onyx/direction :output
    :onyx/consumption :sequential
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name "out-queue"}])

(def workflow {:in {:inc :out}})

(def n-jobs 9)

(def tasks-per-job 3)

(tap (:offer-mult coordinator) offer-spy)

(doseq [_ (range n-jobs)]
  (>!! (:planning-ch-head coordinator) {:catalog catalog :workflow workflow}))

(doseq [_ (range n-jobs)]
  (<!! offer-spy))

(def linear-model-id (d/tempid :model))

(def linear-cluster-model-data
  [{:db/id linear-model-id
    :model/type :model.type/linear-cluster
    :model/n-peers 5
    :model/peek-peers 10
    :model/peer-rate 200
    :model/mean-ack-time 250
    :model/mean-completion-time 500}])

(def linear-cluster-model
  (-> @(d/transact sim-conn linear-cluster-model-data)
      (u/tx-ent linear-model-id)))

(defmethod sim/perform-action :action.type/register-peer
  [action process]
  (let [peer (extensions/create (:sync components) :peer)]
    (swap! cluster assoc peer
           (sim-utils/create-peer
            linear-cluster-model
            components peer))))

(defmethod sim/perform-action :action.type/unregister-peer
  [action process]
  (let [cluster-val @cluster
        n (count cluster-val)
        victim (nth (keys cluster-val) (rand-int n))]
    (let [pulse (extensions/read-place (:sync components) victim)]
      (prn "~>" victim)
      (extensions/delete (:sync components) (:pulse victim))
      (future-cancel (get cluster-val victim))
      (swap! cluster dissoc victim))))

(sim-utils/create-peers! linear-cluster-model components cluster)

(def linear-cluster-test
  (sim/create-test sim-conn
                   linear-cluster-model
                   {:db/id (d/tempid :test)
                    :test/duration 15000}))

(def linear-cluster-sim
  (sim/create-sim sim-conn
                  linear-cluster-test
                  {:db/id (d/tempid :sim)
                   :sim/systemURI (str "datomic:mem://" (d/squuid))
                   :sim/processCount 1}))

(sim/create-fixed-clock sim-conn linear-cluster-sim {:clock/multiplier 1})

(sim/create-action-log sim-conn linear-cluster-sim)

(time (mapv (fn [prun] @(:runner prun))
            (->> #(sim/run-sim-process sim-uri (:db/id linear-cluster-sim))
                 (repeatedly (:sim/processCount linear-cluster-sim))
                 (into []))))

(testing "All tasks complete"
  (loop []
    (let [db (:db-after (.take tx-queue))
          query '[:find (count ?task) :where [?task :task/complete? true]]
          result (ffirst (d/q query db))]
      (prn result)
      (when-not (= result (* n-jobs tasks-per-job))
        (recur)))))

(def sim-db (d/db sim-conn))

(def result-db (d/db (:conn log)))

(deftest test-small-cluster-few-jobs
  (testing "No tasks are left incomplete"
    (sim-utils/task-completeness result-db))

  (testing "No sequential task ever had more than 1 peer"
    (sim-utils/task-safety result-db)))

(def insts
  (->> (-> '[:find ?inst :where
             [_ :peer/status _ ?tx]
             [?tx :db/txInstant ?inst]]
           (d/q (d/history result-db)))
       (map first)
       (sort)))

(def dt-and-peers
  (map (fn [tx]
         (let [db (d/as-of result-db tx)]
           (->> (d/q '[:find (count ?p) :where [?p :peer/status]] db)
                (map first)
                (concat [tx]))))
       insts))

(view (line-chart
       (map first dt-and-peers)
       (map second dt-and-peers)
       :x-label "Time"
       :y-label "Peers"))

(alter-var-root #'system component/stop)

