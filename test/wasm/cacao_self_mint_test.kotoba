(ns wasm.cacao-self-mint-test
  "Hosts wasm/cacao_self_mint.wasm (compiled from wasm/cacao_self_mint.kotoba,
  see wasm/README.md) via kototama.tender -- proves the confined slice of
  kawaraban.cacao/mint (Ed25519 keypair generation, SHA-256 fingerprint,
  Ed25519 signing, flat CBOR encode) runs as a real WASM guest hosted by a
  real Chicory Instance, not a mock.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity, same convention as cloud-itonami-isic-6310/-6419/-6511's wasm
  ports); the SIWE message text is written into the guest's exported linear
  memory at fixed offsets before calling main() -- see
  wasm/cacao_self_mint.kotoba's header comment for the exact offset layout,
  and wasm/README.md for why (msg-len, seed/fingerprint/sig, pairs, CBOR
  output) each land at the addresses this test reads."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender])
  (:import [java.security MessageDigest]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/cacao_self_mint.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:gen-keypair :sign :sha256-hex :cbor-encode]
                        :limits {:allow-secret-imports? true}}))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn- run-mint [siwe-message]
  (let [instance (tender/instantiate (wasm-bytes)
                                     [:gen-keypair :sign :sha256-hex :cbor-encode]
                                     caps)
        memory (.memory instance)
        msg-bytes (.getBytes ^String siwe-message "UTF-8")]
    (.writeI32 memory 0 (count msg-bytes))
    (.write memory 8 msg-bytes 0 (count msg-bytes))
    (let [written (tender/call-main instance)]
      {:instance instance
       :written written
       ;; alloc's bump allocator hands out, in this module's fixed evaluation
       ;; order: seed-ptr@2048 (64B) -> fp-ptr@2112 (64B) -> sig-ptr@2176
       ;; (64B) -> pairs-ptr@2240 (256B) -> out-ptr@2496 (512B). See
       ;; wasm/README.md "Output buffer offset".
       :seed (#'tender/read-bytes! instance 2048 32)
       :pub (#'tender/read-bytes! instance 2080 32)
       :fingerprint-hex (tender/read-memory-string instance 2112 64)
       :sig (#'tender/read-bytes! instance 2176 64)
       :cbor (when (pos? written) (#'tender/read-bytes! instance 2496 written))})))

(deftest cacao-self-mint-produces-nonempty-cbor
  (testing "main returns a positive bytes-written count -- cbor-encode succeeded"
    (let [{:keys [written]} (run-mint "aozora.app wants you to sign in with your Ethereum account:\ndemo\n\nURI: https://pds.aozora.app\nVersion: 1")]
      (is (pos? written)))))

(deftest cacao-self-mint-cbor-is-a-definite-length-3-entry-map
  (testing "CBOR major-type 5 (map) header byte 0xA3 = definite-length map, 3 entries
            (type/fingerprint/sig) -- matches cbor-encode's flat-pairs contract"
    (let [{:keys [cbor]} (run-mint "test message for cbor shape")]
      (is (= 0xA3 (bit-and (int (aget cbor 0)) 0xff))))))

(deftest cacao-self-mint-fingerprint-is-real-sha256-of-pubkey
  (testing "the `fingerprint` field genuinely is sha256-hex(pubkey) -- the
            real hash/sha256 capability ran inside the guest, not a stub"
    (let [{:keys [pub fingerprint-hex]} (run-mint "fingerprint check message")
          real-hex (hex (.digest (MessageDigest/getInstance "SHA-256") pub))]
      (is (= real-hex fingerprint-hex)))))

(deftest cacao-self-mint-signature-verifies-against-the-guest-generated-pubkey
  (testing "the `sig` field genuinely verifies (real Ed25519, not a stub) against
            the SAME message this test wrote into guest memory and the SAME
            pubkey gen-keypair derived from the seed it wrote alongside it"
    (let [msg "kawaraban CACAO self-mint signature verification message"
          {:keys [pub sig]} (run-mint msg)
          verify (requiring-resolve 'ed25519.core/verify)]
      (is (true? (verify pub (.getBytes ^String msg "UTF-8") sig))))))

(deftest cacao-self-mint-different-messages-yield-different-signatures
  (testing "sanity: signing is message-sensitive, not a constant stub"
    (let [{sig-a :sig} (run-mint "message A")
          {sig-b :sig} (run-mint "message B")]
      (is (not= (seq sig-a) (seq sig-b))))))
