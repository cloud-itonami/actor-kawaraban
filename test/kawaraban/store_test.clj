(ns kawaraban.store-test
  "store_test.clj — kawaraban.store（ローカル datom アーカイブ）+ kawaraban.query
  （DataScript datalog）の契約テスト（com-junkawasaki/root ADR-2607252600）。

  実 feed も実 PDS も触らない: `ingest/normalize-batch` に食わせる record は
  この場で組み立て、書き込み先は毎回一時ディレクトリ。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [kawaraban.methods.ingest :as ingest]
            [kawaraban.methods.live-fetch :as live-fetch]
            [kawaraban.query :as query]
            [kawaraban.store :as store]))

(defn- tmp-dir! []
  (str (java.nio.file.Files/createTempDirectory
        "kawaraban-store-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

;; 2026-07-20T00:00:00Z / 2026-07-21T00:00:00Z
(def ^:private jul-20 1784505600)
(def ^:private jul-21 1784592000)

(defn- raw-record
  [{:keys [id outlet as-of headline sourcing]}]
  (cond-> {"id" id
           "outlet" outlet
           "section" "sec.international"
           "url" (str "https://example.org/" id)
           "headline" headline
           "excerpt" "bounded excerpt"
           "lang" "en"
           "asOf" as-of
           "access" "open"}
    sourcing (assoc "sourcing" sourcing)))

;; ── datomize ────────────────────────────────────────────────────────────────

(deftest datomize-produces-transactable-keyword-datoms
  (let [[[rec] refused] (ingest/normalize-batch
                         [(raw-record {:id "art.a" :outlet "outlet.bbc-world" :as-of jul-20
                                       :headline "H" :sourcing "verified"})])
        d (store/datomize rec)]
    (is (empty? refused))
    (testing "string keys become :news.* keywords"
      (is (= "art.a" (:news.article/id d)))
      (is (= "outlet.bbc-world" (:news.article/outlet d)))
      (is (= jul-20 (:news.article/as-of d))))
    (testing "enum-valued attributes become real keywords, not \":mirror\" strings"
      (is (= :mirror (:news.article/kind d)))
      (is (= :verified (:news.article/sourcing d))))
    (testing "internal flags are not datoms"
      (is (not (contains? d :_excerpt_truncated)))
      (is (every? #(not= \_ (first (name %))) (keys d))))
    (testing "G4 — no full-body attribute can appear, whatever the feed contained"
      (is (not-any? #{:news.article/full-text :news.article/body} (keys d))))))

(deftest offline-batches-stay-representative
  (testing "sourcing defaults to :representative so pre-existing offline callers are unchanged"
    (let [[[rec] _] (ingest/normalize-batch
                     [(raw-record {:id "art.b" :outlet "outlet.x" :as-of jul-20 :headline "H"})])]
      (is (= :representative (:news.article/sourcing (store/datomize rec)))))))

(deftest unknown-sourcing-is-refused
  (testing "G5 — a sourcing value outside the lexicon enum is refused, not written through"
    (let [[ok refused] (ingest/normalize-batch
                        [(raw-record {:id "art.c" :outlet "outlet.x" :as-of jul-20
                                      :headline "H" :sourcing "eyewitness"})])]
      (is (empty? ok))
      (is (= 1 (count refused)))
      (is (re-find #"source-provenance-honest" (first refused))))))

;; ── day partitioning ────────────────────────────────────────────────────────

(deftest day-key-partitions-by-utc-and-isolates-unknown-dates
  (is (= "2026-07-20" (store/day-key {:news.article/as-of jul-20})))
  (is (= "2026-07-21" (store/day-key {:news.article/as-of jul-21})))
  (testing "an unparseable feed date is quarantined, not backdated to 1970 or forward-dated to today"
    (is (= store/unknown-day (store/day-key {:news.article/as-of 0})))
    (is (= store/unknown-day (store/day-key {})))))

;; ── merge / dedupe ──────────────────────────────────────────────────────────

(deftest merge-dedupes-by-article-id-and-sorts-deterministically
  (let [a1 {:news.article/id "art.1" :news.article/as-of 100 :news.article/headline "old"}
        a1' {:news.article/id "art.1" :news.article/as-of 100 :news.article/headline "corrected"}
        a2 {:news.article/id "art.2" :news.article/as-of 200 :news.article/headline "newer"}
        merged (store/merge-articles [a1] [a2 a1'])]
    (is (= 2 (count merged)))
    (testing "later fetch wins — the outlet's current public page is authoritative"
      (is (= "corrected" (:news.article/headline (first (filter #(= "art.1" (:news.article/id %)) merged))))))
    (testing "as-of descending, so the file reads newest-first and diffs stay small"
      (is (= ["art.2" "art.1"] (mapv :news.article/id merged))))))

;; ── file round-trip ─────────────────────────────────────────────────────────

(deftest append-is-idempotent-and-round-trips
  (let [dir (tmp-dir!)]
    (try
      (let [[ok _] (ingest/normalize-batch
                    [(raw-record {:id "art.1" :outlet "outlet.bbc-world" :as-of jul-20 :headline "one" :sourcing "verified"})
                     (raw-record {:id "art.2" :outlet "outlet.dw" :as-of jul-21 :headline "two" :sourcing "verified"})
                     (raw-record {:id "art.3" :outlet "outlet.dw" :as-of 0 :headline "undated" :sourcing "verified"})])
            first-pass (store/append-articles! dir ok)]
        (testing "one file per UTC day, plus the unknown-as-of quarantine"
          (is (= #{"2026-07-20" "2026-07-21" store/unknown-day} (set (keys first-pass))))
          (is (= {:added 1 :total 1} (get first-pass "2026-07-20"))))
        (testing "re-appending the same fetch adds nothing (dedupe = schema/news.edn's :db.unique/identity)"
          (let [second-pass (store/append-articles! dir ok)]
            (is (every? zero? (map :added (vals second-pass))))
            (is (every? #(= 1 %) (map :total (vals second-pass))))))
        (testing "written files parse back as datoms"
          (let [loaded (store/load-archive dir)]
            (is (= 3 (count loaded)))
            (is (= #{"art.1" "art.2" "art.3"} (set (map :news.article/id loaded))))
            (is (every? #(= :verified (:news.article/sourcing %)) loaded)))))
      (finally (delete-tree! dir)))))

(deftest archive-files-carry-no-full-text
  (let [dir (tmp-dir!)]
    (try
      (let [[ok _] (ingest/normalize-batch
                    [(raw-record {:id "art.1" :outlet "outlet.bbc-world" :as-of jul-20 :headline "one" :sourcing "verified"})])]
        (store/append-articles! dir ok)
        (let [text (slurp (store/day-file-path dir "2026-07-20"))]
          ;; pr-str emits the namespace-map form `#:news.article{:headline …}`, which
          ;; edn/read-string round-trips back to fully-qualified :news.article/* keys.
          (is (re-find #"#:news\.article\{" text))
          (is (re-find #":headline" text))
          (testing "G4 — the on-disk archive has no body-bearing attribute at all"
            (is (not (re-find #"full-text|fullText|articleBody" text))))))
      (finally (delete-tree! dir)))))

;; ── query ───────────────────────────────────────────────────────────────────

(deftest datalog-joins-articles-to-outlets-through-the-id-string
  (let [dir (tmp-dir!)]
    (try
      (let [[ok _] (ingest/normalize-batch
                    [(raw-record {:id "art.1" :outlet "outlet.bbc-world" :as-of jul-20 :headline "one" :sourcing "verified"})
                     (raw-record {:id "art.2" :outlet "outlet.bbc-world" :as-of jul-21 :headline "two" :sourcing "verified"})
                     (raw-record {:id "art.3" :outlet "outlet.dw" :as-of jul-21 :headline "three" :sourcing "verified"})])]
        (store/append-articles! dir ok)
        (let [{:keys [db counts]} (query/load-db {:schema-path "schema/news.edn"
                                                  :articles-dir dir
                                                  :seed-path "data/seed.edn"
                                                  :include-seed? false})]
          (is (= 3 (:archive counts)))
          (testing "seed is excluded by default, so counts are real coverage not illustration"
            (is (zero? (:seed counts))))
          (testing "the outlet registry joins in from the archive's own outlet-id strings"
            ;; ADR-2607253000 replaced this assertion's original form. It used to assert
            ;; the join returned EMPTY, because outlets were never loaded: the allowlist
            ;; is a policy file, and the outlet_ingest cell meant to turn it into
            ;; :news.outlet/* datoms is an R0 scaffold whose solve throws. load-db now
            ;; projects the allowlist directly, so the join resolves to the real countries
            ;; of the outlets these fixture articles came from.
            (is (= #{["GB"] ["DE"]}
                   (set (d/q '[:find ?country
                               :where
                               [?a :news.article/outlet ?oid]
                               [?o :news.outlet/id ?oid]
                               [?o :news.outlet/country ?country]]
                             db)))))
          (testing "counting by outlet works directly off the archive"
            (is (= #{["outlet.bbc-world" 2] ["outlet.dw" 1]}
                   (set (d/q '[:find ?oid (count ?a)
                                             :where [?a :news.article/outlet ?oid]]
                                           db)))))
          (testing ":db.unique/identity means a re-transacted article upserts, never duplicates"
            (is (= 3 (d/q '[:find (count ?a) . :where [?a :news.article/id]] db))))))
      (finally (delete-tree! dir)))))

(deftest seed-is-loadable-but-clearly-marked-representative
  (let [dir (tmp-dir!)]
    (try
      (let [{:keys [db counts]} (query/load-db {:schema-path "schema/news.edn"
                                                :articles-dir dir
                                                :seed-path "data/seed.edn"
                                                :include-seed? true})]
        (is (pos? (:seed counts)))
        (testing "every seed article is :representative — none masquerades as collected coverage"
          (is (zero? (or (d/q '[:find (count ?a) . :where [?a :news.article/sourcing :verified]] db) 0))))
        (testing "the illustrative graph transacts against the same schema the live archive uses"
          (is (= 7 (d/q '[:find (count ?a) . :where [?a :news.article/kind :mirror]] db)))
          (is (= 10 (d/q '[:find (count ?s) . :where [?s :news.section/id]] db)))))
      (finally (delete-tree! dir)))))

;; ── outlet registry projection + byline（ADR-2607253000）─────────────────────

(deftest allowlist-projects-to-news-outlet-datoms
  (let [entry {:id "outlet.x" :name "X Broadcasting" :kind "public-broadcaster"
               :country "ZZ" :lang ["en" "fr"] :access "open"
               :homepage "https://x.example" :feed-url "https://x.example/rss"
               :verified true :section "sec.international" :note "verify-feeds 2026-07-25: HTTP 200, rss, 20 items."}
        d (store/outlet-datom entry)]
    (testing "lexicon fields become :news.outlet/* with keyword enums"
      (is (= "outlet.x" (:news.outlet/id d)))
      (is (= :public-broadcaster (:news.outlet/kind d)))
      (is (= :open (:news.outlet/access d)))
      (is (= ["en" "fr"] (:news.outlet/lang d))))
    (testing "ingest policy stays OUT of the publishable outlet record"
      (is (= "https://x.example/rss" (:kawaraban.ingest/feed-url d)))
      (is (true? (:kawaraban.ingest/verified d)))
      (is (nil? (:news.outlet/feed-url d)))
      (is (nil? (:news.outlet/verified d))))
    ;; ADR-2607253400 / issue 6bcb348 replaced this assertion. It used to require that
    ;; :news.outlet/sourcing follow the feed measurement, which is exactly the confusion
    ;; being fixed: a live feed says nothing about who the outlet is, and :kind in
    ;; particular is the charter's own selection criterion.
    (testing "sourcing describes the ORGANISATION record, not the feed"
      (is (= :third-party (:news.outlet/sourcing d))
          "a live feed alone must not make the organisation record :verified")
      (is (= :verified (:news.outlet/sourcing
                        (store/outlet-datom (assoc entry :org-verified true
                                                         :org-provenance "https://x.example"
                                                         :org-checked "2026-07-26")))))
      (testing "a dead feed does not downgrade a confirmed organisation record"
        (is (= :verified (:news.outlet/sourcing
                          (store/outlet-datom (assoc entry :verified false
                                                           :org-verified true)))))))
    (testing "organisational provenance rides on the outlet record, feed policy does not"
      (let [v (store/outlet-datom (assoc entry :org-verified true
                                               :org-provenance "https://x.example"
                                               :org-checked "2026-07-26"
                                               :org-kind-evidence "X is the public broadcaster of ZZ."))]
        (is (= "https://x.example" (:news.outlet/provenance v)))
        (is (= "2026-07-26" (:news.outlet/last-verified v)))
        (is (= "X is the public broadcaster of ZZ." (:news.outlet/kind-evidence v)))
        (testing "kind-evidence is recorded, never used to set :kind"
          (is (= :public-broadcaster (:news.outlet/kind v))
              "kind still comes from the registry, not from the evidence string"))))))

(deftest query-joins-articles-to-outlets-for-real
  (testing "the join that was empty in ADR-2607252600 now resolves"
    (let [dir (tmp-dir!)]
      (try
        (let [[ok _] (ingest/normalize-batch
                      [(raw-record {:id "art.1" :outlet "outlet.bbc-world" :as-of jul-20
                                    :headline "one" :sourcing "verified"})])]
          (store/append-articles! dir ok)
          (let [{:keys [db counts]} (query/load-db {:schema-path "schema/news.edn"
                                                    :articles-dir dir
                                                    :seed-path "data/seed.edn"
                                                    :include-seed? false
                                                    :allowlist-path "data/outlets/allowlist.edn"})]
            (is (pos? (:outlets counts)))
            (is (= #{["GB"]} (set (d/q '[:find ?country
                                         :where
                                         [?a :news.article/outlet ?oid]
                                         [?o :news.outlet/id ?oid]
                                         [?o :news.outlet/country ?country]]
                                       db))))
            (testing "feed-verified outlets are countable per country"
              (is (pos? (count (d/q '[:find ?c
                                      :where [?o :kawaraban.ingest/verified true]
                                             [?o :news.outlet/country ?c]]
                                    db)))))))
        (finally (delete-tree! dir))))))

(deftest byline-is-recorded-when-the-outlet-published-one
  (testing "a published byline is kept, bounded to the lexicon maxLength"
    (let [[[rec] _] (ingest/normalize-batch
                     [(assoc (raw-record {:id "art.b1" :outlet "outlet.x" :as-of jul-20
                                          :headline "H" :sourcing "verified"})
                             "byline" "  Jane Doe  ")])]
      (is (= "Jane Doe" (:news.article/byline (store/datomize rec))))))
  (testing "no byline means the attribute is ABSENT, not empty-string"
    (let [[[rec] _] (ingest/normalize-batch
                     [(assoc (raw-record {:id "art.b2" :outlet "outlet.x" :as-of jul-20
                                          :headline "H" :sourcing "verified"})
                             "byline" "   ")])]
      (is (not (contains? (store/datomize rec) :news.article/byline)))))
  (testing "over-long bylines are truncated to 200 chars (lexicon maxLength)"
    (let [[[rec] _] (ingest/normalize-batch
                     [(assoc (raw-record {:id "art.b3" :outlet "outlet.x" :as-of jul-20
                                          :headline "H" :sourcing "verified"})
                             "byline" (apply str (repeat 500 "x")))])]
      (is (= 200 (count (:news.article/byline (store/datomize rec))))))))

(deftest byline-extraction-drops-contact-details
  (testing "RSS dc:creator is preferred"
    (is (= "Jane Doe" (live-fetch/rss-byline "<item><dc:creator>Jane Doe</dc:creator><author>x@y.com</author></item>"))))
  (testing "RSS 2.0 author is an email address — only the display name is kept"
    (is (= "Jane Doe" (live-fetch/rss-byline "<item><author>jane@example.com (Jane Doe)</author></item>")))
    (testing "a bare address with no display name is dropped rather than stored as a contact"
      (is (nil? (live-fetch/rss-byline "<item><author>jane@example.com</author></item>")))))
  (testing "Atom takes author/name, not contributor/name"
    (is (= "Jane Doe" (live-fetch/atom-byline "<entry><author><name>Jane Doe</name></author><contributor><name>Someone Else</name></contributor></entry>")))
    (is (nil? (live-fetch/atom-byline "<entry><contributor><name>Someone Else</name></contributor></entry>"))))
  (testing "a feed with no byline yields nil, so ingest omits the attribute"
    (is (nil? (live-fetch/rss-byline "<item><title>t</title></item>")))
    (is (nil? (live-fetch/atom-byline "<entry><title>t</title></entry>")))))
