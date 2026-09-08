(ns kawaraban.cells.issue-compose.state-machine
  "Phase state machine for kawaraban 瓦版 issue_compose — G2/G7/G8/G10 gate.
  1:1 port of cells/issue_compose/state_machine.py (ADR-2606061900). Composes an EDITION. COMPOSED
  only if: G2 rank signals ⊆ public-good allowlist; G10 final is false (非終末論); G7 server_held_key
  false. PUBLISHED stays false unless member-signed AND operator-gated (G7/G8)."
  (:require [kotoba.lang.text :as str]))

(def allowed-rank #{"recency" "section-fit" "source-diversity" "actor-relevance" "geo-proximity"})

(def state-defaults
  {"phase" "init" "issue_id" ""
   "rank_signals" ["recency" "source-diversity" "actor-relevance"] "lead_ids" []
   "final" false "member_signed" false "operator_gated" false "server_held_key" false
   "published" false "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))
(defn- norm [v] (str/replace (str (or v "")) #"^:+" ""))

(defn compose [state]
  (let [cs (cell-state state)
        cs (assoc cs
                  "issue_id" (get state "issue_id" (get cs "issue_id"))
                  "rank_signals" (mapv norm (get state "rank_signals" (get cs "rank_signals")))
                  "lead_ids" (vec (get state "lead_ids" (get cs "lead_ids")))
                  "final" (boolean (get state "final" (get cs "final")))
                  "member_signed" (boolean (get state "member_signed" (get cs "member_signed")))
                  "operator_gated" (boolean (get state "operator_gated" (get cs "operator_gated")))
                  "server_held_key" (boolean (get state "server_held_key" (get cs "server_held_key"))))
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})
        bad-rank (first (remove allowed-rank (get cs "rank_signals")))]
    (cond
      bad-rank
      (refuse (str "G2: rank signal " (pr-str bad-rank) " not public-good; paid/engagement ranking unrepresentable"))

      (get cs "final")
      (refuse "G10: an edition is never final (非終末論)")

      (get cs "server_held_key")
      (refuse "G7: server never signs an edition (no-server-key)")

      :else
      (let [published (boolean (and (get cs "member_signed") (get cs "operator_gated")))]
        {"cell_state" (assoc cs "refusal" "" "phase" "composed" "published" published
                             "payload" {"issueId" (get cs "issue_id") "rankSignals" (get cs "rank_signals")
                                        "leadCount" (count (get cs "lead_ids")) "final" false
                                        "serverHeldKey" false "published" published})}))))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate issue_compose via Council ADR (post-2606061900 ratification)" {:scaffold true})))
