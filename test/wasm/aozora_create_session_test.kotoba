(ns wasm.aozora-create-session-test
  "Hosts wasm/aozora_create_session.wasm via kototama.tender -- proves the
  confined slice of kawaraban.aozora/mint-session!'s HTTP half (build the
  com.atproto.server.createSession JSON body via json-encode, then http-post
  it) runs as a real WASM guest, and that kototama's SSRF denylist actually
  fires against a compiler-emitted (not hand-written WAT) guest -- the same
  proof shape kototama's own kotoba-compiled-http-fetch.wasm fixture
  established for http-fetch (ADR-2607230943 second wave).

  [com-junkawasaki/root \"Phase H\", 2026-07-23] wasm/aozora_create_session.kotoba
  now targets the REAL https://pds.aozora.app (a deliberate, separate
  recompile from this loopback-only build -- see wasm/README.md), so
  kototama's SSRF denylist alone no longer refuses it (a public HTTPS host
  is not loopback/private/link-local). This session STILL makes NO real
  internet calls: `caps` below explicitly sets `:allowed-url-prefixes []`
  (kototama.contract/url-allowed?'s documented fail-closed \"empty
  collection = deny all\" semantics, NOT the `nil` = unrestricted default),
  so http-post is GUARANTEED to return -1 regardless of which real host the
  literal names."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/aozora_create_session.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:http-post :json-encode]
                        :limits {:max-http-posts 1 :allowed-url-prefixes []}}))

(defn- run-create-session [cacao-text]
  (let [instance (tender/instantiate (wasm-bytes) [:http-post :json-encode] caps)
        memory (.memory instance)
        cacao-bytes (.getBytes ^String cacao-text "UTF-8")]
    (.writeI32 memory 0 (count cacao-bytes))
    (.write memory 8 cacao-bytes 0 (count cacao-bytes))
    (let [written (tender/call-main instance)]
      ;; alloc order in this module: pairs-ptr@2048 (2048B) ->
      ;; body-ptr@4096 (2200B) -> resp-ptr@6296 (2048B, widened from 512B
  ;; per Phase H go-live diagnostic -- real createSession responses can
  ;; exceed 512 bytes). See
      ;; wasm/README.md "Output buffer offset".
      {:written written
       :json-body (tender/read-memory-string
                   instance 4096
                   (count (str "{\"cacao\":\"" cacao-text "\"}")))})))

(deftest aozora-create-session-real-url-is-refused-by-empty-allowlist
  (testing "with `:allowed-url-prefixes []`, the now-real pds.aozora.app
            destination is refused before any HttpClient.send -- real
            compiler+tender LINKAGE + real allowlist-guard EXECUTION, not a
            live network round trip (no internet access in this session)"
    (let [{:keys [written]} (run-create-session "deadbeef-demo-cacao-blob")]
      (is (= -1 written)))))

(deftest aozora-create-session-body-shape-matches-xrpc-envelope
  (testing "the JSON body json-encode built is exactly
            com.atproto.server.createSession's real flat envelope shape
            (kawaraban.aozora/mint-session!'s (json-write {:cacao cacao}))"
    (let [{:keys [json-body]} (run-create-session "hello-cacao-token-text")]
      (is (= "{\"cacao\":\"hello-cacao-token-text\"}" json-body)))))

(deftest aozora-create-session-carries-arbitrary-host-provided-cacao-text
  (testing "the cacao text is genuinely dynamic (host-poked memory), not a
            compile-time literal -- proves per-run parameterization works
            under kototama's 0-arity main convention"
    (let [{:keys [json-body]} (run-create-session "another-different-token")]
      (is (= "{\"cacao\":\"another-different-token\"}" json-body)))))
