(ns kawaraban.cells.fulltext-cache.state-machine
  "Phase state machine for kawaraban 瓦版 fulltext_cache — the PRIVATE analysis
  buffer (ADR-2607010930, kawaraban G4 internal-buffer amendment).

  kawaraban is a MEDIUM: its PUBLIC article_mirror cell keeps G4 absolute —
  :news.article/full-text stays :db/allowed [false] / lexicon :const false, and a
  mirrored article projects headline + link + ≤280-char excerpt ONLY. That PUBLIC
  invariant is NOT weakened here.

  This cell is the charter-clean place kawaraban holds a fetched article BODY for
  an analysis consumer (yomi 読み, the news-intelligence SOURCE) — PRIVATELY. The
  body is cached in data/ingest/fulltext-buffer/ (gitignored), keyed by article_id
  with url/outlet/fetched-at provenance. It is NEVER transacted to the public
  Datom log, NEVER projected to a 面, NEVER published. article_mirror's fullText
  stays false in the public projection regardless of what is cached here.

  This is an 実装 (engineering) addition, NOT a G4 / Charter-Rider weakening: the
  public-facing copyright membrane (never publish / never represent full text in
  the public ontology) is unchanged; only an internal, non-public, analysis-only
  cache is added.

  Gate (access membrane, mirroring article_mirror's G4 access rule): a body is
  cached ONLY if the outlet is public-facing (:outlet/access ∈ {:open
  :registration-wall}). A paywall / proprietary-terminal body is REFUSED —
  kawaraban cannot have fetched it on the public-web-up contract in the first
  place. .solve() raises at R0 (live fetch is G8-gated = Council Lv6+ + operator)."
  (:require [kotoba.lang.text :as str]))

(def state-defaults
  {"phase" "init" "article_id" "" "outlet" "" "url" "" "access" "" "body" ""
   "fetched_at" "" "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))

(defn cache [state]
  (let [cs (cell-state state)
        cs (reduce (fn [m f] (assoc m f (get state f (get m f))))
                   cs ["article_id" "outlet" "url" "access" "body" "fetched_at"])
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})]
    (cond
      (not (#{:open :registration-wall
              "open" "registration-wall"} (get cs "access")))
      (refuse (str "G4-access: fulltext_cache is public-facing only; outlet access "
                   (pr-str (get cs "access")) " ∉ {:open :registration-wall}"))

      (or (not (seq (get cs "outlet"))) (not (seq (get cs "url"))) (not (seq (get cs "article_id"))))
      (refuse "G4/G11: a cached body needs article_id + outlet + canonical url (provenance)")

      (not (seq (get cs "body")))
      (refuse "no body to cache")

      :else
      ;; PRIVATE buffer record — NOT a public Datom. fullText in the public
      ;; article_mirror projection remains false; this payload stays in
      ;; data/ingest/fulltext-buffer/ (gitignored), consumed by yomi off-log.
      {"cell_state"
       (assoc cs "refusal" "" "phase" "cached"
              "payload" {"articleId"  (get cs "article_id")
                         "outlet"     (get cs "outlet")
                         "url"        (get cs "url")
                         "fetchedAt"  (get cs "fetched_at")
                         "private"    true
                         "publicProjection" {"fullText" false}})})))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate fulltext_cache via Council ADR (live public-web fetch is G8-gated)" {:scaffold true})))
