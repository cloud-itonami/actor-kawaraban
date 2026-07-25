(ns kawaraban.wasm-orchestrator
  "Phase G (com-junkawasaki/root, this session) -- wires kawaraban's EXISTING,
  already-verified building blocks into ONE runnable pipeline: RSS/Atom fetch
  (`methods/live_fetch.cljc`, UNCHANGED) -> G1/G3/G4/G8 charter gate
  (`methods/ingest.cljc`, UNCHANGED) -> per-outlet identity + CACAO
  signing + aozora XRPC publish, all via the kotoba-wasm componentization
  (`wasm/*.kotoba`, hosted through `kototama.tender`) Phase B/Phase F built
  and byte-exact-verified ONE MODULE AT A TIME. This namespace is the first
  thing that actually CALLS them as a single connected flow.

  `src/kawaraban/cacao.clj` / `src/kawaraban/aozora.clj` (the pre-existing
  pure-JVM path) are DELIBERATELY not required anywhere below -- replacing
  them with the wasm path is this phase's whole point, not a convenience
  shortcut back to the old implementation. The small amount of PURE
  payload-shaping logic every CACAO envelope still needs (SIWE-message text,
  resource-URI construction, did:key / canonical-graph derivation) is
  reimplemented below, kept deliberately in sync with `kawaraban.cacao`'s
  own copy by hand (small, stable, reviewed once here) -- NOT because it
  could move into wasm (wasm/README.md finding 6 already established
  did:key/graph-cid derivation needs bignum/base58/base32 encoding this
  language cannot express; it is host-side by hard necessity, not by
  choice, in every phase of this port).

  ============================================================================
  HONEST SCOPE -- three real findings this phase surfaced, read before
  assuming more than what is actually wired below:
  ============================================================================

  1. **cacao_self_mint.kotoba always mints a FRESH key -- it cannot sign
     with a PERSISTED one.** Its `main` unconditionally calls `gen-keypair`
     internally; there is no ABI to inject an existing seed. That is fine
     for Phase B's own proof (\"the fresh keygen+sign chain runs as real
     wasm\") but useless for THIS phase's actual need: kawaraban's per-outlet
     mirror identity must be minted ONCE and then reused (same did:key)
     across every subsequent run, exactly like `kawaraban.mirror-actor`'s
     existing `load-or-create-mirror-identity!` pattern. Closing this gap
     needed a genuinely NEW wasm module -- `wasm/identity_sign.kotoba`
     (this phase) -- whose only job is `(sign seed msg)` against a
     HOST-SUPPLIED seed (see that file's own header comment; it has no
     `gen-keypair` import at all, T3 capability confinement). Identity
     MINTING (first run for an outlet) still goes through
     `cacao_self_mint.wasm` (real Ed25519 keygen, wasm-hosted) -- only the
     keypair half of its output is kept; SIGNING on every subsequent run
     goes through the new `identity_sign.wasm` with that persisted seed.

  2. **wasm/aozora_create_record.kotoba's `record.*` sub-fields are
     digest-shaped, not kawaraban-article-shaped, and genuinely cannot be
     made to match without a language change.** That module's ABI
     (`record.analysis` / `record.cites.0.url` / `record.cites.1.url` /
     `record.createdAt` / `record.actor`) was built against
     cloud-itonami's `net.itonami.media.digest` record shape (a synthesized
     multi-article digest with citations), not kawaraban's own per-article
     `:news.article/*` mirror-record shape (`:news.article/headline` /
     `:news.article/excerpt` / `:news.article/url` / `:news.article/section`
     / `:news.article/outlet` / `:news.article/lang` / `:news.article/as-of`
     / `:news.article/sourcing`). Attempting a genuinely faithful port hits
     a real, NEW language-limitation finding (finding 7, `wasm/README.md`):
     `kototama.tender`'s dotted-path nesting convention
     (`build-nested-tree`, `(str/split k #\"\\.\")`) splits on EVERY literal
     '.' in a key -- but kawaraban's own field names (e.g.
     `\":news.article/id\"`) contain an embedded '.' as PART of the key
     itself (mirroring a namespaced-keyword's own dot), which collides
     with the SAME character the nesting convention uses as its ONLY
     structural separator. There is no escape mechanism for a literal dot
     inside a dotted-path segment today, so `{\"record\": {\":news.article/id\":
     ...}}` cannot be produced byte-faithfully via this convention without
     either widening kototama (out of scope this session) or renaming keys
     (a real wire-incompatibility, not attempted silently). Given that, this
     namespace REUSES `aozora_create_record.wasm` AS-IS with an explicit,
     documented field-mapping (see `create-record-via-wasm!`'s own
     docstring) rather than compiling a new, hand-offset-arithmetic'd module
     under this session's time/risk budget -- `repo`/`collection`/`rkey`/
     `actor` are semantically correct (real outlet DID, kawaraban's REAL
     collection, the article's own deterministic id); only
     `record.analysis`/`record.cites.*.url` are a deliberate repurpose
     (headline -> analysis, article url + outlet homepage -> the two
     citation slots). A dedicated `:news.article/*`-shaped module is a
     documented follow-up, not a silent gap.

  3. **The destination URL is baked into each network-calling wasm module
     as a `.kotoba` compile-time string literal, not a runtime parameter.**
     `.kotoba` has no runtime string construction (`str-ptr` only accepts a
     literal the compiler can see at compile time -- wasm/README.md
     finding 1), so `wasm/aozora_create_session.kotoba` /
     `wasm/aozora_create_record.kotoba` each hardcode
     `http://127.0.0.1/xrpc/...` in their own source -- there is no way for
     THIS orchestrator to point the already-compiled `.wasm` binaries at
     `https://pds.aozora.app` at runtime. \"Injectable http-fn\" therefore
     means something different here than in the old JVM path
     (`kawaraban.aozora/jvm-http-fn` was a literal function value threaded
     through opts): at the wasm layer, \"pointing at production\" is a
     RECOMPILE (swap the literal URL, rerun `wasm/README.md`'s own
     \"Rebuilding\" recipe) that produces a DIFFERENT `.wasm` file --
     this orchestrator's OWN injectability is at the file-path layer
     (`:wasm-dir` in every opts map below): swap in a directory containing
     production-URL-compiled `.wasm` binaries and every call site here
     picks them up unchanged, no code edit. This session deliberately does
     NOT compile or ship any such production-URL variant (that would be a
     real, callable, internet-reaching binary sitting in the repo even if
     never invoked THIS session -- a risk not worth taking against this
     task's hard \"no real network calls this session\" constraint). Making
     a production run possible is therefore a FUTURE, explicit compilation
     step for the repo owner, not something this phase silently prepares
     un-flagged.

     [Phase H, com-junkawasaki/root, 2026-07-23] That future step has now
     happened, deliberately and separately from this phase: both modules
     were recompiled with the real `https://pds.aozora.app` URL baked in
     (this repo's default `wasm/` directory now IS the production-URL
     variant; `:wasm-dir` injection above still works for anyone who wants
     to point at a DIFFERENT, e.g. loopback/test, build instead). The
     resulting real network reachability is gated by a SEPARATE, explicit
     opt-in (`KAWARABAN_WASM_PDS_ALLOWLIST`, see `pds-allowlist` below) --
     unset by default, so installing/running this orchestrator is still
     safe by default even against the now-real-URL binaries.
  ============================================================================

  Bounded by design (`:max-outlets` / `:max-articles-per-outlet`, both
  conservative by default): the 2026-07-10 first live-ingest activation at
  \"3 articles/outlet\" already pushed CPU past a safe budget once (see
  `run_live_ingest.clj`'s own addendum-2 note on shared-graph write
  pressure) -- this orchestrator defaults smaller still (1/outlet) since it
  is new, unproven code exercising a SEPARATE code path (wasm/Chicory
  instantiation per article, not just an HTTP POST) with its own unknown
  cost profile.

  High-water-mark (`data/ingest/last-seen-wasm.edn` by default --
  DELIBERATELY a different file from `run_live_ingest.clj`'s own
  `data/ingest/last-seen.edn`, since the two orchestrators mint DIFFERENT
  per-outlet identities -- see `identity-dir`'s own docstring -- and must
  not silently share or clobber each other's progress marks): same
  discipline as `run_live_ingest.clj` (mark advances to the max `as-of`
  among every GATE-PASSED/fetched record, regardless of publish outcome --
  a transient publish failure is retried next tick via the normal feed-
  window overlap, not by refusing to advance the mark)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kawaraban.methods.live-fetch :as live-fetch]
            [kawaraban.publisher :as publisher]
            [kawaraban.store :as store]
            [kototama.contract :as contract]
            [kototama.tender :as tender])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util Base64 UUID])
  (:gen-class))

;; ============================================================================
;; Pure CACAO-payload helpers -- duplicated (not required) from
;; kawaraban.cacao, see namespace docstring for why.
;; ============================================================================

(def ^:private cap->op {:cap/read "datom:read" :cap/transact "datom:transact" :cap/admin "tx:create"})

(defn grant->resources
  "[op-uri graph-uri] for a {:cap :scope} grant -- same 2-element shape
  `kawaraban.cacao/grant->resources` always returns (a real domain
  invariant: every real grant here is exactly one capability over exactly
  one graph)."
  [{:keys [cap scope]}]
  [(str "kotoba://op/" (cap->op cap)) (str "kotoba://graph/" scope)])

(defn- iss-address [^String iss] (last (str/split iss #":")))

(defn siwe-message
  "The exact SIWE message text a CACAO signature is computed over --
  ported verbatim from `kawaraban.cacao/siwe-message` (kept in sync by
  hand; see namespace docstring)."
  [{:keys [iss aud issued-at expiry nonce domain statement version resources]}]
  (->> (concat
        [(str domain " wants you to sign in with your Ethereum account:") (iss-address iss) ""]
        (when statement [statement ""])
        [(str "URI: " aud) (str "Version: " version) "Chain ID: 1"
         (str "Nonce: " nonce) (str "Issued At: " issued-at)]
        (when expiry [(str "Expiration Time: " expiry)])
        (when (seq resources) (cons "Resources:" (map #(str "- " %) resources))))
       (str/join "\n")))

(def ^:private b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- base58btc [^bytes data]
  (let [zeros (count (take-while zero? data))
        sb (StringBuilder.) fifty8 (java.math.BigInteger/valueOf 58)]
    (loop [n (java.math.BigInteger. 1 data)]
      (when (pos? (.signum n))
        (.append sb (.charAt b58 (.intValue (.mod n fifty8))))
        (recur (.divide n fifty8))))
    (dotimes [_ zeros] (.append sb \1))
    (.toString (.reverse sb))))

(defn did-key
  "did:key:z<base58btc(multicodec-ed25519-pub-prefix + raw 32-byte pubkey)>
  -- ported verbatim from `kawaraban.cacao/did-key`. Genuinely cannot move
  into wasm (bignum base58 division -- wasm/README.md finding 6)."
  [^bytes raw-pub]
  (let [framed (byte-array (concat [(unchecked-byte 0xED) (unchecked-byte 0x01)] (seq raw-pub)))]
    (str "did:key:z" (base58btc framed))))

(defn- sha256 ^bytes [^bytes data] (.digest (MessageDigest/getInstance "SHA-256") data))

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32-lower-no-pad [^bytes data]
  (let [sb (StringBuilder.)
        {:keys [bits value]}
        (reduce
         (fn [{:keys [bits value]} b]
           (let [b (bit-and (int b) 0xff)
                 value (bit-or (bit-shift-left value 8) b)
                 bits (+ bits 8)]
             (loop [bits bits value value]
               (if (>= bits 5)
                 (do (.append sb (.charAt b32 (bit-and (unsigned-bit-shift-right value (- bits 5)) 31)))
                     (recur (- bits 5) value))
                 {:bits bits :value value}))))
         {:bits 0 :value 0}
         data)]
    (when (pos? bits)
      (.append sb (.charAt b32 (bit-and (bit-shift-left value (- 5 bits)) 31))))
    (.toString sb)))

(defn graph-cid-from-name
  "CIDv1/dag-cbor/sha2-256 base32-lower multibase of SHA-256(name) --
  ported verbatim from `kawaraban.cacao/graph-cid-from-name`."
  [^String name]
  (let [hash (sha256 (.getBytes name "UTF-8"))
        cid (byte-array (concat [(unchecked-byte 0x01) (unchecked-byte 0x71)
                                  (unchecked-byte 0x12) (unchecked-byte 0x20)]
                                 (seq hash)))]
    (str "b" (base32-lower-no-pad cid))))

(defn canonical-graph [did db-name] (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

(def default-db-name "kawaraban")

;; ============================================================================
;; wasm module plumbing
;; ============================================================================

(def default-wasm-dir
  "Relative to the repo root (matches every existing test/wasm/*_test.clj's
  own `(io/file \"wasm/...\")` convention)."
  "wasm")

(defn- wasm-path [wasm-dir module-name] (str wasm-dir "/" module-name ".wasm"))

(defn- wasm-bytes [path] (.readAllBytes (io/input-stream (io/file path))))

;; ── identity minting (cacao_self_mint.wasm -- FIRST run for an outlet only) ─

(def ^:private self-mint-caps
  (contract/host-caps {:grants [:gen-keypair :sign :sha256-hex :cbor-encode]
                        :limits {:allow-secret-imports? true}}))

(defn mint-keypair-via-wasm!
  "Calls wasm/cacao_self_mint.wasm ONCE via kototama.tender purely to
  obtain a FRESH, real Ed25519 {:seed :pub} (32 raw bytes each) out of its
  guest memory. That module's OWN flat-CBOR/fingerprint/signature outputs
  are discarded here -- this orchestrator only needs the keypair-generation
  half of what Phase B's module proves (see wasm/README.md's own \"Why
  cacao_self_mint.kotoba itself was not rewritten\" note for why minting
  and wire-encoding are deliberately two separate modules)."
  [wasm-dir]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "cacao_self_mint"))
                                     [:gen-keypair :sign :sha256-hex :cbor-encode]
                                     self-mint-caps)
        memory (.memory instance)
        msg (.getBytes "kawaraban identity mint (Phase G, message discarded)" "UTF-8")]
    (.writeI32 memory 0 (count msg))
    (.write memory 8 msg 0 (count msg))
    (tender/call-main instance)
    {:seed (#'tender/read-bytes! instance 2048 32)
     :pub  (#'tender/read-bytes! instance 2080 32)}))

;; ── signing with a PERSISTED seed (identity_sign.wasm -- every run) ─────────

(def ^:private sign-caps
  (contract/host-caps {:grants [:sign] :limits {:allow-secret-imports? true}}))

(defn sign-via-wasm!
  "Signs `message` with a PERSISTED (host-supplied) 32-byte Ed25519 seed via
  wasm/identity_sign.wasm (this phase's new module -- see its own header
  comment for why cacao_self_mint.wasm cannot do this). Returns the raw
  64-byte signature, or nil if the guest refused (never expected in
  practice -- :sign is unconditionally granted above -- but never assumed)."
  [wasm-dir ^bytes seed ^String message]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "identity_sign")) [:sign] sign-caps)
        memory (.memory instance)
        msg-bytes (.getBytes message "UTF-8")]
    (.write memory 0 seed 0 32)
    (.writeI32 memory 32 (count msg-bytes))
    (.write memory 40 msg-bytes 0 (count msg-bytes))
    (let [written (tender/call-main instance)]
      (when (= 64 written) (#'tender/read-bytes! instance 2048 64)))))

;; ── nested CACAO wire encode (cacao_wire_encode.wasm) ────────────────────────

(def ^:private wire-encode-caps (contract/host-caps {:grants [:cbor-encode]}))

;; MUST match wasm/cacao_wire_encode.kotoba's own ABI table exactly.
;; res0/res1 widened 64 -> 100 bytes (com-junkawasaki/root Phase G) --
;; shifting exp/sig accordingly -- because a REAL `canonical-graph` value
;; is 74 bytes, exceeding the original 64-byte cap (see that file's own
;; header comment for the full memory-corruption finding this fixes).
(def ^:private wire-field-offsets
  {:iss 0 :aud 80 :iat 160 :nonce 216 :domain 272 :version 320
   :res0 344 :res1 460 :exp 576 :sig 632})

(defn- write-field! [memory offset ^String text]
  (let [bs (.getBytes (or text "") "UTF-8")]
    (.writeI32 memory offset (count bs))
    (.write memory (+ offset 8) bs 0 (count bs))))

(defn cacao-wire-encode-via-wasm!
  "Builds the REAL nested CACAO wire CBOR via wasm/cacao_wire_encode.wasm.
  `fields` is a map over `wire-field-offsets`' keys (:iss :aud :iat :nonce
  :domain :version :res0 :res1 :exp :sig), every value already
  host-formatted text (this module performs zero string construction of
  its own -- see wasm/README.md finding 1). Returns the raw CBOR bytes, or
  nil on guest refusal."
  [wasm-dir fields]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "cacao_wire_encode")) [:cbor-encode] wire-encode-caps)
        memory (.memory instance)]
    (doseq [[k offset] wire-field-offsets] (write-field! memory offset (get fields k)))
    (let [written (tender/call-main instance)]
      (when (pos? written) (#'tender/read-bytes! instance 4096 written)))))

(defn mint-cacao!
  "The wasm-path equivalent of `kawaraban.aozora/mint-session!`'s CACAO
  half: build a fresh transact-scoped grant payload for `identity`'s own
  canonical graph, sign the SIWE message text with `identity`'s PERSISTED
  seed (`sign-via-wasm!`), wire-encode the real nested envelope
  (`cacao-wire-encode-via-wasm!`), and base64-wrap the CBOR bytes into the
  final `cacao_b64` token text -- matching `kawaraban.cacao/mint`'s own
  final step (standard Base64, not urlsafe; the embedded `s.s` signature
  value IS urlsafe-no-pad, same as `kawaraban.cacao/mint`). Throws ex-info
  if either wasm step refuses.

  Returns {:cacao-b64 :message :sig :payload}, NOT just the bare token
  string -- `:message` (the exact SIWE text signed) and `:sig` (the raw 64
  signature bytes) let a caller (namely
  `wasm_orchestrator_test.clj`) independently re-verify the embedded
  signature via `ed25519.core/verify` without writing a CBOR decoder, a
  genuine end-to-end crypto proof mirroring
  `test/wasm/cacao_self_mint_test.clj`'s own
  `cacao-self-mint-signature-verifies-against-the-guest-generated-pubkey`
  pattern. `publish-article!` only ever uses `:cacao-b64`."
  [identity {:keys [wasm-dir pds] :or {wasm-dir default-wasm-dir pds "http://127.0.0.1"}}]
  (let [graph (canonical-graph (:did identity) default-db-name)
        now (str (Instant/now))
        expiry (str (.plusSeconds (Instant/now) 3600))
        nonce (str (UUID/randomUUID))
        resources (grant->resources {:cap :cap/transact :scope graph})
        payload {:iss (:did identity) :aud pds :issued-at now :nonce nonce
                 :domain "aozora.app" :version "1" :expiry expiry :resources resources}
        msg (siwe-message payload)
        sig (sign-via-wasm! wasm-dir (:seed identity) msg)]
    (when-not sig (throw (ex-info "wasm identity_sign refused/failed" {:did (:did identity)})))
    (let [sig-b64 (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) sig)
          cbor (cacao-wire-encode-via-wasm!
                wasm-dir {:iss (:did identity) :aud pds :iat now :nonce nonce
                          :domain "aozora.app" :version "1"
                          :res0 (first resources) :res1 (second resources)
                          :exp expiry :sig sig-b64})]
      (when-not cbor (throw (ex-info "wasm cacao_wire_encode refused/failed" {:did (:did identity)})))
      {:cacao-b64 (.encodeToString (Base64/getEncoder) cbor)
       :message msg
       :sig sig
       :payload payload})))

;; ── URL allowlist (Phase H, com-junkawasaki/root, 2026-07-23) ────────────────
;;
;; wasm/aozora_create_session.kotoba and wasm/aozora_create_record.kotoba now
;; target the REAL https://pds.aozora.app (a deliberate recompile from Phase
;; B/F's loopback-only build -- see wasm/README.md), so kototama's
;; unconditional SSRF denylist alone no longer refuses these calls (a public
;; HTTPS host is not loopback/private/link-local). Safety now comes from an
;; EXPLICIT `:allowed-url-prefixes` allowlist
;; (kototama.contract/url-allowed?'s documented \"empty collection = deny
;; all\" semantics -- NOT the `nil` = unrestricted default, which this repo
;; deliberately never relies on): unset/empty by default (every real network
;; call refused before any HttpClient.send), and an operator opts in to real
;; publishing by setting KAWARABAN_WASM_PDS_ALLOWLIST explicitly -- same
;; deliberate, separate-from-installation opt-in shape as
;; cloud-itonami's MEDIA_WASM_PDS_ALLOWLIST.
(defn- pds-allowlist []
  (if-let [v (System/getenv "KAWARABAN_WASM_PDS_ALLOWLIST")]
    (vec (remove str/blank? (str/split v #",")))
    []))

;; ── HTTP timeout (com-junkawasaki/root, 2026-07-23 reliability follow-up) ────
;;
;; kototama's default HostCaps timeout (5s connect + 5s request, see
;; kotoba-lang/kototama ADR-2607231955) is byte-identical to what this repo
;; already relied on implicitly before that ADR -- but the actual go-live
;; run against pds.aozora.app repeatedly hit this ceiling for requests that
;; DID go on to succeed (confirmed via a 30s raw HttpClient retry; the
;; launchd daemon's own log shows real \"request timed out\" entries, not a
;; hypothetical concern). 20000ms is this repo's OWN chosen default (not
;; kototama's global default, which stays 5000ms for every other caller) --
;; generous enough for pds.aozora.app's observed occasional slowness,
;; overridable via KAWARABAN_WASM_HTTP_TIMEOUT_MS for further tuning.
(defn- http-timeout-ms []
  (if-let [v (System/getenv "KAWARABAN_WASM_HTTP_TIMEOUT_MS")]
    (Integer/parseInt v)
    20000))

;; ── createSession (aozora_create_session.wasm) ───────────────────────────────

(defn- session-caps []
  (contract/host-caps {:grants [:http-post :json-encode]
                       :limits {:max-http-posts 1 :allowed-url-prefixes (pds-allowlist)
                                :http-connect-timeout-ms (http-timeout-ms)
                                :http-request-timeout-ms (http-timeout-ms)}}))

(defn create-session-via-wasm!
  "POSTs `cacao-b64` as `{\"cacao\":\"...\"}` via
  wasm/aozora_create_session.wasm against the REAL
  `https://pds.aozora.app/xrpc/com.atproto.server.createSession` (Phase H
  recompile). Refused (`:written -1`) unless
  KAWARABAN_WASM_PDS_ALLOWLIST explicitly names this host (see
  `pds-allowlist` above) -- deliberately fail-closed by default, exactly
  like every wasm test in this repo already proves for the empty-allowlist
  case."
  [wasm-dir cacao-b64]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "aozora_create_session"))
                                     [:http-post :json-encode] (session-caps))
        memory (.memory instance)
        cacao-bytes (.getBytes ^String cacao-b64 "UTF-8")]
    (.writeI32 memory 0 (count cacao-bytes))
    (.write memory 8 cacao-bytes 0 (count cacao-bytes))
    (let [written (tender/call-main instance)]
      ;; resp-ptr@6296 -- this module's alloc order (pairs-ptr@2048/2048B ->
      ;; body-ptr@4096/2200B -> resp-ptr@6296/2048B, widened from 512B per
      ;; Phase H go-live diagnostic), same layout
      ;; test/wasm/aozora_create_session_test.clj documents; `written` IS the
      ;; response byte count http-post wrote there (kototama.tender's
      ;; http-post-host-fn docstring: \"-> bytes-written|-1\"), not a guess.
      {:written written
       :refused (= -1 written)
       :response-body (when (pos? written) (tender/read-memory-string instance 6296 written))})))

;; ── createRecord (aozora_create_record.wasm) ─────────────────────────────────

(defn- record-caps []
  (contract/host-caps {:grants [:http-post-headers :json-encode]
                       :limits {:max-http-posts 1 :allowed-url-prefixes (pds-allowlist)
                                :http-connect-timeout-ms (http-timeout-ms)
                                :http-request-timeout-ms (http-timeout-ms)}}))

(def ^:private record-field-offsets
  {:repo 0 :collection 80 :rkey 160 :analysis 240 :cites0 384 :cites1 464
   :created 544 :actor 600 :jwt 680})

;; wasm/aozora_create_record.kotoba's own ABI table (each field's declared
;; max WIDTH, matching that file's header comment) -- now enforced here via
;; `write-record-field!`'s required max-bytes, not merely documented.
(def ^:private record-field-widths
  {:repo 64 :collection 64 :rkey 64 :analysis 128 :cites0 64 :cites1 64
   :created 40 :actor 64 :jwt 300})

(defn truncate-utf8
  "Byte-safe truncation to `max-bytes` UTF-8 bytes, returning
  {:text :truncated?} -- NEVER a silent cut (wasm/README.md's own
  documented-scope-limit convention, e.g. `msg-len <= 2000` / `jwt <= 300`:
  a genuine, flagged constraint, not a hidden one)."
  [^String s max-bytes]
  (let [bs (.getBytes (or s "") "UTF-8")]
    (if (<= (alength bs) max-bytes)
      {:text (or s "") :truncated? false}
      ;; Trim at a UTF-8 byte boundary that never splits a multi-byte
      ;; sequence (continuation bytes are 10xxxxxx = 0x80-0xBF).
      (let [cut (loop [i max-bytes]
                  (if (or (zero? i) (not= 2r10 (bit-shift-right (bit-and (aget bs i) 0xff) 6)))
                    i
                    (recur (dec i))))]
        {:text (String. bs 0 cut "UTF-8") :truncated? true}))))

(defn- write-record-field!
  "[com-junkawasaki/root \"Phase H\" go-live diagnostic, 2026-07-23]:
  MAX-BYTES is now REQUIRED and enforced via `truncate-utf8` before the
  write, not left to each call site to remember. A real go-live attempt
  against the live PDS found a genuine data-corruption bug: `cites0`/
  `cites1` (real article URLs, up to ~80 bytes with RSS tracking params)
  were written RAW, with no bound against this field's own 64-byte ABI
  width -- the wasm guest's own `copy-bytes!` blindly trusts whatever
  length the host claims, so an oversized write corrupted the NEXT
  field's length header (observed live: a real BBC URL truncated
  mid-query-string with embedded control bytes in the published record).
  `analysis` already correctly truncated via a separate inline call
  (namespace docstring's own field-mapping table); this makes the SAME
  discipline structural for every field, not opt-in per call site."
  [memory offset max-bytes ^String text]
  (let [{:keys [text truncated?]} (truncate-utf8 (or text "") max-bytes)
        bs (.getBytes ^String text "UTF-8")]
    (.writeI32 memory offset (count bs))
    (.write memory (+ offset 8) bs 0 (count bs))
    truncated?))

(defn create-record-via-wasm!
  "Publishes ONE article's data through wasm/aozora_create_record.wasm.

  HONEST FIELD-MAPPING NOTE (namespace docstring finding 2 -- read before
  assuming this is a byte-exact port of kawaraban's OWN `:news.article/*`
  record shape; it is NOT): this module's `record.*` sub-fields
  (`analysis`/`cites.0.url`/`cites.1.url`/`createdAt`/`actor`) are
  cloud-itonami digest-shaped, not kawaraban-article-shaped, and cannot be
  renamed to kawaraban's real field names without a kototama dotted-path
  escape mechanism this session does not add (finding 2's full
  explanation). This function maps kawaraban's real per-article data onto
  that EXISTING ABI as a documented interim measure:
    repo        <- outlet mirror identity's OWN did (correct, unchanged)
    collection  <- kawaraban's REAL collection, `publisher/collection`
                   (correct -- NOT this module's own golden-fixture test
                   value \"net.itonami.media.digest\"; `collection` is a
                   host-write field, so overriding it here is legitimate)
    rkey        <- the article's own deterministic `:news.article/id`
                   (correct -- matches kawaraban's existing idempotent-rkey
                   design, safe to republish)
    analysis    <- the article's `:news.article/headline`, truncated to
                   this module's 128-byte field cap (`truncate-utf8`;
                   REPURPOSED, not the module's real field name)
    cites.0.url <- the article's own canonical `:news.article/url`
                   (REPURPOSED, but a genuine link-out, satisfying G4's
                   spirit even under the wrong key name)
    cites.1.url <- `outlet`'s own `:homepage` (REPURPOSED, secondary
                   provenance reference)
    actor       <- the SAME outlet mirror identity did as `repo`

  The target URL is now the REAL
  `https://pds.aozora.app/xrpc/com.atproto.repo.createRecord` (Phase H
  recompile) -- refused (`:written -1`) unless
  KAWARABAN_WASM_PDS_ALLOWLIST explicitly names this host, same as
  `create-session-via-wasm!`."
  [wasm-dir identity outlet record jwt]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "aozora_create_record"))
                                     [:http-post-headers :json-encode] (record-caps))
        memory (.memory instance)
        w! (fn [field text] (write-record-field! memory (get record-field-offsets field)
                                                 (get record-field-widths field) text))
        repo-truncated? (w! :repo (:did identity))
        collection-truncated? (w! :collection publisher/collection)
        rkey-truncated? (w! :rkey (get record ":news.article/id"))
        analysis-truncated? (w! :analysis (get record ":news.article/headline" ""))
        cites0-truncated? (w! :cites0 (get record ":news.article/url" ""))
        cites1-truncated? (w! :cites1 (get outlet :homepage ""))
        created-truncated? (w! :created (str (Instant/now)))
        actor-truncated? (w! :actor (:did identity))
        jwt-truncated? (w! :jwt jwt)
        written (tender/call-main instance)]
    {:written written
     :refused (= -1 written)
     ;; NOTE: `identity` here is a fn PARAMETER (the outlet's identity
     ;; map, e.g. {:did ... :seed ...}), which shadows clojure.core/
     ;; identity within this function's whole body -- `(keep identity
     ;; ...)` would silently resolve to `(keep <identity-map> ...)`,
     ;; treating each vector element as a KEY LOOKUP into that map
     ;; (always nil, since none of :repo/:collection/etc. are keys the
     ;; identity map actually has) and returning an always-empty
     ;; result. `remove nil?` is the shadow-safe equivalent (this bug
     ;; was caught by this fix's own new regression test failing
     ;; despite write-record-field! itself working correctly in
     ;; isolation -- see debug trail in PR description).
     :truncated-fields (vec (remove nil?
                                     [(when repo-truncated? :repo)
                                      (when collection-truncated? :collection)
                                      (when rkey-truncated? :rkey)
                                      (when analysis-truncated? :analysis)
                                      (when cites0-truncated? :cites0)
                                      (when cites1-truncated? :cites1)
                                      (when created-truncated? :created)
                                      (when actor-truncated? :actor)
                                      (when jwt-truncated? :jwt)]))}))

;; ── deleteRecord (aozora_delete_record.wasm) ─────────────────────────────────
;;
;; [com-junkawasaki/root "Phase H" go-live diagnostic, 2026-07-23]: added to
;; fix a real corrupted live record -- a real go-live attempt found that
;; re-`createRecord`-ing an ALREADY-EXISTING rkey with corrected field
;; values does not overwrite in place (it hit an HttpTimeoutException, not
;; a clean 200/409); AT-Proto's fix-in-place path is delete-then-recreate,
;; not upsert-via-createRecord. This is the delete half; the caller is
;; expected to follow it with a fresh `create-record-via-wasm!` call using
;; the SAME rkey once this delete has completed.

(defn- delete-record-caps []
  (contract/host-caps {:grants [:http-post-headers :json-encode]
                       :limits {:max-http-posts 1 :allowed-url-prefixes (pds-allowlist)
                                :http-connect-timeout-ms (http-timeout-ms)
                                :http-request-timeout-ms (http-timeout-ms)}}))

(defn delete-record-via-wasm!
  "Deletes ONE record (by REPO/COLLECTION/RKEY) via
  wasm/aozora_delete_record.wasm -- the REAL `com.atproto.repo.
  deleteRecord`, gated by the same KAWARABAN_WASM_PDS_ALLOWLIST allowlist
  as create-session-via-wasm!/create-record-via-wasm!."
  [wasm-dir repo collection rkey jwt]
  (let [instance (tender/instantiate (wasm-bytes (wasm-path wasm-dir "aozora_delete_record"))
                                     [:http-post-headers :json-encode] (delete-record-caps))
        memory (.memory instance)]
    (write-record-field! memory 0 64 repo)
    (write-record-field! memory 80 64 collection)
    (write-record-field! memory 160 64 rkey)
    (write-record-field! memory 240 300 jwt)
    (let [written (tender/call-main instance)]
      {:written written :refused (= -1 written)})))

;; ============================================================================
;; Per-outlet identity persistence
;; ============================================================================

(def identity-dir
  "DELIBERATELY separate from `kawaraban.mirror-actor/identity-dir`
  (`.kawaraban/mirrors/`): that path's files are `{:private-b64
  :public-b64}` PKCS8/X.509 DER (JVM `KeyFactory`-loadable); this
  orchestrator's are `{:seed-b64 :pub-b64 :did}` raw Ed25519 seed bytes
  (wasm `sign`-loadable). The two encodings are NOT interchangeable --
  reading one format as the other would silently mint a DIFFERENT did:key
  for the same outlet-id. Keeping them in separate subdirectories (both
  already covered by the repo's single `/.kawaraban/` .gitignore entry)
  means the JVM path and this wasm path can coexist without either one
  clobbering or misreading the other's identity file. Unifying storage
  format is a follow-up IF/WHEN the JVM path is ever retired in favor of
  the wasm path exclusively -- not attempted here."
  ".kawaraban/mirrors-wasm")

(defn identity-path [outlet-id] (str identity-dir "/" outlet-id ".edn"))

(defn load-or-create-identity!
  "Load this outlet's persisted wasm-path identity, or mint + persist a new
  one (via `mint-keypair-via-wasm!` + `did-key`) on first use. Returns
  {:seed :pub :did} (:seed and :pub are raw bytes)."
  [outlet-id wasm-dir]
  (let [f (io/file (identity-path outlet-id))]
    (if (.exists f)
      (let [{:keys [seed-b64 pub-b64 did]} (edn/read-string (slurp f))]
        {:seed (.decode (Base64/getDecoder) ^String seed-b64)
         :pub (.decode (Base64/getDecoder) ^String pub-b64)
         :did did})
      (let [{:keys [seed pub]} (mint-keypair-via-wasm! wasm-dir)
            did (did-key pub)]
        (io/make-parents f)
        (spit f (pr-str {:seed-b64 (.encodeToString (Base64/getEncoder) seed)
                          :pub-b64 (.encodeToString (Base64/getEncoder) pub)
                          :did did}))
        {:seed seed :pub pub :did did}))))

;; ============================================================================
;; Article-level publish + outlet/run orchestration (bounded, high-water-mark)
;; ============================================================================

(def default-max-outlets
  "Conservative default -- see namespace docstring's bounded-by-design note."
  2)

(def default-max-articles-per-outlet
  "Conservative default (the 2026-07-10 \"3/outlet\" precedent overran CPU
  budget once already; this orchestrator is new, unproven code on top of
  that, so it defaults even smaller)."
  1)

(defn- extract-access-jwt
  "Pulls \"accessJwt\" out of a real com.atproto.server.createSession
  response body. [Phase H, com-junkawasaki/root, 2026-07-23]: does this
  HOST-side (clojure.data.json, already a project dep) rather than via a
  wasm json-extract-field round-trip -- the response bytes are already a
  plain JVM string by the time create-session-via-wasm! returns them
  (http-post-host-fn reads them off the wire before ever poking them into
  guest memory), so re-entering wasm to re-extract a field from data the
  host already holds would add a second untested wasm module under time
  pressure for no confinement benefit (the sensitive step -- MINTING and
  SENDING the CACAO-authenticated request -- already happened wasm-side;
  reading a field back out of the plain-text JSON reply is not a
  capability boundary). Returns nil (not an exception) on any parse
  failure or missing/non-string field -- caller treats that as a failed
  session, never fabricates a JWT."
  [response-body]
  (try
    (let [v (get (json/read-str (or response-body "")) "accessJwt")]
      (when (string? v) v))
    (catch Exception _ nil)))

(defn publish-article!
  "One article's full wasm-path publish attempt: mint a fresh CACAO session
  token, attempt createSession, extract the real accessJwt from its
  response, and -- only if that succeeded -- attempt createRecord with
  that real JWT.

  [Phase H, com-junkawasaki/root, 2026-07-23]: both wasm modules now target
  the REAL https://pds.aozora.app (recompiled from Phase B/F's loopback
  build), gated by KAWARABAN_WASM_PDS_ALLOWLIST (see `pds-allowlist`) --
  createSession can genuinely succeed now, so the PRIOR version of this
  function's hardcoded placeholder JWT (\"unreachable-in-loopback-mode\",
  dead code while createSession always refused) would have been a REAL BUG
  the moment createSession started succeeding: createRecord would have
  authenticated with a fabricated string instead of the real session
  token. `extract-access-jwt` closes that gap. `create-record-via-wasm!`'s
  own wiring is independently proven by `wasm_orchestrator_test.clj` with a
  synthetic jwt, matching how `test/wasm/aozora_create_record_test.clj`
  already tests that module in isolation from createSession's own outcome."
  [identity outlet record opts]
  (let [article-id (get record ":news.article/id")]
    (try
      (let [{:keys [cacao-b64]} (mint-cacao! identity opts)
            session (create-session-via-wasm! (:wasm-dir opts default-wasm-dir) cacao-b64)]
        (cond
          (:refused session)
          {:ok false :stage :session :refused true :article-id article-id}

          :else
          (let [jwt (extract-access-jwt (:response-body session))]
            (if (nil? jwt)
              {:ok false :stage :session :jwt-extract-failed true :article-id article-id}
              (let [rec (create-record-via-wasm! (:wasm-dir opts default-wasm-dir) identity outlet record jwt)]
                (if (:refused rec)
                  {:ok false :stage :record :refused true :article-id article-id}
                  {:ok true :article-id article-id}))))))
      (catch Exception e
        {:ok false :stage :error :error (ex-message e) :article-id article-id}))))

(defn- new-since [records mark] (filter #(> (get % ":news.article/as-of" 0) mark) records))

(defn- published-mark
  "[com-junkawasaki/root, 2026-07-23 reliability follow-up] MARK only
  advances past articles that were ACTUALLY PUBLISHED this run --
  articles that were merely fetched/gate-passed but never attempted
  (beyond the :max-articles-per-outlet bound), OR attempted and failed
  (session/record refused, or errored -- e.g. a transient
  \"request timed out\" against pds.aozora.app, observed for real in this
  orchestrator's own launchd daemon log, not hypothetical) stay ELIGIBLE
  for the next tick, never silently consumed.

  This is a DELIBERATE DEPARTURE from `run_live_ingest.clj`'s own
  pre-existing `(max-as-of ok mark)` policy (which advances past EVERY
  gate-passed record regardless of publish outcome) -- that policy's own
  docstring claims a skipped record \"stays eligible next tick via the
  normal window overlap\", but that claim does not actually hold: once an
  article's `as-of` is subsumed into `mark`, `new-since`'s `(> as-of
  mark)` filter excludes it on every future tick even if the same
  article is still present in a later fetch, so a transient publish
  failure permanently drops that article. This orchestrator instead
  mirrors `cloud-itonami.media.batch`'s ALREADY-correct, already-shipped
  policy (`advance-marks`'s own docstring: mark advances only past
  articles \"actually PICKED INTO a digest that went on to PUBLISH\") --
  proven precedent in this same codebase, not a novel design."
  [bounded results mark]
  (reduce max mark
          (keep (fn [[record result]]
                  (when (:ok result) (get record ":news.article/as-of" 0)))
                (map vector bounded results))))

(defn process-outlet-records!
  "The wasm-path publish loop over ALREADY gate-passed records (`ok`, from
  `ingest/normalize-batch`) -- separated from `run-outlet!` so tests can
  exercise this half directly WITHOUT needing
  `KAWARABAN_ALLOW_LIVE_INGEST=1` (matching
  `test/kawaraban/methods/test_live_fetch.cljc`'s own \"simulate the gate
  being open without touching real env vars\" pattern: call the
  lower-level fns directly rather than the G8-gated edge). Bounded by
  `:max-articles-per-outlet` (default `default-max-articles-per-outlet`).
  The high-water-mark (`:new-mark`) advances only past articles that were
  ACTUALLY PUBLISHED this run (see `published-mark`'s own docstring for
  why this deliberately departs from `run_live_ingest.clj`'s own
  \"advance regardless of publish outcome\" policy) -- an unattempted
  (bound-excluded) or failed-to-publish article stays genuinely eligible
  for the next tick, not merely claimed to be."
  [outlet ok mark opts]
  (let [fresh (new-since ok mark)
        bounded (vec (take (get opts :max-articles-per-outlet default-max-articles-per-outlet) fresh))
        identity (load-or-create-identity! (:id outlet) (get opts :wasm-dir default-wasm-dir))
        results (mapv #(publish-article! identity outlet % opts) bounded)]
    {:outlet (:id outlet)
     :fetched (count ok)
     :new (count fresh)
     :attempted (count bounded)
     :published (count (filter :ok results))
     :session-refused (count (filter #(= :session (:stage %)) results))
     :record-refused (count (filter #(= :record (:stage %)) results))
     :errors (mapv :error (filter #(= :error (:stage %)) results))
     :new-mark (published-mark bounded results mark)}))

(def default-articles-dir
  "[com-junkawasaki/root ADR-2607252600] Where `kawaraban.store` writes the
  day-partitioned, `schema/news.edn`-transactable mirror archive. Aliased
  from `kawaraban.store` so the writer here and the reader in
  `kawaraban.query` cannot drift apart."
  store/default-archive-dir)

(defn- archive-records!
  "[com-junkawasaki/root ADR-2607252600] Persist every GATE-PASSED record to
  the local datom archive, BEFORE and INDEPENDENT of the aozora publish
  half.

  Deliberately not bounded by `:max-articles-per-outlet`: that bound exists
  to protect the PDS and this machine's CPU from per-article wasm/Chicory
  instantiation, and neither cost applies to appending EDN. Nor is it
  conditioned on publish success -- the archive is an observation record,
  not a publish ledger, so an article that was fetched and passed the
  charter gate belongs in it whether or not aozora accepted it (or was
  even attempted). This is the ONLY place kawaraban's own collected
  articles become queryable without round-tripping through the PDS.

  Returns nil (and reports the failure into the result map) rather than
  throwing: a full disk or a read-only checkout must not take down the
  publish half of a run that is otherwise working."
  [dir records]
  (when (and dir (seq records))
    (try
      {:archived (reduce + 0 (map :added (vals (store/append-articles! dir records))))}
      (catch Exception e
        {:archive-error (ex-message e)}))))

(defn run-outlet!
  "One outlet's G8-gated fetch -> gate -> archive -> wasm-publish pass. `mark`
  is this outlet's prior high-water-mark (0 if never seen). G8 (live ingest
  requires `KAWARABAN_ALLOW_LIVE_INGEST=1` + Council attestation) is
  enforced by `live-fetch/fetch-outlet!` itself, UNCHANGED -- this function
  never bypasses or re-implements that gate.

  `:articles-dir` (default `default-articles-dir`, nil to disable) is the
  local datom archive -- see `archive-records!` for why it is written from
  the FULL gate-passed set rather than the bounded/published subset."
  ([outlet mark] (run-outlet! outlet mark {}))
  ([outlet mark opts]
   (let [{:keys [refused reason ok gate-refused fetch-error]} (live-fetch/fetch-outlet! outlet)]
     (cond
       refused {:outlet (:id outlet) :refused true :reason reason :new-mark mark}
       fetch-error {:outlet (:id outlet) :fetch-error fetch-error :new-mark mark}
       :else (merge (process-outlet-records! outlet ok mark opts)
                    {:gate-refused (count gate-refused)}
                    (archive-records! (get opts :articles-dir default-articles-dir) ok))))))

(defn load-last-seen [path]
  (let [f (io/file path)]
    (if (.exists f) (edn/read-string (slurp f)) {})))

(defn save-last-seen! [path last-seen]
  (io/make-parents path)
  (spit path (pr-str last-seen)))

(def default-inter-outlet-delay-ms
  "Same reasoning as `run_live_ingest.clj`'s own delay -- spread bursts of
  wasm/Chicory instantiation + (refused) network attempts across the run
  rather than firing every outlet back-to-back. 0 in tests (see
  `wasm_orchestrator_test.clj`)."
  1000)

(defn run-all!
  "`on-result` (default no-op) is called with each outlet's result map as
  soon as it finishes -- same rationale as `run_live_ingest.clj`'s own
  `run-all!`: a slow/stuck run must still be visible outlet-by-outlet, not
  silent until the very end. Bounded by `:max-outlets` (default
  `default-max-outlets`) over the :verified allowlist entries."
  ([allowlist-path last-seen-path opts] (run-all! allowlist-path last-seen-path opts (fn [_])))
  ([allowlist-path last-seen-path opts on-result]
   (let [max-outlets (get opts :max-outlets default-max-outlets)
         delay-ms (get opts :inter-outlet-delay-ms default-inter-outlet-delay-ms)
         outlets (->> (live-fetch/load-allowlist allowlist-path)
                      (filter :verified)
                      (take max-outlets))
         last-seen (load-last-seen last-seen-path)
         results (mapv (fn [outlet]
                          (when (pos? delay-ms) (Thread/sleep delay-ms))
                          (let [mark (get last-seen (:id outlet) 0)
                                result (try
                                         (run-outlet! outlet mark opts)
                                         (catch Exception e
                                           {:outlet (:id outlet) :error (ex-message e) :new-mark mark}))]
                            (on-result result)
                            result))
                        outlets)
         updated (reduce (fn [m r] (assoc m (:outlet r) (:new-mark r))) last-seen results)]
     (save-last-seen! last-seen-path updated)
     results)))

(defn -main [& _]
  (let [allowlist-path (or (System/getenv "KAWARABAN_ALLOWLIST_PATH") "data/outlets/allowlist.edn")
        last-seen-path (or (System/getenv "KAWARABAN_WASM_LAST_SEEN_PATH") "data/ingest/last-seen-wasm.edn")
        opts {:wasm-dir (or (System/getenv "KAWARABAN_WASM_DIR") default-wasm-dir)
              :pds (or (System/getenv "KAWARABAN_WASM_PDS") "http://127.0.0.1")
              :max-outlets (if-let [v (System/getenv "KAWARABAN_WASM_MAX_OUTLETS")]
                             (Integer/parseInt v) default-max-outlets)
              :max-articles-per-outlet (if-let [v (System/getenv "KAWARABAN_WASM_MAX_ARTICLES_PER_OUTLET")]
                                         (Integer/parseInt v) default-max-articles-per-outlet)
              ;; [ADR-2607252600] Local datom archive. KAWARABAN_ARTICLES_DIR="" disables it
              ;; (publish-only run); unset = the in-repo default.
              :articles-dir (let [v (System/getenv "KAWARABAN_ARTICLES_DIR")]
                              (cond (nil? v) default-articles-dir
                                    (str/blank? v) nil
                                    :else v))}
        results (run-all! allowlist-path last-seen-path opts
                           (fn [r] (println (pr-str r)) (flush)))
        errors (filter #(or (:error %) (:fetch-error %) (seq (:errors %))) results)]
    (println (str "kawaraban wasm-orchestrator: " (count results) " outlets, "
                   (reduce + 0 (map #(or (:archived %) 0) results)) " archived, "
                   (reduce + 0 (map #(or (:published %) 0) results)) " published, "
                   (reduce + 0 (map #(or (:session-refused %) 0) results)) " session-refused, "
                   (count errors) " with errors"))
    (when (and (seq errors) (= (count errors) (count results)))
      (System/exit 1))))
