(ns kawaraban.methods.test-analyze
  "kawaraban — tests for the edition composer (analyze.cljc). 1:1 port of test_analyze.py."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [kawaraban.methods.route :as route]
            [kawaraban.methods.analyze :as a]))

(defn- seed-path [] (clojure.java.io/file "data/seed.edn"))

(defn- c [] (a/compose (route/load-edn (seed-path))))

(deftest test-compose-has-leads-and-sections
  (let [cc (c)]
    (is (= 4 (count (get cc "leads"))) (count (get cc "leads")))
    (is (seq (get-in cc ["by_men" "international"])) "international 面 empty")
    (is (seq (get-in cc ["by_men" "economy"])) "economy 面 empty")))

(deftest test-rank-signals-are-public-good-only
  ;; G2 — used signals ⊆ allowlist; no engagement/paid signal exists.
  (doseq [s a/USED-SIGNALS]
    (is (some #{s} a/ALLOWED-RANK-SIGNALS) s))
  (doseq [banned ["paid-placement" "sponsored" "engagement" "dwell-time"]]
    (is (not (some #{banned} a/ALLOWED-RANK-SIGNALS)) banned)))

(deftest test-assert-rank-signals-refuses-engagement
  (is (nil? (a/assert-rank-signals a/USED-SIGNALS)))  ;; ok
  (doseq [banned ["engagement" "paid-placement" "dwell-time"]]
    (try (a/assert-rank-signals [banned])
         (is false (str "expected G2 refusal for " banned))
         (catch clojure.lang.ExceptionInfo e
           (is (str/includes? (.getMessage e) "G2") (.getMessage e))))))

(deftest test-score-monotonic-in-recency
  (let [mentions []
        a-new {":news.article/id" "n" ":news.article/as-of" 100}
        a-old {":news.article/id" "o" ":news.article/as-of" 0}
        s-new (a/score a-new mentions 100 0 #{})
        s-old (a/score a-old mentions 100 0 #{})]
    (is (> s-new s-old) [s-new s-old])))

(deftest test-render-md-contains-front-and-wire
  (let [md (a/render-md (c))]
    (is (str/includes? md "一面"))
    (is (str/includes? md "Actor-to-actor wire"))
    (is (or (str/includes? (str/lower md) "no full") (str/includes? md "G4")))
    (is (str/includes? md "([link]("))))

(deftest test-render-edn-is-unpublished-and-not-final
  (let [edn (a/render-edn (c))]
    (is (str/includes? edn ":news.issue/published false"))
    (is (str/includes? edn ":news.issue/final false"))
    (is (str/includes? edn ":news.issue/server-held-key false"))
    (is (str/includes? edn ":news.medium.link/"))))

(deftest test-compose-refuses-charter-violating-seed
  (let [bad (conj (route/load-edn (seed-path))
                  {":news.article/id" "art.bad" ":news.article/kind" ":mirror"
                   ":news.article/outlet" "o" ":news.article/url" "u"
                   ":news.article/truth-rating" 5})]
    (try (a/compose bad)
         (is false "expected G1 refusal during compose()")
         (catch clojure.lang.ExceptionInfo e
           (is (str/includes? (.getMessage e) "G1") (.getMessage e))))))
