(ns kawaraban.run-live-ingest
  "Non-interactive entrypoint that runs one live-ingest pass over every :verified outlet
  in `data/outlets/allowlist.edn`: fetch + normalize (src/kawaraban/methods/live_fetch.cljc, G1/G3/G4/G8
  gates unchanged) -> publish under that outlet's own per-outlet mirror identity
  (kawaraban.mirror-actor, G9 disclosure). Designed to be invoked periodically
  (clojure -M:live-ingest / GitHub Actions cron, ADR-2607110200 addendum 2) rather than
  only by hand.

  High-water-mark, NOT full-feed-republish every tick (ADR-2607110200 addendum 2, learned
  the hard way: a single one-outlet backfill run publishing 30 articles at once already
  pushed pds.aozora.app read latency on the shared operator graph from ~4s to ~50s, the
  same class of problem this ADR's addendum 1 fixed -- a naive 'republish everything the
  feed currently lists, every run' design would recreate that mass-write pressure on every
  single cron tick across 20 outlets, not just once). `last-seen-path` persists the max
  `:news.article/as-of` already published PER OUTLET; each run only publishes records
  strictly newer than that mark, so steady-state ticks touch a handful of genuinely-new
  articles instead of re-writing the whole visible feed window. The file is committed back
  to the repo by the calling workflow (NOT gitignored, NOT a secret -- unlike
  `.kawaraban/mirrors/*.edn`) so the mark survives across ephemeral CI runners. A fresh
  outlet (no prior mark) backfills its current feed window once, same as any first run.

  Publishing itself stays idempotent regardless (kawaraban.publish's :rkey is the
  article's own deterministic :news.article/id) -- the high-water-mark is purely a
  write-volume optimization, not a correctness requirement.

  KAWARABAN_ALLOW_LIVE_INGEST must be set for fetch-outlet! to do anything (G8); this
  script does not set it -- an operator/workflow env config does, same as any other run."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kawaraban.methods.live-fetch :as live-fetch]
            [kawaraban.mirror-actor :as mirror-actor]
            [kawaraban.publish :as publish]
            [kawaraban.store :as store])
  (:gen-class))

(def ^:private base-opts
  ;; NOT :key-fn keyword -- kawaraban.aozora's mint-session!/create-record! look up
  ;; response fields with STRING keys ((get sbody "accessJwt"), (get rbody "uri")), so
  ;; json-read must produce string-keyed maps to match (a keyword-keyed :json-read here
  ;; silently makes every real aozora call fail: status 200 but jwt/uri always nil).
  {:json-write json/write-str
   :json-read json/read-str})

(defn load-last-seen [path]
  (let [f (io/file path)]
    (if (.exists f) (edn/read-string (slurp f)) {})))

(defn save-last-seen! [path last-seen]
  (io/make-parents path)
  (spit path (pr-str last-seen)))

(defn- new-since [records mark]
  (filter #(> (get % ":news.article/as-of" 0) mark) records))

(defn- max-as-of [records mark]
  (reduce max mark (map #(get % ":news.article/as-of" 0) records)))

(defn- publish-outlet! [outlet records]
  (let [{:keys [publisher]} (mirror-actor/mirror-publisher base-opts outlet)]
    (mapv (fn [record]
            (try
              (let [{:keys [uri]} (publish/publish-mirror! publisher record)]
                {:ok true :uri uri})
              (catch Exception e
                {:ok false :error (.getMessage e)})))
          records)))

(defn run-outlet!
  "One outlet's fetch->filter-new->publish pass. `mark` is this outlet's prior
  high-water-mark (0 if never seen). Returns the result map plus :new-mark (the mark to
  persist next -- the max as-of seen among FETCHED records, whether or not every one of
  them published successfully; a transient publish failure is retried next tick via the
  normal feed-window overlap, not by refusing to advance the mark)."
  ([outlet] (run-outlet! outlet 0 {}))
  ([outlet mark] (run-outlet! outlet mark {}))
  ([outlet mark opts]
   (let [{:keys [refused reason ok gate-refused fetch-error]} (live-fetch/fetch-outlet! outlet)]
     (cond
       refused {:outlet (:id outlet) :refused true :reason reason :new-mark mark}
       fetch-error {:outlet (:id outlet) :fetch-error fetch-error :new-mark mark}
       :else (let [fresh (new-since ok mark)
                   results (publish-outlet! outlet fresh)]
               (merge
                {:outlet (:id outlet)
                 :fetched (count ok)
                 :new (count fresh)
                 :gate-refused (count gate-refused)
                 :published (count (filter :ok results))
                 :publish-errors (mapv :error (remove :ok results))
                 :new-mark (max-as-of ok mark)}
                ;; [ADR-2607252600] Same local datom archive the wasm orchestrator writes.
                ;; Both paths fetch the same articles through the same gate, so whichever
                ;; one an operator runs, the collected articles end up queryable -- an
                ;; article's presence in the archive must not depend on which orchestrator
                ;; happened to be scheduled. `:articles-dir` nil disables it.
                (when-let [dir (get opts :articles-dir store/default-archive-dir)]
                  (when (seq ok)
                    (try
                      {:archived (reduce + 0 (map :added (vals (store/append-articles! dir ok))))}
                      (catch Exception e {:archive-error (ex-message e)}))))))))))

(def ^:private inter-outlet-delay-ms
  "Spaced out, not fired back-to-back (ADR-2607110200 addendum 2): a single outlet's
  first-run backfill (30 articles) alone measured pushing the shared operator graph's read
  latency from ~4s to ~50s. 20 outlets fired in immediate succession would concentrate that
  same class of write pressure into one burst -- this spreads it across the run so the
  5-minute fold cron has more room to keep the graph compacted between outlets, without
  changing the eventual total write count."
  3000)

(defn run-all!
  "`on-result` (default no-op) is called with each outlet's result map as soon as it
  finishes -- ADR-2607110200 addendum 2: the prior version buffered every outlet into one
  final println at the end, so a slow/stuck run (a single unresponsive HTTP call, before
  jvm-http-fn's timeout fix) produced zero visible output for the run's entire duration,
  making it indistinguishable from a genuine hang from the outside (CI logs, a human
  watching) until it either finished or the job's own outer timeout killed it."
  ([allowlist-path last-seen-path] (run-all! allowlist-path last-seen-path (fn [_]) {}))
  ([allowlist-path last-seen-path on-result] (run-all! allowlist-path last-seen-path on-result {}))
  ([allowlist-path last-seen-path on-result opts]
   (let [outlets (->> (live-fetch/load-allowlist allowlist-path)
                       (filter :verified))
         last-seen (load-last-seen last-seen-path)
         results (mapv (fn [outlet]
                          (when (pos? inter-outlet-delay-ms) (Thread/sleep inter-outlet-delay-ms))
                          (let [result (try
                                         (run-outlet! outlet (get last-seen (:id outlet) 0) opts)
                                         (catch Exception e
                                           {:outlet (:id outlet) :error (.getMessage e)
                                            :new-mark (get last-seen (:id outlet) 0)}))]
                            (on-result result)
                            result))
                        outlets)
         updated (reduce (fn [m r] (assoc m (:outlet r) (:new-mark r))) last-seen results)]
     (save-last-seen! last-seen-path updated)
     results)))

(defn -main [& _]
  (let [allowlist-path (or (System/getenv "KAWARABAN_ALLOWLIST_PATH") "data/outlets/allowlist.edn")
        last-seen-path (or (System/getenv "KAWARABAN_LAST_SEEN_PATH") "data/ingest/last-seen.edn")
        ;; [ADR-2607252600] KAWARABAN_ARTICLES_DIR="" disables the local archive.
        articles-dir (let [v (System/getenv "KAWARABAN_ARTICLES_DIR")]
                       (cond (nil? v) store/default-archive-dir
                             (str/blank? v) nil
                             :else v))
        results (run-all! allowlist-path last-seen-path
                           (fn [r] (println (pr-str r)) (flush))
                           {:articles-dir articles-dir})
        errors (filter #(or (:error %) (:fetch-error %) (seq (:publish-errors %))) results)]
    (println (str "kawaraban live-ingest: " (count results) " outlets, "
                   (reduce + 0 (map #(or (:archived %) 0) results)) " archived, "
                   (reduce + 0 (map #(or (:published %) 0) results)) " published, "
                   (count errors) " with errors"))
    (when (and (seq errors) (= (count errors) (count results)))
      ;; every single outlet errored -- likely a systemic problem (network, gate,
      ;; identity), not per-outlet flakiness -- fail the run loudly instead of a
      ;; silent green no-op
      (System/exit 1))))
