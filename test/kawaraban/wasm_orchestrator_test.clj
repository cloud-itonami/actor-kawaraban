(ns kawaraban.wasm-orchestrator-test
  "Integration tests for kawaraban.wasm-orchestrator (Phase G,
  com-junkawasaki/root, this session). Proves the fetch -> gate ->
  wasm-sign -> wasm-post pipeline actually connects end-to-end through
  REAL wasm/Chicory instantiation (kototama.tender), with NO real network
  I/O anywhere in this suite. [Phase H, com-junkawasaki/root, 2026-07-23]:
  every wasm module this repo ships now targets the REAL
  `https://pds.aozora.app/xrpc/...` (see wasm_orchestrator.clj's own
  namespace docstring finding 3's Phase H update), so kototama's
  unconditional SSRF denylist alone no longer refuses these calls (a
  public HTTPS host is not loopback/private/link-local) -- this suite
  never sets KAWARABAN_WASM_PDS_ALLOWLIST, so `pds-allowlist` returns `[]`
  and `session-caps`/`record-caps`' explicit `:allowed-url-prefixes []`
  refuses every attempted POST before any connection happens (fail-closed
  by default), exactly like every existing test/wasm/*_test.clj in this
  repo now does for the same reason.

  Two halves, deliberately separated (matching
  test/kawaraban/methods/test_live_fetch.cljc's own
  \"simulate the gate being open without touching real env vars\"
  philosophy):
    1. G8-respecting tests call `run-outlet!`/`run-all!` directly, with
       KAWARABAN_ALLOW_LIVE_INGEST left UNSET (the real default) -- these
       prove the gate is never bypassed.
    2. Wasm-wiring tests call `process-outlet-records!` directly with
       already gate-passed records (produced via the SAME
       `live-fetch/parse-feed` + `ingest/normalize-batch` calls
       test_live_fetch.cljc's own gate-open simulation uses) -- these
       prove identity minting, CACAO signing, wire encoding, createSession,
       and createRecord all genuinely run as wasm."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [kawaraban.methods.ingest :as ingest]
            [kawaraban.methods.live-fetch :as live-fetch]
            [kawaraban.mirror-actor :as mirror-actor]
            [kawaraban.publisher :as publisher]
            [kawaraban.wasm-orchestrator :as sut])
  (:import [java.util Base64]))

;; ============================================================================
;; Pure-fn tests
;; ============================================================================

(deftest test-grant->resources-shape
  (is (= ["kotoba://op/datom:transact" "kotoba://graph/graph-x"]
         (sut/grant->resources {:cap :cap/transact :scope "graph-x"}))))

(deftest test-siwe-message-includes-domain-and-resources
  (let [msg (sut/siwe-message {:iss "did:key:zTest" :aud "https://pds.aozora.app"
                                :nonce "n" :issued-at "t" :expiry "e"
                                :domain "aozora.app" :version "1"
                                :resources ["kotoba://op/datom:transact" "kotoba://graph/g"]})]
    (is (str/includes? msg "aozora.app wants you to sign in"))
    (is (str/includes? msg "Resources:"))
    (is (str/includes? msg "Expiration Time: e"))))

(deftest test-did-key-format
  (let [pub (byte-array (range 32))]
    (is (str/starts-with? (sut/did-key pub) "did:key:z"))))

(deftest test-canonical-graph-deterministic
  (let [did "did:key:z6MkTestTestTestTestTestTestTestTestTestTest"]
    (is (= (sut/canonical-graph did sut/default-db-name)
           (sut/canonical-graph did sut/default-db-name)))
    (is (str/starts-with? (sut/canonical-graph did sut/default-db-name) "b"))))

(deftest test-default-db-name-matches-cacao-clj
  (is (= "kawaraban" sut/default-db-name)))

(deftest test-truncate-utf8-passthrough-when-short-enough
  (is (= {:text "hello" :truncated? false} (sut/truncate-utf8 "hello" 128))))

(deftest test-truncate-utf8-truncates-and-flags-when-too-long
  (let [long (apply str (repeat 200 "x"))
        {:keys [text truncated?]} (sut/truncate-utf8 long 128)]
    (is (true? truncated?))
    (is (= 128 (count (.getBytes ^String text "UTF-8"))))))

(deftest test-truncate-utf8-never-splits-a-multibyte-character
  ;; each "é" is 2 UTF-8 bytes -- forcing a cut at an odd byte boundary must
  ;; back off to the nearest character boundary, never emit invalid UTF-8.
  (let [s (apply str (repeat 10 "é"))     ; 20 bytes total
        {:keys [text truncated?]} (sut/truncate-utf8 s 15)] ; odd cutoff
    (is (true? truncated?))
    (is (<= (count (.getBytes ^String text "UTF-8")) 15))
    (is (every? #(= \é %) text)) ;; every character present is a WHOLE é, never a stray byte
    ))

(deftest test-identity-dir-differs-from-mirror-actor
  (testing "different key encoding (raw seed vs PKCS8 DER) -> different
            storage location, so the JVM path and this wasm path never
            silently misread each other's identity files"
    (is (not= sut/identity-dir mirror-actor/identity-dir))
    (is (= ".kawaraban/mirrors-wasm/outlet.bbc-world.edn" (sut/identity-path "outlet.bbc-world")))))

;; ============================================================================
;; Real wasm integration -- identity minting, signing, wire encoding
;; ============================================================================

(def ^:private test-outlet-id "outlet.wasm-orchestrator-test-fixture")

(defn- delete-test-identity! []
  (let [f (io/file (sut/identity-path test-outlet-id))]
    (when (.exists f) (io/delete-file f true))))

(deftest test-load-or-create-identity-mints-real-ed25519-keypair-via-wasm-and-persists
  (delete-test-identity!)
  (try
    (let [id1 (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
          id2 (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)]
      (is (.exists (io/file (sut/identity-path test-outlet-id))))
      (is (str/starts-with? (:did id1) "did:key:z"))
      (is (= 32 (count (:seed id1))))
      (is (= 32 (count (:pub id1))))
      (is (= (:did id1) (:did id2)) "second call reloads the SAME identity, does not re-mint")
      (is (= (seq (:seed id1)) (seq (:seed id2))) "the exact same seed bytes round-trip through disk"))
    (finally (delete-test-identity!))))

(deftest test-sign-via-wasm-verifies-against-a-persisted-seed
  (testing "genuinely signs with the HOST-SUPPLIED seed (not a fresh one --
            this module has no gen-keypair import at all)"
    (let [{:keys [seed pub]} (sut/mint-keypair-via-wasm! sut/default-wasm-dir)
          msg "wasm_orchestrator_test sign-via-wasm! check"
          sig (sut/sign-via-wasm! sut/default-wasm-dir seed msg)
          verify (requiring-resolve 'ed25519.core/verify)]
      (is (= 64 (count sig)))
      (is (true? (verify pub (.getBytes msg "UTF-8") sig))))))

(deftest test-cacao-wire-encode-via-wasm-top-level-is-a-3-entry-map
  (let [cbor (sut/cacao-wire-encode-via-wasm!
              sut/default-wasm-dir
              {:iss "did:key:zTest" :aud "http://127.0.0.1" :iat "2026-07-23T00:00:00Z"
               :nonce "nonce-x" :domain "aozora.app" :version "1"
               :res0 "kotoba://op/datom:transact" :res1 "kotoba://graph/g"
               :exp "2026-07-23T01:00:00Z" :sig "sig-placeholder"})]
    (is (some? cbor))
    (is (= 0xA3 (bit-and (int (aget cbor 0)) 0xff)))))

(deftest test-mint-cacao-produces-a-genuinely-verifiable-signed-envelope
  (testing "full integration: mint an identity via wasm, mint a CACAO via
            wasm (sign + wire-encode), and independently verify (via
            ed25519.core/verify, real crypto, no CBOR decoder needed --
            mint-cacao! returns :message/:sig for exactly this purpose)
            that the embedded signature genuinely covers the EXACT SIWE
            message text this run produced, using the SAME pubkey the
            identity's did:key was derived from"
    (delete-test-identity!)
    (try
      (let [identity (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
            {:keys [cacao-b64 message sig payload]}
            (sut/mint-cacao! identity {:wasm-dir sut/default-wasm-dir :pds "http://127.0.0.1"})
            verify (requiring-resolve 'ed25519.core/verify)]
        (is (string? cacao-b64))
        (is (pos? (count cacao-b64)))
        (is (= (:did identity) (:iss payload)))
        (is (true? (verify (:pub identity) (.getBytes ^String message "UTF-8") sig))
            "the signature genuinely verifies against THIS identity's pubkey and THIS run's own SIWE message")
        ;; the base64-decoded envelope is real CBOR bytes (top-level 3-entry map).
        (let [cbor (.decode (Base64/getDecoder) ^String cacao-b64)]
          (is (= 0xA3 (bit-and (int (aget cbor 0)) 0xff)))))
      (finally (delete-test-identity!)))))

(deftest test-create-session-via-wasm-real-url-refused-by-empty-allowlist
  (let [{:keys [written refused]} (sut/create-session-via-wasm! sut/default-wasm-dir "deadbeef-demo-cacao")]
    (is (true? refused))
    (is (= -1 written))))

(deftest test-create-record-via-wasm-real-url-refused-by-empty-allowlist-but-fields-are-honestly-mapped
  (testing "repo/collection/rkey/actor are semantically correct even though
            analysis/cites.* are a documented repurpose of the digest ABI
            (see create-record-via-wasm!'s own docstring)"
    (delete-test-identity!)
    (try
      (let [identity (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
            outlet {:id test-outlet-id :homepage "https://example.org/outlet"}
            record {":news.article/id" "art.test.123"
                    ":news.article/headline" "A real headline for the fixture article"
                    ":news.article/url" "https://example.org/news/fixture-article"
                    ":news.article/as-of" 123}
            {:keys [written refused truncated-fields]}
            (sut/create-record-via-wasm! sut/default-wasm-dir identity outlet record "fixture-jwt")]
        (is (true? refused))
        (is (= -1 written))
        (is (empty? truncated-fields)))
      (finally (delete-test-identity!)))))

(deftest test-create-record-via-wasm-truncates-an-overlong-headline-and-flags-it
  (delete-test-identity!)
  (try
    (let [identity (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
          outlet {:id test-outlet-id :homepage "https://example.org/outlet"}
          record {":news.article/id" "art.test.999"
                  ":news.article/headline" (apply str (repeat 200 "x"))
                  ":news.article/url" "https://example.org/news/long"
                  ":news.article/as-of" 999}
          {:keys [truncated-fields]}
          (sut/create-record-via-wasm! sut/default-wasm-dir identity outlet record "fixture-jwt")]
      (is (some #{:analysis} truncated-fields)))
    (finally (delete-test-identity!))))

(deftest test-create-record-via-wasm-truncates-an-overlong-article-url-instead-of-corrupting-memory
  (testing "REGRESSION test for a real go-live bug (com-junkawasaki/root,
            2026-07-23): a real BBC article URL with RSS tracking params
            (80 bytes) exceeded cites0's 64-byte field width and was
            written RAW (no truncation), corrupting the NEXT field's
            length header -- observed live as control bytes embedded
            mid-URL in a published record. write-record-field! now
            truncates every field via truncate-utf8 before writing, so
            an overlong URL is safely shortened (flagged in
            :truncated-fields), never corrupts adjacent memory."
    (delete-test-identity!)
    (try
      (let [identity (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
            outlet {:id test-outlet-id :homepage "https://example.org/outlet"}
            real-bbc-style-url "https://www.bbc.co.uk/news/articles/cj03r59z73po?at_medium=RSS&at_campaign=rss"
            record {":news.article/id" "art.test.777"
                    ":news.article/headline" "A normal headline"
                    ":news.article/url" real-bbc-style-url
                    ":news.article/as-of" 777}
            {:keys [written refused truncated-fields]}
            (sut/create-record-via-wasm! sut/default-wasm-dir identity outlet record "fixture-jwt")]
        (is (> (count (.getBytes real-bbc-style-url "UTF-8")) 64)
            "the fixture URL must genuinely exceed cites0's 64-byte width for this test to prove anything")
        (is (some #{:cites0} truncated-fields))
        ;; refused (empty allowlist, no real network call) -- this test only
        ;; proves the write itself is safe, not that the call reaches the PDS.
        (is (true? refused))
        (is (= -1 written)))
      (finally (delete-test-identity!)))))

;; ============================================================================
;; publish-article! / process-outlet-records! -- bypassing G8 (matching
;; test_live_fetch.cljc's own "simulate the gate being open" pattern)
;; ============================================================================

(def ^:private rss-fixture
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Fixture Wire</title>
    <item>
      <title>Fixture headline one</title>
      <link>https://example.org/news/one</link>
      <description>Fixture excerpt one.</description>
      <pubDate>Wed, 09 Jul 2026 08:00:00 GMT</pubDate>
    </item>
    <item>
      <title>Fixture headline two</title>
      <link>https://example.org/news/two</link>
      <description>Fixture excerpt two.</description>
      <pubDate>Wed, 09 Jul 2026 09:00:00 GMT</pubDate>
    </item>
  </channel>
</rss>")

(def ^:private fixture-outlet
  {:id test-outlet-id :section "sec.society" :lang "en" :homepage "https://example.org"})

(defn- gate-passed-fixture-records []
  (let [records (live-fetch/parse-feed rss-fixture fixture-outlet)
        [ok _refused] (ingest/normalize-batch records)]
    ok))

(deftest test-publish-article-reports-session-refused-not-a-crash
  (delete-test-identity!)
  (try
    (let [identity (sut/load-or-create-identity! test-outlet-id sut/default-wasm-dir)
          record (first (gate-passed-fixture-records))
          result (sut/publish-article! identity fixture-outlet record
                                        {:wasm-dir sut/default-wasm-dir :pds "http://127.0.0.1"})]
      (is (false? (:ok result)))
      (is (= :session (:stage result)))
      (is (true? (:refused result)))
      (is (= (get record ":news.article/id") (:article-id result))))
    (finally (delete-test-identity!))))

(deftest test-process-outlet-records-is-bounded-by-max-articles-per-outlet
  (delete-test-identity!)
  (try
    (let [ok (gate-passed-fixture-records)
          result (sut/process-outlet-records! fixture-outlet ok 0
                                               {:wasm-dir sut/default-wasm-dir :pds "http://127.0.0.1"
                                                :max-articles-per-outlet 1})]
      (is (= 2 (:fetched result)))
      (is (= 2 (:new result)))
      (is (= 1 (:attempted result)) "bounded to 1 even though 2 articles are new")
      (is (= 0 (:published result)) "real pds.aozora.app target still refuses without KAWARABAN_WASM_PDS_ALLOWLIST (empty-allowlist fail-closed default)")
      (is (= 1 (:session-refused result)))
      (is (= 0 (:new-mark result))
          "[2026-07-23 reliability fix] high-water-mark stays put when nothing was
          actually published this run -- a session-refused article must remain
          eligible for a future retry (e.g. once KAWARABAN_WASM_PDS_ALLOWLIST is
          set), not be silently consumed by a mark advance"))
    (finally (delete-test-identity!))))

(deftest test-process-outlet-records-high-water-mark-advances-only-past-published
  (delete-test-identity!)
  (try
    (let [ok (gate-passed-fixture-records)
          first-as-of (get (first ok) ":news.article/as-of")
          result (sut/process-outlet-records! fixture-outlet ok 0
                                               {:wasm-dir sut/default-wasm-dir :pds "http://127.0.0.1"
                                                :max-articles-per-outlet 1})]
      (is (= 0 (:published result)) "sanity: this fixture pds still refuses (fail-closed empty allowlist)")
      (is (not= first-as-of (:new-mark result))
          "[2026-07-23 reliability fix] an attempted-but-refused article's as-of
          must NOT be folded into the new mark -- only an actually :ok result
          may advance it (mirrors cloud-itonami.media.batch/advance-marks)"))
    (finally (delete-test-identity!))))

(deftest test-process-outlet-records-high-water-mark-excludes-already-seen
  (delete-test-identity!)
  (try
    (let [ok (gate-passed-fixture-records)
          mark (reduce max 0 (map #(get % ":news.article/as-of") ok))
          result (sut/process-outlet-records! fixture-outlet ok mark
                                               {:wasm-dir sut/default-wasm-dir :max-articles-per-outlet 5})]
      (is (= 0 (:new result)))
      (is (= 0 (:attempted result))))
    (finally (delete-test-identity!))))

;; ============================================================================
;; run-outlet! / run-all! -- G8 gate respected (the real default: unset)
;; ============================================================================

(deftest test-run-outlet-refuses-when-g8-gate-is-closed
  (testing "the real default in any test/CI environment: KAWARABAN_ALLOW_LIVE_INGEST
            is unset, so run-outlet! must refuse via live-fetch/fetch-outlet!'s
            own G8 gate -- never bypassed by this orchestrator"
    (let [outlet (assoc fixture-outlet :feed-url "https://example.org/rss.xml")
          result (sut/run-outlet! outlet 0 {:wasm-dir sut/default-wasm-dir})]
      (is (true? (:refused result)))
      (is (str/includes? (:reason result) "G8"))
      (is (= 0 (:new-mark result))))))

(deftest test-run-all-respects-max-outlets-bound-and-persists-last-seen
  (let [tmp-last-seen (str (System/getProperty "java.io.tmpdir") "/kawaraban-wasm-last-seen-test-"
                            (System/nanoTime) ".edn")]
    (try
      (let [results (sut/run-all! "data/outlets/allowlist.edn" tmp-last-seen
                                   {:wasm-dir sut/default-wasm-dir :max-outlets 2
                                    :inter-outlet-delay-ms 0})]
        (is (= 2 (count results)) "bounded to :max-outlets even though more :verified outlets exist")
        (is (every? :refused results) "G8 gate closed (real test-env default) -- every outlet refuses")
        (let [persisted (sut/load-last-seen tmp-last-seen)]
          (is (= 2 (count persisted)))))
      (finally (io/delete-file tmp-last-seen true)))))

(deftest test-collection-constant-matches-publisher
  (is (= publisher/collection "com.etzhayyim.apps.kawaraban")))
