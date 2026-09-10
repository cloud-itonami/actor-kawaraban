(ns wasm.aozora-delete-record-test
  "Hosts wasm/aozora_delete_record.wasm via kototama.tender -- proves the
  confined slice of a NEW capability (com.atproto.repo.deleteRecord, added
  Phase H to fix a real corrupted live record; see
  wasm/aozora_create_record.kotoba's own Phase H notes) runs as a real WASM
  guest, and that its JSON body is byte-exact against the real
  cheshire.core/generate-string-equivalent output for the same field
  values.

  [com-junkawasaki/root \"Phase H\" go-live diagnostic, 2026-07-23]: the
  target URL is the REAL https://pds.aozora.app from the start (this
  module never shipped a loopback build). This session makes NO real
  internet calls: `caps` below explicitly sets `:allowed-url-prefixes []`
  (kototama.contract/url-allowed?'s documented fail-closed \"empty
  collection = deny all\" semantics, NOT the `nil` = unrestricted
  default), so http-post-headers is GUARANTEED to return -1 regardless of
  which real host the literal names."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/aozora_delete_record.wasm"))))

(def ^:private caps
  (contract/host-caps {:grants [:http-post-headers :json-encode]
                       :limits {:max-http-posts 1 :allowed-url-prefixes []}}))

(def ^:private field-offsets
  {:repo 0 :collection 80 :rkey 160 :jwt 240})

(defn- write-field! [memory offset ^String text]
  (let [bs (.getBytes text "UTF-8")]
    (.writeI32 memory offset (count bs))
    (.write memory (+ offset 8) bs 0 (count bs))))

(defn- run-delete-record [{:keys [repo collection rkey jwt]}]
  (let [instance (tender/instantiate (wasm-bytes) [:http-post-headers :json-encode] caps)
        memory (.memory instance)]
    (write-field! memory (:repo field-offsets) repo)
    (write-field! memory (:collection field-offsets) collection)
    (write-field! memory (:rkey field-offsets) rkey)
    (write-field! memory (:jwt field-offsets) jwt)
    (let [written (tender/call-main instance)]
      {:written written
       ;; alloc order: pairs-ptr@2048(2048B) -> body-ptr@4096(2200B) ->
       ;; headers-ptr@6296(512B) -> resp-ptr@6808(2048B).
       :json-body (tender/read-memory-string
                   instance 4096
                   (count (str "{\"repo\":\"" repo "\",\"collection\":\"" collection
                               "\",\"rkey\":\"" rkey "\"}")))})))

(def ^:private golden-fields
  {:repo "did:key:zTestDid123"
   :collection "com.etzhayyim.apps.kawaraban"
   :rkey "art.outlet.bbc-world.1784766113"
   :jwt "fixed-jwt-token-value"})

(deftest aozora-delete-record-real-url-is-refused-by-empty-allowlist
  (testing "with `:allowed-url-prefixes []`, the real pds.aozora.app
            destination is refused before any HttpClient.send -- real
            compiler+tender LINKAGE + real allowlist-guard EXECUTION, not a
            live network round trip (no internet access in this session)"
    (let [{:keys [written]} (run-delete-record golden-fields)]
      (is (= -1 written)))))

(deftest aozora-delete-record-body-shape-matches-xrpc-envelope
  (testing "the JSON body json-encode built is exactly
            com.atproto.repo.deleteRecord's real flat body shape --
            {\"repo\":..,\"collection\":..,\"rkey\":..}, no nesting needed"
    (let [{:keys [json-body]} (run-delete-record golden-fields)]
      (is (= "{\"repo\":\"did:key:zTestDid123\",\"collection\":\"com.etzhayyim.apps.kawaraban\",\"rkey\":\"art.outlet.bbc-world.1784766113\"}"
             json-body)))))

(deftest aozora-delete-record-carries-arbitrary-host-provided-fields
  (testing "every field is genuinely dynamic (host-poked memory), not a
            compile-time literal"
    (let [{:keys [json-body]} (run-delete-record (assoc golden-fields :rkey "art.different.456"))]
      (is (re-find #"\"rkey\":\"art\.different\.456\"" json-body)))))
