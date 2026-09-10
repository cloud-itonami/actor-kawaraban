(ns wasm.aozora-extract-session-fields-test
  "Hosts wasm/aozora_extract_session_fields.wasm via kototama.tender --
  proves the confined slice of kawaraban.aozora/mint-session!'s response
  half (`(get sbody \"accessJwt\")`) runs as a real WASM guest. Uses a
  literal example com.atproto.server.createSession response body (same
  'known example, not live data' pattern kototama's OWN
  kotoba-compiled-json-extract-field.kotoba fixture already established for
  `{\"k\":\"v\"}` -- see wasm/README.md for why this module's input is a
  compile-time literal rather than host-poked memory)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/aozora_extract_session_fields.wasm"))))

(deftest aozora-extract-accessJwt-from-example-createSession-response
  (testing "json-extract-field pulls \"demo.session.jwt\" out of the literal
            example {\"did\":...,\"accessJwt\":\"demo.session.jwt\"} response
            -- the exact field kawaraban.aozora/mint-session! reads"
    (let [instance (tender/instantiate (wasm-bytes) [:json-extract-field]
                                        (contract/host-caps {:grants [:json-extract-field]}))
          written (tender/call-main instance)]
      (is (= 16 written))
      (is (= "demo.session.jwt" (tender/read-memory-string instance 2048 written))))))
