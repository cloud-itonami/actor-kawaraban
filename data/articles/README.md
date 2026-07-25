# `data/articles/` — collected mirror archive

Empty until the first gated live run. This is the correct state, not a missing file.

`kawaraban.store/append-articles!` writes one `YYYY-MM-DD.edn` per UTC publication day
(plus `unknown-as-of.edn` for articles whose feed date could not be parsed — those are
quarantined rather than backdated to 1970 or forward-dated to the run day). Each file is a
vector of `:news.*` datom maps that transacts unchanged against `../../schema/news.edn`,
into Datomic or DataScript.

Articles land here when `kawaraban.wasm-orchestrator` runs **with the G8 gate open**
(`KAWARABAN_ALLOW_LIVE_INGEST=1` + Council Lv6+ attestation). Until then
`live-fetch/fetch-outlet!` refuses every fetch, so there is nothing to archive. Registering
an outlet in `../outlets/allowlist.edn` does not by itself collect anything.

Two properties worth knowing before you query this directory:

- **The archive is not bounded by the publish limit.** `:max-articles-per-outlet` throttles
  how many articles get pushed to the aozora PDS per tick; it does not throttle this
  archive. Every article that passed the charter gate is written here whether or not it was
  published, attempted, or accepted. It is an observation record, not a publish ledger.
- **`:news.article/sourcing` is `:verified` here and `:representative` in
  `../seed.edn`.** The seed is an illustrative graph — real-looking but not collected from
  anywhere. `clojure -M:query` excludes it unless you pass `--seed`, so counts read as real
  coverage by default.
