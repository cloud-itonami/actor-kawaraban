(ns kawaraban.cells.article-mirror.state-machine
  "Phase state machine for kawaraban 瓦版 article_mirror — G1/G4/G9 gate.
  1:1 port of cells/article_mirror/state_machine.py (ADR-2606061900). Mirrors a REAL article as an
  observation ONLY if: G11 kind=mirror with outlet+url; G4 no full_text + excerpt ≤280; G1 no
  verdict/truth_rating; G9 no speak_as. Illegal article REFUSED, never coerced."
  (:require [kotoba.lang.text :as str]))

(def state-defaults
  {"phase" "init" "article_id" "" "section" "" "outlet" "" "url" "" "headline" "" "excerpt" ""
   "verdict" false "truth_rating" 0 "full_text" false "speak_as" false "refusal" "" "payload" nil})

(defn- cell-state [state] (merge state-defaults (get state "cell_state" {})))

(defn mirror [state]
  (let [cs (cell-state state)
        cs (reduce (fn [m f] (assoc m f (get state f (get m f))))
                   cs ["article_id" "section" "outlet" "url" "headline" "excerpt"])
        cs (assoc cs
                  "verdict" (boolean (get state "verdict" (get cs "verdict")))
                  "truth_rating" (long (or (get state "truth_rating" (get cs "truth_rating")) 0))
                  "full_text" (boolean (get state "full_text" (get cs "full_text")))
                  "speak_as" (boolean (get state "speak_as" (get cs "speak_as"))))
        refuse (fn [msg] {"cell_state" (assoc cs "refusal" msg "phase" "refused")})]
    (cond
      (or (get cs "verdict") (not (zero? (get cs "truth_rating"))))
      (refuse "G1: a mirrored article carries no verdict/truth-rating (ake/danjo boundary)")

      (get cs "full_text")
      (refuse "G4: full body is unrepresentable; headline + link + excerpt only")

      (get cs "speak_as")
      (refuse "G9: never post AS the outlet (mirror, not impersonation)")

      (or (not (seq (get cs "outlet"))) (not (seq (get cs "url"))))
      (refuse "G4/G11: a :mirror article needs outlet + canonical url (link-out)")

      (> (count (get cs "excerpt")) 280)
      (refuse (str "G4: excerpt " (count (get cs "excerpt")) " chars > 280 (fair-use bound)"))

      :else
      {"cell_state" (assoc cs "refusal" "" "phase" "mirrored"
                           "payload" {"articleId" (get cs "article_id") "kind" "mirror"
                                      "section" (get cs "section") "outlet" (get cs "outlet")
                                      "url" (get cs "url") "headline" (get cs "headline")
                                      "excerpt" (get cs "excerpt") "verdict" false
                                      "fullText" false "speakAs" false})})))

(defn solve [_input-state]
  (throw (ex-info "kawaraban R0 scaffold: activate article_mirror via Council ADR (post-2606061900 ratification)" {:scaffold true})))
