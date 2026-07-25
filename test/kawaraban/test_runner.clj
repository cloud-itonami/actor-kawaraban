(ns kawaraban.test-runner
  (:require [clojure.test :as t]
            [kawaraban.cacao-test]
            [kawaraban.mirror-actor-test]
            [kawaraban.publish-test]
            [kawaraban.publisher-test]
            [kawaraban.run-live-ingest-test]
            [kawaraban.store-test]
            [kawaraban.cells.test-state-machine]
            [kawaraban.methods.test-analyze]
            [kawaraban.methods.test-charter-gates]
            [kawaraban.methods.test-ingest]
            [kawaraban.methods.test-live-fetch]
            [kawaraban.methods.test-route]))

(def suites
  '[kawaraban.cacao-test
    kawaraban.mirror-actor-test
    kawaraban.publish-test
    kawaraban.publisher-test
    kawaraban.run-live-ingest-test
    kawaraban.store-test
    kawaraban.cells.test-state-machine
    kawaraban.methods.test-analyze
    kawaraban.methods.test-charter-gates
    kawaraban.methods.test-ingest
    kawaraban.methods.test-live-fetch
    kawaraban.methods.test-route])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (when (pos? (+ fail error))
      (System/exit 1))))
