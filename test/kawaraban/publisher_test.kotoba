(ns kawaraban.publisher-test
  "kawaraban.publisher — MockPublisher tests (ADR-2607110200). No network I/O."
  (:require [clojure.test :refer [deftest is]]
            [kawaraban.publisher :as publisher]))

(deftest test-collection-matches-rad-record
  (is (= "com.etzhayyim.apps.kawaraban" publisher/collection)))

(deftest test-mock-publisher-records-and-returns-uri
  (let [a (atom [])
        p (publisher/mock-publisher a)
        result (publisher/publish! p {":news.article/id" "art.test-1" ":news.article/kind" ":mirror"})]
    (is (= "at://mock/kawaraban/art.test-1" (:uri result)))
    (is (= "mock:art.test-1" (:cid result)))
    (is (= 1 (count @a)))
    (is (= "art.test-1" (get (first @a) ":news.article/id")))))

(deftest test-mock-publisher-default-atom-is-fresh-per-instance
  (let [p1 (publisher/mock-publisher)
        p2 (publisher/mock-publisher)]
    (publisher/publish! p1 {":news.article/id" "a"})
    (is (= 1 (count @(:a p1))))
    (is (= 0 (count @(:a p2))) "each mock-publisher call without an atom arg gets its own atom")))
