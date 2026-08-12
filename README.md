# kawaraban 瓦版 — News Medium

Canonical repository: `cloud-itonami/actor-kawaraban`. This governed,
resident public-information actor belongs to the Itonami operations plane. It
mirrors and routes attributed public events; it neither authors source claims
nor owns Tamaki's artificial-organism evolution loop.

> Real-media **mirror** + actor-to-actor **wire**. kotoba-wasm-native, runs on the
> Murakumo fleet. ADR-2606061900.

瓦版 was the original Japanese news medium — Edo-period clay-block broadsheets cried and
sold in the street. It is a **sheet (面)**, not an oracle: it carries *what was said* and
*who it concerns*, it does not rule what is true.

## What it is

A **news MEDIUM** with two faces over one kotoba Datom log:

1. **Mirror** — datafies the world's real news media (outlets · 面/sections · headlines ·
   bylines · links) into the Datom log as an append-only as-of trail, matching the
   **surface (面)** of actual news media: 一面 / 政治 / 経済 / 国際 / 社会 / 文化 / 科学 /
   スポーツ. Each mirrored article is `headline + canonical link + bounded fair-use excerpt
   + outlet`. **It links out; it never stores the body and never rules truth.**

2. **Medium** — the connective wire **between etzhayyim actors**. Each first-party actor's
   own Datom as-of events project into the matching 面 as `:article/kind :actor-event`, and
   every article carries `:news.mention` edges to the actors/entities it concerns. The
   `article × mention × 面` graph **is** the actor-to-actor wire:

   ```
   danjo finds a discrepancy ─┐
   watari sees a chokepoint   ├─►  kawaraban routes each into its 面 ──► one story,
   watatsuna a cable fault    │     mention-edges link the actors    ──► kanae renders
   mitooshi a forecast       ─┘                                            the 経済/国際 面
   ```

## Why it is the charter-clean inverse of a "news app"

| A news app does… | kawaraban does the opposite |
|---|---|
| sells ads / sponsored placement (Charter-Rider §2) | **G2** — `:paid-placement`/`:sponsored` unrepresentable |
| optimizes for engagement / dwell (Charter §1.13) | **G2** — ranks by recency / 面-fit / source-diversity / actor-relevance only |
| profiles the reader, personalizes the feed | **G3** — no `:reader` entity; the 面 is identical for all |
| republishes the full article body | **G4** — `:full-text` unrepresentable; headline + link + excerpt |
| adjudicates / fact-checks ("true/false") | **G1** — `:verdict`/`:truth-rating` unrepresentable (ake/danjo own that) |
| speaks in an outlet's or person's name | **G9** — `:speak-as` unrepresentable (mirror, ADR-2606042330) |
| is itself the source | **G11** — every article is a `:mirror` or an `:actor-event`; `:original` does not exist |

## Layout

```text
manifest.edn                 canonical actor manifest
src/kawaraban/               cells, methods, publisher, CACAO and live-ingest runtime
test/kawaraban/              complete standalone test suite
data/lex/                    canonical semantic EDN lexicons
data/lex-datoms/             derived Datomic projections
data/seed.edn                representative news graph (:representative — illustrative, NOT collected)
data/outlets/allowlist.edn   live-ingest source policy (:verified is set by scripts/verify-feeds.cljs,
                              never by hand — see below)
data/articles/YYYY-MM-DD.edn collected mirror articles as :news.* datoms (:verified), written by
                              kawaraban.store; day-partitioned, deduped on :news.article/id
schema/news.edn              :news.* attribute schema — transacts into Datomic or DataScript as-is
wire/                        JSON/JSON-LD fixtures and WIT interoperability contract
wasm/                        .kotoba/WASM componentization of the CACAO self-mint and
                              aozora XRPC boundary (see wasm/README.md); test/wasm/ hosts
                              them via kotoba-lang/kototama's kototama.tender
```

Ontology: [`/00-contracts/schemas/news-medium-ontology.kotoba.edn`](../../00-contracts/schemas/news-medium-ontology.kotoba.edn).

## Run

```sh
clojure -M:test                       # complete standalone suite
clojure -M:lint

# query the collected archive (ADR-2607252600)
clojure -M:query count                # real coverage only; seed excluded by default
clojure -M:query coverage             # articles by country × outlet kind
clojure -M:query --seed q '[:find ?headline ?country
                            :where [?a :news.article/headline ?headline]
                                   [?a :news.article/outlet ?oid]
                                   [?o :news.outlet/id ?oid]
                                   [?o :news.outlet/country ?country]]'

# re-measure every feed in the allowlist and write :verified/:note back
nbb scripts/verify-feeds.cljs data/outlets/allowlist.edn --discover --apply
```

`:verified` in the allowlist means "this URL actually returned parseable RSS/Atom items
on the date in its `:note`" — a measurement, not an intention. Do not hand-edit it;
run `verify-feeds.cljs`. An outlet whose feed cannot be confirmed stays `:verified false`
with an honest note and is skipped by the orchestrator (which filters on `:verified`),
so a coverage gap is visible in the file rather than silently absent from it.

Python, Go/TinyGo, shell runners, self-referential path symlinks, and obsolete nbb
wrappers are forbidden by audit.

## Status

🟡 **R0** — design + datafication + offline composition only. No live RSS/outlet ingest, no
live publish (all G8-gated → Council Lv6+ + operator). Registered in INFRA_ACTORS →
`did:web:etzhayyim.com:actor:kawaraban`.
