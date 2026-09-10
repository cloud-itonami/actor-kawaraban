(ns kawaraban.mirror-actor
  "Per-organization mirror actor identities (owner instruction 2026-07-10, ADR-2607110200
  addendum). Instead of every outlet's mirrored article being posted under ONE shared
  kawaraban did:key, each outlet gets its OWN self-sovereign identity (own key, own aozora
  profile, own post history) — a distinct 'news' actor per organization, separate from any
  'media'/writer actor that later analyzes/interprets/translates that primary-source content
  (that layer is a deliberately separate, not-yet-built follow-up).

  G9 (mirror-not-impersonation) is the hard constraint this whole namespace exists to keep:
  a per-outlet identity is EXPLICITLY, VISIBLY a mirror of that outlet — never an account
  that could be mistaken for the outlet's own official presence. `mirror-display-name` /
  `mirror-description` always say 'kawaraban mirror' + 'automated, unofficial' up front;
  `ensure-profile!` writes exactly that text to the identity's aozora.app profile page
  before it ever publishes an article, so anyone landing on the profile sees the disclosure
  first. Each identity is otherwise a completely ordinary kawaraban.aozora identity (same
  did:key self-mint, same com.etzhayyim.apps.kawaraban collection, same G1/G3/G4 gates on
  every article via the shared ingest/normalize-* path) — only WHICH key signs the post
  changes, nothing about what may be posted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kawaraban.cacao :as cacao]
            [kawaraban.aozora :as aozora]))

(def identity-dir
  "Per-outlet identities live under their own subdir, separate from kawaraban's own root
  identity (`.kawaraban/identity.edn`, unaffected by this namespace)."
  ".kawaraban/mirrors")

(defn identity-path [outlet-id]
  (str identity-dir "/" outlet-id ".edn"))

(defn load-or-create-mirror-identity!
  "Load or mint the per-outlet identity for `outlet-id` (an :id from
  data/outlets/allowlist.edn, e.g. \"outlet.bbc-world\")."
  [outlet-id]
  (cacao/load-or-create-identity! (identity-path outlet-id)))

(defn mirror-display-name [outlet]
  (str "kawaraban mirror — " (:name outlet)))

(defn mirror-description [outlet]
  (str "🪞 Automated, UNOFFICIAL mirror of " (:name outlet)
       " public headlines (headline + link-out + bounded excerpt only, never full text) — "
       "not affiliated with or operated by " (:name outlet) ". "
       "Run by kawaraban (瓦版), a non-adjudicating news medium: "
       "github.com/etzhayyim/com-etzhayyim-kawaraban. "
       "Read the original at the linked source."))

(defn ensure-profile!
  "Idempotently (re-)write this outlet's mirror-actor profile so the disclosure text is
  always current, even if `mirror-description` changes later. opts = the same map
  `kawaraban.aozora/aozora-publisher` takes (:pds :identity :json-write :json-read
  :http-fn), :identity must already be this outlet's mirror identity."
  [opts outlet]
  (aozora/set-profile! opts (mirror-display-name outlet) (mirror-description outlet)))

(defn mirror-publisher
  "The full per-outlet wiring: load/create this outlet's own identity, ensure its profile
  discloses the mirror relationship, and return an aozora-publisher scoped to that identity.
  `base-opts` supplies :pds/:json-write/:json-read/:http-fn (no :identity — this fn provides
  it)."
  [base-opts outlet]
  (let [identity (load-or-create-mirror-identity! (:id outlet))
        opts (assoc base-opts :identity identity)]
    (ensure-profile! opts outlet)
    {:identity identity :publisher (aozora/aozora-publisher opts)}))

(defn all-mirror-identities
  "Convenience: did + profile path for every outlet already minted under identity-dir (does
  NOT mint new ones) — for auditing 'which mirror actors exist so far'."
  []
  (let [dir (io/file identity-dir)]
    (if (.exists dir)
      (for [f (.listFiles dir) :when (.endsWith (.getName f) ".edn")]
        (let [outlet-id (subs (.getName f) 0 (- (count (.getName f)) 4))
              identity (cacao/load-identity (edn/read-string (slurp f)))]
          {:outlet-id outlet-id :did (:did identity)}))
      [])))
