(ns kawaraban.cells.outlet-ingest.state-machine
  "Phase state machine for kawaraban 瓦版 outlet_ingest — G4/G5 membrane.
  1:1 port of cells/outlet_ingest/state_machine.py (ADR-2606061900). An outlet is INGESTED only if
  its access is a PUBLIC facing page (:open / :registration-wall); :paywall / :proprietary-terminal
  are REFUSED (G4). Refusal, never coercion. Conventions: dataclass → plain map (Python string keys)."
  (:require [kotoba.lang.text :as str]))

(def open-access #{"open" "registration-wall"})
(def outlet-kinds #{"public-broadcaster" "wire-agency" "newspaper" "magazine" "digital-native" "ngo-press"})

(def state-defaults
  {"phase" "init" "outlet_id" "" "name" "" "kind" "wire-agency" "access" "open" "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))
(defn- norm [v] (str/replace (str (or v "")) #"^:+" ""))

(defn ingest [state]
  (let [cs (cell-state state)
        cs (assoc cs
                  "outlet_id" (get state "outlet_id" (get cs "outlet_id"))
                  "name" (get state "name" (get cs "name"))
                  "kind" (norm (get state "kind" (get cs "kind")))
                  "access" (norm (get state "access" (get cs "access"))))
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})]
    (cond
      (or (not (seq (get cs "outlet_id"))) (not (seq (get cs "name"))))
      (refuse "outlet needs id + name (G5 provenance)")

      (not (contains? open-access (get cs "access")))
      (refuse (str "G4: access " (pr-str (get cs "access")) " is not a public facing page; paywall/terminal not mirrored"))

      (not (contains? outlet-kinds (get cs "kind")))
      (refuse (str "unknown outlet kind " (pr-str (get cs "kind"))))

      :else
      {"cell_state" (assoc cs "refusal" "" "phase" "ingested"
                           "payload" {"outletId" (get cs "outlet_id") "name" (get cs "name")
                                      "kind" (get cs "kind") "access" (get cs "access")
                                      "sourcing" "representative"})})))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate outlet_ingest via Council ADR (post-2606061900 ratification)" {:scaffold true})))
