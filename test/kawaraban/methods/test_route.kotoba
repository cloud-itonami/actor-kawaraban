(ns kawaraban.methods.test-route
  "kawaraban — tests for the medium/routing core (route.cljc). 1:1 port of test_route.py."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [kawaraban.methods.route :as route]))

(def W "did:web:etzhayyim.com:actor:")

(defn- seed-path [] (clojure.java.io/file "data/seed.edn"))

(defn- rows [] (route/load-edn (seed-path)))

(deftest test-classify-counts
  (let [[outlets sections articles mentions wires] (route/classify (rows))]
    (is (= 7 (count outlets)) (count outlets))
    (is (= 10 (count sections)) (count sections))
    (is (= 12 (count articles)) (count articles))
    (is (= 24 (count mentions)) (count mentions))
    (is (= 9 (count wires)) (count wires))))

(deftest test-validate-passes-on-seed
  (let [[_ _ articles _ _] (route/classify (rows))]
    (is (nil? (route/validate articles)))))   ;; must not throw

(defn- throws-with [f sub]
  (try (f) (is false "expected a refusal")
       (catch clojure.lang.ExceptionInfo e
         (is (str/includes? (.getMessage e) sub) (.getMessage e)))))

(deftest test-validate-refuses-verdict
  (throws-with #(route/validate [{":news.article/id" "x" ":news.article/kind" ":mirror"
                                  ":news.article/outlet" "o" ":news.article/url" "u"
                                  ":news.article/verdict" true}])
               "G1"))

(deftest test-validate-refuses-full-text
  (throws-with #(route/validate [{":news.article/id" "x" ":news.article/kind" ":mirror"
                                  ":news.article/outlet" "o" ":news.article/url" "u"
                                  ":news.article/full-text" "the whole body"}])
               "G4"))

(deftest test-validate-refuses-unknown-kind
  (throws-with #(route/validate [{":news.article/id" "x" ":news.article/kind" ":original"}])
               "G11"))

(deftest test-validate-refuses-mirror-without-url
  (throws-with #(route/validate [{":news.article/id" "x" ":news.article/kind" ":mirror"
                                  ":news.article/outlet" "o"}])
               "url"))

(deftest test-validate-refuses-actor-event-without-provenance
  (throws-with #(route/validate [{":news.article/id" "x" ":news.article/kind" ":actor-event"
                                  ":news.article/source-actor" "did:web:...:danjo"}])
               "source-tid"))

(deftest test-wire-table-maps-actors-to-men
  (let [[_ _ _ _ wires] (route/classify (rows))
        t (route/wire-table wires)]
    (is (= "politics" (get t (str W "danjo"))) (get t (str W "danjo")))
    (is (= "economy" (get t (str W "kanae"))) (get t (str W "kanae")))
    (is (= "international" (get t (str W "watari"))) (get t (str W "watari")))
    (is (= "culture" (get t (str W "kataribe"))) (get t (str W "kataribe")))))

(deftest test-actor-links-chokepoint-cluster
  (let [[_ _ articles mentions _] (route/classify (rows))
        [edges degree] (route/actor-links articles mentions)
        wa (str W "watari") wt (str W "watatsuna") mi (str W "mitooshi")]
    (is (= 4 (get edges #{wa wt})) (get edges #{wa wt}))
    (is (= 2 (get edges #{wa mi})) (get edges #{wa mi}))
    (is (= 1 (get edges #{(str W "danjo") (str W "ooyake")})))
    (is (>= (get degree wt) 2) (get degree wt))))

(deftest test-actor-targets-excludes-entities
  (let [[_ _ _ mentions _] (route/classify (rows))
        tgts (route/actor-targets "art.e1" mentions)]
    (is (some #{(str W "danjo")} tgts))
    (is (not (some #{"gov.jp.mof"} tgts)))))

(deftest test-actor-wire-constant-has-no-duplicates-collapsing-men
  (let [valid #{"front" "politics" "economy" "international" "society"
                "culture" "science" "sports" "local" "opinion"}]
    (doseq [[actor men] route/ACTOR-WIRE]
      (is (contains? valid men) [actor men]))))
