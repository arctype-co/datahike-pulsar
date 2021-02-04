(defproject arctype/datahike-pulsar "0.1.0-SNAPSHOT"
  :description "Datahike with a Bookkeper transactor."
  :license {:name "Eclipse"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/arctype/datahike-pulsar"

  :repositories [["twitter" "https://maven.twttr.com"]]

  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [com.taoensso/nippy "3.1.1"]
                 [io.replikativ/datahike "0.3.4-SNAPSHOT"]
                 [io.replikativ/superv.async "0.2.11"]
                 [org.apache.pulsar/pulsar-client "2.7.0"]]

  :aliases {"test-clj"     ["run" "-m" "datahike-pulsar.test/transactor_test"]
            "test-all"     ["do" ["clean"] ["test-clj"]]}

  :profiles {:dev {:source-paths ["test"]
                   :dependencies [[org.clojure/tools.nrepl     "0.2.13"]
                                  [org.clojure/tools.namespace "0.3.1"]
                                  [org.slf4j/slf4j-simple "1.7.5"]]}})