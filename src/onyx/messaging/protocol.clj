(ns ^:no-doc onyx.messaging.protocol
  (:require [onyx.compression.nippy :refer [compress decompress]]
            [gloss.io :as io]
            [gloss.core :as g])
  (:import [java.util UUID]
           [io.netty.buffer ByteBuf Unpooled UnpooledByteBufAllocator ByteBufAllocator]))

;; RAW NIO IMPL

(defn byte-buffer [size]
  (.heapBuffer ^ByteBufAllocator UnpooledByteBufAllocator/DEFAULT size))

(defn write-uuid [^ByteBuf buf ^UUID uuid]
  (.writeLong buf (.getMostSignificantBits uuid))
  (.writeLong buf (.getLeastSignificantBits uuid)))

(defn take-uuid [^ByteBuf buf]
  (java.util.UUID. (.readLong buf)
                   (.readLong buf)))

(def messages-id ^byte (byte 0))
(def ack-id ^byte (byte 1))
(def completion-id ^byte (byte 2))

(def completion-msg-length 17)
(def ack-msg-length 41)

(defn write-completion-msg [msg] 
  (let [buf ^ByteBuf (byte-buffer 17)] 
    (.writeByte buf completion-id)
    (write-uuid buf (:id msg))))

(defn write-message-msg [^ByteBuf buf {:keys [id acker-id completion-id ack-val message]}]
  (write-uuid buf id)
  (write-uuid buf acker-id)
  (write-uuid buf completion-id)
  (.writeLong buf ack-val)
  (.writeInt buf (int (count message)))
  (doall
    (map (fn [b]
           (.writeByte buf b))
         message)))

(defn write-messages-msg [{:keys [messages]}] 
  (let [compressed-messages (map (fn [msg]
                                   (update-in msg [:message] compress))
                                 messages)
        buf-size (+ 1 ; message type 
                    4 ; message count
                    ; id, acker-id, completion-id, ack-val, nippy byte count
                    (* (count messages) 56)
                    ; fressian compressed segments
                    (apply + (map (comp count :message) compressed-messages)))
        buf ^ByteBuf (byte-buffer buf-size)] 
    ; Might have to write total length here so we can tell whether the whole message is in
    (.writeByte buf messages-id) ; message type
    ; need the buf-size so we know whether the full payload has arrived
    (.writeInt buf (int buf-size))
    (.writeInt buf (int (count compressed-messages))) ; number of messages
    (doall
      (map (partial write-message-msg buf)
           compressed-messages))
    buf))

(defn write-ack-msg [msg] 
  (let [buf ^ByteBuf (byte-buffer ack-msg-length)] 
    (.writeByte buf 1)
    (write-uuid buf (:id msg))
    (write-uuid buf (:completion-id msg))
    (.writeLong buf (:ack-val msg))))

(defn write-msg [msg]
  (let [t ^byte (:type msg)] 
    (cond 
      (= t messages-id) (write-messages-msg msg)
      (= t ack-id) (write-ack-msg msg)
      (= t completion-id) (write-completion-msg msg))))

(defn read-message-buf [^ByteBuf buf]
  (let [id (take-uuid buf)
        acker-id (take-uuid buf)
        completion-id (take-uuid buf)
        ack-val (.readLong buf)
        message-size (.readInt buf)
        ; may be slow
        message (decompress (into-array Byte/TYPE (repeatedly message-size #(.readByte buf))))]
    {:id id 
     :acker-id acker-id 
     :completion-id completion-id 
     :ack-val ack-val 
     :message message}))

(defn read-messages-buf [^ByteBuf buf]
  (let [message-count (.readInt buf)]
    (repeatedly message-count #(read-message-buf buf))))

(defn read-ack-buf [^ByteBuf buf]
  {:type ack-id
   :id (take-uuid buf)
   :completion-id (take-uuid buf)
   :ack-val (.readLong buf)})

(defn read-completion-buf [^ByteBuf buf]
  {:type completion-id 
   :id (take-uuid buf)})

(defn read-buf [^ByteBuf buf]
  (let [msg-type (.readByte buf)
        n-received (.writerIndex buf)] 
    (case msg-type
      0 (if (<= (.readInt buf) n-received)
          {:type messages-id :messages (read-messages-buf buf)})
      1 (if (<= ack-msg-length n-received)
          (read-ack-buf buf))
      2 (if (<= completion-msg-length n-received)
          (read-completion-buf buf)))))

;;; GLOSS IMPL
;;;;;; USED BY ALEPH MESSAGING

(defn longs->uuid [[lsbs msbs]]
  (java.util.UUID. msbs lsbs))

(defn uuid->longs [^UUID uuid]
  (vector (.getLeastSignificantBits uuid)
          (.getMostSignificantBits uuid)))

(def char-frame
  (g/compile-frame (g/repeated :byte :prefix :none)))

(def send-frame
  (g/compile-frame 
    {:type :byte
     :messages (g/finite-frame :int32 (g/repeated 
                                        {:id [:int64 :int64]
                                         :acker-id [:int64 :int64]
                                         :completion-id [:int64 :int64]
                                         :message (g/finite-frame :int32 (g/repeated :byte :prefix :none))
                                         :ack-val :int64}                        
                                        :prefix :none))}))

(def ack-frame
  (g/compile-frame {:type :byte
                    :id [:int64 :int64]
                    :completion-id [:int64 :int64]
                    :ack-val :int64}))


(def completion-frame
  (g/compile-frame {:type :byte
                    :id [:int64 :int64]}))

(def send-id (byte 0))
(def ack-id (byte 1))
(def completion-id (byte 2))

(g/defcodec onyx-codec
  (g/compile-frame
   (g/header
    :ubyte
    (fn [header-byte]
      (cond (= header-byte send-id)
            send-frame
            (= header-byte ack-id)
            ack-frame 
            (= header-byte completion-id)
            completion-frame))
    :type)))

(defn send-messages->frame [messages]
  {:type send-id
   :messages (map (fn [msg] 
                    (-> msg 
                        (update-in [:id] uuid->longs)
                        (update-in [:acker-id] uuid->longs)
                        (update-in [:completion-id] uuid->longs)
                        (update-in [:message] compress)))
                  messages)})

(defn frame->send-messages [frame]
  (update-in frame
             [:messages]
             (fn [messages] 
               (map (fn [msg]
                      (-> msg 
                          (update-in [:id] longs->uuid)
                          (update-in [:acker-id] longs->uuid)
                          (update-in [:completion-id] longs->uuid)
                          (update-in [:message] (comp decompress
                                                      (partial into-array Byte/TYPE)))))
                    messages))))

(defn ack-msg->frame [msg]
  (-> msg 
      (update-in [:id] uuid->longs)
      (update-in [:completion-id] uuid->longs)
      (assoc :type ack-id)))

(defn frame->ack-message [msg]
  (-> msg 
      (update-in [:id] longs->uuid)
      (update-in [:completion-id] longs->uuid)))

(defn completion-msg->frame [msg]
  (-> msg
      (update-in [:id] uuid->longs)
      (assoc :type completion-id)))

(defn frame->completion-msg [msg]
  (-> msg 
      (update-in [:id] longs->uuid)))

(defn frame->msg [{:keys [type] :as frame}]
  (cond (= send-id type) 
        (frame->send-messages frame)
        (= ack-id type)
        (frame->ack-message frame)
        (= completion-id type) 
        (frame->completion-msg frame)))

(def codec-protocol
  (g/compile-frame
    onyx-codec
    identity
    frame->msg))
