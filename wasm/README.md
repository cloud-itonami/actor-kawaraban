# wasm/ — kotoba-wasm componentization of kawaraban (Phase B, Phase F, Phase H)

This directory ports confined slices of `src/kawaraban/cacao.clj` and
`src/kawaraban/aozora.clj` into the `.kotoba` language subset, compiled to
real WASM modules via `kotoba wasm emit` (in practice: a direct
`kotoba.runtime/wasm-binary` call against a local sibling checkout, see
"Rebuilding" below), and hosted via `kototama.tender` (`test/wasm/*_test.clj`)
— the same `kotoba wasm emit` → `kototama.tender` pipeline established by
ADR-2607062330 addendum 5 and already used by `cloud-itonami-isic-6310`'s
`wasm/achievement_band.kotoba`, `cloud-itonami-isic-6419`'s
`wasm/iban_checksum.kotoba`, and `cloud-itonami-isic-6511`'s
`wasm/underwriting_decision.kotoba`.

This directory has grown across two sessions:

- **Phase B** (com-junkawasaki/root ADR-2607231200, this repo's PR #9) —
  `cacao_self_mint.kotoba`, `aozora_create_session.kotoba`,
  `aozora_extract_session_fields.kotoba`. ADR-2607231022 ("Phase A")
  registered the `http-fetch`/`cbor-encode`/`json-encode`/
  `json-extract-field` capabilities this phase needed in
  `kotoba-lang/kototama` PR #49. Phase B hit two walls documented below
  (findings 4 and 5) and explicitly did NOT port the actual per-article
  publish call (`com.atproto.repo.createRecord`).
- **Phase F** (com-junkawasaki/root, this session) — `cacao_wire_encode.kotoba`,
  `aozora_create_record.kotoba`. ADR-2607231234 ("Phase E", a
  `kotoba-lang/kototama`/`kotoba-lang/kotoba-core-contracts`-side change,
  PR #50/#11) closed BOTH of Phase B's remaining walls (`http-post-headers`
  for the mandatory `Authorization: Bearer <jwt>` header, and dotted-path
  nesting for `cbor-encode`/`json-encode`) — this phase spends that
  capability budget to actually port the real `com.atproto.repo.createRecord`
  call and a byte-exact `->wire` (nested CACAO envelope) encoder. Live
  fleet placement on Murakumo is a later Phase (not attempted here).

## Honest scope: what this DOES and does NOT port

**RSS/Atom fetch + parse (`src/kawaraban/methods/live_fetch.cljc`) is
deliberately NOT ported and stays JVM-side, unchanged.** `.kotoba` has no
loop/recur over unbounded input, no string search/slice primitive, and no
XML/tag-matching facility of any kind — parsing arbitrary-length untrusted
network responses (variable tag names, nested elements, RSS 1.0/RDF vs.
Atom vs. RSS 2.0 shape differences `live_fetch.cljc` already handles) is
not expressible in the confined subset this compiler actually implements.
This mirrors the `.kotoba` reality every prior sibling wasm port already
documented (see "Language-limitation findings" below) and is explicitly
permitted by this task's own instructions when the language genuinely
cannot express the logic. `methods/ingest.cljc`'s charter-gate logic
(G1/G3/G4 `normalize-record`) is equally untouched — this port never
re-implements or weakens it, in either phase.

**What genuinely IS ported and running as real WASM, verified against a
real Chicory `Instance` (not a mock, not a hand-written WAT string):**

| module | ports | capabilities used | phase |
|---|---|---|---|
| `cacao_self_mint.kotoba` | a confined slice of `kawaraban.cacao/mint`: fresh Ed25519 identity + SHA-256 fingerprint of the pubkey + Ed25519 signature over a host-formatted message + FLAT CBOR encode (deliberately unchanged, see below) | `identity/keypair`, `identity/sign`, `hash/sha256`, `data/cbor` | B |
| `aozora_create_session.kotoba` | the HTTP half of `kawaraban.aozora/mint-session!`: build `com.atproto.server.createSession`'s real `{"cacao": "..."}` JSON envelope and POST it | `data/json`, `http/post` | B |
| `aozora_extract_session_fields.kotoba` | the response half of `kawaraban.aozora/mint-session!`: pull `accessJwt` out of an example createSession response | `data/json` | B |
| `cacao_wire_encode.kotoba` | a faithful, standalone port of `kawaraban.cacao/->wire` — the REAL nested `{"h":{"t":"eip4361"},"p":{...,"resources":[...]},"s":{"t":"EdDSA","s":"..."}}` CACAO wire shape, byte-exact vs. the real JVM reference | `data/cbor` (nested) | F |
| `aozora_create_record.kotoba` | the confined slice of `kawaraban.aozora/create-record!`: build the real `com.atproto.repo.createRecord` body (nested `record` object, incl. an array-of-objects field) and POST it with BOTH `Content-Type` and `Authorization: Bearer <jwt>` headers | `data/json` (nested), `http/post` (via `http-post-headers`) | F |

## Language-limitation findings (independently confirmed, not new)

Findings 1-3 and 6 below were independently reached by at least one prior
sibling wasm port (`cloud-itonami-isic-6310`/`-6419`/`-6511`) by reading
`kotoba-lang/kotoba/src/kotoba/runtime.clj`'s `compile-wasm-expr`
end-to-end; Phase B confirmed them again from scratch and added finding 4
(nested CBOR maps) and finding 5 (no header parameter). **Phase F closes
findings 4 and 5** — see each finding's own note below.

1. **No runtime string construction beyond compile-time literals.**
   `str-ptr`/`str-len`/`byte-at` only accept string values the compiler can
   see as literals at compile time (`literal-bytes` requires a literal
   argument) — there is no `str`/`format`/`substring`/`concat` builtin.
   Also confirmed directly (Phase F): a `.kotoba` string literal is capped
   at **127 UTF-8 bytes** (`kotoba.runtime`'s
   `portable-string-symbol-values` admission check, `literal-bytes`) — this
   is why both new Phase F modules build their (much larger) flat/dotted-path
   pairs buffers out of many small (<=20-byte) literal KEY-name fragments
   interleaved with `copy-bytes!` calls that copy DYNAMIC (host-poked)
   VALUE text, rather than ever trying to embed a full pre-assembled pairs
   string as one literal.
2. **`main` is always 0-arity.** `kotoba wasm emit` rejects a parameterized
   `main` (`:main-arity`). Real per-run inputs are threaded in by having
   the HOST write raw bytes into the guest's own exported linear memory at
   fixed, pre-agreed offsets **before** calling `main()` — the established
   convention every sibling wasm port above uses. Both Phase F modules
   extend this convention from "one dynamic field" (Phase B's SIWE message
   / CACAO token text) to **9-10 independent dynamic fields at once**
   (see each module's own ABI table) — still architecture, not a missing
   feature: `[0, 1024)` is always safe host-writable territory, because
   `kotoba.runtime/memory-layout` lays out this module's own string
   literals starting at a fixed offset **1024**, and `alloc`'s bump
   allocator never hands out memory below `heap-base` (always >= 2048).
3. **No i64 division/mod/quot.** Unchanged from Phase B; moot for every
   module in this port because all per-run text (timestamps included) is
   host-formatted before being poked into guest memory, never computed
   guest-side.
4. **`cbor-encode`/`json-encode` used to only produce a FLAT (single-level)
   definite-length map — CLOSED by Phase F.** `kotoba-lang/kototama` PR #50
   (com-junkawasaki/root ADR-2607231234, "Phase E") extended
   `kototama.tender`'s interpretation of the SAME flat `key<TAB>value` wire
   format with a **dotted-path convention** (`"s.t"`, `"resources.0"`,
   `"p.resources.0"` — a numeric segment selects an array index, any other
   segment selects an object field; dot-free keys are byte-identical to
   the pre-extension behavior). The `cbor_encode`/`json_encode` host-import
   ABI shape itself did not change at all — only `kototama.tender`'s
   interpretation of the bytes grew — so no `kotoba-core-contracts` change
   was needed for this half of Phase E.
   `cacao_wire_encode.kotoba` (Phase F) uses this to produce the REAL
   nested CACAO wire shape, byte-exact against the real JVM reference
   (see "Verified locally" below). `cacao_self_mint.kotoba` (Phase B)
   **deliberately still emits its OWN flat 3-field CBOR** — see the
   "Why `cacao_self_mint.kotoba` itself was not rewritten" note below for
   why that is a considered decision, not an oversight.
5. **`http-post`/`http-fetch` used to take no header parameter at all —
   CLOSED by Phase F for POST.** `com.atproto.repo.createRecord` genuinely
   needs `Authorization: Bearer <jwt>` (`kawaraban.aozora/create-record!`).
   `kotoba-lang/kototama` PR #50 / `kotoba-lang/kotoba-core-contracts`
   PR #11 (Phase E) added a **separate host-import**, `http-post-headers`
   (NOT a new arity on `http-post` itself — a Wasm import's arity is part
   of the compiled guest's own import section, so widening `http-post`'s
   arity would have broken every already-compiled guest that imports it).
   `http-post-headers` reuses `http-post`'s own SSRF/DoS guard
   (`blocked-http-post-destination?`, `contract/url-allowed?`) and its
   `:max-http-posts` quota (same operation, same budget, not a second
   quota) — headers themselves travel as the SAME flat `key<TAB>value`,
   LF-separated wire format `cbor-encode`/`json-encode` already use (a
   header block is inherently flat, so it never needed the dotted-path
   extension). `aozora_create_record.kotoba` (Phase F) uses this to send
   BOTH `Content-Type: application/json` and `Authorization: Bearer <jwt>`.
   `http-fetch` (GET-only) was NOT given a headers variant in Phase E —
   moot for this port, nothing here needs a GET with custom headers.
6. **did:key / graph-cid derivation cannot be expressed.** Unchanged from
   Phase B — both need arbitrary-precision (bignum) integer division /
   5-bit-group bit-packing this language's bit ops could express in
   principle but at disproportionate effort for this pass. Both stay
   host-side, unchanged. Still not attempted in Phase F.

## Why `cacao_self_mint.kotoba` itself was not rewritten in place

Phase F's task explicitly asked to close the flat-CBOR gap
`cacao_self_mint.kotoba`'s own README entry (finding 4, Phase B) had
flagged. The gap IS closed — but as a **separate, dedicated module**
(`cacao_wire_encode.kotoba`), not as an in-place rewrite of
`cacao_self_mint.kotoba`'s own CBOR construction. Reasoning:

`kawaraban.cacao/->wire` is, in the real Clojure source, a deliberately
**pure data-shaping function** — `(defn ->wire [payload sig-b64] {"h" ...
"p" ... "s" ...})`. It takes an ALREADY-COMPUTED `sig-b64` string as a
plain argument; the actual Ed25519 signing happens one step earlier,
inside `mint` (which calls `ed-sign` and THEN calls `->wire`, not the
other way around). `cacao_self_mint.kotoba`'s OWN job, meanwhile, is to
prove the CRYPTO CHAIN — `gen-keypair` → `sha256-hex` → `sign` — actually
runs as real WASM (its tests verify the guest's OWN freshly-generated
keypair's fingerprint against an independently-computed SHA-256, and the
guest's OWN signature genuinely verifies via `ed25519.core/verify`). A
freshly-generated Ed25519 signature is, by construction, never the same
two runs in a row (different keypair each time) — so a module that
insists on doing REAL signing can never be BYTE-EXACT tested against one
fixed golden CBOR array the way ADR-2607231234's own JVM reference
verification was (that verification used a canned placeholder string,
`"sig-b64-fixed-value"`, standing in for a real signature — because
`->wire` itself doesn't care how `sig-b64` was produced).

Splitting these two concerns into two modules — `cacao_self_mint.kotoba`
(real crypto chain, still flat, unchanged since Phase B) and
`cacao_wire_encode.kotoba` (real nested wire shape, byte-exact vs. the
golden fixture, ZERO crypto inside it — `sig-b64` is host-provided opaque
text, exactly matching `->wire`'s own real function signature) — mirrors
the real Clojure namespace's OWN separation of concerns (`mint` calls
`ed-sign` THEN `->wire`; they are two different functions today) and lets
BOTH proofs stay clean: `cacao_self_mint_test.clj`'s crypto assertions are
completely unchanged from Phase B, and `cacao_wire_encode_test.clj`'s
byte-exact assertion is a genuine, unambiguous, zero-fudging match against
the same golden fixture `kotoba-lang/kototama`'s own test computed. A
future phase could still fold both into one module (host provides the
p-payload fields, guest signs internally, and produces a REAL fresh
nested envelope using its own real signature) — that module would no
longer be byte-exact-testable against a fixed golden array (a genuine
signature differs every run) but would be structurally end-to-end
testable (parse the CBOR back, verify the embedded signature via
`ed25519.core/verify`). Not attempted in Phase F; left as a clearly-scoped
follow-up, not a silent gap.

## Not ported (and why)

- **did:key / graph-cid derivation** — see finding 6.
- **RSS/Atom fetch + parse** — see "Honest scope" above.
- ~~`com.atproto.repo.createRecord`~~ — **NOW PORTED as of Phase F**
  (`aozora_create_record.kotoba`), closing the gap Phase B's README
  originally listed here.

## ABI — Phase B modules (unchanged)

### `cacao_self_mint.kotoba`

Input (host writes before calling `main`):

| offset | field | notes |
|---|---|---|
| 0 (i32) | `msg-len` | length of the SIWE message text at offset 8 |
| 8.. | SIWE message text | UTF-8, host-formatted via `kawaraban.cacao/siwe-message` unchanged; `msg-len` MUST be `<= 2000` (stays clear of `heap-base` 2048) |

`main`'s internal `alloc` sequence (bump allocator, deterministic given
this exact source — verified empirically, see `wasm/README.md`'s own
rebuild + `test/wasm/cacao_self_mint_test.clj`'s comments): `seed-ptr`
@2048 (64B: 32B seed + 32B pubkey) → `fp-ptr` @2112 (64B: sha256-hex ASCII
text) → `sig-ptr` @2176 (64B: raw Ed25519 signature) → `pairs-ptr` @2240
(256B: assembled flat-pairs text) → `out-ptr` @2496 (512B: CBOR output).

Output: `main` returns `cbor-encode`'s bytes-written count. The CBOR bytes
themselves are at address 2496 (`out-ptr`) for that many bytes — a
definite-length map (`0xA3` header byte = major-type 5, count 3) with
`type` → `"eip4361"`, `fingerprint` → 64-char lowercase hex
`sha256(pubkey)`, `sig` → 128-char lowercase hex Ed25519 signature over the
SIWE message text, signed with the freshly-generated seed. **Still a flat
approximation, on purpose — see "Why `cacao_self_mint.kotoba` itself was
not rewritten in place" above.**

### `aozora_create_session.kotoba`

Input (host writes before calling `main`):

| offset | field | notes |
|---|---|---|
| 0 (i32) | `cacao-len` | length of the CACAO token text at offset 8 |
| 8.. | CACAO token text | opaque to this module — e.g. hex(cbor bytes) or a real base64 `cacao_b64`; `cacao-len` MUST be `<= 2032` |

Alloc sequence: `pairs-ptr` @2048 (2048B) → `body-ptr` @4096 (2200B: the
JSON body `{"cacao":"<cacao text>"}`) → `resp-ptr` @6296 (512B).

Output: `main` returns `http-post`'s result — **always `-1` in this repo's
test suite** (the target URL, `http://127.0.0.1/xrpc/com.atproto.server.createSession`,
is loopback ON PURPOSE; `kototama.tender`'s unconditional SSRF denylist
refuses it before any connection is attempted). No internet access happens
anywhere in this port's test suite (task constraint for every session so
far, Phase B and Phase F alike).

### `aozora_extract_session_fields.kotoba`

No input ABI — the example `com.atproto.server.createSession` response
text is a compile-time literal (127-UTF8-byte string-literal admission
cap forced trimming it to just the 2 fields this module reads). Returns
`json-extract-field`'s bytes-written count for the `accessJwt` field
(`16`, `"demo.session.jwt"`).

## ABI — Phase F modules (new)

### `cacao_wire_encode.kotoba`

A faithful, standalone port of `kawaraban.cacao/->wire` — see "Why
`cacao_self_mint.kotoba` itself was not rewritten in place" above for why
this is its own module. Every one of the 10 wire fields is host-provided
opaque UTF-8 text (an `(i32 length, text)` pair each), all inside
`[0, 1024)` (always safe — see finding 2's note above for why):

| field | len offset | text offset | max width |
|---|---:|---:|---:|
| `iss` | 0 | 8 | 64 |
| `aud` | 80 | 88 | 64 |
| `iat` | 160 | 168 | 40 |
| `nonce` | 216 | 224 | 40 |
| `domain` | 272 | 280 | 32 |
| `version` | 320 | 328 | 8 |
| `resources.0` | 344 | 352 | 64 |
| `resources.1` | 424 | 432 | 64 |
| `exp` | 504 | 512 | 40 |
| `sig` (`s.s`) | 560 | 568 | 200 |

(`kawaraban.cacao/grant->resources` always returns EXACTLY 2 resource URIs
— `[op-uri graph-uri]` — for every real grant, so a fixed 2-element
`resources` array is a genuine domain invariant, not an arbitrary
simplification. `:statement` is omitted — `kawaraban.aozora/mint-session!`'s
real call site never passes one either.)

Alloc sequence: `pairs-ptr` @2048 (2048B) → `out-ptr` @4096 (512B: CBOR
output).

Output: `main` returns `cbor-encode`'s bytes-written count. For the fixed
field values `test/wasm/cacao_wire_encode_test.clj` uses (the SAME values
`kotoba-lang/kototama`'s own golden-fixture test, ADR-2607231234, used),
this is exactly **264 bytes**, byte-exact against the real JVM reference
(`cloud_itonami.media.cacao/->wire`, traced to `kawaraban.cacao/->wire`).

### `aozora_create_record.kotoba`

The confined slice of `kawaraban.aozora/create-record!`: build the real
`com.atproto.repo.createRecord` request body (`{"repo":..,
"collection":.., "rkey":.., "record":{...}}`, `record` itself an object
with a nested array-of-objects field) and POST it with both required
headers. Every field is host-provided opaque UTF-8 text, all inside
`[0, 1024)`:

| field | len offset | text offset | max width |
|---|---:|---:|---:|
| `repo` (did) | 0 | 8 | 64 |
| `collection` | 80 | 88 | 64 |
| `rkey` | 160 | 168 | 64 |
| `record.analysis` | 240 | 248 | 128 |
| `record.cites.0.url` | 384 | 392 | 64 |
| `record.cites.1.url` | 464 | 472 | 64 |
| `record.createdAt` | 544 | 552 | 40 |
| `record.actor` | 600 | 608 | 64 |
| `jwt` (session `accessJwt`) | 680 | 688 | 300 |

(A real per-outlet session JWT MUST fit within the 300-byte cap above — a
genuine, documented scope limit of THIS module, the same class of honest
constraint `cacao_self_mint.kotoba`'s `msg-len <= 2000` already documents,
not a silent truncation.)

Alloc sequence: `pairs-ptr` @2048 (2048B) → `body-ptr` @4096 (2200B: the
JSON body) → `headers-ptr` @6296 (512B: `Content-Type`/`Authorization`
flat-pairs header block) → `resp-ptr` @6808 (512B).

Output: `main` returns `http-post-headers`' result — **always `-1`** in
this repo's test suite (the target URL,
`http://127.0.0.1/xrpc/com.atproto.repo.createRecord`, is loopback ON
PURPOSE; same unconditional SSRF denylist as every other `http-post*`
module here). For the fixed field values
`test/wasm/aozora_create_record_test.clj` uses (the SAME values
`kotoba-lang/kototama`'s own golden-fixture test used), the JSON body this
module built is exactly **297 bytes**, byte-exact against the real JVM
reference (`cloud_itonami.media.aozora/create-record!`, via
`cheshire.core/generate-string`, traced to
`kawaraban.aozora/create-record!`). The headers this module assembles
(`Content-Type: application/json`, `Authorization: Bearer <jwt>`) are
verified, via `kototama.tender`'s own `parse-flat-pairs` +
`post-request-with-headers` bypass-the-destination-guard test pattern, to
genuinely reach a local HTTP server with both values intact — this
module's own test does not repeat that full local-server round trip
(kototama's own `tender_test.clj` already proves the header-application
code path against a real server); it proves this module builds the exact
same header block text.

## Rebuilding

`kotoba wasm emit`'s CLI wraps a mandatory package-admission gate
(`--package-lock`) that this port's build did not go through directly —
same reason `kotoba-lang/kototama` PR #49 compiled its cbor-encode/
json-encode/json-extract-field fixtures directly via
`kotoba.runtime/wasm-binary` against a **local sibling checkout** of
`kotoba-core-contracts` (capability ids `245`/`246`, `data/cbor`/
`data/json`, not yet resolvable through the CLI's own pinned
`kotoba-core-contracts` coordinate at build time). Both Phase B and
Phase F used the identical approach:

```clojure
;; from a kotoba-lang/kotoba checkout with :dev alias sibling overrides
;; (kotoba-core-contracts/kotoba-lang/kotoba-selfhost-contracts/etc. as
;; local/root siblings -- see kotoba-lang/kotoba's own deps.edn :dev alias)
(require '[kotoba.runtime :as runtime])
(require '[clojure.java.io :as io])

(let [forms (runtime/read-file "wasm/cacao_wire_encode.kotoba" :kotoba)
      policy {:kotoba.policy/capabilities #{:data/cbor}}
      wasm (runtime/wasm-binary forms policy)]
  (with-open [os (io/output-stream "wasm/cacao_wire_encode.wasm")]
    (.write os ^bytes (:kotoba.wasm/binary wasm))))
```

Same pattern for `aozora_create_record.kotoba`
(`#{:http/post :data/json}`), and unchanged for the three Phase B modules
(`cacao_self_mint.kotoba` → `#{:identity/keypair :identity/sign
:hash/sha256 :data/cbor}`; `aozora_create_session.kotoba` →
`#{:http/post :data/json}`; `aozora_extract_session_fields.kotoba` →
`#{:data/json}`). **Phase F ran this against a `kotoba-lang/kotoba`
checkout whose sibling `kotoba-core-contracts` was ALREADY at (or past)
the Phase E pin (`8646539fc954a7c88eacb8302c5813d839230f51`, registering
`http-post-headers` under the reused `http/post` capability id 223) —
`http-post-headers` and the dotted-path nesting extension are NOT new
`kotoba-core-contracts` registrations to begin with (nesting needed zero
registry changes; `http-post-headers` needed one, already present at that
pin) — no additional registry change was needed for Phase F itself.**

`test/wasm/*_test.clj`'s `:test` alias in `deps.edn` also needed its
`io.github.kotoba-lang/kototama` git/sha bumped from the Phase B
post-PR#49 pin to the Phase E post-PR#50 pin
(`38613c0808dbb27c782b616299138ec687509f2b`) — the OLD pin's
`kototama.tender` genuinely does not have `http-post-headers-host-fn` or
the dotted-path nesting extension; every Phase F test would fail
`ex-info "rejected by contract"` / `import-id` lookup errors without this
bump.

## Verified locally (Phase F, this PR)

- `kotoba.runtime/wasm-binary` compiles both new Phase F modules cleanly
  (0 problems): `cacao_wire_encode.kotoba` → 975 bytes / 1 host import;
  `aozora_create_record.kotoba` → 1093 bytes / 2 host imports.
- `test/wasm/*_test.clj` load and run all 5 compiled `.wasm` modules
  (3 unchanged from Phase B + 2 new) through a real
  `kototama.tender/instantiate`/`call-main` (Chicory `Instance`, not a
  mock): **39 `deftest`s across 5 files** (29 pre-existing from Phase B +
  10 new), including:
  - the SAME Phase B crypto round-trip proofs, unchanged
    (`cacao_self_mint_test.clj`'s real-SHA-256/real-Ed25519-verify
    assertions still pass byte-for-byte as before);
  - `cacao_wire_encode_test.clj`'s byte-exact assertion: this module's
    real compiled-guest CBOR output for a fixed field set equals, byte
    for byte, the SAME 264-byte golden array
    `kotoba-lang/kototama`'s own `tender_test.clj`
    (`cbor-encode-nested-reproduces-cacao-wire-envelope-byte-exact`,
    ADR-2607231234) computed from the real JVM reference implementation;
  - `aozora_create_record_test.clj`'s byte-exact assertion: this module's
    real compiled-guest JSON body for a fixed field set equals, byte for
    byte, the SAME 297-byte golden JSON string
    `kotoba-lang/kototama`'s own `tender_test.clj`
    (`json-encode-nested-reproduces-createrecord-body-byte-exact`,
    ADR-2607231234) computed from the real JVM reference implementation,
    plus a genuine SSRF-denial proof (`http-post-headers` returns `-1`
    against the loopback target) and a real header-delivery proof
    (`Content-Type`/`Authorization: Bearer <jwt>` both parse and would
    both genuinely reach a real HTTP server, mirroring kototama's own
    `post-request-with-headers-sends-real-headers-to-a-local-server`
    proof pattern).
- `kbb -M:test` — **39 tests / 72 assertions, 0 failures, 0 errors**
  (29 tests / 56 assertions pre-existing from Phase B + this phase's 10
  tests / 16 assertions).
- `kbb -M scripts/audit.clj` — `audit: ok`.
- `kbb -M:lint` — 7 pre-existing errors / 39 pre-existing warnings, ALL
  in `.cljc` files unrelated to this port (unresolved `clojure.*`/JDK
  interop symbols under clj-kondo's cljc-shared-config analysis — a
  pre-existing baseline, not introduced by this port; neither Phase F's
  new `.kotoba`/`.clj` files nor Phase B's appear anywhere in the lint
  output).
- No internet access happened anywhere in this port's test suite, in
  either phase — every `http-post`/`http-post-headers` call target is
  loopback on purpose, refused by kototama's unconditional SSRF denylist
  before any connection is attempted. **This is intentional and was a hard
  constraint for both sessions**: an actual live-network createRecord
  publish (or createSession) is a separate, deliberate decision for the
  repo owner to make in yet another later session, not something either
  porting session performs unprompted.

## Follow-ups (not closure blockers for Phase F)

- Fold `cacao_self_mint.kotoba` and `cacao_wire_encode.kotoba` into ONE
  module that both signs for real AND emits the byte-faithful nested
  envelope with its own fresh signature (see "Why `cacao_self_mint.kotoba`
  itself was not rewritten in place" above for the tradeoff — that module
  would no longer be byte-exact-testable against a fixed golden array, but
  could be structurally end-to-end tested by decoding the CBOR back and
  verifying the embedded signature via `ed25519.core/verify`).
- Decide whether did:key / graph-cid derivation (finding 6) is worth
  expressing in `.kotoba` or should stay a permanent host-side
  responsibility — not urgent either way since it is a
  one-time-per-outlet-identity operation, not a per-post hot path.
- Wire `aozora_create_session.kotoba` + `aozora_extract_session_fields.kotoba`
  + `cacao_wire_encode.kotoba` + `aozora_create_record.kotoba` into one
  end-to-end orchestrated publish flow (still against a loopback/mock
  target for any further no-internet session) — currently four
  independently-testable modules, not yet wired into a single call chain.
- Live fleet placement on Murakumo, and any actual internet-reaching
  createSession/createRecord call against the real `pds.aozora.app` — both
  explicitly deferred to a future session with the repo owner's
  case-by-case sign-off, per this session's own task constraints.

## Phase G — connected orchestrator (com-junkawasaki/root, ADR-2607231404)

Phase F's own follow-up list above said the four network-touching modules
were "not yet wired into a single call chain". Phase G is that wiring:
`src/kawaraban/wasm_orchestrator.clj` connects RSS/Atom fetch
(`src/kawaraban/methods/live_fetch.cljc`, **unchanged**) → G1/G3/G4/G8
charter gate (`src/kawaraban/methods/ingest.cljc`, **unchanged**) →
per-outlet identity → CACAO signing → nested wire encoding → createSession
→ createRecord, all via the wasm modules above, and actually **runs** it
(`kbb -M:test` / the new `:wasm-orchestrator` alias), not just links
each module in isolation. Read that namespace's own docstring for the
full detail; this section is a summary + the three honest findings it
surfaced.

### New module: `identity_sign.kotoba`

`cacao_self_mint.kotoba`'s `main` unconditionally calls `gen-keypair` —
every invocation mints a **fresh** random Ed25519 seed. That is exactly
right for Phase B's own proof ("the keygen→sign chain runs as real wasm")
but useless for an orchestrator that needs to sign with the **same**
persisted per-outlet identity across many runs (`kawaraban.mirror-actor`'s
own `load-or-create-mirror-identity!` pattern). `kototama.tender`'s `sign`
host-import (`(sign seed-ptr msg-ptr msg-len sig-ptr sig-cap)`) reads
whatever 32 raw bytes are at `seed-ptr` at call time — it does not care
whether those bytes came from this call's own `gen-keypair` or were
host-written before `main` ran. `identity_sign.kotoba` exploits exactly
that: the host writes an already-persisted seed into guest memory and
this module calls `sign` directly — it has **no `gen-keypair` import at
all** (T3 capability confinement: `#{:identity/sign}` only). Verified via
`test/wasm/identity_sign_test.clj`: signs with a host-supplied seed,
verifies against `ed25519.core/verify`, is deterministic (same seed + same
message twice → identical signature, RFC 8032), and different
seeds/messages yield different signatures.

`wasm_orchestrator.clj`'s own per-outlet identity persists to
`.kawaraban/mirrors-wasm/<outlet-id>.edn` — **deliberately a different
directory from** `kawaraban.mirror-actor`'s `.kawaraban/mirrors/`: that
path's files are `{:private-b64 :public-b64}` PKCS8/X.509 DER (JVM
`KeyFactory`-loadable); this orchestrator's are `{:seed-b64 :pub-b64
:did}` raw Ed25519 seed bytes (wasm `sign`-loadable). The two encodings
are not interchangeable — reading one as the other would silently mint a
different `did:key` for the same outlet. Both live under the repo's single
`/.kawaraban/` `.gitignore` entry.

### Finding 7 (new): dotted-path nesting cannot represent a key that itself contains a literal '.'

Attempting a **byte-faithful** port of kawaraban's own
`:news.article/*` per-article mirror-record shape into a new
`createRecord`-shaped wasm module hit a genuinely new language-limitation
finding: `kototama.tender`'s dotted-path nesting convention
(`build-nested-tree`, `(str/split k #"\.")`) splits on **every** literal
`.` in a key. Kawaraban's own field names — e.g. `":news.article/id"` —
contain an embedded `.` as **part of the key itself** (mirroring a
namespaced-keyword's own dot), colliding with the exact character the
nesting convention uses as its only structural separator. There is no
escape mechanism for a literal dot inside a dotted-path segment today, so
`{"record": {":news.article/id": ...}}` cannot be produced this way
without either widening `kototama` (out of scope this session) or
renaming keys (a real wire-incompatibility, not attempted silently).

Given that, `wasm_orchestrator.clj` **reuses `aozora_create_record.wasm`
as-is** with an explicit, documented field-mapping
(`create-record-via-wasm!`'s own docstring): `repo`/`collection`/`rkey`/
`actor` are semantically correct (the outlet's own DID, kawaraban's real
`"com.etzhayyim.apps.kawaraban"` collection, the article's own
deterministic id) — only `record.analysis`/`record.cites.0.url`/
`record.cites.1.url` are a deliberate repurpose of the
`net.itonami.media.digest`-shaped ABI (headline → `analysis`, article url
+ outlet homepage → the two citation slots), **not** kawaraban's real
field names. A dedicated `:news.article/*`-shaped module (once `kototama`
grows a dot-escape mechanism, or using dot-free renamed keys) is a
documented follow-up, not silently done here.

### Finding 8 (new): the destination URL is a `.kotoba` compile-time literal, not a runtime parameter

`wasm/aozora_create_session.kotoba` / `wasm/aozora_create_record.kotoba`
each hardcode `http://127.0.0.1/xrpc/...` in their own source (finding 1:
`.kotoba` has no runtime string construction). There is no way for the
orchestrator to point an already-compiled `.wasm` binary at
`https://pds.aozora.app` at runtime — "pointing at production" is a
**recompile** (swap the literal URL, rerun the "Rebuilding" recipe above),
producing a *different* `.wasm` file. `wasm_orchestrator.clj`'s own
injectability is therefore at the file-path layer (`:wasm-dir` in every
opts map): swap in a directory containing production-URL-compiled `.wasm`
binaries and every call site picks them up unchanged, no code edit. **This
session deliberately does not compile or ship any such production-URL
variant** — an already-real, internet-reaching binary sitting in the repo,
even if never invoked, is a risk not worth taking against this task's hard
"no real network calls this session" constraint. Making a production run
possible is a future, explicit compilation step for the repo owner.

### Bug found and fixed: `cacao_wire_encode.kotoba`'s `resources` field width

Wiring `mint-cacao!` against a **real** identity's real `canonical-graph`
output surfaced a genuine memory-corruption bug in the existing
(Phase F) `cacao_wire_encode.kotoba`: its `resources.0`/`resources.1`
fields were declared 64 bytes wide, sized only against ADR-2607231234's
own short golden-fixture example (`"kotoba://graph/graph-42"`, 23 bytes) —
never checked against a **real** `canonical-graph` value's true length. A
real graph URI (`"kotoba://graph/" + <CIDv1 base32-lower CID>`) is **74
bytes**, exceeding the 64-byte cap. Writing 74 bytes into a declared
64-byte host-input slot is not truncation — it silently overflows into the
**next field's own length-header bytes**, corrupting it into a garbage i32
that then drove `copy-bytes!`'s recursion into a JVM `StackOverflowError`
(observed empirically, not hypothetical). Fixed by widening
`resources.0`/`resources.1` to 100 bytes each (shifting `exp`/`sig`
accordingly — see that file's own header comment for the exact new offset
table) and updating `test/wasm/cacao_wire_encode_test.clj`'s
`field-offsets` to match. The existing 264-byte byte-exact golden-fixture
assertion is **unchanged** by this (buffer width does not affect the bytes
written for a given real field value — only the offsets needed updating).

### Bounded by design + high-water-mark

`run-all!`/`run-outlet!` are bounded by `:max-outlets` (default 2) and
`:max-articles-per-outlet` (default 1) — conservative on purpose: the
2026-07-10 first live-ingest activation at "3 articles/outlet" already
pushed CPU past a safe budget once (see `run_live_ingest.clj`'s own
addendum-2 note), and this orchestrator is new, unproven code exercising a
**separate** cost profile (wasm/Chicory instantiation per article, not
just an HTTP POST) on top of that. The high-water-mark persists to
`data/ingest/last-seen-wasm.edn` — **deliberately a different file** from
`run_live_ingest.clj`'s own `data/ingest/last-seen.edn`, since the two
orchestrators mint different per-outlet identities and must not silently
share or clobber each other's progress marks.

### Deployment (launchd)

`scripts/launchd/com.etzhayyim.kawaraban.wasm-orchestrator.plist` runs
`kbb -M:wasm-orchestrator` (→ `kawaraban.wasm-orchestrator/-main`)
every 6 hours (`StartInterval` 21600), matching the format
`orgs/gftdcojp/cloud-itonami/scripts/launchd/`'s 5 existing plists already
establish. Install (same `sed` HOME/REPO-path-substitution pattern those
files use):

```sh
sed "s|/Users/junkawasaki|$HOME|g; s|/Users/junkawasaki/github/com-junkawasaki/orgs/etzhayyim/com-etzhayyim-kawaraban|/path/to/orgs/etzhayyim/com-etzhayyim-kawaraban|g" \
  scripts/launchd/com.etzhayyim.kawaraban.wasm-orchestrator.plist \
  > ~/Library/LaunchAgents/com.etzhayyim.kawaraban.wasm-orchestrator.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.etzhayyim.kawaraban.wasm-orchestrator.plist
```

Logs: `~/.kawaraban/logs/wasm-orchestrator.log`. **G8 stays closed by
default** (`KAWARABAN_ALLOW_LIVE_INGEST` unset) — until an operator
explicitly sets it (with Council attestation, per G8), every tick is a
harmless no-op (`fetch-outlet!` refuses before any key generation, signing,
or network attempt happens). `KAWARABAN_WASM_MAX_OUTLETS` /
`KAWARABAN_WASM_MAX_ARTICLES_PER_OUTLET` override the conservative
defaults above if an operator ever needs to. **Actually running
`launchctl bootstrap` is NOT done by this phase** — the plist is prepared,
not installed.

### Verified locally (Phase G, this PR)

- `wasm/identity_sign.kotoba` compiles cleanly (150 bytes, 0 host imports
  beyond `sign`, heap-base 2048 — this module references zero string
  literals).
- `wasm/cacao_wire_encode.kotoba` recompiles cleanly after the width fix
  (975 bytes, heap-base 2048 — unchanged, since the widening only shifts
  numeric offsets, not the literal-string region).
- `test/kawaraban/wasm_orchestrator_test.clj` (22 tests) +
  `test/wasm/identity_sign_test.clj` (6 tests), plus every pre-existing
  test in this repo (cells/methods/cacao/mirror-actor/publish/publisher/
  run-live-ingest/wasm.*): **136 tests / 295 assertions, 0 failures, 0
  errors** (`kbb -M:test -r ".*"` from this repo's root — the default
  `kbb -M:test` regex, `.*-test$`, only matches namespace names ending
  in `-test`, which misses this repo's `test-*`-prefixed cells/methods
  namespaces; `-r ".*"` runs the complete suite in one pass).
- `kbb -M scripts/audit.clj` — `audit: ok`.
- `kbb -M:lint` — 7 errors / 39 warnings, the exact same pre-existing
  baseline Phase F already documented — zero new lint findings from any
  Phase G file.
- No internet access happened anywhere in this phase's test suite — every
  `http-post`/`http-post-headers` call target is loopback on purpose,
  refused by `kototama`'s unconditional SSRF denylist before any
  connection is attempted (`publish-article!`'s own docstring: with the
  loopback-target modules this repo ships, `createSession` always refuses,
  so `createRecord` is architecturally never reached via the real call
  graph in this configuration — `create-record-via-wasm!`'s own wiring is
  independently proven with a synthetic jwt instead, matching how
  `aozora_create_record_test.clj` already tested that module in isolation).

### Follow-ups (not closure blockers for Phase G)

- A dedicated `:news.article/*`-shaped `createRecord` wasm module (finding
  7's follow-up: needs either a `kototama` dot-escape mechanism or
  dot-free renamed keys).
- Fold `cacao_self_mint.kotoba` + `identity_sign.kotoba` into a decision
  about whether first-run minting should also go through some other,
  more auditable capability boundary — not urgent (minting is a
  one-time-per-outlet operation, not a per-post hot path).
- Compile a production-URL variant of `aozora_create_session.kotoba` /
  `aozora_create_record.kotoba` (finding 8) — an explicit, case-by-case
  decision for the repo owner in a future session, never done
  automatically. **Done in Phase H, see below.**
- Actually `launchctl bootstrap` the plist above, and (separately, with
  the repo owner's explicit sign-off) flip `KAWARABAN_ALLOW_LIVE_INGEST`
  on against a real, production-URL-compiled deployment — both untouched
  by this phase. **`launchctl bootstrap` and the live-ingest gate are
  STILL untouched by Phase H below — this repo's checked-in state remains
  "safe to install, refuses to post without further explicit operator
  action."**

## Phase H — production URL recompile + real accessJwt (com-junkawasaki/root, 2026-07-23)

Owner-approved, explicit, separate-from-installation step (see "Follow-ups"
above and finding 8/finding 3's own repeated framing: "a FUTURE, explicit
compilation step for the repo owner, not something this phase silently
prepares un-flagged"):

- **`wasm/aozora_create_session.kotoba` and `wasm/aozora_create_record.kotoba`
  recompiled** with `https://pds.aozora.app/xrpc/com.atproto.server.
  createSession` / `.../xrpc/com.atproto.repo.createRecord` baked in,
  replacing the `http://127.0.0.1/...` literal every prior phase shipped.
  The `.wasm` files checked into this directory ARE this production-URL
  variant now (no separate `:wasm-dir` needed to reach it) — anyone who
  wants the OLD loopback-only build back for local testing without
  touching real infrastructure should recompile from git history at the
  Phase F tip.
- **Safety no longer comes from kototama's SSRF denylist** (a public HTTPS
  host is not loopback/private/link-local, so that guard does not fire
  against these URLs anymore). It comes ENTIRELY from an explicit
  `:allowed-url-prefixes` allowlist, unset (`[]`, deny-all) by default:
  `wasm_orchestrator.clj`'s `session-caps`/`record-caps` now read a new
  `KAWARABAN_WASM_PDS_ALLOWLIST` env var (comma-separated prefixes, see
  `pds-allowlist`) instead of leaving `:allowed-url-prefixes` unset
  (which would have meant kototama's OWN documented `nil` = "unrestricted
  default" semantics — a real safety regression this recompile
  deliberately avoids). `test/wasm/aozora_create_session_test.clj` and
  `test/wasm/aozora_create_record_test.clj` were updated to pass
  `:allowed-url-prefixes []` explicitly (previously relying on the now-
  moot SSRF denylist for the loopback literal) — same fail-closed
  guarantee, different (correct) mechanism.
- **Fixed a real bug this recompile would otherwise have exposed in
  production**: `publish-article!` previously hardcoded
  `jwt "unreachable-in-loopback-mode"` for the createRecord call, dead
  code while createSession always refused against loopback. The moment
  createSession can genuinely succeed (real URL + an operator-set
  allowlist), that placeholder would have made createRecord authenticate
  with a fabricated string instead of the real session token. Closed via
  a new `extract-access-jwt` (host-side `clojure.data.json`, NOT a new
  wasm round-trip — the response bytes are already a plain JVM string by
  the time `create-session-via-wasm!` returns them, so re-entering wasm to
  re-extract a field from data the host already holds would add a second
  untested wasm module under time pressure for no confinement benefit).
  `create-session-via-wasm!` now also returns `:response-body` (read from
  its `resp-ptr@6296` output buffer, length = `written`, the real
  bytes-written count `http-post` already returns — not a guess).
- **Going live still requires TWO separate, explicit operator actions**,
  neither performed by this recompile: (1) `KAWARABAN_ALLOW_LIVE_INGEST=1`
  (G8, Council-attested per this repo's own charter) so `live-fetch/fetch-
  outlet!` stops refusing every fetch, and (2) `KAWARABAN_WASM_PDS_
  ALLOWLIST=https://pds.aozora.app` (this phase's new gate) so the wasm
  network calls stop refusing. Neither env var is set by this PR, the
  launchd plist, or any file in this repo — both are operator-set on the
  actual deployment host only.
- Verified: `kbb -M:test -r ".*"` — 136 tests / 295 assertions, 0
  failures/errors (same count as Phase G — this recompile changed WHAT the
  refused-by-default tests refuse, not whether they refuse). `clojure
  -M:lint` — 7 errors / 39 warnings, identical pre-existing baseline, zero
  new findings. No real network calls or LLM API calls were made in this
  session (every test explicitly sets an allowlist that excludes
  `pds.aozora.app`).
