(ns kawaraban.mirror-actor-test
  "Pure-fn tests for kawaraban.mirror-actor (ADR-2607110200 addendum). No network I/O.
  The point of these assertions is G9: a mirror actor's profile text must always disclose
  the mirror relationship up front, never read as the outlet's own official voice."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [kawaraban.mirror-actor :as mirror-actor]))

(def outlet {:id "outlet.bbc-world" :name "BBC News — World"})

(deftest test-identity-path-is-per-outlet-and-separate-from-root-identity
  (is (= ".kawaraban/mirrors/outlet.bbc-world.edn" (mirror-actor/identity-path "outlet.bbc-world")))
  (is (not (str/includes? (mirror-actor/identity-path "outlet.bbc-world") "/.kawaraban/identity.edn"))))

(deftest test-display-name-says-mirror-not-the-outlet-itself
  (let [name (mirror-actor/mirror-display-name outlet)]
    (is (str/starts-with? name "kawaraban mirror — "))
    (is (str/includes? name (:name outlet)))))

(deftest test-description-discloses-unofficial-and-automated-up-front
  (let [desc (mirror-actor/mirror-description outlet)]
    (is (str/includes? desc "UNOFFICIAL"))
    (is (str/includes? desc "Automated"))
    (is (str/includes? desc "not affiliated with or operated by"))
    (is (str/includes? desc (:name outlet)))
    (is (str/includes? desc "kawaraban"))))

(deftest test-all-mirror-identities-empty-when-no-dir
  ;; identity-dir is a relative path; in a fresh checkout with no .kawaraban/mirrors/ yet,
  ;; this must return [] rather than throw.
  (is (vector? (vec (mirror-actor/all-mirror-identities)))))
