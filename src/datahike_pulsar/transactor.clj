(ns datahike-pulsar.transactor
  (:require
    [clojure.core.async :refer [>!! alt! chan promise-chan close! go timeout]]
    [datahike.transactor :refer [create-transactor PTransactor]]
    [superv.async :refer [<?? thread-try S]]
    [taoensso.timbre :as log]
    [taoensso.nippy :as nippy])
  (:import
    (java.nio ByteBuffer)
    (org.apache.pulsar.client.api PulsarClient Producer Consumer Message MessageId PulsarClientException$AlreadyClosedException AuthenticationFactory SubscriptionType SubscriptionInitialPosition)
    (java.util.function Function BiFunction)
    (java.util.concurrent TimeUnit TimeoutException)))

(defn- wrap-message-id
  [^MessageId message-id]
  (-> message-id (.toByteArray) (ByteBuffer/wrap)))

(def ^:private log-ack-failure
  (reify Function
    (apply [_ error]
      (log/warn error "Failed to acknowledge Pulsar message"))))

(defn create-rx-thread
  [connection ^Consumer consumer callbacks consumer-poll-timeout-ms update-and-flush-db]
  (thread-try
    S
    (let [resolve-fn (memoize resolve)]
      (try
        (loop []
          (when-let [^Message msg (.receive consumer consumer-poll-timeout-ms TimeUnit/MILLISECONDS)]
            (let [message-id (wrap-message-id (.getMessageId msg))
                  {:keys [tx-data tx-meta tx-fn]} (-> (.getData msg) (nippy/thaw))
                  _ (log/debug "Pulsar message received" {:tx-data tx-data
                                                          :tx-meta tx-meta
                                                          :tx-fn tx-fn})
                  update-fn (resolve-fn tx-fn)
                  tx-report (try (update-and-flush-db connection tx-data tx-meta update-fn)
                                 ; Only catch ExceptionInfo here (intentionally rejected transactions).
                                 ; Any other exceptions should crash the transactor and signal the supervisor.
                                 (catch clojure.lang.ExceptionInfo e e))
                  ack (.acknowledgeCumulativeAsync consumer (.getMessageId msg))
                  callback (dosync
                             (let [cb (get @callbacks message-id)]
                               (alter callbacks dissoc message-id)
                               cb))]
              (.exceptionally ack log-ack-failure)
              (if (some? callback)
                (do
                  (log/debug "Callback matched")
                  (>!! callback tx-report))
                (log/debug "Callback not matched for message id:" message-id (pr-str @callbacks)))))
          (recur))
        (catch PulsarClientException$AlreadyClosedException _
          (log/debug "Pulsar transactor rx thread gracefully closed")))
      {})))

(defn- create-pulsar-client
  [pulsar-config]
  (cond-> (PulsarClient/builder)

          (:url pulsar-config)
          (.serviceUrl (:url pulsar-config))

          (:token pulsar-config)
          (.authentication (AuthenticationFactory/token ^String (:token pulsar-config)))

          (:auth-factory pulsar-config)
          (.authentication (:auth-factory pulsar-config))

          true (.build)))

(defrecord PulsarTransactor
  [^PulsarClient pulsar ^Producer producer ^Consumer consumer callbacks rx-thread transaction-rtt-timeout-ms]
  PTransactor
  (send-transaction! [_ tx-data tx-meta tx-fn]
    (go
      (let [p (promise-chan)
            timeout (timeout transaction-rtt-timeout-ms)
            tx-envelope
            {:tx-fn tx-fn
             :tx-meta tx-meta
             :tx-data tx-data}
            buf (nippy/freeze tx-envelope)]
        (doto (.sendAsync producer buf)
          (.handle
            (reify BiFunction
              (apply [_ message-id error]
                (if (some? error)
                  (do
                    (log/warn error "Transactor failed to send pulsar message")
                    (>!! p error))
                  (do
                    (log/debug "Pulsar message sent")
                    (dosync
                      (alter callbacks assoc (wrap-message-id message-id) p))))))))
        (alt!
          p ([result] result)
          timeout (TimeoutException. "Transaction timeout")))))

  (shutdown [_]
    (thread-try
      S
      (.close producer)
      (.close consumer)
      (.close pulsar)
      (<?? S rx-thread))))

(def ^:private default-config
  {:consumer-poll-timeout-ms 1000 ; Timeout for a consumer poll. Effects how fast a consumer can shut down.
   ; A transaction must travel through the producer and be consumed on the
   ; other end in order to receive a tx-report callback.
   :transaction-rtt-timeout-ms 10000 ; Timeout for a transaction round trip.
   })

(defmethod create-transactor :pulsar
  [config connection update-and-flush-db]
  (let [{pulsar-config :pulsar
         subscription-id :subscription-id
         consumer-poll-timeout-ms :consumer-poll-timeout-ms
         transaction-rtt-timeout-ms :transaction-rtt-timeout-ms} (merge default-config config)
        ^PulsarClient pulsar (create-pulsar-client pulsar-config)
        producer (-> pulsar (.newProducer) (.topic (:topic pulsar-config)) (.create))
        ^Consumer consumer (-> pulsar
                               (.newConsumer) (.topic (into-array String [(:topic pulsar-config)]))
                               (.subscriptionName subscription-id) 
                               (.subscriptionType SubscriptionType/Exclusive)
                               (.subscriptionInitialPosition SubscriptionInitialPosition/Earliest)
                               (.subscribe))
        callbacks (ref {})
        rx-thread (create-rx-thread connection consumer callbacks
                                    consumer-poll-timeout-ms
                                    update-and-flush-db)]
    (map->PulsarTransactor
      {:pulsar pulsar
       :callbacks callbacks
       :producer producer
       :consumer consumer
       :subscription-id subscription-id
       :rx-thread rx-thread
       :transaction-rtt-timeout-ms transaction-rtt-timeout-ms})))
