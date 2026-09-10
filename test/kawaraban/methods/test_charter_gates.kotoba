(ns kawaraban.methods.test-charter-gates
  "kawaraban 瓦版 — constitutional-gate conformance tests (manifest + lexicons).

  Substrate-native Clojure (ADR-2606160842 py→clj port wave; clj + datomic first tier).
  Reads the Datomic projections under `data/lex-datoms` and canonical `manifest.edn`.

  kawaraban is the news MEDIUM — the charter-clean inverse of a news app. Its 11 gates are
  structural in the lexicons: never rules truth (G1), never republishes the body (G4 link-out),
  never personalizes (G3), never speaks AS anyone (G9), medium-not-source (G11, no :original),
  never ad/engagement-ranks (G2), never holds a server key (G7), never declares finality (G10)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.edn :as edn]
            [clojure.set :as set]))

#?(:clj
   (do
     (def ^:private actor-dir (.getCanonicalFile (java.io.File. ".")))
     (def ^:private lexdir (java.io.File. actor-dir "data/lex-datoms"))
     (defn- lex [name] (edn/read-string (slurp (java.io.File. lexdir (str name ".edn")))))
     (defn- manifest []
       (let [m (edn/read-string (slurp (java.io.File. actor-dir "manifest.edn")))
             gates (into {} (map (fn [g] [(:gate/id g) g]) (:actor/gates m)))]
         {"constitutionalGates" {"gates" gates}}))))

;; ── generic walkers: collect {field-keyword → attr-value} anywhere in the lexicon tree ──
(defn- collect [doc attr]
  (let [acc (atom {})]
    (letfn [(walk [x parent]
              (cond
                (map? x) (do (when (and (keyword? parent) (contains? x attr))
                               (swap! acc assoc parent (get x attr)))
                             (doseq [[k v] x] (walk v k)))
                (sequential? x) (doseq [v x] (walk v parent))
                :else nil))]
      (walk doc nil))
    @acc))

(defn- a-const [doc field] (get (collect doc :const) field))
(defn- enum-of [doc field] (some-> (get (collect doc :enum) field) (->> (map str) set)))
(defn- maxlen [doc field] (get (collect doc :maxLength) field))

(def RANK-SIGNALS #{"recency" "section-fit" "source-diversity" "actor-relevance" "geo-proximity"})

;; ── full gate set ──
(deftest all-11-gates-declared
  (let [gates (set (keys (get-in (manifest) ["constitutionalGates" "gates"])))]
    (is (= gates (set (map #(str "G" %) (range 1 12)))) "manifest must declare G1–G11")))

;; ── G1 — mirror, not adjudicator: no verdict / truth-rating ──
(deftest g1-no-verdict
  (let [a (lex "article")]
    (is (= false (a-const a :verdict)) "G1: article.verdict const false")
    (is (= 0 (a-const a :truthRating)) "G1: article.truthRating const 0")))

;; ── G4 — link-out, never the body; bounded fair-use excerpt; no paywall ──
(deftest g4-link-out-no-full-text
  (is (= false (a-const (lex "article") :fullText)) "G4: article.fullText const false")
  (is (= 280 (maxlen (lex "article") :excerpt)) "G4: excerpt bounded to 280")
  (is (= #{"open" "registration-wall"} (enum-of (lex "outlet") :access))
      "G4: outlet access excludes paywalled/terminal feeds"))

;; ── G3 — identical 面 for all; no per-reader personalization ──
(deftest g3-no-personalization
  (is (= false (a-const (lex "article") :personalizedFor)) "G3: article.personalizedFor const false"))

;; ── G9 — never speaks AS an outlet or another actor ──
(deftest g9-no-speak-as
  (is (= false (a-const (lex "article") :speakAs)) "G9: article.speakAs const false"))

;; ── G11 — medium, not source: kind ∈ {mirror, actor-event} (no :original) ──
(deftest g11-medium-not-source
  (is (= #{"mirror" "actor-event"} (enum-of (lex "article") :kind))
      "G11: article.kind must be {mirror, actor-event} (no 'original')"))

;; ── G2 — no ads / no engagement rank ──
(deftest g2-no-engagement-rank
  (let [sigs (some-> (collect (lex "issue") :enum)
                     vals
                     (->> (mapcat identity) (map str) set))]
    (is (= RANK-SIGNALS (set/intersection sigs RANK-SIGNALS))
        "G2: public-good rank signals present")
    (is (empty? (set/intersection sigs #{"paid-placement" "sponsored" "engagement" "dwell-time"}))
        "G2: no ad/engagement signal representable")))

;; ── G7 no-server-key + G10 non-eschatological (no :final issue) ──
(deftest g7-no-server-key-g10-non-final
  (let [i (lex "issue")]
    (is (= false (a-const i :serverHeldKey)) "G7: issue.serverHeldKey const false")
    (is (= false (a-const i :final)) "G10: issue.final const false (non-eschatological)")))

#?(:clj
   (defn -main [& _]
     (let [r (run-tests 'kawaraban.methods.test-charter-gates)]
       (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))))
