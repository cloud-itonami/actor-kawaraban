(ns kawaraban.cells.actor-project.state-machine
  "Phase state machine for kawaraban 瓦版 actor_project — THE MEDIUM (G7/G9/G11 gate).
  1:1 port of cells/actor_project/state_machine.py (ADR-2606061900). Projects a first-party actor's
  Datom as-of event into the matching 面 as :article/kind 'actor-event'. PROJECTED only if: G11 kind
  with source_actor+source_tid; G7 member-signed + server_held_key false; G9 no speak_as."
  (:require [kotoba.lang.text :as str]))

(def state-defaults
  {"phase" "init" "article_id" "" "source_actor" "" "source_tid" "" "men" "front" "headline" ""
   "member_signed" false "server_held_key" false "speak_as" false "mentions" [] "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))

(defn project [state]
  (let [cs (cell-state state)
        cs (reduce (fn [m f] (assoc m f (get state f (get m f))))
                   cs ["article_id" "source_actor" "source_tid" "men" "headline"])
        cs (assoc cs
                  "member_signed" (boolean (get state "member_signed" (get cs "member_signed")))
                  "server_held_key" (boolean (get state "server_held_key" (get cs "server_held_key")))
                  "speak_as" (boolean (get state "speak_as" (get cs "speak_as")))
                  "mentions" (vec (get state "mentions" (get cs "mentions"))))
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})]
    (cond
      (or (not (seq (get cs "source_actor"))) (not (seq (get cs "source_tid"))))
      (refuse "G11: an :actor-event needs source_actor + source_tid (provenance to the Datom)")

      (get cs "server_held_key")
      (refuse "G7: server never signs a projection; member signature required (no-server-key)")

      (not (get cs "member_signed"))
      (refuse "G7: projection not member-signed; refused (no-server-key)")

      (get cs "speak_as")
      (refuse "G9: kawaraban observes the actor's event, never speaks AS the actor")

      :else
      {"cell_state" (assoc cs "refusal" "" "phase" "projected"
                           "payload" {"articleId" (get cs "article_id") "kind" "actor-event"
                                      "men" (get cs "men") "sourceActor" (get cs "source_actor")
                                      "sourceTid" (get cs "source_tid") "headline" (get cs "headline")
                                      "serverHeldKey" false "speakAs" false
                                      "wires" (count (get cs "mentions"))})})))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate actor_project via Council ADR (post-2606061900 ratification)" {:scaffold true})))
