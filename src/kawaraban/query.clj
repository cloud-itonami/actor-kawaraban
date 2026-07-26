(ns kawaraban.query
  "query.clj — kawaraban 瓦版 の datom を DataScript にロードして datalog で
  問い合わせる CLI（com-junkawasaki/root ADR-2607252600）。

      clojure -M:query count
      clojure -M:query coverage
      clojure -M:query q '[:find ?country (count ?a)
                           :where [?a :news.article/outlet ?oid]
                                  [?o :news.outlet/id ?oid]
                                  [?o :news.outlet/country ?country]]'

  ## 既定で seed を混ぜない（G5）

  `data/seed.edn` は `:news.article/sourcing :representative` — 実在の記事では
  なく、グラフの形を説明するための例示である。実際の収集結果を数えたいときに
  これが混ざると「7 件の記事がある」が実測なのか例示なのか区別できなくなる。
  したがって **既定では実アーカイブ（`data/articles/`、:verified）だけ**を
  ロードし、seed が要るときだけ `--seed` で明示的に足す。ロード時には必ず
  どちらを何件読んだかを stderr に出す。

  ## なぜ nbb ではなく JVM か

  npm `datascript` パッケージが公開するのは datascript.js インターフェースで、
  属性も値も **裸の文字列**になる（superproject の `manifest/edn-query.cljs`
  の冒頭コメントが同じ制約を記録している）。それでは `schema/news.edn` の
  `:db.unique/identity` upsert も、`:mirror` / `:verified` という keyword 値も
  文字列に潰れてしまい、スキーマを書いた意味が消える。この repo は既に
  `clojure -M:wasm-orchestrator` を launchd から回している JVM repo なので、
  同じ runtime の alias を1つ足すのが素直（ADR-2607173000 が退役させたのは
  `bb` であって、この repo 自身の既存 runtime ではない）。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [datascript.core :as d]
            [kawaraban.store :as store]))

(def ^:private ds-schema-keys
  "DataScript が実際に解釈するスキーマキーだけを通す。`schema/news.edn` は
  Datomic 形（:db/ident/:db/valueType/:db/doc を持つ map の vector）なので、
  そのまま渡すと DataScript 側で未知キーになる。:db/valueType は
  :db.type/ref のときだけ意味を持つ（他は Datomic 専用の型宣言）。"
  #{:db/cardinality :db/unique :db/index :db/isComponent :db/tupleAttrs})

(defn ->datascript-schema
  "Datomic 形スキーマ（:db/ident 付き map の vector）→ DataScript 形 {attr {…}}。"
  [datomic-schema]
  (reduce (fn [m {:db/keys [ident valueType] :as attr-def}]
            (let [kept (cond-> (select-keys attr-def ds-schema-keys)
                         (= valueType :db.type/ref) (assoc :db/valueType :db.type/ref))]
              (cond-> m
                (seq kept) (assoc ident kept))))
          {}
          datomic-schema))

(defn load-db
  "スキーマ + outlet registry + 記事アーカイブ（+ 任意で seed）を DataScript DB にする。
  戻り値 {:db … :counts {:outlets n :archive n :seed n}}。

  outlet は `data/outlets/allowlist.edn` から **その場で射影**する（ADR-2607253000）。
  生成ファイルを挟まないので allowlist と outlet entity がずれない。これが無いと
  article→outlet→country の join が空になる（allowlist は policy ファイルであって
  :news.outlet/* datom ではなく、それを作るはずの outlet_ingest cell は R0 scaffold
  のままだったため — 実際 ADR-2607252600 の時点でこの join は何も返さなかった）。"
  [{:keys [schema-path articles-dir seed-path include-seed? allowlist-path]}]
  (let [schema (->datascript-schema (edn/read-string (slurp schema-path)))
        outlets (store/load-outlets (or allowlist-path "data/outlets/allowlist.edn"))
        archive (store/load-archive articles-dir)
        seed (if (and include-seed? (.exists (io/file seed-path)))
               (edn/read-string (slurp seed-path))
               [])
        conn (d/create-conn schema)]
    ;; outlet を先に入れる。seed も :news.outlet/id を持つので、:db.unique/identity に
    ;; より同じ id は upsert され行が二重にならない（seed の NHK と allowlist の NHK は
    ;; 別 id なので実際には衝突しないが、将来揃えたときに壊れない順序にしておく）。
    (when (seq outlets) (d/transact! conn (vec outlets)))
    (when (seq archive) (d/transact! conn (vec archive)))
    (when (seq seed) (d/transact! conn (vec seed)))
    {:db (d/db conn)
     :counts {:outlets (count outlets) :archive (count archive) :seed (count seed)}}))

(def ^:private coverage-q
  '[:find ?country ?kind (count ?a)
    :where
    [?a :news.article/outlet ?oid]
    [?o :news.outlet/id ?oid]
    [?o :news.outlet/country ?country]
    [?o :news.outlet/kind ?kind]])

(defn- report-counts! [{:keys [outlets archive seed]}]
  (binding [*out* *err*]
    (println (str "loaded " outlets " outlet(s) from data/outlets/allowlist.edn + "
                  archive " archived article datom(s) from data/articles"
                  (if (pos? seed)
                    (str " + " seed " ILLUSTRATIVE seed datom(s) (:representative — not real coverage)")
                    " (seed excluded; pass --seed to include data/seed.edn)")))))

(defn -main [& argv]
  (let [args (vec (remove #{"--seed"} argv))
        include-seed? (boolean (some #{"--seed"} argv))
        {:keys [db counts]} (load-db {:schema-path (or (System/getenv "KAWARABAN_SCHEMA_PATH") "schema/news.edn")
                                      :articles-dir (or (System/getenv "KAWARABAN_ARTICLES_DIR") store/default-archive-dir)
                                      :seed-path "data/seed.edn"
                                      :include-seed? include-seed?})]
    (report-counts! counts)
    (case (first args)
      (nil "count")
      ;; `(count ?a) .` returns nil (not 0) on an empty relation — report 0, so
      ;; "no articles collected yet" reads as a measurement rather than a null.
      (let [n (fn [q] (or (d/q q db) 0))]
        (pp/pprint {:articles (n '[:find (count ?a) . :where [?a :news.article/id]])
                    :articles-verified (n '[:find (count ?a) . :where [?a :news.article/sourcing :verified]])
                    :articles-representative (n '[:find (count ?a) . :where [?a :news.article/sourcing :representative]])
                    :articles-with-byline (n '[:find (count ?a) . :where [?a :news.article/byline]])
                    :outlets (n '[:find (count ?o) . :where [?o :news.outlet/id]])
                    ;; 2つの数は別物で、別々に出す（ADR-2607253400 / issue 6bcb348）。
                    ;; feed-verified は「その媒体の feed がその日 items を返した」、
                    ;; org-verified は「その媒体自身のページで組織記録を照合した」。
                    ;; 片方をもう片方の代わりに読ませないために、まとめない。
                    :outlets-feed-verified (n '[:find (count ?o) . :where [?o :kawaraban.ingest/verified true]])
                    :outlets-org-verified (n '[:find (count ?o) . :where [?o :news.outlet/sourcing :verified]])
                    :countries-org-verified (count (d/q '[:find ?c
                                                          :where [?o :news.outlet/sourcing :verified]
                                                                 [?o :news.outlet/country ?c]]
                                                        db))
                    :countries (count (d/q '[:find ?c :where [?o :news.outlet/country ?c]] db))
                    :countries-feed-verified (count (d/q '[:find ?c
                                                           :where [?o :kawaraban.ingest/verified true]
                                                                  [?o :news.outlet/country ?c]]
                                                         db))}))

      "coverage"
      (doseq [row (sort (d/q coverage-q db))]
        (println (format "%-4s %-20s %d" (nth row 0) (name (nth row 1)) (nth row 2))))

      ;; 記事がまだ0件でも「どの国からどれだけ取りに行けるか」は今すぐ答えられる。
      ;; :kawaraban.ingest/verified は「その日 feed が実際に items を返した」の測定値。
      "outlets"
      (let [rows (d/q '[:find ?country ?kind ?verified (count ?o)
                        :where
                        [?o :news.outlet/id]
                        [?o :news.outlet/country ?country]
                        [?o :news.outlet/kind ?kind]
                        [?o :kawaraban.ingest/verified ?verified]]
                      db)]
        (doseq [row (sort rows)]
          (println (format "%-4s %-20s %-14s %d"
                           (nth row 0) (name (nth row 1))
                           (if (nth row 2) "feed-verified" "unconfirmed") (nth row 3))))
        (println (format "-- %d outlets, %d feed-verified, %d countries/regions with at least one working feed"
                         (count (d/q '[:find ?o :where [?o :news.outlet/id]] db))
                         (count (d/q '[:find ?o :where [?o :kawaraban.ingest/verified true]] db))
                         (count (d/q '[:find ?c :where [?o :kawaraban.ingest/verified true]
                                       [?o :news.outlet/country ?c]] db)))))

      "q"
      (if-let [qs (second args)]
        (pp/pprint (d/q (edn/read-string qs) db))
        (binding [*out* *err*] (println "usage: clojure -M:query q '<datalog edn>'") (System/exit 2)))

      (binding [*out* *err*]
        (println "usage: clojure -M:query [--seed] count|outlets|coverage|q '<datalog edn>'")
        (System/exit 2)))))
