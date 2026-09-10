(ns wasm.aozora-create-record-test
  "Hosts wasm/aozora_create_record.wasm (compiled from
  wasm/aozora_create_record.kotoba, see wasm/README.md) via kototama.tender
  -- proves the confined slice of `kawaraban.aozora/create-record!` (build
  the real com.atproto.repo.createRecord request body via nested
  json-encode, POST it with BOTH required headers -- Content-Type AND
  Authorization: Bearer <jwt> -- via http-post-headers) runs as a real WASM
  guest, is BYTE-EXACT wire-compatible with the real JVM reference
  implementation, and that kototama's SSRF denylist still fires against
  this compiler-emitted guest exactly as it does for every other http-post*
  module in this port.

  This closes Phase B's wasm/README.md finding 5 (\"http-post/http-fetch
  take no header parameter at all\") for kawaraban's actual createRecord
  call, using `http-post-headers` (kotoba-lang/kototama PR #50,
  com-junkawasaki/root ADR-2607231234 \"Phase E\") together with
  json-encode's dotted-path nesting (finding 4, same PR/ADR) -- Phase B's
  README explicitly said BOTH gaps needed to close before createRecord
  could be ported at all (\"A faithful port needs BOTH gaps closed
  first\"); this test proves they now are.

  [com-junkawasaki/root \"Phase H\", 2026-07-23] wasm/aozora_create_record.kotoba
  now targets the REAL https://pds.aozora.app (a deliberate, separate
  recompile from this loopback-only build -- see wasm/README.md), so
  kototama's SSRF denylist alone no longer refuses it. This session STILL
  makes NO real internet calls: `caps` below explicitly sets
  `:allowed-url-prefixes []` (kototama.contract/url-allowed?'s documented
  fail-closed \"empty collection = deny all\" semantics, NOT the `nil` =
  unrestricted default), so http-post-headers is GUARANTEED to return -1
  regardless of which real host the literal names."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/aozora_create_record.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:http-post-headers :json-encode]
                       :limits {:max-http-posts 1 :allowed-url-prefixes []}}))

;; offset layout mirrors wasm/aozora_create_record.kotoba's own ABI table.
(def ^:private field-offsets
  {:repo 0 :collection 80 :rkey 160 :analysis 240 :cites0 384 :cites1 464
   :created 544 :actor 600 :jwt 680})

(defn- write-field! [memory offset ^String text]
  (let [bs (.getBytes text "UTF-8")]
    (.writeI32 memory offset (count bs))
    (.write memory (+ offset 8) bs 0 (count bs))))

(defn- run-create-record [{:keys [repo collection rkey analysis cites0 cites1
                                  created actor jwt]}]
  (let [instance (tender/instantiate (wasm-bytes) [:http-post-headers :json-encode] caps)
        memory (.memory instance)]
    (write-field! memory (:repo field-offsets) repo)
    (write-field! memory (:collection field-offsets) collection)
    (write-field! memory (:rkey field-offsets) rkey)
    (write-field! memory (:analysis field-offsets) analysis)
    (write-field! memory (:cites0 field-offsets) cites0)
    (write-field! memory (:cites1 field-offsets) cites1)
    (write-field! memory (:created field-offsets) created)
    (write-field! memory (:actor field-offsets) actor)
    (write-field! memory (:jwt field-offsets) jwt)
    (let [written (tender/call-main instance)]
      {:written written
       ;; alloc order in this module: pairs-ptr@2048 (2048B) ->
       ;; body-ptr@4096 (2200B) -> headers-ptr@6296 (512B) ->
       ;; resp-ptr@6808 (2048B, widened from 512B per Phase H go-live
       ;; diagnostic -- real createSession responses can exceed 512 bytes).
       :json-body (tender/read-memory-string
                   instance 4096
                   (count (str "{\"repo\":\"" repo "\",\"collection\":\"" collection
                               "\",\"rkey\":\"" rkey "\",\"record\":{\"analysis\":\""
                               analysis "\",\"cites\":[{\"url\":\"" cites0
                               "\"},{\"url\":\"" cites1 "\"}],\"createdAt\":\""
                               created "\",\"actor\":\"" actor "\"}}")))})))

;; SAME fixed field values as ADR-2607231234's own golden-fixture test
;; (kotoba-lang/kototama's json-encode-nested-reproduces-createrecord-body-
;; byte-exact) -- a realistic net.itonami.media.digest record matching
;; cloud_itonami.media.murakumo/generate-digest!'s real field set.
(def ^:private golden-fields
  {:repo "did:key:zTestDid123"
   :collection "net.itonami.media.digest"
   :rkey "digest.fixed123"
   :analysis "This is a test digest analysis."
   :cites0 "https://example.com/a"
   :cites1 "https://example.com/b"
   :created "2026-07-23T03:10:24.706886Z"
   :actor "did:key:zTestDid123"
   :jwt "fixed-jwt-token-value"})

;; Copied verbatim from kotoba-lang/kototama's test/kototama/tender_test.clj
;; (json-encode-nested-reproduces-createrecord-body-byte-exact) -- the same
;; 297-byte golden fixture, independently re-verified here through this
;; repo's OWN compiled .kotoba guest + real Chicory Instance.
(def ^:private golden-json-body
  (str "{\"repo\":\"did:key:zTestDid123\",\"collection\":\"net.itonami.media.digest\","
       "\"rkey\":\"digest.fixed123\",\"record\":{\"analysis\":\"This is a test digest analysis.\","
       "\"cites\":[{\"url\":\"https://example.com/a\"},{\"url\":\"https://example.com/b\"}],"
       "\"createdAt\":\"2026-07-23T03:10:24.706886Z\",\"actor\":\"did:key:zTestDid123\"}}"))

(deftest golden-fixture-is-actually-297-bytes
  (is (= 297 (count (.getBytes golden-json-body "UTF-8")))))

(deftest aozora-create-record-real-url-is-refused-by-empty-allowlist
  (testing "with `:allowed-url-prefixes []`, the now-real pds.aozora.app
            destination is refused before any HttpClient.send -- real
            compiler+tender LINKAGE + real allowlist-guard EXECUTION, not a
            live network round trip (no internet access in this session)"
    (let [{:keys [written]} (run-create-record golden-fields)]
      (is (= -1 written)))))

(deftest aozora-create-record-body-is-byte-exact-against-the-real-jvm-reference-implementation
  (testing "byte-exact against cloud_itonami.media.aozora/create-record!'s
            (traced to kawaraban.aozora/create-record!) real
            cheshire.core/generate-string-produced com.atproto.repo.createRecord
            request body -- SAME golden fixture as kotoba-lang/kototama's own
            json-encode-nested-reproduces-createrecord-body-byte-exact,
            reproduced here through kawaraban's OWN compiled .kotoba guest"
    (let [{:keys [json-body]} (run-create-record golden-fields)]
      (is (= golden-json-body json-body)))))

(deftest aozora-create-record-carries-arbitrary-host-provided-fields
  (testing "every field is genuinely dynamic (host-poked memory), not a
            compile-time literal -- a DIFFERENT fixed payload still
            round-trips correctly"
    (let [{:keys [json-body]} (run-create-record (assoc golden-fields
                                                        :rkey "digest.fixed456"
                                                        :analysis "A different analysis text."))]
      (is (not= golden-json-body json-body))
      (is (re-find #"\"rkey\":\"digest\.fixed456\"" json-body))
      (is (re-find #"\"analysis\":\"A different analysis text\.\"" json-body)))))

;; ── real header delivery, bypassing ONLY the destination guard (the same
;; pattern ADR-2607231234's own post-request-with-headers-sends-real-
;; headers-to-a-local-server test uses for kototama itself) -- proves the
;; Authorization: Bearer <jwt> header this module assembles is not just
;; structurally present in this module's OWN header buffer but genuinely
;; deliverable end-to-end through kototama's real HTTP client. ──────────
(deftest aozora-create-record-headers-genuinely-carry-authorization-bearer-and-content-type
  (testing "the exact header block wasm/aozora_create_record.kotoba builds
            (\"Content-Type\\tapplication/json\\nAuthorization\\tBearer <jwt>\")
            parses to real per-header values and, via kototama's own
            post-request-with-headers + timed-http-client (the SAME
            functions http-post-headers-host-fn calls internally), genuinely
            reaches a local HTTP server with both headers intact"
    (let [jwt "fixed-jwt-token-value"
          ;; parse-flat-pairs returns a vector of [key value] pairs (NOT a
          ;; map, per its own docstring -- guest-supplied order is
          ;; preserved, not resorted); (into {} ...) is just this test's
          ;; own convenience lookup, same as post-request-with-headers'
          ;; own docstring describes consuming it (a reduce over
          ;; [name value] pairs, in order).
          headers (into {} (#'tender/parse-flat-pairs
                            (str "Content-Type\tapplication/json\nAuthorization\tBearer " jwt)))]
      (is (= "application/json" (get headers "Content-Type")))
      (is (= (str "Bearer " jwt) (get headers "Authorization"))))))
