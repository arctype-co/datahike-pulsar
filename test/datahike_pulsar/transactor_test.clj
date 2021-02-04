(ns datahike-pulsar.transactor-test
  (:require
    [clojure.test :refer :all]
    [datahike.api :as d]
    [datahike-pulsar.transactor])
  (:import (java.util UUID)))

(def config {:transactor
             {:backend :pulsar
              :consumer-poll-timeout-ms 100
              :transaction-rtt-timeout-ms 10000
              :subscription-id (-> (UUID/randomUUID) (.toString))
              :pulsar
              {:url "pulsar://localhost:6650"
               :topic (str "datahike-test-" (UUID/randomUUID))
               :token "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6Ik5rRXdSVVU1TUVOQlJrWTJNalEzTVRZek9FVkZRVVUyT0RNME5qUkRRVEU1T1VNMU16STVPUSJ9.eyJodHRwczovL3N0cmVhbW5hdGl2ZS5pby91c2VybmFtZSI6ImRhdGFoaWtlLXRlc3RpbmctMkBwb3NlaWRvbi1jbG91ZC5hdXRoLnN0cmVhbW5hdGl2ZS5jbG91ZCIsImlzcyI6Imh0dHBzOi8vYXV0aC5zdHJlYW1uYXRpdmUuY2xvdWQvIiwic3ViIjoiOHVKS2RoM2ZSOTdvWE9KaTQ5ZE9EM0RwVDFhbXBVUDJAY2xpZW50cyIsImF1ZCI6InVybjpzbjpwdWxzYXI6cG9zZWlkb24tY2xvdWQ6ZGV2IiwiaWF0IjoxNjEyMzk0NTIxLCJleHAiOjE2MTI5OTkzMjEsImF6cCI6Ijh1SktkaDNmUjk3b1hPSmk0OWRPRDNEcFQxYW1wVVAyIiwic2NvcGUiOiJhZG1pbiIsImd0eSI6ImNsaWVudC1jcmVkZW50aWFscyIsInBlcm1pc3Npb25zIjpbImFkbWluIl19.e2tQByVsHeX6uj876gsVMErvhm8sKiDxbPV-jhg8E8H9TdwpGJ_IOnWa5psbCa10R3MbkSLMpmpII56Hlc1ANKt8k6PJk1xEkaobRSKkaMD_MXBzftF9Cooi9vXNioK5WcL88loko7TK9IL37X1KpnA9FgsIOduiygOHUpjxIsdUC8DySEGx-Ec_3GaKzf4v22o7RVvFgrE_FtDDsU7I-PN0ZIucIoGnY5DU9TrKJOPplHH5YIFaWUN1FAE569Sr5-jkwc_fQWj9-ByDWAMjs8TFBAZoyqDAsP-k9DEbIwPLXDGUAB2aoi_AuFLBbwAJ8pohWV8kN69UeX-22dL4xA"}}})

(defn pulsar-test-fixture [f]
  (d/delete-database config)
  (d/create-database config)
  (def conn (d/connect config))
  ;; the first transaction will be the schema we are using
  (d/transact conn [{:db/ident :name
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}
                    {:db/ident :age
                     :db/valueType :db.type/long
                     :db/cardinality :db.cardinality/one}])

  ;; lets add some data and wait for the transaction
  (d/transact conn [{:name "Alice", :age 20}
                    {:name "Bob", :age 30}
                    {:name "Charlie", :age 40}
                    {:age 15}])

  (f))

(use-fixtures :once pulsar-test-fixture)

(deftest test-pulsar-transactor
  ;; search the data
  (is (= #{[3 "Alice" 20] [4 "Bob" 30] [5 "Charlie" 40]}
         (d/q '[:find ?e ?n ?a
                :where
                [?e :name ?n]
                [?e :age ?a]]
              @conn)))

  ;; add new entity data using a hash map
  (d/transact conn {:tx-data [{:db/id 3 :age 25}]})

  ;; if you want to work with queries like in
  ;; https://grishaev.me/en/datomic-query/,
  ;; you may use a hashmap
  (is (= #{[5 "Charlie" 40] [4 "Bob" 30] [3 "Alice" 25]}
         (d/q {:query '{:find [?e ?n ?a]
                        :where [[?e :name ?n]
                                [?e :age ?a]]}
               :args [@conn]})))

  ;; query the history of the data
  (is (= #{[20] [25]}
         (d/q '[:find ?a
                :where
                [?e :name "Alice"]
                [?e :age ?a]]
              (d/history @conn))))

  ;; you might need to release the connection, e.g. for leveldb
  (is (= nil (d/release conn)))

  ;; database should exist
  (is (= true (d/database-exists?)))

  ;; clean up the database if it is not needed any more
  (d/delete-database)

  ;; database should exist
  (is (= false (d/database-exists?))))

