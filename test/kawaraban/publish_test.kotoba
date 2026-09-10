(ns kawaraban.publish-test
  "kawaraban.publish glue tests (ADR-2607110200) — MockPublisher only, no network I/O."
  (:require [clojure.test :refer [deftest is]]
            [kawaraban.publish :as publish]
            [kawaraban.publisher :as publisher]))

(def gated-mirror-record
  ;; the exact shape ingest/normalize-record produces for a passed :mirror record.
  {":news.article/id" "art.outlet-example.1"
   ":news.article/kind" ":mirror"
   ":news.article/section" "sec.international"
   ":news.article/outlet" "outlet.example"
   ":news.article/url" "https://example.org/a"
   ":news.article/headline" "Example headline"
   ":news.article/excerpt" "Example excerpt."
   ":news.article/lang" "en"
   ":news.article/as-of" 1749251000
   ":news.article/sourcing" ":representative"
   "_excerpt_truncated" false})

(def projected-actor-event-payload
  ;; the exact shape the actor_project cell's `project` fn returns as :payload on success.
  {"articleId" "art.actor-event.1" "kind" "actor-event" "men" "economy"
   "sourceActor" "did:web:etzhayyim.com:actor:kanae" "sourceTid" "tid-1"
   "headline" "kanae observed a chokepoint" "serverHeldKey" false "speakAs" false "wires" 2})

(deftest test-mirror-record->wire-adds-rkey-drops-truncated-flag
  (let [wire (publish/mirror-record->wire gated-mirror-record)]
    (is (= "art.outlet-example.1" (:rkey wire)))
    (is (not (contains? wire "_excerpt_truncated")))
    (is (= "Example headline" (get wire ":news.article/headline")))))

(deftest test-actor-event-payload->wire-adds-rkey
  (let [wire (publish/actor-event-payload->wire projected-actor-event-payload)]
    (is (= "art.actor-event.1" (:rkey wire)))
    (is (= "economy" (get wire "men")))))

(deftest test-publish-mirror-round-trip
  (let [a (atom [])
        p (publisher/mock-publisher a)
        result (publish/publish-mirror! p gated-mirror-record)]
    (is (= "at://mock/kawaraban/art.outlet-example.1" (:uri result)))
    (is (= 1 (count @a)))
    (is (= "art.outlet-example.1" (:rkey (first @a))))))

(deftest test-publish-actor-event-round-trip
  (let [a (atom [])
        p (publisher/mock-publisher a)
        result (publish/publish-actor-event! p projected-actor-event-payload)]
    (is (= "at://mock/kawaraban/art.actor-event.1" (:uri result)))
    (is (= 1 (count @a)))
    (is (= "economy" (get (first @a) "men")))))
