(ns wasm.identity-sign-test
  "Hosts wasm/identity_sign.wasm (compiled from wasm/identity_sign.kotoba,
  see wasm/README.md) via kototama.tender -- proves this NEW Phase G module
  signs with a HOST-SUPPLIED (persisted) 32-byte Ed25519 seed, not a fresh
  one generated inside the guest -- the gap cacao_self_mint.kotoba's own
  `main` cannot close (it always calls gen-keypair internally). This is
  what makes a per-outlet mirror identity that persists ACROSS runs
  possible over the wasm path: mint once (cacao_self_mint.kotoba), persist
  the seed, then sign many times thereafter with THIS module + the SAME
  persisted seed.

  ABI: main is 0-arity; the host writes a raw 32-byte seed at offset 0 and
  a length-prefixed UTF-8 message at offset 32/40 before calling main() --
  see wasm/identity_sign.kotoba's header comment for the exact layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender])
  (:import [java.security SecureRandom]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/identity_sign.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:sign]
                        :limits {:allow-secret-imports? true}}))

(defn- run-sign [^bytes seed ^String message]
  (let [instance (tender/instantiate (wasm-bytes) [:sign] caps)
        memory (.memory instance)
        msg-bytes (.getBytes message "UTF-8")]
    (.write memory 0 seed 0 32)
    (.writeI32 memory 32 (count msg-bytes))
    (.write memory 40 msg-bytes 0 (count msg-bytes))
    (let [written (tender/call-main instance)]
      {:written written
       ;; this module's FIRST and ONLY alloc call -> heap-base (2048,
       ;; verified against the real compile in wasm/README.md -- this
       ;; module references zero string literals, so the compiler's
       ;; memory-layout leaves the literal region empty and heap-base
       ;; falls back to its own minimum, 2048).
       :sig (when (pos? written) (#'tender/read-bytes! instance 2048 written))})))

(defn- fresh-seed ^bytes []
  (let [seed (byte-array 32)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(deftest identity-sign-produces-a-64-byte-signature
  (testing "main returns exactly 64 -- sign's real bytes-written count"
    (let [{:keys [written]} (run-sign (fresh-seed) "hello kawaraban")]
      (is (= 64 written)))))

(deftest identity-sign-signature-verifies-against-the-host-supplied-seed
  (testing "the signature genuinely verifies (real Ed25519, not a stub)
            against the SAME seed the host wrote into guest memory --
            proves this module signs with a HOST-SUPPLIED seed, not one
            it generated itself (it has no gen-keypair import at all --
            see wasm/identity_sign.kotoba's own capability policy)"
    (let [seed (fresh-seed)
          msg "kawaraban wasm-orchestrator CACAO session message"
          {:keys [sig]} (run-sign seed msg)
          pubkey-from-seed (requiring-resolve 'ed25519.core/pubkey-from-seed)
          verify (requiring-resolve 'ed25519.core/verify)
          pub (pubkey-from-seed seed)]
      (is (true? (verify pub (.getBytes ^String msg "UTF-8") sig))))))

(deftest identity-sign-same-seed-same-message-is-deterministic
  (testing "Ed25519 signing is deterministic (RFC 8032) -- signing the
            SAME message with the SAME persisted seed twice, in two
            SEPARATE guest instantiations, yields the IDENTICAL signature
            -- this is exactly the property that makes 'persist a seed,
            sign many times later' meaningful (a non-deterministic scheme
            would still verify, but couldn't be used to prove two
            different ticks used the same identity's key material without
            re-verifying every time)"
    (let [seed (fresh-seed)
          msg "determinism check message"
          {sig-a :sig} (run-sign seed msg)
          {sig-b :sig} (run-sign seed msg)]
      (is (= (seq sig-a) (seq sig-b))))))

(deftest identity-sign-different-seeds-yield-different-signatures-for-the-same-message
  (testing "sanity: the seed genuinely determines the key, not a constant
            stub -- two DIFFERENT persisted seeds signing the SAME message
            produce DIFFERENT signatures"
    (let [msg "same message, different identity"
          {sig-a :sig} (run-sign (fresh-seed) msg)
          {sig-b :sig} (run-sign (fresh-seed) msg)]
      (is (not= (seq sig-a) (seq sig-b))))))

(deftest identity-sign-different-messages-yield-different-signatures
  (testing "sanity: signing is message-sensitive, not a constant stub"
    (let [seed (fresh-seed)
          {sig-a :sig} (run-sign seed "message A")
          {sig-b :sig} (run-sign seed "message B")]
      (is (not= (seq sig-a) (seq sig-b))))))

(deftest identity-sign-has-no-gen-keypair-import
  (testing "T3 capability confinement: this module's compiled import
            section genuinely does not contain gen_keypair -- granting
            ONLY :sign (this test's own `caps`) is sufficient to
            instantiate and run it end-to-end, proving the guest never
            asks for keypair generation at all (verified by the absence of
            a :grant/missing denial for gen-keypair, which would have
            thrown during instantiate/call-main above if the compiled
            binary had imported it without a matching grant)"
    (is (some? (run-sign (fresh-seed) "capability confinement sanity")))))
