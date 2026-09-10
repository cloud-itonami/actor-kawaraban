(ns kawaraban.run-live-ingest-test
  "Pure-fn tests for the high-water-mark logic (ADR-2607110200 addendum 2). No network I/O
  -- run-outlet!/run-all!'s live-fetch/aozora-publish integration is exercised manually
  against the real feeds/PDS, not here (same discipline as the rest of this repo's test
  suite: gates and pure transforms are unit-tested, real I/O edges are not mocked into a
  false sense of coverage)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kawaraban.run-live-ingest :as sut]))

(defn- record [as-of] {":news.article/id" (str "art.test." as-of) ":news.article/as-of" as-of})

(deftest test-run-outlet-filters-to-strictly-newer-than-mark
  (testing "a record at or below the mark is not \"new\""
    (is (= [] (#'sut/new-since [(record 100)] 100)))
    (is (= [(record 101)] (#'sut/new-since [(record 100) (record 101)] 100)))))

(deftest test-max-as-of-never-goes-backwards
  (is (= 100 (#'sut/max-as-of [(record 50)] 100)))
  (is (= 150 (#'sut/max-as-of [(record 50) (record 150)] 100)))
  (is (= 0 (#'sut/max-as-of [] 0))))

(deftest test-last-seen-round-trips-through-a-file
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/kawaraban-last-seen-test-"
                  (System/nanoTime) ".edn")]
    (try
      (is (= {} (sut/load-last-seen tmp)) "missing file reads as empty map, not an error")
      (sut/save-last-seen! tmp {"outlet.bbc-world" 123 "outlet.dw" 456})
      (is (= {"outlet.bbc-world" 123 "outlet.dw" 456} (sut/load-last-seen tmp)))
      (finally (io/delete-file tmp true)))))
