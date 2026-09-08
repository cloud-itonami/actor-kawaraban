(ns kawaraban.methods.route
  "route.cljc — kawaraban 瓦版 THE MEDIUM: actor→面 routing + actor-to-actor wire.
  1:1 Clojure port of `methods/route.py` (ADR-2606061900). Same house style as
  inochi/shionome (kebab keyword keys for Clojure-land, Python ':…' strings kept literal,
  pure fns, file I/O only at the #?(:clj) edge).

  Reads a kotoba-EDN news-medium graph (:news.outlet/* :news.section/* :news.article/*
  :news.mention/* :news.wire/*) and exposes the connective core of the news medium:

    1. classify(rows)            — bucket the flat datom vector into entity maps.
    2. validate(articles)        — enforce the structural gates (G1/G4/G9/G11) by REFUSAL.
    3. wire-table(wires)         — actor did → 面 (medium config; seed first, ACTOR_WIRE fallback).
    4. section-men(section-id, sections) — the 面 men keyword for a section.
    5. actor-links(articles, mentions) — THE WIRE: every co-mention = an actor-to-actor edge.

  CONSTITUTIONAL: an article is an OBSERVATION carried by a medium, never a verdict (G1) and
  never spoken in anyone's name (G9). kawaraban authors no :original claim (G11).

  G1 / G3 / G4 / G9 forbidden fields, G11 closed kinds, and the G4 excerpt bound all REFUSE
  by throwing ex-info (mirror of Python's ValueError)."
  (:require [kotoba.lang.text :as str]))

;; ── minimal EDN reader (subset: [] {} :kw \"str\" num bool nil) — ported from route.py
;; (itself from watari/watatsuna). Keywords kept as \":ns/name\" STRINGS, not clojure
;; keywords, so the whole pipeline stays string-keyed byte-for-byte with the Python port.

(def ^:private tok-re
  ;; _TOK = re.compile(r'[\s,]+|;[^\n]*|(\[|\]|\{|\}|"(?:\\.|[^"\\])*"|[^\s,\[\]{}]+)')
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn tokens
  "Lazy seq of significant tokens (group 1 of each tok-re match that captured)."
  [s]
  (let [m (re-matcher tok-re s)]
    ((fn step []
       (lazy-seq
        (when (.find m)
          (let [t (.group m 1)]
            (if (nil? t) (step) (cons t (step))))))))))

(defn atom-of
  "Port of _atom: \"…\" → unescaped string; true/false/nil → bool/nil; \":…\" kept as string;
  int → long; else float; else raw string."
  [t]
  (cond
    (str/starts-with? t "\"")
    (-> (subs t 1 (dec (count t)))
        (str/replace "\\\"" "\"")
        (str/replace "\\\\" "\\"))
    (= t "true") true
    (= t "false") false
    (= t "nil") nil
    (str/starts-with? t ":") t
    :else
    (let [as-long (try (Long/parseLong t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
      (if (not= as-long ::nan)
        as-long
        (let [as-dbl (try (Double/parseDouble t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
          (if (not= as-dbl ::nan) as-dbl t))))))

(def ^:private end-marker ::end)

(defn- parse-step
  "Consume one form from the token vector at index i. Returns [value next-i] or
  [end-marker next-i] when a closing ] or } is hit (mirror of _parse's _END sentinel)."
  [toks i]
  (let [t (nth toks i)
        i (inc i)]
    (cond
      (= t "[")
      (loop [i i, out []]
        (let [[x i] (parse-step toks i)]
          (if (= x end-marker) [out i] (recur i (conj out x)))))

      (= t "{")
      (loop [i i, out {}]
        (let [[k i] (parse-step toks i)]
          (if (= k end-marker)
            [out i]
            (let [[v i] (parse-step toks i)]
              (recur i (assoc out k v))))))

      (or (= t "]") (= t "}")) [end-marker i]
      :else [(atom-of t) i])))

(defn read-edn
  "Parse the first top-level form from EDN text (matches _parse(_tokens(text)))."
  [text]
  (let [toks (vec (tokens text))]
    (first (parse-step toks 0))))

#?(:clj
   (defn load-edn
     "Read + parse a news-medium EDN graph file. File I/O only at this edge.
     The Clojure equivalent of route.load_edn — keywords kept as \":…\" strings."
     [path]
     (read-edn (slurp (str path)))))

;; ── canonical actor → 面 (men) wire fallback. Seed :news.wire/* rows are the SSoT;
;;    this constant is the last-resort default so a first-party actor always has a 面.
;;    NOTE: an array-map preserves insertion order ≤8 keys; this has 14, so we keep the
;;    order explicit only where it matters. ACTOR_WIRE order is never iterated for output
;;    (wire-table merges it, then seed rows override; the only iterations over the table in
;;    main/route are sorted). We use an ordinary map.
(def ACTOR-WIRE
  {"did:web:etzhayyim.com:actor:danjo" "politics"
   "did:web:etzhayyim.com:actor:ooyake" "politics"
   "did:web:etzhayyim.com:actor:moushibumi" "politics"
   "did:web:etzhayyim.com:actor:kanae" "economy"
   "did:web:etzhayyim.com:actor:kanjo" "economy"
   "did:web:etzhayyim.com:actor:kabuto" "economy"
   "did:web:etzhayyim.com:actor:mitooshi" "international"
   "did:web:etzhayyim.com:actor:watari" "international"
   "did:web:etzhayyim.com:actor:watatsuna" "international"
   "did:web:etzhayyim.com:actor:kataribe" "culture"
   "did:web:etzhayyim.com:actor:sanae" "society"
   "did:web:etzhayyim.com:actor:mitsuho" "society"
   "did:web:etzhayyim.com:actor:noroshi" "science"
   "did:web:etzhayyim.com:actor:hotaru" "science"})

;; Forbidden article fields — if any appears truthy, a charter invariant is violated.
;; Ordered to mirror Python dict iteration (first violation wins the message).
(def ^:private FORBIDDEN
  [[":news.article/verdict" "G1 (mirror-not-adjudicator)"]
   [":news.article/truth-rating" "G1 (no fact-check score)"]
   [":news.article/full-text" "G4 (copyright / link-out only)"]
   [":news.article/personalized-for" "G3 (no per-reader feed)"]
   [":news.article/speak-as" "G9 (mirror-not-impersonation)"]])

(def VALID-KINDS [":mirror" ":actor-event"])

(defn classify
  "Bucket the flat datom vector into entity maps. Returns
  [outlets sections articles mentions wires]. outlets/sections are ordered maps keyed by id;
  articles/mentions/wires are vectors in seed order (mirror of route.classify)."
  [rows]
  (loop [rs rows
         outlets (array-map) sections (array-map)
         articles [] mentions [] wires []]
    (if (empty? rs)
      [outlets sections articles mentions wires]
      (let [r (first rs)]
        (if-not (map? r)
          (recur (rest rs) outlets sections articles mentions wires)
          (cond
            (contains? r ":news.outlet/id")
            (recur (rest rs) (assoc outlets (get r ":news.outlet/id") r) sections articles mentions wires)
            (contains? r ":news.section/id")
            (recur (rest rs) outlets (assoc sections (get r ":news.section/id") r) articles mentions wires)
            (contains? r ":news.article/id")
            (recur (rest rs) outlets sections (conj articles r) mentions wires)
            (contains? r ":news.mention/id")
            (recur (rest rs) outlets sections articles (conj mentions r) wires)
            (contains? r ":news.wire/id")
            (recur (rest rs) outlets sections articles mentions (conj wires r))
            :else
            (recur (rest rs) outlets sections articles mentions wires)))))))

(defn truthy?
  "Mirror of Python `v not in (None, False, 0)` — nil/false/0/0.0 are falsy here."
  [v]
  (not (or (nil? v) (false? v) (and (number? v) (zero? v)))))

(defn- py-repr
  "Best-effort {v!r} for the messages the tests inspect (only substring assertions are
  tested, so exactness beyond that is cosmetic)."
  [v]
  (cond
    (nil? v) "None"
    (true? v) "True"
    (false? v) "False"
    (string? v) (str "'" v "'")
    :else (str v)))

(defn validate
  "Enforce G1/G3/G4/G9/G11 by refusal. Throws ex-info on the first violation (mirror of
  route.validate's ValueError). Returns nil when all articles pass."
  [articles]
  (doseq [a articles]
    (let [aid (get a ":news.article/id" "<?>")]
      ;; G1/G3/G4/G9 — forbidden fields may never appear truthy.
      (doseq [[field gate] FORBIDDEN]
        (let [v (get a field)]
          (when (truthy? v)
            (throw (ex-info (str aid ": field " field " = " (py-repr v) " violates " gate "; unrepresentable") {})))))
      ;; G11 — every article is :mirror or :actor-event; :original is not a kind.
      (let [kind (get a ":news.article/kind")]
        (when-not (some #{kind} VALID-KINDS)
          (throw (ex-info (str aid ": kind " (py-repr kind) " not in " (pr-str (vec VALID-KINDS))
                               " — kawaraban is a medium, not a source (G11)") {})))
        (if (= kind ":mirror")
          (when (or (not (truthy? (get a ":news.article/outlet")))
                    (not (truthy? (get a ":news.article/url"))))
            (throw (ex-info (str aid ": :mirror article needs :outlet + :url (G4/G5 link-out)") {})))
          ;; :actor-event
          (when (or (not (truthy? (get a ":news.article/source-actor")))
                    (not (truthy? (get a ":news.article/source-tid"))))
            (throw (ex-info (str aid ": :actor-event needs :source-actor + :source-tid (G7/G11 provenance)") {})))))
      ;; G4 — bounded excerpt.
      (let [ex (get a ":news.article/excerpt" "")]
        (when (and (string? ex) (> (count ex) 280))
          (throw (ex-info (str aid ": excerpt " (count ex) " chars > 280 (G4 fair-use bound)") {})))))))

(defn wire-table
  "actor did → 面 (men keyword without the colon). Seed rows first, then ACTOR_WIRE
  (mirror of route.wire_table: start from ACTOR_WIRE, override with seed wires)."
  [wires]
  (reduce (fn [t w]
            (let [actor (get w ":news.wire/actor")
                  sec (get w ":news.wire/section")]
              (if (and (truthy? actor) (truthy? sec))
                (assoc t actor (if (string? sec)
                                 (let [i (str/index-of sec ".")]
                                   (if i (subs sec (inc i)) sec))
                                 sec))
                t)))
          ACTOR-WIRE
          wires))

(defn section-men
  "men keyword (without leading colon) of a section, or '?' when unknown."
  [section-id sections]
  (let [s (get sections section-id {})
        men (get s ":news.section/men" ":?")]
    (if (string? men)
      (if (str/starts-with? men ":") (subs men 1) men)
      (str men))))

(defn actor-targets
  "First-party actor mention targets of one article (the wire endpoints), in seed order."
  [article-id mentions]
  (->> mentions
       (filter #(and (= (get % ":news.mention/article") article-id)
                     (= (get % ":news.mention/target-kind") ":actor")))
       (mapv #(get % ":news.mention/target"))))

(defn actor-links
  "THE WIRE. For every article, each pair of co-mentioned first-party actors is an
  actor-to-actor edge. Returns [edges degree]:
    edges  : {#{a b} count-of-shared-articles}
    degree : {actor number-of-distinct-actors-it-is-wired-to}
  Mirror of route.actor_links (edges keyed by a 2-element set, degree from neighbor sets)."
  [articles mentions]
  (let [[edges neighbors]
        (reduce
         (fn [[edges neighbors] a]
           (let [aid (get a ":news.article/id")
                 actors (vec (sort (distinct (actor-targets aid mentions))))
                 n (count actors)]
             (loop [i 0, edges edges, neighbors neighbors]
               (if (>= i n)
                 [edges neighbors]
                 (let [[edges neighbors]
                       (loop [j (inc i), edges edges, neighbors neighbors]
                         (if (>= j n)
                           [edges neighbors]
                           (let [ai (nth actors i) aj (nth actors j)
                                 pair #{ai aj}]
                             (recur (inc j)
                                    (update edges pair (fnil inc 0))
                                    (-> neighbors
                                        (update ai (fnil conj #{}) aj)
                                        (update aj (fnil conj #{}) ai))))))]
                   (recur (inc i) edges neighbors))))))
         [{} {}]
         articles)]
    [edges (into {} (map (fn [[a ns]] [a (count ns)]) neighbors))]))

(defn short-did
  "did:…:name → name (mirror of route._short; non-did strings pass through)."
  [did]
  (if (and (string? did) (str/starts-with? did "did:"))
    (let [i (str/last-index-of did ":")]
      (if i (subs did (inc i)) did))
    did))

#?(:clj
   (defn -main
     "CLI: load seed → classify → validate → print the medium config + actor-to-actor wire."
     [& argv]
     (let [argv (vec argv)
           methods-dir (delay (-> *file* clojure.java.io/file .getParentFile))
           seed (if (seq argv)
                  (clojure.java.io/file (first argv))
                  (clojure.java.io/file @methods-dir ".." "data" "seed-news-graph.kotoba.edn"))
           rows (load-edn seed)
           [outlets sections articles mentions wires] (classify rows)]
       (validate articles)
       (let [table (wire-table wires)
             [edges degree] (actor-links articles mentions)]
         (println (str "kawaraban route — " (count outlets) " outlets · " (count sections) " 面 · "
                       (count articles) " articles · " (count mentions) " mentions · "
                       (count wires) " wires"))
         (println "\nactor → 面 wire (medium config):")
         (doseq [[actor men] (sort-by key table)]
           (when (some #(= (get % ":news.wire/actor") actor) wires)
             (println (str "  " (format "%-12s" (short-did actor)) " → " men))))
         (println "\nactor-to-actor wire (co-mention edges, the medium):")
         (doseq [[pair n] (sort-by (fn [[p c]] [(- c) (vec (sort p))]) edges)]
           (let [[a b] (sort (map short-did pair))]
             (println (str "  " (format "%-12s" a) " —" n "— " b))))
         (println "\nmost-wired actors (degree):")
         (doseq [[actor d] (take 5 (sort-by (fn [[a c]] [(- c) a]) degree))]
           (println (str "  " (format "%-12s" (short-did actor)) " wired to " d " actor(s)"))))
       0)))
