# Bootstrap Attestation — kawaraban live ingest (G8)

**Actor**: kawaraban 瓦版 · **ADR**: 2607110200 / 2607252600 / 2607253400 · **Gate**: G8 outward-gated · **Mode**: BOOTSTRAP (Council < quorum)

## Authorization

Per the etzhayyim bootstrap-phase governance precedent — constitutional gates are
released under *proposed* status by **Seat 1 (Founder, Lv7)** alone during the
Bootstrap Council RFP, and a 3-of-5 Council multisig is physically unconstructable
while the organization has a single participant (same reasoning as ooyake's
`BOOTSTRAP-ATTESTATION-reconcile-live.md`, 2026-06-02) — the Founder hereby grants a
**provisional bootstrap authorization** for kawaraban's G8 live-ingest gate.

- **Granted by**: Seat 1 — Jun Kawasaki (Lv7, Founder)
- **Date**: 2026-07-26, given in session ("kawaraban の g8 gate を承認")
- **Instrument**: provisional, bootstrap-phase. **Re-ratification REQUIRED** by a
  Council Lv6+ 3-of-5 multisig once the Council reaches quorum.
- **Cryptographic attestation**: no signature is attached and **no agent read or used
  any signing key** for this record. This document is the human-readable record of
  the authorization given in session, matching the ooyake precedent.

## Scope — what this authorization turns on, and what it does NOT

G8 in the charter reads "outward-gated (live ingest/publish)". The implementation
splits that into two independent switches, and this attestation deliberately opens
only the first.

**Covers — `KAWARABAN_ALLOW_LIVE_INGEST=1`: reading.**

`live-fetch/fetch-outlet!` may issue real HTTP GETs against the `:verified` feeds in
`data/outlets/allowlist.edn`, and gate-passed articles may be written to the local
datom archive `data/articles/YYYY-MM-DD.edn` (ADR-2607252600). Every existing
structural gate is unchanged and still applies to each record:

- **G4** — headline + canonical URL + ≤280-char excerpt only; a record carrying a
  body is refused by `ingest/normalize-record`, not filtered later.
- **G4** — only `:open` / `:registration-wall` outlets; paywalled sources are
  unrepresentable.
- **G1** — a record carrying a verdict or truth-rating is refused.
- **G3** — a record carrying any reader identifier is refused.
- **G5** — live-fetched records are marked `:news.article/sourcing :verified` and are
  distinguishable from the illustrative `:representative` seed.

**Does NOT cover — `KAWARABAN_WASM_PDS_ALLOWLIST`: writing outward.**

Publishing to `pds.aozora.app` stays refused. The allowlist is unset, which
`kototama.contract/url-allowed?` treats as deny-all, so every outbound HTTP call is
refused before `HttpClient.send`. Opening that is a separate decision and needs its
own record.

The separation is deliberate rather than timid. Reading public RSS that outlets
publish for reading is materially different from writing signed records into a shared
production PDS other services depend on, and this repo's own history argues for
proving the first before enabling the second: the 2026-07-10 activation at 3
articles/outlet overran its CPU budget, and shared-graph read latency was measured
going from ~4s to ~50s under burst writes (ADR-2607110200 addendum 2). Verify, then
deploy.

## First run

Bounded deliberately, and archive-only:

- `KAWARABAN_ALLOW_LIVE_INGEST=1`
- `KAWARABAN_WASM_MAX_ARTICLES_PER_OUTLET=0` — zero publish attempts. The archive is
  written from the full gate-passed set and is not bounded by this limit
  (`archive-records!`), so collection is complete while outward work is nil.
- `KAWARABAN_WASM_PDS_ALLOWLIST` unset — deny-all.

## Revocation

Unsetting `KAWARABAN_ALLOW_LIVE_INGEST` restores the refusal immediately; the gate is
read per call, not cached. Nothing in this authorization is self-renewing, and it
confers nothing about publishing, about any other actor, or about any other gate.
