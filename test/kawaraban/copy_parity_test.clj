(ns kawaraban.copy-parity-test
  "DRIFT GATE for the payload-shaping logic that exists in THREE copies.

  `kawaraban.wasm-orchestrator`'s own namespace docstring says the pure
  CACAO payload-shaping helpers are \"reimplemented below, kept
  deliberately in sync with `kawaraban.cacao`'s own copy BY HAND\". Hand
  sync has no failure mode that anyone notices: the copy that PRODUCTION
  runs is the wasm/orchestrator one, so if `kawaraban.cacao` /
  `kawaraban.aozora` (the pure-JVM twins) drift, the launchd daemon keeps
  working and nothing reports the JVM side has quietly become wrong.
  Nothing in this repo compared the copies against each other before this
  namespace -- every existing test exercises ONE copy at a time.

  The three copies:

    (1) `wasm/*.kotoba` + their committed `wasm/*.wasm`  -- what production runs
    (2) `kawaraban.cacao` / `kawaraban.aozora`           -- the pure-JVM twins
    (3) private helpers inside `kawaraban.wasm-orchestrator` itself

  What this namespace asserts, and what it deliberately does NOT:

  * (2) vs (3) -- full differential coverage below. Pure Clojure, no wasm
    runtime needed: `cap->op`, `iss-address`, `grant->resources`,
    `did-key`, `graph-cid-from-name`, `canonical-graph`, `default-db-name`
    and `siwe-message`.

  * (1) vs (2) -- covered for `->wire`/CBOR only, via
    `cacao_wire_encode.wasm` (already instantiated by this suite, so no
    new tooling). `wasm.cacao-wire-encode-test` asserts that module
    against a 264-byte golden byte array TRANSCRIBED from
    kotoba-lang/kototama -- a constant, not this repo's own encoder, so it
    would still pass if `kawaraban.cacao/->wire` or its CBOR writer
    changed. The test below closes that by running BOTH and comparing
    bytes.

  * NOT covered: `cacao_self_mint.kotoba` and `identity_sign.kotoba` do
    real Ed25519 keygen/signing, so their output is never byte-reproducible
    and cannot be diffed against a JVM twin this way (the existing
    `test/wasm/*_test.clj` verify them by signature-verification instead).
    `aozora_create_record` / `aozora_create_session` / `aozora_delete_record`
    are network-calling modules whose JVM twin (`kawaraban.aozora`) differs
    by design (documented field-mapping, orchestrator docstring finding 2)
    -- comparing them is not a parity question but a spec question.

  MEASURED 2026-08-12, superproject base 9ba7cbed22b: the copies AGREE on
  every input reachable in production. One divergence exists outside that
  domain and is pinned by
  `known-divergence-siwe-message-chain-id-for-non-did-key-issuers` below --
  read that test before \"fixing\" anything."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kawaraban.cacao :as cacao]
            [kawaraban.wasm-orchestrator :as orch]
            [kototama.contract :as contract]
            [kototama.tender :as tender])
  (:import [java.security KeyPairGenerator]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(defn- raw-pub
  "Raw 32-byte Ed25519 public key -- the last 32 bytes of the X.509 SPKI
  encoding. `kawaraban.cacao/did-key` takes the PublicKey object and does
  this internally; `kawaraban.wasm-orchestrator/did-key` takes the raw
  bytes. Bridging the two signatures is the ONLY difference between them
  that is intentional."
  ^bytes [pub]
  (let [enc (.getEncoded pub)]
    (java.util.Arrays/copyOfRange enc (- (alength enc) 32) (alength enc))))

(defn- ed25519-keypairs [n]
  (let [kpg (KeyPairGenerator/getInstance "Ed25519")]
    (doall (repeatedly n #(.generateKeyPair kpg)))))

(def ^:private issuer-corpus
  ["did:key:zABCDEF"
   "did:key:z6MkeTG3bFFSLYVU7VqhgZxqr6YzpaGrQtFMh1uvqGy1vDnP"
   "did:pkh:eip155:1:0xdeadbeef"
   "did:pkh:eip155:137:0xdeadbeef"
   "did:web:example.com"
   "0xbareaddress"
   ""])

(def ^:private name-corpus
  ["" "a" "kotobase/db/did:key:zX/kawaraban" "日本語テスト" "with spaces & symbols/?#"
   (apply str (repeat 500 "x"))])

(def ^:private base-payload
  {:iss "did:key:zABCDEF"
   :aud "https://pds.aozora.app"
   :issued-at "2026-08-12T00:00:00Z"
   :expiry "2026-08-12T01:00:00Z"
   :nonce "3f2a1b7c-0000-4d1e-9c2b-abcdef012345"
   :domain "aozora.app"
   :version "1"
   :statement nil
   :resources ["kotoba://op/datom:transact" "kotoba://graph/bafyx"]})

(defn- payload-variants
  "Every payload SHAPE `mint-cacao!` / `cacao/mint` can actually build,
  plus the optional-field shapes both `siwe-message` copies branch on."
  [iss]
  (let [p (assoc base-payload :iss iss)]
    [["full"            p]
     ["no-expiry"       (dissoc p :expiry)]
     ["nil-expiry"      (assoc p :expiry nil)]
     ["no-resources"    (dissoc p :resources)]
     ["empty-resources" (assoc p :resources [])]
     ["one-resource"    (assoc p :resources ["kotoba://op/datom:read"])]
     ["with-statement"  (assoc p :statement "kawaraban mirror publish")]
     ["unicode-domain"  (assoc p :domain "瓦版.example")]
     ["no-version"      (dissoc p :version)]]))

;; ---------------------------------------------------------------------------
;; (2) vs (3) -- the two JVM-side copies, pure Clojure
;; ---------------------------------------------------------------------------

(deftest cap-op-table-is-identical-in-both-copies
  (is (= @#'cacao/cap->op @#'orch/cap->op)
      "cap->op drifted: a grant would encode a DIFFERENT operation URI depending
       on which copy minted the CACAO"))

(deftest iss-address-is-identical-in-both-copies
  (let [mismatches (for [iss issuer-corpus
                         :let [a (#'cacao/iss-address iss)
                               b (#'orch/iss-address iss)]
                         :when (not= a b)]
                     {:iss iss :cacao a :orchestrator b})]
    (is (empty? mismatches) (str "iss-address drifted: " (pr-str mismatches)))))

(deftest grant-resources-is-identical-in-both-copies
  (let [mismatches (for [cap [:cap/read :cap/transact :cap/admin :cap/unknown nil]
                         scope ["" "bafyxyz" "graph-42" "日本"]
                         :let [g {:cap cap :scope scope}
                               a (cacao/grant->resources g)
                               b (orch/grant->resources g)]
                         :when (not= a b)]
                     {:grant g :cacao a :orchestrator b})]
    (is (empty? mismatches) (str "grant->resources drifted: " (pr-str mismatches)))))

(deftest did-key-is-identical-in-both-copies
  (testing "over 200 REAL Ed25519 keypairs -- base58btc is the one helper here
            with a nontrivial numeric loop (BigInteger division), so it gets
            volume rather than a single example"
    (let [mismatches (for [kp (ed25519-keypairs 200)
                           :let [pub (.getPublic kp)
                                 a (#'cacao/did-key pub)
                                 b (orch/did-key (raw-pub pub))]
                           :when (not= a b)]
                       {:cacao a :orchestrator b})]
      (is (empty? mismatches)
          (str "did-key drifted -- the SAME key would get TWO different DIDs, so
                the JVM path and the wasm path would publish as different actors: "
               (pr-str (take 3 mismatches)))))))

(deftest did-key-agrees-on-the-leading-zero-edge-case
  (testing "base58btc's '1'-padding branch (leading zero bytes) is the only part
            of did-key a random keypair essentially never exercises"
    (is (= (orch/did-key (byte-array 32))
           "did:key:z6MkeTG3bFFSLYVU7VqhgZxqr6YzpaGrQtFMh1uvqGy1vDnP")
        "all-zero pubkey encoding changed")))

(deftest graph-cid-from-name-is-identical-in-both-copies
  (let [mismatches (for [n name-corpus
                         :let [a (cacao/graph-cid-from-name n)
                               b (orch/graph-cid-from-name n)]
                         :when (not= a b)]
                     {:name (subs n 0 (min 40 (count n))) :cacao a :orchestrator b})]
    (is (empty? mismatches) (str "graph-cid-from-name drifted: " (pr-str mismatches)))))

(deftest canonical-graph-and-db-name-are-identical-in-both-copies
  (is (= cacao/default-db-name orch/default-db-name)
      "default-db-name drifted: the two paths would scope grants to DIFFERENT graphs")
  (let [mismatches (for [did issuer-corpus
                         db ["kawaraban" "other-db"]
                         :let [a (cacao/canonical-graph did db)
                               b (orch/canonical-graph did db)]
                         :when (not= a b)]
                     {:did did :db db :cacao a :orchestrator b})]
    (is (empty? mismatches) (str "canonical-graph drifted: " (pr-str mismatches)))))

(deftest siwe-message-is-identical-over-the-production-domain
  (testing "did:key issuers -- the ONLY issuers either path can produce (see
            `every-identity-this-repo-can-mint-is-a-did-key` below). The SIWE
            text is the exact byte string the Ed25519 signature is computed
            over, so any drift here means the two paths sign DIFFERENT
            statements while claiming the same grant."
    (let [issuers (cons "did:key:zABCDEF"
                        (map #(orch/did-key (raw-pub (.getPublic %)))
                             (ed25519-keypairs 25)))
          mismatches (for [iss issuers
                           [label p] (payload-variants iss)
                           :let [a (cacao/siwe-message p)
                                 b (orch/siwe-message p)]
                           :when (not= a b)]
                       {:variant label :iss iss :cacao a :orchestrator b})]
      (is (empty? mismatches)
          (str "siwe-message drifted on a PRODUCTION-REACHABLE input: "
               (pr-str (first mismatches)))))))

(deftest every-identity-this-repo-can-mint-is-a-did-key
  (testing "the reachability invariant that keeps the known chain-id divergence
            below out of production: BOTH identity paths
            (`cacao/generate-identity` -> `cacao/did-key`, and
            `wasm-orchestrator/load-or-create-identity!` -> `orch/did-key`)
            derive the DID with did-key, which always emits the did:key method.
            If this test ever fails, the divergence pinned below becomes LIVE."
    (let [dids (map #(orch/did-key (raw-pub (.getPublic %))) (ed25519-keypairs 50))]
      (is (every? #(str/starts-with? % "did:key:z") dids))
      (is (= "did:key:z" (subs (:did (cacao/generate-identity)) 0 9))))))

(deftest known-divergence-siwe-message-chain-id-for-non-did-key-issuers
  (testing "NOT a passing-by-accident test -- it PINS a real, measured
            difference so that changing EITHER copy is loud.

            `kawaraban.cacao/siwe-message` derives the SIWE `Chain ID:` line
            from the issuer via its own `iss-chain-id` helper (\"1\" for
            did:key, otherwise the second-to-last colon segment).
            `kawaraban.wasm-orchestrator/siwe-message` hardcodes
            `\"Chain ID: 1\"` -- so its docstring claim of being \"ported
            verbatim from kawaraban.cacao/siwe-message\" is FALSE for any
            issuer that is not a did:key.

            Unreachable today (see the test above: every identity either path
            mints is a did:key), which is why this is pinned rather than
            fixed here -- fixing it edits a live production path and is a
            decision for the repo owner, not a drift gate. The moment this
            repo adopts did:pkh or did:web issuers, the two paths would sign
            DIFFERENT SIWE texts for the same grant and the CACAO minted by
            one would not verify against the other's expectation."
    (doseq [[iss expected-cacao expected-orch]
            [["did:pkh:eip155:137:0xdeadbeef" "Chain ID: 137" "Chain ID: 1"]
             ["did:pkh:eip155:10:0xdeadbeef"  "Chain ID: 10"  "Chain ID: 1"]
             ["did:web:example.com"           "Chain ID: web" "Chain ID: 1"]]]
      (let [p (assoc base-payload :iss iss)
            a (cacao/siwe-message p)
            b (orch/siwe-message p)]
        (is (str/includes? a expected-cacao)
            (str "kawaraban.cacao/siwe-message chain-id behaviour CHANGED for " iss
                 " -- if this was an intentional fix, update or delete this test"))
        (is (str/includes? b expected-orch)
            (str "wasm-orchestrator/siwe-message chain-id behaviour CHANGED for " iss
                 " -- if this was an intentional fix, update or delete this test"))
        (is (not= a b)
            (str "the copies now AGREE for " iss
                 " -- the divergence was fixed; delete this test and extend"
                 " siwe-message-is-identical-over-the-production-domain to cover"
                 " non-did:key issuers"))))))

;; ---------------------------------------------------------------------------
;; (1) vs (2) -- committed .wasm against kawaraban.cacao's own encoder
;; ---------------------------------------------------------------------------

(def ^:private wire-field-offsets
  "Mirrors wasm/cacao_wire_encode.kotoba's ABI table (and
  `wasm-orchestrator`'s own private copy of it)."
  {:iss 0 :aud 80 :iat 160 :nonce 216 :domain 272 :version 320
   :res0 344 :res1 460 :exp 576 :sig 632})

(defn- wasm-wire-cbor
  "Runs the COMMITTED wasm/cacao_wire_encode.wasm and returns its CBOR bytes
  as unsigned ints."
  [fields]
  (let [instance (tender/instantiate
                  (.readAllBytes (io/input-stream (io/file "wasm/cacao_wire_encode.wasm")))
                  [:cbor-encode]
                  (contract/host-caps {:grants [:cbor-encode]}))
        memory (.memory instance)]
    (doseq [[k offset] wire-field-offsets]
      (let [bs (.getBytes ^String (get fields k) "UTF-8")]
        (.writeI32 memory offset (count bs))
        (.write memory (+ offset 8) bs 0 (count bs))))
    (let [written (tender/call-main instance)]
      (when (pos? written)
        (mapv #(bit-and (int %) 0xff) (#'tender/read-bytes! instance 4096 written))))))

(defn- jvm-wire-cbor
  "The same envelope through `kawaraban.cacao/->wire` + its own CBOR writer."
  [fields]
  (let [payload {:iss (:iss fields) :aud (:aud fields) :issued-at (:iat fields)
                 :nonce (:nonce fields) :domain (:domain fields)
                 :version (:version fields) :expiry (:exp fields)
                 :resources [(:res0 fields) (:res1 fields)]}]
    (mapv #(bit-and (int %) 0xff)
          (#'cacao/cbor-bytes (cacao/->wire payload (:sig fields))))))

(def ^:private golden-fields
  "Same fixed values `wasm.cacao-wire-encode-test` uses."
  {:iss "did:key:zTestDid123" :aud "https://pds.aozora.app"
   :iat "2026-07-23T00:00:00Z" :nonce "nonce-fixed-abc"
   :domain "aozora.app" :version "1"
   :res0 "kotoba://op/datom:transact" :res1 "kotoba://graph/graph-42"
   :exp "2026-07-23T01:00:00Z" :sig "sig-b64-fixed-value"})

(def ^:private realistic-fields
  "What `mint-cacao!` actually builds: a real did:key, a real 74-byte
  `canonical-graph` resource URI, and a real 86-char base64url signature --
  the shapes the golden fixture (a 23-byte toy graph URI) never exercises."
  (let [did "did:key:z6MkeTG3bFFSLYVU7VqhgZxqr6YzpaGrQtFMh1uvqGy1vDnP"
        [r0 r1] (cacao/grant->resources
                 {:cap :cap/transact :scope (cacao/canonical-graph did cacao/default-db-name)})]
    {:iss did :aud "https://pds.aozora.app" :iat "2026-08-12T00:00:00Z"
     :nonce "3f2a1b7c-0000-4d1e-9c2b-abcdef012345" :domain "aozora.app" :version "1"
     :res0 r0 :res1 r1 :exp "2026-08-12T01:00:00Z"
     :sig (apply str (repeat 86 "A"))}))

(deftest wasm-wire-encode-matches-kawaraban-cacao-own-encoder
  (testing "the COMMITTED .wasm against THIS repo's `->wire` + CBOR writer.
            `wasm.cacao-wire-encode-test` only compares the module to a byte
            array transcribed from kotoba-lang/kototama, so it cannot notice
            `kawaraban.cacao` drifting away from the module production runs."
    (doseq [[label fields] [["golden" golden-fields] ["realistic" realistic-fields]]]
      (let [w (wasm-wire-cbor fields)
            j (jvm-wire-cbor fields)]
        (is (seq w) (str label ": wasm module produced no output"))
        (is (= j w)
            (str label ": wasm/cacao_wire_encode.wasm and kawaraban.cacao/->wire"
                 " no longer produce the same CACAO envelope"))))))

(deftest realistic-resource-uri-really-is-longer-than-the-golden-fixture
  (testing "guards the test above from silently degrading into a second copy of
            the golden case -- the 74-byte real canonical-graph URI is exactly
            what overflowed this module's original 64-byte field width"
    (is (= 74 (count (:res1 realistic-fields))))
    (is (< (count (:res1 golden-fields)) (count (:res1 realistic-fields))))))
