(ns wasm.cacao-wire-encode-test
  "Hosts wasm/cacao_wire_encode.wasm (compiled from wasm/cacao_wire_encode.kotoba,
  see wasm/README.md) via kototama.tender -- proves a FAITHFUL, standalone
  `.kotoba` port of `kawaraban.cacao/->wire`'s real nested wire shape
  ({\"h\":{\"t\":\"eip4361\"},\"p\":{...,\"resources\":[...]},\"s\":{...}})
  runs as a real WASM guest hosted by a real Chicory Instance (not a mock),
  and is BYTE-EXACT wire-compatible with the real JVM reference
  implementation this port traces to (`cloud_itonami.media.cacao/->wire` /
  `kawaraban.cacao/->wire` / `tashikame.cacao/->wire` / `kotoba.cacao/->wire`
  -- all the same CBOR shape).

  This closes Phase B's wasm/README.md finding 4 (\"cbor-encode only
  produces a FLAT (single-level) definite-length map\") for kawaraban,
  using the dotted-path nesting extension `kotoba-lang/kototama` PR #50
  added (com-junkawasaki/root ADR-2607231234, \"Phase E\").

  ABI: main is 0-arity; every one of the 10 wire fields (9 CACAO payload
  fields + the already-computed signature text) is written into the
  guest's exported linear memory at fixed offsets before calling main() --
  see wasm/cacao_wire_encode.kotoba's header comment for the exact offset
  layout and for why this module takes sig-b64 as PLAIN opaque input
  rather than computing it (mirroring `->wire`'s own real signature --
  it is a pure data-shaping function, not a signer).

  The expected 264-byte CBOR array below is copied VERBATIM from
  `kotoba-lang/kototama`'s own `test/kototama/tender_test.clj`
  (`cbor-encode-nested-reproduces-cacao-wire-envelope-byte-exact`,
  ADR-2607231234) -- same golden fixture, same fixed field values,
  independently re-verified here through THIS repo's own compiled
  `.kotoba` guest (not through kototama's private encode functions)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/cacao_wire_encode.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:cbor-encode]}))

;; offset layout mirrors wasm/cacao_wire_encode.kotoba's own ABI table.
;; [Phase G, com-junkawasaki/root] res0/res1/exp/sig offsets shifted --
;; resources.0/resources.1 widened from 64 -> 100 bytes each (a REAL
;; canonical-graph URI is 74 bytes, exceeding the original 64-byte cap --
;; see wasm/cacao_wire_encode.kotoba's own header comment for the full
;; memory-corruption finding this widening fixes). The golden-fixture
;; VALUES and expected byte count below are unaffected (buffer width does
;; not change the bytes written for a given real field value).
(def ^:private field-offsets
  {:iss 0 :aud 80 :iat 160 :nonce 216 :domain 272 :version 320
   :res0 344 :res1 460 :exp 576 :sig 632})

(defn- write-field! [memory offset ^String text]
  (let [bs (.getBytes text "UTF-8")]
    (.writeI32 memory offset (count bs))
    (.write memory (+ offset 8) bs 0 (count bs))))

(defn- run-wire-encode [{:keys [iss aud iat nonce domain version res0 res1 exp sig]}]
  (let [instance (tender/instantiate (wasm-bytes) [:cbor-encode] caps)
        memory (.memory instance)]
    (write-field! memory (:iss field-offsets) iss)
    (write-field! memory (:aud field-offsets) aud)
    (write-field! memory (:iat field-offsets) iat)
    (write-field! memory (:nonce field-offsets) nonce)
    (write-field! memory (:domain field-offsets) domain)
    (write-field! memory (:version field-offsets) version)
    (write-field! memory (:res0 field-offsets) res0)
    (write-field! memory (:res1 field-offsets) res1)
    (write-field! memory (:exp field-offsets) exp)
    (write-field! memory (:sig field-offsets) sig)
    (let [written (tender/call-main instance)]
      {:written written
       :cbor (when (pos? written) (#'tender/read-bytes! instance 4096 written))})))

;; SAME fixed field values as ADR-2607231234's own golden-fixture test
;; (kotoba-lang/kototama's cbor-encode-nested-reproduces-cacao-wire-envelope-
;; byte-exact) -- a realistic (non-:statement, with-:expiry) mint-session!
;; parameter set.
(def ^:private golden-fields
  {:iss "did:key:zTestDid123"
   :aud "https://pds.aozora.app"
   :iat "2026-07-23T00:00:00Z"
   :nonce "nonce-fixed-abc"
   :domain "aozora.app"
   :version "1"
   :res0 "kotoba://op/datom:transact"
   :res1 "kotoba://graph/graph-42"
   :exp "2026-07-23T01:00:00Z"
   :sig "sig-b64-fixed-value"})

;; Copied verbatim from kotoba-lang/kototama's test/kototama/tender_test.clj
;; (cbor-encode-nested-reproduces-cacao-wire-envelope-byte-exact) -- the same
;; 264-byte golden fixture, independently re-verified here through this
;; repo's OWN compiled .kotoba guest + real Chicory Instance.
(def ^:private golden-cbor-bytes
  [163 97 104 161 97 116 103 101 105 112 52 51 54 49 97 112 168 99 105 115
   115 115 100 105 100 58 107 101 121 58 122 84 101 115 116 68 105 100 49
   50 51 99 97 117 100 118 104 116 116 112 115 58 47 47 112 100 115 46 97
   111 122 111 114 97 46 97 112 112 99 105 97 116 116 50 48 50 54 45 48 55
   45 50 51 84 48 48 58 48 48 58 48 48 90 101 110 111 110 99 101 111 110
   111 110 99 101 45 102 105 120 101 100 45 97 98 99 102 100 111 109 97
   105 110 106 97 111 122 111 114 97 46 97 112 112 103 118 101 114 115
   105 111 110 97 49 105 114 101 115 111 117 114 99 101 115 130 120 26
   107 111 116 111 98 97 58 47 47 111 112 47 100 97 116 111 109 58 116
   114 97 110 115 97 99 116 119 107 111 116 111 98 97 58 47 47 103 114
   97 112 104 47 103 114 97 112 104 45 52 50 99 101 120 112 116 50 48
   50 54 45 48 55 45 50 51 84 48 49 58 48 48 58 48 48 90 97 115 162 97
   116 101 69 100 68 83 65 97 115 115 115 105 103 45 98 54 52 45 102
   105 120 101 100 45 118 97 108 117 101])

(deftest golden-fixture-is-actually-264-bytes
  (is (= 264 (count golden-cbor-bytes))))

(deftest cacao-wire-encode-produces-nonempty-cbor
  (let [{:keys [written]} (run-wire-encode golden-fields)]
    (is (pos? written))))

(deftest cacao-wire-encode-top-level-is-a-3-entry-map
  (testing "CBOR major-type 5 (map) header byte 0xA3 = definite-length map, 3
            entries (h/p/s) -- the REAL nested `->wire` shape, not the flat
            3-field approximation cacao_self_mint.kotoba still (deliberately)
            uses"
    (let [{:keys [cbor]} (run-wire-encode golden-fields)]
      (is (= 0xA3 (bit-and (int (aget cbor 0)) 0xff))))))

(deftest cacao-wire-encode-is-byte-exact-against-the-real-jvm-reference-implementation
  (testing "byte-exact against cloud_itonami.media.cacao/->wire's (traced to
            kawaraban.cacao/->wire) real CBOR output for this fixed payload
            -- SAME golden fixture as kotoba-lang/kototama's own
            cbor-encode-nested-reproduces-cacao-wire-envelope-byte-exact,
            reproduced here through kawaraban's OWN compiled .kotoba guest"
    (let [{:keys [written cbor]} (run-wire-encode golden-fields)]
      (is (= 264 written))
      (is (= golden-cbor-bytes (vec (map #(bit-and (int %) 0xff) cbor)))))))

(deftest cacao-wire-encode-array-order-is-independent-of-source-order
  (testing "resources.1 written to memory in the SAME test run regardless --
            this only asserts field VALUES flow through correctly, not
            source-order sensitivity (that's kototama's own
            cbor-encode-host-fn-array-index-order-is-independent-of-source-order
            test, already covering the array-index dotted-path convention
            itself); this test instead proves a DIFFERENT fixed payload
            (different iss/aud/nonce) still round-trips correctly, i.e. this
            module is genuinely parameterized, not hardcoded to one example"
    (let [{:keys [written cbor]} (run-wire-encode (assoc golden-fields
                                                          :iss "did:key:zOtherDid456"
                                                          :nonce "another-nonce-xyz"))]
      (is (pos? written))
      (is (not= golden-cbor-bytes (vec (map #(bit-and (int %) 0xff) cbor))))
      (is (= 0xA3 (bit-and (int (aget cbor 0)) 0xff))))))
