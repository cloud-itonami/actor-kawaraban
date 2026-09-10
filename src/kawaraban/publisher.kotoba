(ns kawaraban.publisher
  "Publisher — the outbound surface for a kawaraban article/issue projection, injected so the
  network is a swap (MockPublisher default ‖ real app-aozora createRecord via
  `kawaraban.aozora`). ADR-2607110200 (R0->R1), same shape as `tashikame.publisher`.

  record shape (what gets published) — a :news.article/* map as produced by
  `ingest/normalize-record` (:mirror) or the `actor_project` cell (:actor-event), plus an
  optional :collection override."
  )

(def collection
  "Matches the RAD identity journal's pre-recorded :rad/aozora-collection
  (kawaraban.identity.journal.edn tx 4)."
  "com.etzhayyim.apps.kawaraban")

(defprotocol Publisher
  (publish! [p record] "publish one article/issue record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/kawaraban/" (or (get record ":news.article/id") (get record "articleId")))
     :cid (str "mock:" (or (get record ":news.article/id") (get record "articleId")))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been published."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
