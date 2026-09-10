(ns kawaraban.publish
  "Glue: take an already-gated record — `ingest/normalize-record`'s :mirror output, or the
  `actor_project` cell's :actor-event `payload` — and hand it to a `kawaraban.publisher`
  (MockPublisher by default, `kawaraban.aozora/aozora-publisher` for the real PDS).
  ADR-2607110200. Never re-validates: gating already happened upstream (ingest.cljc's
  normalize-batch, or route/validate + the cell's own refusal branch) — this namespace only
  addresses the record for the wire (:rkey) and calls publish!, per
  ADR-2606281500 (publication is autonomous by default, no additional gate here)."
  (:require [kawaraban.publisher :as publisher]))

(defn mirror-record->wire
  "ingest/normalize-record's :mirror output (string-keyed \":news.article/*\" map) -> the
  shape kawaraban.aozora/aozora-publisher expects (adds :rkey; keeps the content fields
  as-is for JSON serialization)."
  [record]
  (-> record
      (dissoc "_excerpt_truncated")
      (assoc :rkey (get record ":news.article/id"))))

(defn actor-event-payload->wire
  "actor_project cell's :actor-event `payload` (from a :ready cell-plan/`project` result) ->
  the shape kawaraban.aozora/aozora-publisher expects."
  [payload]
  (assoc payload :rkey (get payload "articleId")))

(defn publish-mirror!
  "The :mirror path end-to-end: a raw feed record (already through
  `ingest/normalize-batch`, i.e. already in `ok`) -> publish. Returns the publisher's
  {:uri :cid}. Callers are expected to only pass records from the `ok` half of
  normalize-batch — a refused record must never reach this fn (same discipline as
  tashikame's :commit: only a governor-passed proposal may publish)."
  [publisher record]
  (publisher/publish! publisher (mirror-record->wire record)))

(defn publish-actor-event!
  "The :actor-event path end-to-end: an already-:projected cell payload -> publish."
  [publisher payload]
  (publisher/publish! publisher (actor-event-payload->wire payload)))
