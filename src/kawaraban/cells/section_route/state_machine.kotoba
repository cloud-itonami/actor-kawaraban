(ns kawaraban.cells.section-route.state-machine
  "Phase state machine for kawaraban 瓦版 section_route — G2/G11 gate.
  1:1 port of cells/section_route/state_machine.py (ADR-2606061900). Routes an article into its 面
  + attaches :news.mention edges. ROUTED only if the 面 is a real section, G2 rank signals ⊆
  public-good allowlist (paid/engagement unrepresentable), G11 mention roles are observational."
  (:require [kotoba.lang.text :as str]))

(def men #{"front" "politics" "economy" "international" "society" "culture" "science" "sports" "local" "opinion"})
(def allowed-rank #{"recency" "section-fit" "source-diversity" "actor-relevance" "geo-proximity"})
(def roles #{"subject" "source" "mentioned" "affected" "responding"})

(def state-defaults
  {"phase" "init" "article_id" "" "men" "front"
   "rank_signals" ["recency" "section-fit"] "mentions" [] "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))
(defn- norm [v] (str/replace (str (or v "")) #"^:+" ""))

(defn route [state]
  (let [cs (cell-state state)
        cs (assoc cs
                  "article_id" (get state "article_id" (get cs "article_id"))
                  "men" (norm (get state "men" (get cs "men")))
                  "rank_signals" (mapv norm (get state "rank_signals" (get cs "rank_signals")))
                  "mentions" (vec (get state "mentions" (get cs "mentions"))))
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})
        bad-rank (first (remove allowed-rank (get cs "rank_signals")))
        bad-role (first (remove roles (map #(norm (get % "role")) (get cs "mentions"))))]
    (cond
      (not (contains? men (get cs "men")))
      (refuse (str "unknown 面 " (pr-str (get cs "men")) "; not a real news-media section"))

      bad-rank
      (refuse (str "G2: rank signal " (pr-str bad-rank) " not public-good; paid/engagement ranking unrepresentable"))

      bad-role
      (refuse (str "G11: mention role " (pr-str bad-role) " is not observational (subject/source/mentioned/affected/responding)"))

      :else
      {"cell_state" (assoc cs "refusal" "" "phase" "routed"
                           "payload" {"articleId" (get cs "article_id") "men" (get cs "men")
                                      "rankSignals" (get cs "rank_signals")
                                      "mentionCount" (count (get cs "mentions"))})})))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate section_route via Council ADR (post-2606061900 ratification)" {:scaffold true})))
