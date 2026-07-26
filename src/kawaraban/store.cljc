(ns kawaraban.store
  "store.cljc — kawaraban 瓦版 の **query 可能なローカル記事アーカイブ**
  （com-junkawasaki/root ADR-2607252600）。

  これが埋める穴: Phase G/H の `wasm_orchestrator` は取得した記事を aozora
  (AT-Proto PDS) へ `createRecord` で publish するだけで、手元には
  `data/ingest/last-seen*.edn`（outlet 別の high-water mark）しか残らなかった。
  つまり「収集した記事を repo に EDN で持ち、datalog で問い合わせる」経路が
  存在しなかった。この namespace は gate を通過した記事を
  `data/articles/YYYY-MM-DD.edn` に **日付分割・追記マージ**して残し、
  `schema/news.edn` でそのまま transact できる形にする。

  ## 3つの設計判断

  1. **publish 成否と独立**。aozora への publish は
     `:max-articles-per-outlet` で意図的に絞られている（CPU/PDS 負荷）が、
     ローカルアーカイブはその上限に縛られない — gate を通った記事は
     publish されなくても、失敗しても、全部残す。publish 台帳ではなく
     観測記録なので、記録できるものを落とす理由がない。

  2. **:news.article/id で冪等**。同じ記事を別 tick で再取得しても行は増えない
     （`merge-articles` の dedupe = `schema/news.edn` の
     `:db.unique/identity` と同じ規則）。後から来た方を採る — 媒体が見出しを
     直したなら、その媒体の現在の公開ページが正しい。

  3. **as-of 不明は捏造しない**。RSS の日付が読めなかった記事（as-of 0）は
     取得日のファイルに紛れ込ませず `unknown-as-of.edn` に隔離する。
     1970-01-01 に置くのも、走った日に置くのも、どちらも「いつの記事か
     知っている」という嘘になる（G5 source-provenance-honest）。

  純粋部（datomize / day-key / merge-articles / group-by-day）に I/O は無く、
  ファイル書き込みは `#?(:clj)` の edge だけ — 本 repo の
  methods/*.cljc と同じ house style。"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])))

(def default-archive-dir
  "アーカイブの既定位置。`wasm_orchestrator`（書き手）と `query`（読み手）が
  同じ定数を見るよう、どちらでもなくここに置く。"
  "data/articles")

(def unknown-day
  "as-of を解決できなかった記事の隔離先（`day-key` の戻り値、およびファイル名の stem）。"
  "unknown-as-of")

(def ^:private keyword-valued
  "値が keyword である :news.* 属性（`schema/news.edn` の :db.type/keyword）。
  ingest の string-keyed record では \":mirror\" のように先頭コロン付き文字列で
  運ばれてくるので、ここに載っている属性だけを keyword へ戻す。見出し本文が
  たまたまコロンで始まっても壊れないよう、値の形ではなく属性名で判定する。"
  #{:news.article/kind :news.article/sourcing
    :news.outlet/kind :news.outlet/access :news.outlet/sourcing
    :news.section/men
    :news.mention/target-kind :news.mention/role})

(defn- ->attr
  "\":news.article/id\" → :news.article/id（既に keyword ならそのまま）。"
  [k]
  (cond
    (keyword? k) k
    (and (string? k) (str/starts-with? k ":")) (keyword (subs k 1))
    :else (keyword (str k))))

(defn- ->value
  [attr v]
  (if (and (contains? keyword-valued attr) (string? v))
    (keyword (cond-> v (str/starts-with? v ":") (subs 1)))
    v))

(defn datomize
  "`ingest/normalize-record` が返す string-keyed record を、`schema/news.edn` で
  transact できる keyword-keyed datom map に変換する。

  `_excerpt_truncated` のような先頭アンダースコアの内部フラグは datom では
  ないので落とす（切り詰め自体は G4 の既定動作で、excerpt が 280 字を超えない
  ことはスキーマ側の不変条件）。"
  [record]
  (reduce-kv (fn [m k v]
               (if (and (string? k) (str/starts-with? k "_"))
                 m
                 (let [attr (->attr k)]
                   (assoc m attr (->value attr v)))))
             {}
             record))

#?(:clj
   (defn- epoch->utc-date
     [^long epoch-seconds]
     (-> (java.time.Instant/ofEpochSecond epoch-seconds)
         (.atZone java.time.ZoneOffset/UTC)
         (.toLocalDate)
         (.toString))))

#?(:cljs
   (defn- epoch->utc-date
     [epoch-seconds]
     (subs (.toISOString (js/Date. (* 1000 epoch-seconds))) 0 10)))

(defn day-key
  "記事の :news.article/as-of（epoch 秒）→ \"YYYY-MM-DD\"（UTC）。
  as-of が正でない（= feed の日付を解決できなかった）記事は `unknown-day`。
  UTC 固定 — 走らせたマシンの時間帯でファイル分割が変わると、同じ記事が
  別の日のファイルに現れて dedupe をすり抜ける。"
  [article]
  (let [as-of (get article :news.article/as-of 0)]
    (if (and (number? as-of) (pos? as-of))
      (epoch->utc-date (long as-of))
      unknown-day)))

(defn group-by-day
  "datom map の列 → {\"YYYY-MM-DD\" [article …]}。"
  [articles]
  (group-by day-key articles))

(defn- sort-key [a]
  [(- (get a :news.article/as-of 0)) (str (get a :news.article/id))])

(defn merge-articles
  "既存の日次ファイル内容 `existing` に `incoming` をマージする。
  :news.article/id で dedupe（後勝ち = 媒体の現在の公開ページを採る）、
  as-of 降順 → id 昇順で安定ソート。差分が git 上で読めるよう決定的に並べる。"
  [existing incoming]
  (->> (concat existing incoming)
       (reduce (fn [acc a] (assoc acc (get a :news.article/id) a)) {})
       vals
       (sort-by sort-key)
       vec))

(def ^:private file-header
  ";; kawaraban 瓦版 — live mirror archive（自動生成 / 手編集しない）\n;; `kawaraban.store/append-articles!` が追記マージする。1行1記事、\n;; :news.article/id で dedupe、as-of 降順。`schema/news.edn` でそのまま\n;; transact でき、`clojure -M:query` が読む。\n;;\n;; G4 — headline + canonical url + 280字以内の excerpt + outlet のみ。\n;; 本文は決して含まれない（含む record は ingest が refuse する）。\n;; G5 — :news.article/sourcing :verified = その媒体自身の feed から実際に\n;; 取得した記事。data/seed.edn の :representative（例示）とは別物。\n")

(defn render-day-file
  "日次ファイルの本文（header comment + 1行1記事の vector）。"
  [articles]
  (str file-header
       "\n[" (str/join "\n " (map pr-str articles)) "]\n"))

#?(:clj
   (defn read-day-file
     "既存の日次ファイルを読む。無ければ []。"
     [path]
     (let [f (io/file path)]
       (if (.exists f)
         (edn/read-string (slurp f))
         []))))

#?(:clj
   (defn day-file-path
     [dir day]
     (str (io/file dir (str day ".edn")))))

#?(:clj
   (defn append-articles!
     "`records`（`ingest/normalize-batch` が返した string-keyed record、または
     既に datomize 済みの map）を日付分割して `dir` 配下にマージ保存する。
     戻り値は {\"YYYY-MM-DD\" {:added n :total n}} — 何件が新規で、その日の
     ファイルが何件になったかを呼び出し側が正直に報告できるようにする。"
     [dir records]
     (let [by-day (group-by-day (map datomize records))]
       (reduce-kv
        (fn [summary day articles]
          (let [path (day-file-path dir day)
                existing (read-day-file path)
                merged (merge-articles existing articles)]
            (io/make-parents path)
            (spit path (render-day-file merged))
            (assoc summary day {:added (- (count merged) (count existing))
                                :total (count merged)})))
        {}
        by-day))))

;; ── outlet registry → :news.outlet/* datoms ─────────────────────────────────
;; ADR-2607253000. `data/outlets/allowlist.edn` は「どの feed を取りに行ってよいか」
;; の **policy** ファイルであって :news.outlet/* datom ではない。allowlist の header は
;; 「entry を足したら outlet_ingest cell 経由で :news.outlet/* を作れ」と書いているが、
;; その cell は R0 scaffold（solve が RuntimeError）で、live ingest も outlet datom を
;; 作らない。結果として article は溜まるのに outlet が空で、article→outlet→country の
;; join が引けなかった。
;;
;; ここでは **生成ファイルを増やさず allowlist から直接射影する**。allowlist が唯一の
;; 正本であり続けるので、両者がずれる余地そのものが無い（生成物を置くと、その同期が
;; 新しい仕事として増える）。
;;
;; lexicon の :news.outlet/* と、kawaraban 固有の取得ポリシー
;; (:kawaraban.ingest/*) は分けている。feed-url / verified / note は「その媒体が
;; どういう存在か」ではなく「kawaraban がその媒体をどう取りに行くか」なので、
;; AT-Proto に publish される outlet record に混ぜない。

(def ^:private outlet-key->attr
  {:id :news.outlet/id
   :name :news.outlet/name
   :kind :news.outlet/kind
   :country :news.outlet/country
   :lang :news.outlet/lang
   :access :news.outlet/access
   :homepage :news.outlet/homepage})

(defn outlet-datom
  "allowlist の 1 entry → :news.outlet/* datom（+ :kawaraban.ingest/* の取得ポリシー）。

  **`:news.outlet/sourcing` は feed の生死から切り離されている**（ADR-2607253400 /
  issue 6bcb348）。以前はここが allowlist の `:verified` を読んでいたが、それが
  測っているのは『feed が items を返したか』の1点だけで、その媒体が誰で・どの国の・
  どういう種類かは一度も確認していなかった。とりわけ `:kind` は憲章の選定基準
  そのもの（state/public-broadcaster または非営利・国際機関 press のみ）なので、
  未確認のまま `:verified` を名乗らせるのは、確認していないことを確認したことに
  する類の主張だった。

  いまは:
  - `:news.outlet/sourcing` ← `:org-verified`（`scripts/verify-outlets.cljs` が
    その媒体自身のページで名称を確認したか）。既定は `:third-party`。
  - `:kawaraban.ingest/verified` ← `:verified`（feed の生死）。取得ポリシーとして
    正しい置き場はこちら。

  `:news.outlet/kind-evidence` は kind を支持する逐語引用で、**記録するだけで
  `:kind` の決定には使わない**。"
  [entry]
  (let [base (reduce-kv (fn [m k attr]
                          (if-let [v (get entry k)]
                            (assoc m attr (if (contains? keyword-valued attr)
                                            (keyword v)
                                            v))
                            m))
                        {}
                        outlet-key->attr)]
    (cond-> (assoc base :news.outlet/sourcing (if (:org-verified entry) :verified :third-party)
                        :kawaraban.ingest/verified (boolean (:verified entry)))
      (:org-provenance entry) (assoc :news.outlet/provenance (:org-provenance entry))
      (:org-checked entry) (assoc :news.outlet/last-verified (:org-checked entry))
      (:org-kind-evidence entry) (assoc :news.outlet/kind-evidence (:org-kind-evidence entry))
      (:feed-url entry) (assoc :kawaraban.ingest/feed-url (:feed-url entry))
      (:section entry) (assoc :kawaraban.ingest/section (:section entry))
      (:note entry) (assoc :kawaraban.ingest/note (:note entry))
      (:org-note entry) (assoc :kawaraban.ingest/org-note (:org-note entry)))))

(defn outlet-datoms [allowlist] (mapv outlet-datom allowlist))

#?(:clj
   (defn load-outlets
     "allowlist EDN を読んで :news.outlet/* datom 列にする。ファイルが無ければ []。"
     [path]
     (let [f (io/file path)]
       (if (.exists f)
         (outlet-datoms (edn/read-string (slurp f)))
         []))))

#?(:clj
   (defn load-archive
     "`dir` 配下の全日次ファイルを読み、1本の datom 列にする（query ローダ用）。"
     [dir]
     (let [d (io/file dir)]
       (if (.isDirectory d)
         (->> (.listFiles d)
              (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
              (sort-by #(.getName ^java.io.File %))
              (mapcat #(edn/read-string (slurp %)))
              vec)
         []))))
