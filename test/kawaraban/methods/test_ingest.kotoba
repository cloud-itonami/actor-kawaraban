(ns kawaraban.methods.test-ingest
  "kawaraban — tests for the offline outlet normalizer membrane (ingest.cljc).
  1:1 port of test_ingest.py. Refusals (Python IngestRefused/ValueError) become ex-info,
  caught here as clojure.lang.ExceptionInfo (assertRaises analogue)."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [kawaraban.methods.ingest :as ingest]))

(defn- batch-path [] (clojure.java.io/file "wire/ingest/sample-batch.json"))

(defn- records []
  (ingest/parse-json (slurp (batch-path))))

(deftest test-batch-accepts-clean-refuses-violations
  (let [[ok refused] (ingest/normalize-batch (records))]
    (is (= 2 (count ok)) (mapv #(get % ":news.article/id") ok))
    (is (= 3 (count refused)) refused)
    (let [blob (str/join " " refused)]
      (is (and (str/includes? blob "G4") (str/includes? blob "G1"))))))  ;; body/paywall (G4) + verdict (G1)

(deftest test-refuses-full-body
  (try
    (ingest/normalize-record {"outlet" "o" "url" "u" "body" "the whole thing"})
    (is false "expected G4 refusal")
    (catch clojure.lang.ExceptionInfo e
      (is (str/includes? (.getMessage e) "G4")))))

(deftest test-refuses-paywall
  (try
    (ingest/normalize-record {"outlet" "o" "url" "u" "access" "paywall"})
    (is false "expected G4 refusal")
    (catch clojure.lang.ExceptionInfo e
      (is (str/includes? (.getMessage e) "G4")))))

(deftest test-refuses-verdict
  (try
    (ingest/normalize-record {"outlet" "o" "url" "u" "verdict" true})
    (is false "expected G1 refusal")
    (catch clojure.lang.ExceptionInfo e
      (is (str/includes? (.getMessage e) "G1")))))

(deftest test-requires-url
  (try
    (ingest/normalize-record {"outlet" "o" "headline" "h"})
    (is false "expected G4/G5 url refusal")
    (catch clojure.lang.ExceptionInfo e
      (is (str/includes? (str/lower (.getMessage e)) "url")))))

(deftest test-excerpt-truncated-to-280
  (let [rec (ingest/normalize-record {"outlet" "o" "url" "u" "excerpt" (apply str (repeat 500 "x"))})]
    (is (= 280 (count (get rec ":news.article/excerpt"))))
    (is (= true (get rec "_excerpt_truncated")))))

(deftest test-normalized-is-mirror-kind
  (let [rec (ingest/normalize-record {"outlet" "outlet.nhk" "url" "https://x" "headline" "h" "asOf" 5})]
    (is (= ":mirror" (get rec ":news.article/kind")))
    (is (= ":representative" (get rec ":news.article/sourcing")))))

(deftest test-live-refused-at-r0
  (is (= false (ingest/live-allowed))))  ;; no operator gate env set
