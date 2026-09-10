(ns kawaraban.cells.test-state-machine
  "State-machine tests for kawaraban 瓦版 cells (R0). 1:1 port of cells/test_state_machines.py
  (ADR-2606061900). outlet_ingest (G4/G5) · article_mirror (G1/G4/G9) · section_route (G2/G11) ·
  actor_project (G7/G9/G11, the medium) · issue_compose (G2/G7/G8/G10) · fulltext_cache (G4
  PRIVATE buffer, ADR-2607010930); .solve() raises at R0."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [kawaraban.cells.outlet-ingest.state-machine :as oi]
            [kawaraban.cells.article-mirror.state-machine :as am]
            [kawaraban.cells.section-route.state-machine :as sr]
            [kawaraban.cells.actor-project.state-machine :as ap]
            [kawaraban.cells.issue-compose.state-machine :as ic]
            [kawaraban.cells.fulltext-cache.state-machine :as fc]))

(defn- p [r] (get-in r ["cell_state" "phase"]))
(defn- refusal [r] (get-in r ["cell_state" "refusal"]))

;; ── outlet_ingest ──
(deftest test-outlet-ingest-ok
  (is (= "ingested" (p (oi/ingest {"outlet_id" "outlet.nhk" "name" "NHK" "kind" "public-broadcaster" "access" "open"})))))

(deftest test-outlet-ingest-refuses-paywall
  (let [r (oi/ingest {"outlet_id" "o" "name" "X" "access" "paywall"})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G4"))))

;; ── article_mirror ──
(deftest test-article-mirror-ok
  (is (= "mirrored" (p (am/mirror {"article_id" "a" "section" "sec.international" "outlet" "outlet.ap"
                                   "url" "https://apnews.com/x" "headline" "h" "excerpt" "short"})))))

(deftest test-article-mirror-refuses-verdict
  (let [r (am/mirror {"article_id" "a" "outlet" "o" "url" "u" "verdict" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G1"))))

(deftest test-article-mirror-refuses-full-text
  (let [r (am/mirror {"article_id" "a" "outlet" "o" "url" "u" "full_text" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G4"))))

(deftest test-article-mirror-refuses-speak-as
  (let [r (am/mirror {"article_id" "a" "outlet" "o" "url" "u" "speak_as" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G9"))))

(deftest test-article-mirror-refuses-missing-url
  (let [r (am/mirror {"article_id" "a" "outlet" "o"})]
    (is (= "refused" (p r))) (is (str/includes? (str/lower (refusal r)) "url"))))

;; ── section_route ──
(deftest test-section-route-ok
  (is (= "routed" (p (sr/route {"article_id" "a" "men" "economy" "rank_signals" ["recency" "source-diversity"]
                                "mentions" [{"target" "did:web:...:kanjo" "targetKind" "actor" "role" "mentioned"}]})))))

(deftest test-section-route-refuses-paid-rank
  (let [r (sr/route {"article_id" "a" "men" "front" "rank_signals" ["paid-placement"]})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G2"))))

(deftest test-section-route-refuses-bad-role
  (let [r (sr/route {"article_id" "a" "men" "front" "mentions" [{"target" "x" "role" "accused"}]})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G11"))))

;; ── actor_project (the medium) ──
(deftest test-actor-project-ok
  (is (= "projected" (p (ap/project {"article_id" "a" "source_actor" "did:web:...:danjo" "source_tid" "danjo:obs:1"
                                     "men" "politics" "member_signed" true "server_held_key" false})))))

(deftest test-actor-project-refuses-server-key
  (let [r (ap/project {"article_id" "a" "source_actor" "did" "source_tid" "t" "member_signed" true "server_held_key" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G7"))))

(deftest test-actor-project-refuses-unsigned
  (let [r (ap/project {"article_id" "a" "source_actor" "did" "source_tid" "t" "member_signed" false})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G7"))))

(deftest test-actor-project-refuses-missing-provenance
  (let [r (ap/project {"article_id" "a" "source_actor" "did" "member_signed" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G11"))))

;; ── issue_compose ──
(deftest test-issue-compose-unpublished-by-default
  (let [r (ic/compose {"issue_id" "i" "rank_signals" ["recency" "actor-relevance"] "lead_ids" ["a" "b"]})]
    (is (= "composed" (p r)))
    (is (= false (get-in r ["cell_state" "payload" "published"])))))

(deftest test-issue-compose-publishes-only-when-signed-and-gated
  (is (= true (get-in (ic/compose {"issue_id" "i" "member_signed" true "operator_gated" true})
                      ["cell_state" "payload" "published"]))))

(deftest test-issue-compose-refuses-final
  (let [r (ic/compose {"issue_id" "i" "final" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G10"))))

(deftest test-issue-compose-refuses-paid-rank
  (let [r (ic/compose {"issue_id" "i" "rank_signals" ["engagement"]})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G2"))))

(deftest test-issue-compose-refuses-server-key
  (let [r (ic/compose {"issue_id" "i" "server_held_key" true})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G7"))))

;; ── .solve() R0 guards ──
(deftest test-all-cells-solve-raises-at-r0
  (doseq [solve [oi/solve am/solve sr/solve ap/solve ic/solve fc/solve]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"R0 scaffold" (solve {})))))

;; ── fulltext_cache (PRIVATE G4 buffer, ADR-2607010930) ──
(deftest test-fulltext-cache-ok-and-stays-private
  (let [r (fc/cache {"article_id" "a" "outlet" "outlet.nhk" "url" "https://nhk.or.jp/x"
                     "access" "open" "body" "the body" "fetched_at" "2026-07-01T09:00Z"})]
    (is (= "cached" (p r)))
    (is (= false (get-in r ["cell_state" "payload" "publicProjection" "fullText"])))
    (is (= true (get-in r ["cell_state" "payload" "private"])))))

(deftest test-fulltext-cache-refuses-paywall
  (let [r (fc/cache {"article_id" "a" "outlet" "o" "url" "u" "access" "paywall" "body" "b"})]
    (is (= "refused" (p r))) (is (str/includes? (refusal r) "G4-access"))))

(deftest test-fulltext-cache-refuses-missing-provenance
  (let [r (fc/cache {"article_id" "a" "url" "u" "access" "open" "body" "b"})]
    (is (= "refused" (p r))) (is (str/includes? (str/lower (refusal r)) "outlet"))))
