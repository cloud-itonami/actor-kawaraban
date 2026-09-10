(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[kotoba.lang.text :as str])

(def root (.getCanonicalFile (io/file ".")))
(def files (->> (file-seq root)
                (remove #(.isDirectory %))
                (remove #(str/includes? (.getPath %) "/.git/"))))
(def rel #(.toString (.relativize (.toPath root) (.toPath %))))

(doseq [f (filter #(str/ends-with? (.getName %) ".edn") files)]
  (try (edn/read-string (slurp f))
       (catch Exception e
         (throw (ex-info "invalid canonical EDN" {:file (rel f)} e)))))

(let [canonical (set (map #(str/replace (.getName %) #"\.edn$" "")
                          (filter #(and (= "data/lex" (some-> % .getParentFile rel))
                                        (str/ends-with? (.getName %) ".edn")) files)))
      wire (set (map #(str/replace (.getName %) #"\.json$" "")
                     (filter #(and (= "wire/lex" (some-> % .getParentFile rel))
                                   (str/ends-with? (.getName %) ".json")) files)))
      forbidden (filter #(re-find #"(?:^|/)(?:go\.mod|go\.sum|run_tests\.sh|deploy\.sh|requirements\.txt|package\.json|nbb\.edn|[^/]+\.(?:go|py))$" (rel %)) files)
      misplaced (filter #(and (re-find #"\.(?:json|jsonld|jsonl|wit)$" (rel %))
                              (not (str/starts-with? (rel %) "wire/"))
                              (not= (rel %) ".well-known/did.json")) files)]
  (when-not (= canonical wire)
    (throw (ex-info "canonical and wire lexicon names differ" {:canonical canonical :wire wire})))
  (when (seq forbidden)
    (throw (ex-info "deprecated implementation artifacts remain" {:files (mapv rel forbidden)})))
  (when (seq misplaced)
    (throw (ex-info "serialized artifacts must live under wire/" {:files (mapv rel misplaced)}))))

(println "audit: ok")
