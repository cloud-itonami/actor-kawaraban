(ns kawaraban.methods.analyze
  "analyze.cljc — kawaraban 瓦版 EDITION composer (front-面 digest). ADR-2606061900.
  1:1 Clojure port of `methods/analyze.py`. Imports the sibling MEDIUM module
  `kawaraban.methods.route` (the analyzer + the sibling it depends on, ported together —
  same full-stack pattern as shionome analyze/weave/edn).

  The composer ranks articles into 面 (sections) using G2 PUBLIC-GOOD signals ONLY —
  recency, source-diversity, actor-relevance — and NEVER by paid placement, sponsorship,
  engagement, or dwell-time (those are not representable; `assert-rank-signals` THROWS if an
  illegal signal is requested).

  It emits:
    1. out/edition.md             — a human-readable front-面 edition (一面 leads + each 面;
                                    every article = headline + outlet/source + link (mirror) or
                                    source-actor (actor-event) + the actors it wires).
    2. out/news-medium.kotoba.edn — derived :news.issue/* + :news.medium.link/* datoms (the
                                    actor-to-actor connection edges), flagged :derived.

  Every article is an OBSERVATION carried by a medium — never a verdict (G1), never spoken in
  anyone's name (G9). The edition is dated, not final (G10), unsigned + unpublished at R0
  (G7/G8). House style: Python ':…' keyword strings stay strings; pure fns; file I/O at the
  #?(:clj) edge; HALF_EVEN round() via exact BigDecimal.(double)."
  (:require [kotoba.lang.text :as str]
            [kawaraban.methods.route :as route]))

;; G2 INVARIANT — the only ranking signals that exist. Engagement / paid placement /
;; sponsorship / dwell-time are NOT members and can never be added.
(def ALLOWED-RANK-SIGNALS
  ["recency" "section-fit" "source-diversity" "actor-relevance" "geo-proximity"])
(def USED-SIGNALS ["recency" "source-diversity" "actor-relevance"])

;; 面 display order (mirrors a real newspaper's section order).
(def MEN-ORDER
  ["front" "politics" "economy" "international" "society"
   "culture" "science" "sports" "local" "opinion"])

(defn assert-rank-signals
  "G2 — refuse any ranking signal outside the public-good allowlist (mirror of
  assert_rank_signals; throws ex-info)."
  [signals]
  (doseq [s signals]
    (when-not (some #{s} ALLOWED-RANK-SIGNALS)
      (throw (ex-info (str "G2: ranking signal '" s "' is not public-good (" (pr-str (vec ALLOWED-RANK-SIGNALS)) "); "
                           "paid-placement / engagement / dwell-time are unrepresentable (Charter §1.13 + Rider §2)") {})))))

(defn- as-of-of
  "article.get(':news.article/as-of', 0) — coerce to a number (default 0)."
  [a]
  (get a ":news.article/as-of" 0))

(defn- men-of
  "_men_of: explicit section's 面, else 'front'."
  [a sections]
  (let [sid (get a ":news.article/section")]
    (if (route/truthy? sid) (route/section-men sid sections) "front")))

(defn- py-round-6
  "Python round(x, 6) — ROUND_HALF_EVEN on the exact binary double, returned as a double."
  [x]
  #?(:clj (-> (java.math.BigDecimal. (double x))
              (.setScale 6 java.math.RoundingMode/HALF_EVEN)
              (.doubleValue))
     :cljs (let [f 1000000.0 r (/ (Math/round (* (double x) f)) f)] r)))

(defn score
  "Blended G2 public-good score. Higher = more prominent. Deterministic. (mirror of score())"
  [article mentions newest oldest seen-outlets]
  (let [as-of (as-of-of article)
        span (max 1 (- newest oldest))
        recency (/ (double (- as-of oldest)) (double span))
        relevance (count (set (route/actor-targets (get article ":news.article/id") mentions)))
        ;; Python: src = outlet or source-actor or "?"
        src-val (let [o (get article ":news.article/outlet")
                      sa (get article ":news.article/source-actor")]
                  (cond (route/truthy? o) o (route/truthy? sa) sa :else "?"))
        diversity (if (contains? seen-outlets src-val) 0.0 1.0)]
    (py-round-6 (+ (* 0.5 recency)
                   (* 0.3 (/ (double (min relevance 3)) 3.0))
                   (* 0.2 diversity)))))

(defn compose
  "Load + classify + validate the rows, then rank leads, group by 面, build edges. Returns a
  map of all the rendered pieces (mirror of compose())."
  ([rows] (compose rows 4))
  ([rows lead-n]
   (let [[outlets sections articles mentions wires] (route/classify rows)]
     (route/validate articles)               ;; gates first — refuse before composing
     (assert-rank-signals USED-SIGNALS)
     (let [table (route/wire-table wires)
           [edges degree] (route/actor-links articles mentions)
           as-ofs (let [v (mapv as-of-of articles)] (if (seq v) v [0]))
           newest (apply max as-ofs)
           oldest (apply min as-ofs)
           ;; rank for the 一面 leads with a source-diversity pass.
           ;; sorted(articles, key=lambda x: -as-of) — Python sort is STABLE.
           by-recency (sort-by #(- (as-of-of %)) articles)
           ranked (loop [as by-recency, seen #{}, acc []]
                    (if (empty? as)
                      acc
                      (let [a (first as)
                            sc (score a mentions newest oldest seen)
                            outlet-or-actor (let [o (get a ":news.article/outlet")
                                                  sa (get a ":news.article/source-actor")]
                                              (if (route/truthy? o) o sa))]
                        (recur (rest as)
                               (conj seen outlet-or-actor)
                               (conj acc [sc a])))))
           ;; ranked.sort(key=lambda sa: (-sa[0], -as-of)) — stable sort over the recency list.
           ranked-sorted (sort-by (fn [[sc a]] [(- sc) (- (as-of-of a))]) ranked)
           leads (mapv second (take lead-n ranked-sorted))
           ;; group by 面 — dict seeded with all MEN_ORDER keys, then setdefault per article.
           by-men (reduce (fn [m a]
                            (let [men (men-of a sections)]
                              (update m men (fnil conj []) a)))
                          (into {} (map (fn [m] [m []]) MEN-ORDER))
                          by-recency)]
       {"outlets" outlets "sections" sections "articles" articles
        "mentions" mentions "wires" wires "table" table
        "edges" edges "degree" degree "leads" leads "by_men" by-men
        "newest" newest}))))

(defn- article-line
  "_article_line: one markdown bullet for an article (mirror)."
  [a sections mentions]
  (let [head (get a ":news.article/headline" "")
        wired (mapv route/short-did (sort (set (route/actor-targets (get a ":news.article/id") mentions))))
        wired-s (if (seq wired) (str " — wires: " (str/join ", " wired)) "")]
    (if (= (get a ":news.article/kind") ":mirror")
      (let [outlet0 (get a ":news.article/outlet" "?")
            ;; outlet.split(".", 1)[-1]
            i (str/index-of outlet0 ".")
            outlet (if i (subs outlet0 (inc i)) outlet0)
            url (get a ":news.article/url" "")]
        (str "- **" head "** — _" outlet "_ ([link](" url "))" wired-s))
      (let [actor (route/short-did (get a ":news.article/source-actor" "?"))]
        (str "- **" head "** — _actor-event: " actor "_" wired-s)))))

;; ── edge / degree sort keys (deterministic — mirror of the lambda keys) ───────────
(defn- edge-rows
  "sorted(edges.items(), key=lambda kv: (-kv[1], sorted(kv[0])))"
  [edges]
  (sort-by (fn [[pair n]] [(- n) (vec (sort pair))]) edges))

(defn- degree-rows
  "sorted(degree.items(), key=lambda kv: (-kv[1], kv[0]))"
  [degree]
  (sort-by (fn [[act d]] [(- d) act]) degree))

(defn render-md
  "Render the edition markdown byte-for-byte with render_md."
  [c]
  (let [sections (get c "sections")
        mentions (get c "mentions")
        L (transient
           ["# 瓦版 kawaraban — Edition (as-of snapshot)"
            ""
            (str "_A news MEDIUM: real-media mirror + actor-to-actor wire. Link-out, no full "
                 "text (G4); no verdict (G1); no ads / no engagement ranking (G2); no reader "
                 "profile (G3); not final (G10). Unsigned + unpublished at R0 (G7/G8)._")
            ""
            (str "Ranked by public-good signals only: " (str/join ", " USED-SIGNALS) ".")
            ""
            "## 一面 — Front Page"])]
    (doseq [a (get c "leads")]
      (conj! L (article-line a sections mentions)))
    (conj! L "")
    ;; per-面
    (doseq [men MEN-ORDER]
      (when-not (= men "front")
        (let [arts (get-in c ["by_men" men] [])]
          (when (seq arts)
            (let [name (or (some (fn [s] (when (= (route/section-men s sections) men)
                                           (get-in sections [s ":news.section/name-ja"] men)))
                                 (keys sections))
                           men)]
              (conj! L (str "## " name " (" men ")"))
              (doseq [a arts]
                (conj! L (article-line a sections mentions)))
              (conj! L ""))))))
    ;; the actor-to-actor wire
    (conj! L "## Actor-to-actor wire (the medium)")
    (conj! L "Co-mention edges — kawaraban connects these actors by carrying the same story:")
    (doseq [[pair n] (edge-rows (get c "edges"))]
      (let [[a b] (sort (map route/short-did pair))]
        (conj! L (str "- `" a "` —" n "— `" b "`"))))
    (conj! L "")
    (conj! L (str "Most-wired actors: "
                  (str/join ", " (map (fn [[act d]] (str (route/short-did act) " (" d ")"))
                                      (take 5 (degree-rows (get c "degree")))))))
    (conj! L "")
    (str/join "\n" (persistent! L))))

(defn render-edn
  "Render the derived edition + actor-to-actor link edges edn byte-for-byte with render_edn."
  [c]
  (let [issue-id (str "issue.kawaraban." (get c "newest"))
        sec-ids (keys (get c "sections"))
        lead-ids (map #(get % ":news.article/id") (get c "leads"))
        L (transient
           [";; kawaraban derived edition + actor-to-actor link edges (ADR-2606061900)"
            ";; :derived — NOT re-ingested as authoritative. published=false / final=false (G7/G8/G10)."
            "["])]
    (conj! L (str " {:news.issue/id \"" issue-id "\" :news.issue/as-of " (get c "newest") " "
                  ":news.issue/sections " (count sec-ids) " :news.issue/lead-count " (count lead-ids) " "
                  ":news.issue/published false :news.issue/server-held-key false :news.issue/final false "
                  ":news.issue/derived true}"))
    (doseq [[pair n] (edge-rows (get c "edges"))]
      (let [[a b] (sort pair)
            lid (str "link." (route/short-did a) "--" (route/short-did b))]
        (conj! L (str " {:news.medium.link/id \"" lid "\" :news.medium.link/a \"" a "\" "
                      ":news.medium.link/b \"" b "\" :news.medium.link/shared " n " :news.medium.link/derived true}"))))
    (conj! L "]")
    (str/join "\n" (persistent! L))))

#?(:clj
   (defn -main
     "CLI: load seed → compose → write out/edition.md + out/news-medium.kotoba.edn.
     Byte-parity target = both files (match analyze.py)."
     [& argv]
     (let [argv (vec argv)
           methods-dir (delay (-> *file* clojure.java.io/file .getParentFile))
           args (filterv #(not (str/starts-with? % "--")) argv)
           outdir (if (some #{"--out"} argv)
                    (clojure.java.io/file (nth argv (inc (.indexOf argv "--out"))))
                    (clojure.java.io/file @methods-dir "out"))
           seed (if (seq args)
                  (clojure.java.io/file (first args))
                  (clojure.java.io/file @methods-dir ".." "data" "seed-news-graph.kotoba.edn"))
           rows (route/load-edn seed)
           c (compose rows)]
       (.mkdirs outdir)
       (spit (clojure.java.io/file outdir "edition.md") (render-md c))
       (spit (clojure.java.io/file outdir "news-medium.kotoba.edn") (render-edn c))
       (println (str "composed edition: " (count (get c "articles")) " articles across "
                     (count (filter #(seq (get-in c ["by_men" %])) MEN-ORDER)) " 面; "
                     (count (get c "edges")) " actor-to-actor edges; " (count (get c "leads")) " 一面 leads"))
       (println (str "→ " (clojure.java.io/file outdir "edition.md") "\n→ "
                     (clojure.java.io/file outdir "news-medium.kotoba.edn")))
       0)))
