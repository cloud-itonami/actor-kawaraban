# kawaraban 瓦版 — Maturity

**Stage: R0** (scaffold) — ADR-2606061900. News MEDIUM: mirror of the world's real news media
(headline + link-out + ≤280-char fair-use excerpt) + the actor-to-actor wire. The charter-clean
inverse of a news app. Central ingest actor of the ADR-2606161536 pipeline (`utsushie-loop`).

| Dimension | State |
|---|---|
| Lexicons | ✅ 6 canonical semantic EDN under `data/lex`; Datomic projections and wire JSON separated |
| Cells | ✅ 7 canonical CLJC cells including fulltext-cache and social projection |
| Manifest | ✅ canonical `manifest.edn` — gates G1–G11 |
| Tests | ✅ `clojure -M:test`: **81 tests / 182 assertions / 0 failures** (2026-07-25) |
| Archive | ✅ `data/articles/YYYY-MM-DD.edn` + `schema/news.edn` + `clojure -M:query` (ADR-2607252600) — empty until the G8 gate opens |
| Outlets | 🟡 154 registered / **84 feed-verified across 56 countries** (re-measured weekly by `.github/workflows/refresh-feeds.yml`) |
| Methods | ✅ route/analyze/ingest/live-fetch plus signed publisher/CACAO/Aozora runtime |
| Audit | ✅ EDN syntax, canonical/wire pairing, wire boundary, deprecated artifact exclusion |

## The 11 gates pinned by the new charter-gate test (lexicon `const`/`enum`)

- **G1 mirror-not-adjudicator** — `article.verdict` const false + `article.truthRating` const 0.
- **G2 no ads / no engagement rank** — `issue.rankSignals` ∈ {recency, section-fit,
  source-diversity, actor-relevance, geo-proximity}; paid-placement/sponsored/engagement/
  dwell-time unrepresentable.
- **G3 no reader surveillance** — `article.personalizedFor` const false (面 identical for all).
- **G4 copyright / link-out** — `article.fullText` const false; `excerpt` maxLength 280;
  `outlet.access` ∈ {open, registration-wall} (no paywall/terminal feed).
- **G7 no-server-key** — `issue.serverHeldKey` const false.
- **G9 mirror-not-impersonation** — `article.speakAs` const false.
- **G10 non-eschatological** — `issue.final` const false.
- **G11 medium-not-source** — `article.kind` ∈ {mirror, actor-event} (no `:original`).

(G5 source-provenance / G6 Murakumo-only / G8 outward-gated are enforced in the cells/methods;
the manifest declares all G1–G11.)

## R0 → R1 gate

Council Lv6+ + operator for live RSS/firehose ingest (G8); R1 wires the `actor_project` cell
to the feed-post membrane (ADR-2605231902) for `app.bsky.feed.post` projection. Per the
pipeline ADR-2606161536, the CC-corpus → G4-bounded `:article` derivation (D1) lands here.

> **2026-07-18 standalone migration:** canonical EDN, Datomic projections, wire assets,
> live-ingest/publisher runtime, and all tests are now self-contained in this repository.

> **2026-07-19 world-scope outlet expansion (ADR-2607197800):** `data/outlets/allowlist.edn`
> grew from 22 to 37 entries (registry-only change; no lexicon/cell/charter-gate code
> touched, `KAWARABAN_ALLOW_LIVE_INGEST` semantics unchanged). The prior set skewed heavily
> anglophone + Japan + Qatar (US/GB/DE/FR/HK/CA/AU/JP/QA/INT only). Added 15 new
> state/public-broadcaster or non-profit/international-org outlets spanning China, Russia,
> Iran, Turkey, South Africa, Brazil, Latin America (multi-country), Vietnam, Malaysia,
> Singapore, South Korea, and WHO (international org):
>
> - **10 `:verified true`** (live-fetched this session, confirmed real RSS/Atom XML with
>   current, same-day-dated items): CGTN (China), TASS (Russia), Press TV (Iran), Anadolu
>   Agency (Turkey), SABC News (South Africa), Agência Brasil/EBC (Brazil), BERNAMA
>   (Malaysia), CNA (Singapore, semi-state via Mediacorp/Temasek — flagged honestly in its
>   `:note`), KBS World (South Korea), WHO News (international org).
> - **5 `:verified false`** (honest, non-fabricated): Xinhua (China) — the URL resolves and
>   returns real RSS XML, but every item is stale (dated 2017–2018, an abandoned legacy
>   endpoint, not fabricated but not live either); TRT World (Turkey), Arirang (South Korea),
>   Voice of Vietnam/VOV World (Vietnam), and teleSUR English (Latin America, multi-country)
>   — no working public feed URL could be located this session for these four, so
>   `:feed-url` is left `nil` rather than guessing wrong, same discipline as the pre-existing
>   AP entry.
>
> India (DD News / PIB) was deliberately NOT added here — handled by a separate concurrent
> task on the kouhou repo instead, per ADR-2607197800. World coverage remains best-effort and
> incomplete after this wave, not exhaustive — gaps should keep being filled incrementally
> and honestly flagged, not silently backfilled with guessed URLs.

> **2026-07-23 kotoba-wasm componentization, Phase B (com-junkawasaki/root
> ADR-2607231022, kototama PR #49 "Phase A"):** `wasm/` (new) ports a
> confined slice of `src/kawaraban/cacao.clj` (Ed25519 self-mint: fresh
> keypair + SHA-256 fingerprint + Ed25519 sign + flat CBOR encode) and
> `src/kawaraban/aozora.clj` (the `com.atproto.server.createSession` HTTP
> half: JSON envelope build + POST, and the response-field-extraction
> half) to real `.kotoba` → WASM modules, hosted and verified via
> `kototama.tender` against a real Chicory `Instance` — see
> `wasm/README.md` for the full design, ABI, and an honest gap list
> (nested CBOR maps, HTTP headers, i64 division, did:key/graph-cid
> bignum encoding all stay out of scope for this pass, each with a
> concrete reason). **RSS/Atom fetch+parse (`methods/live_fetch.cljc`)
> and the G1/G3/G4 charter gates (`methods/ingest.cljc`) are
> deliberately NOT touched** — `.kotoba`'s language subset genuinely
> cannot express unbounded-length XML parsing (confirmed empirically,
> not merely assumed), and this pass's scope is the execution-substrate
> change for the CACAO/XRPC boundary only, not a re-implementation of the
> charter. No live internet calls were made anywhere in this pass
> (task constraint) — `aozora_create_session.kotoba`'s `http-post` target
> is loopback on purpose, proving real compiler+tender linkage and real
> SSRF-guard execution the same way kototama's own
> `kotoba-compiled-http-fetch.kotoba` fixture already did, not a live
> round trip. `clojure -M:test` — 29 tests / 56 assertions, 0
> failures/errors (20/46 pre-existing + 9/10 new). Fleet placement on
> Murakumo (Phase D) is a separate, later, explicitly-confirmed step.

> **2026-07-25 queryable archive + measured world coverage (ADR-2607252600):** collected
> articles now persist locally as `:news.*` datoms in `data/articles/YYYY-MM-DD.edn`
> (`kawaraban.store`, written by BOTH orchestrators, deduped on `:news.article/id`,
> undated articles quarantined in `unknown-as-of.edn`), transactable against the new
> `schema/news.edn` and queryable via `clojure -M:query` (DataScript; `data/seed.edn`
> excluded unless `--seed`, so counts read as real coverage rather than illustration).
> The archive is deliberately NOT bounded by `:max-articles-per-outlet` — that bound
> protects the PDS and per-article wasm instantiation cost, neither of which applies to
> appending EDN, and the archive is an observation record rather than a publish ledger.
> `:news.article/sourcing` now distinguishes `:verified` (came off the outlet's own feed)
> from `:representative` (the illustrative seed); the previous hard-coded
> `":representative"` labelled real BBC articles as examples, which G5 forbids.
>
> The outlet allowlist grew 37 → **120 entries, of which 77 were feed-verified live on
> 2026-07-25 across 50 countries/regions and 15 languages** (was: 20 countries, 3
> languages, and a hand-written `:verified` flag that claimed 30 working feeds when only
> 29 parsed). `:verified`/`:note` are now machine-owned, written by
> `nbb scripts/verify-feeds.cljs --discover --apply`, which re-measures every feed and
> discovers replacement URLs from the outlet homepage — 15 of the working feeds were
> found that way after the guessed URL 404'd. The 43 entries that could not be confirmed
> (403 bot-blocks at IMF/OHCHR/UNHCR/ILO/OECD/IAEA/PIB India/Kan/MAP, plus feedless or
> unreachable sites) are KEPT with honest notes so the gap is visible in the file; 22
> registered countries currently have no working feed at all. **`data/articles/` stays
> empty until an operator opens the G8 gate** (`KAWARABAN_ALLOW_LIVE_INGEST=1` + Council
> Lv6+) — that decision is explicitly out of this ADR's scope, and registering an outlet
> collects nothing by itself.

> **2026-07-25 (2) coverage + weekly re-measurement (ADR-2607253200):** allowlist
> 120 → **154 entries, 84 feed-verified across 56 countries** (was 77 / 50). Second- and
> third-choice public broadcasters were tried for every country that had no working feed
> at all; that unlocked **CU, HR, IR, MA, MX, RS** (Granma, Cubadebate, HRT Vijesti, IRNA,
> SNRT, IMER, RTS Vesti), 7 of 34 candidates. The other 27 are kept with dated failure
> notes so the gap stays visible rather than looking unconsidered.
>
> **Sites that return 403 to an honestly-identified bot are recorded as unreachable and
> left that way.** Spoofing a browser User-Agent to get past bot detection is not
> something this repo does, so IMF / OHCHR / UNHCR / ILO / OECD / IAEA / PIB India /
> Kan / MAP / MTI stay `:verified false` with the 403 in their `:note`. That is a real
> coverage limit, not a bug to engineer around.
>
> `.github/workflows/refresh-feeds.yml` now re-measures every feed weekly and commits
> the result, because the failure mode being fixed is "nobody runs the check" — which is
> exactly how the flags came to claim 30 working feeds when 29 parsed. `verify-feeds.cljs`
> refuses to write when the verified count collapses below 70% of the previous run: a
> network-broken runner must fail loudly, never commit "everything is dead" as a finding.
