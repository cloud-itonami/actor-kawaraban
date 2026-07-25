(ns kawaraban.methods.ingest
  "ingest.cljc — kawaraban 瓦版 offline outlet/headline normalizer (G4 membrane, G8 --live gate).
  1:1 Clojure port of `methods/ingest.py` (ADR-2606061900). House style matches the actor's
  sibling route.cljc / analyze.cljc (Python ':…' keyword strings kept literal, string-keyed
  data, pure fns, file/JSON/env I/O only at the #?(:clj) edge).

  Normalizes a batch of public-facing-page headline records (JSON) into :news.article/*
  :mirror datoms. It is a MEMBRANE: it REFUSES, by construction, any record that would breach
  the copyright / surveillance gates —

    • G4 — a record carrying a full body (`body` / `fullText` / `content`) is REFUSED
           (kawaraban stores headline + canonical url + bounded fair-use excerpt only);
           an excerpt over 280 chars is truncated with a flag.
    • G4 — a record whose outlet access is `paywall` / `proprietary-terminal` is REFUSED
           (only public/open facing pages are mirrored; kanjo §2(c) anti-gatekeeping).
    • G1 — a record carrying a `verdict` / `truthRating` is REFUSED (mirror, not adjudicator).
    • G3 — a record carrying `personalizedFor` / any reader id is REFUSED (no per-reader feed).

  `--live` (real RSS/sitemap fetch) is REFUSED unless the operator gate is set
  (KAWARABAN_ALLOW_LIVE_INGEST=1 + Council Lv6+ attestation) — at R0 it always refuses (G8).

  Self-contained: own minimal JSON reader (no cheshire/data.json), no dependency on a sibling
  namespace. A refused record throws ex-info (mirror of Python's IngestRefused/ValueError)."
  (:require [clojure.string :as str]))

(def FORBIDDEN-BODY-KEYS ["body" "fullText" "full_text" "content" "articleBody"])
(def FORBIDDEN-FIELDS
  ;; insertion order preserved (mirror of the Python dict iteration order).
  [["verdict" "G1 (mirror-not-adjudicator)"]
   ["truthRating" "G1 (no fact-check score)"]
   ["personalizedFor" "G3 (no per-reader feed)"]
   ["readerId" "G3 (no reader surveillance)"]])
(def OPEN-ACCESS #{"open" "registration-wall"})
(def SOURCING
  "G5 — mirror of data/lex/article.edn's `sourcing` enum. \"verified\" = actually
  fetched from the outlet's own feed; \"representative\" = illustrative (data/seed.edn)."
  #{"representative" "verified"})

;; ── minimal JSON reader (subset sufficient for ingest batches) ────────────────
;; maps string-keyed, integers → long, literals → true/false/nil — Python json.loads shapes.
(declare json-value)

(defn- skip-ws
  "Skip JSON insignificant whitespace ONLY. Commas are explicit separators, NOT skipped here."
  [^String s i]
  (loop [i i]
    (if (and (< i (count s)) (contains? #{\space \tab \newline \return} (nth s i)))
      (recur (inc i)) i)))

(defn- json-string [^String s i]
  (loop [i (inc i), sb (StringBuilder.)]
    (let [c (nth s i)]
      (cond
        (= c \") [(.toString sb) (inc i)]
        (= c \\)
        (let [e (nth s (inc i))]
          (case e
            \" (do (.append sb \") (recur (+ i 2) sb))
            \\ (do (.append sb \\) (recur (+ i 2) sb))
            \/ (do (.append sb \/) (recur (+ i 2) sb))
            \b (do (.append sb \backspace) (recur (+ i 2) sb))
            \f (do (.append sb \formfeed) (recur (+ i 2) sb))
            \n (do (.append sb \newline) (recur (+ i 2) sb))
            \r (do (.append sb \return) (recur (+ i 2) sb))
            \t (do (.append sb \tab) (recur (+ i 2) sb))
            \u (let [cp (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16)]
                 (.append sb (char cp)) (recur (+ i 6) sb))
            (do (.append sb e) (recur (+ i 2) sb))))
        :else (do (.append sb c) (recur (inc i) sb))))))

(defn- json-number [^String s i]
  (let [end (loop [j i]
              (if (and (< j (count s))
                       (contains? #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \+ \- \. \e \E} (nth s j)))
                (recur (inc j)) j))
        tok (subs s i end)]
    [(if (some #{\. \e \E} tok) (Double/parseDouble tok) (Long/parseLong tok)) end]))

(defn- json-array [^String s i]
  (let [i (skip-ws s (inc i))]
    (if (= (nth s i) \])
      [[] (inc i)]
      (loop [i i, out []]
        (let [[v i] (json-value s i)
              i (skip-ws s i)]
          (if (= (nth s i) \,)
            (recur (skip-ws s (inc i)) (conj out v))
            [(conj out v) (inc i)]))))))

(defn- json-object [^String s i]
  (let [i (skip-ws s (inc i))]
    (if (= (nth s i) \})
      [{} (inc i)]
      (loop [i i, out {}]
        (let [[k i] (json-string s (skip-ws s i))
              i (skip-ws s i)
              [v i] (json-value s (skip-ws s (inc i)))
              out (assoc out k v)
              i (skip-ws s i)]
          (if (= (nth s i) \,)
            (recur (skip-ws s (inc i)) out)
            [out (inc i)]))))))

(defn- json-value [^String s i]
  (let [i (skip-ws s i), c (nth s i)]
    (cond
      (= c \{) (json-object s i)
      (= c \[) (json-array s i)
      (= c \") (json-string s i)
      (= c \t) [true (+ i 4)]
      (= c \f) [false (+ i 5)]
      (= c \n) [nil (+ i 4)]
      :else (json-number s i))))

(defn parse-json
  "Parse the first JSON value in text → Clojure data (maps string-keyed)."
  [text]
  (first (json-value text 0)))

;; ── helpers mirroring Python truthiness + int() ──────────────────────────────
(defn- truthy?
  "Python truthiness for rec.get(k): nil/false/\"\"/0/0.0/[]/{} are falsey."
  [v]
  (cond
    (nil? v) false
    (boolean? v) v
    (string? v) (not= v "")
    (number? v) (not (zero? v))
    (coll? v) (seq v)
    :else true))

(defn- to-int
  "Python int(rec.get('asOf', 0)) — coerce a number/string to a long."
  [v]
  (cond
    (integer? v) (long v)
    (number? v) (long v)
    (string? v) (long (Double/parseDouble v))
    (nil? v) 0
    :else 0))

(defn normalize-record
  "Mirror of normalize_record(rec): validate the structural gates by REFUSAL, else emit a
  :news.article/* :mirror datom map. Throws ex-info on any gate breach."
  [rec]
  (let [oid (get rec "outlet" "?")]
    ;; G4 — no full body may be ingested.
    (doseq [k FORBIDDEN-BODY-KEYS]
      (when (truthy? (get rec k))
        (throw (ex-info (str oid ": field '" k "' present — full body is unrepresentable (G4 link-out)") {}))))
    ;; G1 / G3 — no verdict / no reader. (rec.get(k) not in (None, "", False, 0))
    (doseq [[k gate] FORBIDDEN-FIELDS]
      (let [v (get rec k)]
        (when-not (or (nil? v) (= v "") (= v false) (= v 0) (= v 0.0))
          (throw (ex-info (str oid ": field '" k "' present — violates " gate) {})))))
    ;; G4 — only public/open facing pages.
    (let [access (get rec "access" "open")]
      (when-not (contains? OPEN-ACCESS access)
        (throw (ex-info (str oid ": access '" access "' is not public — paywall/terminal not mirrored (G4)") {}))))
    (when-not (truthy? (get rec "url"))
      (throw (ex-info (str oid ": a :mirror article requires a canonical :url (G4/G5 link-out)") {})))
    (let [raw-excerpt (or (get rec "excerpt") "")
          excerpt (subs raw-excerpt 0 (min 280 (count raw-excerpt)))
          truncated (> (count raw-excerpt) 280)
          ;; ADR-2607253000 — 媒体が公開した署名だけを、lexicon の maxLength 200 に
          ;; 収めて持つ。空文字は属性ごと落とす: 「署名が無い記事」と「署名が空だった
          ;; 記事」を同じ形にすると、後者が前者に化ける。
          raw-byline (or (get rec "byline") "")
          byline (when (seq (str/trim raw-byline))
                   (let [b (str/trim raw-byline)]
                     (subs b 0 (min 200 (count b)))))]
      (cond-> {":news.article/id" (if (truthy? (get rec "id"))
                            (get rec "id")
                            (str "art." oid "." (get rec "asOf" 0)))
       ":news.article/kind" ":mirror"
       ":news.article/section" (get rec "section" "sec.front")
       ":news.article/outlet" oid
       ":news.article/url" (get rec "url")
       ":news.article/headline" (get rec "headline" "")
       ":news.article/excerpt" excerpt
       ":news.article/lang" (get rec "lang" "en")
       ":news.article/as-of" (to-int (get rec "asOf" 0))
       ;; G5 — provenance must not lie in either direction. A record fetched from the
       ;; outlet's OWN feed is "verified"; an illustrative/offline batch record stays
       ;; "representative" (the default, so every pre-existing caller is unchanged).
       ;; Enum mirrors data/lex/article.edn's `sourcing` — anything else is refused
       ;; rather than silently written through.
       ":news.article/sourcing" (let [s (get rec "sourcing" "representative")]
                                  (if (contains? SOURCING s)
                                    (str ":" s)
                                    (throw (ex-info (str oid ": sourcing " (pr-str s)
                                                         " is not in the lexicon enum "
                                                         (pr-str (vec (sort SOURCING)))
                                                         " — G5 source-provenance-honest")
                                                    {}))))
       "_excerpt_truncated" truncated}
        byline (assoc ":news.article/byline" byline)))))

(defn normalize-batch
  "Returns [ok refused] — refused records are reported, never silently dropped (G5).
  (mirror of normalize_batch)."
  [records]
  (reduce (fn [[ok refused] rec]
            (try [(conj ok (normalize-record rec)) refused]
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                   [ok (conj refused (#?(:clj .getMessage :cljs ex-message) e))])))
          [[] []]
          records))

(defn live-allowed
  "G8 — live fetch needs the operator gate. Always false at R0.
  (mirror of live_allowed: os.environ.get('KAWARABAN_ALLOW_LIVE_INGEST') == '1')."
  []
  #?(:clj (= (System/getenv "KAWARABAN_ALLOW_LIVE_INGEST") "1")
     :cljs false))

#?(:clj
   (defn load-json
     "Read + parse a JSON file (file I/O only at this edge)."
     [path]
     (parse-json (slurp (str path)))))

#?(:clj
   (defn -main
     "CLI: offline normalize a batch; --live is refused at R0 (G8). (mirror of main(argv))."
     [& argv]
     (let [argv (vec argv)]
       (if (some #{"--live"} argv)
         (do
           (if-not (live-allowed)
             (binding [*out* *err*]
               (println (str "REFUSED: live RSS/sitemap ingest is Council Lv6+ + operator gated (G8). "
                             "Set KAWARABAN_ALLOW_LIVE_INGEST=1 + Council attestation to enable.")))
             (binding [*out* *err*]
               (println "REFUSED: R0 has no live fetcher wired (G8 design boundary).")))
           2)
         (let [args (filterv #(not (str/starts-with? % "--")) argv)
               methods-dir (-> *file* clojure.java.io/file .getParentFile)
               batch (if (seq args)
                       (clojure.java.io/file (first args))
                       (clojure.java.io/file "wire" "ingest" "sample-batch.json"))
               records (parse-json (slurp batch))
               [ok refused] (normalize-batch records)]
           (println (str "normalized " (count ok) " mirror article(s); refused " (count refused) " (gate violations)"))
           (doseq [r refused]
             (println (str "  REFUSED: " r)))
           0)))))
