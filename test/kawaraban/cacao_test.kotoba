(ns kawaraban.cacao-test
  "Pure crypto round-trip tests for kawaraban.cacao (ADR-2607110200). No network I/O."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [kawaraban.cacao :as cacao]))

(deftest test-generate-identity-shape
  (let [id (cacao/generate-identity)]
    (is (str/starts-with? (:did id) "did:key:z"))
    (is (string? (:graph id)))
    (is (str/starts-with? (:graph id) "b"))))

(deftest test-canonical-graph-deterministic
  (let [did "did:key:z6MkTestTestTestTestTestTestTestTestTestTest"]
    (is (= (cacao/canonical-graph did cacao/default-db-name)
           (cacao/canonical-graph did cacao/default-db-name)))))

(deftest test-default-db-name-is-kawaraban
  (is (= "kawaraban" cacao/default-db-name)))

(deftest test-load-or-create-identity-round-trip
  (let [tmp (java.io.File/createTempFile "kawaraban-identity-test" ".edn")]
    (.delete tmp)
    (try
      (let [id1 (cacao/load-or-create-identity! (.getAbsolutePath tmp))
            id2 (cacao/load-or-create-identity! (.getAbsolutePath tmp))]
        (is (.exists tmp))
        (is (= (:did id1) (:did id2)) "second call reloads the SAME identity, does not regenerate"))
      (finally (io/delete-file tmp true)))))

(deftest test-mint-produces-nonempty-cacao
  (let [id (cacao/generate-identity)
        cacao-b64 (cacao/mint id {:cap :cap/transact :scope (:graph id)}
                              {:aud "https://pds.aozora.app" :nonce "n1"
                               :issued-at "2026-07-10T00:00:00Z" :expiry "2026-07-10T01:00:00Z"})]
    (is (string? cacao-b64))
    (is (pos? (count cacao-b64)))))

(deftest test-siwe-message-includes-domain-and-resources
  (testing "domain defaults to aozora.app (not tashikame's gftd.office)"
    (let [payload (cacao/grant->payload {:cap :cap/transact :scope "graph-x"}
                                        {:iss "did:key:zTest" :aud "https://pds.aozora.app"
                                         :nonce "n" :issued-at "t" :expiry "e"})
          msg (cacao/siwe-message payload)]
      (is (str/includes? msg "aozora.app wants you to sign in"))
      (is (str/includes? msg "Resources:")))))
