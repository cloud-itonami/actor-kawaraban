#!/usr/bin/env nbb
;; scripts/verify-feeds.cljs — kawaraban 瓦版 outlet allowlist の feed 実地検証 +
;; feed URL 自動発見（com-junkawasaki/root ADR-2607252600）。
;;
;; `data/outlets/allowlist.edn` の :verified は「その :feed-url が本当に
;; RSS/Atom を返すか」を表す G5 のフィールドであって、書いた人の自信度では
;; ない。世界規模に広げるほど推測で true を置きたくなるので、その判定を
;; 人間の記憶から外して実測に固定するのがこのスクリプト。
;;
;;   nbb scripts/verify-feeds.cljs data/outlets/allowlist.edn
;;   nbb scripts/verify-feeds.cljs data/outlets/allowlist.edn --apply
;;   nbb scripts/verify-feeds.cljs data/outlets/allowlist.edn --discover --apply
;;
;; 既定は検証してレポートを出すだけ（副作用なし）。`--apply` を付けたときだけ
;; allowlist を書き戻す — :verified を実測結果に、:note を日付つきの機械記録に
;; 置き換える（:note はこのファイルでは元々「live-checked 2026-07-10: …」形式の
;; 機械記録用フィールドとして使われている）。
;;
;; `--discover` は :feed-url が無い/壊れている entry について、:homepage から
;; `<link rel="alternate" type="application/rss+xml">` を読み、無ければよくある
;; パス（/rss、/feed、/rss.xml …）を順に試して、実際に item を返した URL を
;; 採用する。世界の公共放送は feed URL の規約がばらばらで、記憶や推測で書くと
;; 大半が 404 になる — 実際に取りに行って決めるのが唯一正直な方法。
;;
;; 判定は `src/kawaraban/methods/live_fetch.cljc` の `parse-feed` と同じ規則:
;; <rss / <rdf:RDF なら <item>、<feed なら <entry> を数える。ここで 0 件なら
;; 本番の ingest でも 0 件になる。HTTP 200 を返す HTML ページを feed と誤認
;; しないための本質的なチェックで、実際 NHK World の URL がこれで落ちている。
;;
;; charter 上の位置づけ: これは ingest ではない。記事を1件も作らず、保存せず、
;; publish しない（G8 の KAWARABAN_ALLOW_LIVE_INGEST は関与しない）。やって
;; いるのは「この URL は feed か」という到達性確認だけで、allowlist に既に
;; 記録されている "live-checked" 注記と同じ行為を自動化したもの。

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '["node:fs" :as fs])

(def argv (vec *command-line-args*))
(def flags (set (filter #(str/starts-with? % "--") argv)))
(def positional (vec (remove #(str/starts-with? % "--") argv)))
(def allowlist-path (or (first positional) "data/outlets/allowlist.edn"))
(def apply? (contains? flags "--apply"))
(def discover? (contains? flags "--discover"))
(def timeout-ms
  "KAWARABAN_VERIFY_TIMEOUT_MS で上書きできる。タイムアウトは『feed が無い』の
  証拠ではないので、abort した媒体だけを長めの値で1回やり直せるようにしてある
  （既定を最初から長くすると 80 媒体走が現実的な時間で終わらない）。"
  (js/parseInt (or (.. js/process -env -KAWARABAN_VERIFY_TIMEOUT_MS) "8000") 10))
(def concurrency 8)

(def user-agent
  "live_fetch/jvm-http-get と同じ名乗り。検証と本番取得で別の UA を使うと、
  UA で弾く媒体を検証だけ通してしまう。"
  "kawaraban/1.0 (+https://github.com/etzhayyim/com-etzhayyim-kawaraban; news-medium mirror bot, G4 headline+link+bounded-excerpt only)")

(def common-feed-paths
  "homepage が feed link を宣言していないときに試すパス。実測での的中順
  （2026-07-25 の 83 媒体走で `/feed/`・`/rss`・`/api/` がよく当たった。
  `/api/` は VOA/RFE-RL 系の Pangea CMS が feed をそこに置いている）。"
  ["/rss" "/feed" "/feed/" "/rss.xml" "/feed.xml" "/api/" "/index.xml"
   "/rss/news" "/rss/news.xml" "/en/rss" "/en/feed" "/news/rss" "/news/feed"
   "/rssfeed" "/atom.xml" "/rss/index.xml"])

(defn- count-matches [re s] (count (re-seq re s)))

(defn classify
  "body → {:kind :rss|:rdf|:atom|:html|:unknown :items n}。live_fetch/parse-feed と同じ順序で判定する。"
  [body]
  (cond
    (re-find #"(?i)<rss[\s>]" body)     {:kind :rss  :items (count-matches #"(?i)<item[\s>]" body)}
    (re-find #"(?i)<rdf:RDF[\s>]" body) {:kind :rdf  :items (count-matches #"(?i)<item[\s>]" body)}
    (re-find #"(?i)<feed[\s>]" body)    {:kind :atom :items (count-matches #"(?i)<entry[\s>]" body)}
    (re-find #"(?i)<html[\s>]" body)    {:kind :html :items 0}
    :else                                {:kind :unknown :items 0}))

(defn fetch-text
  "→ Promise of {:status n :body s :content-type s} | {:error msg}。例外は投げない。"
  [url]
  (if (str/blank? (str url))
    (js/Promise.resolve {:error "no URL"})
    (let [ctrl (js/AbortController.)
          timer (js/setTimeout #(.abort ctrl) timeout-ms)]
      (-> (js/fetch url #js {:signal (.-signal ctrl)
                             :redirect "follow"
                             :headers #js {"User-Agent" user-agent
                                           "Accept" "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*"}})
          (.then (fn [resp]
                   (-> (.text resp)
                       (.then (fn [body] {:status (.-status resp)
                                          :body body
                                          :content-type (or (.get (.-headers resp) "content-type") "")})))))
          (.catch (fn [e] {:error (or (.-message e) (str e))}))
          (.finally (fn [] (js/clearTimeout timer)))))))

(defn probe-feed
  "1本の URL を feed として評価する → Promise of {:url :status :kind :items :ok}。"
  [url]
  (-> (fetch-text url)
      (.then (fn [{:keys [status body error content-type]}]
               (if error
                 {:url url :status 0 :kind :error :items 0 :ok false :error error}
                 (let [{:keys [kind items]} (classify body)]
                   {:url url :status status :content-type content-type
                    :kind kind :items items
                    :ok (and (= 200 status) (contains? #{:rss :rdf :atom} kind) (pos? items))}))))))

;; ── discovery ───────────────────────────────────────────────────────────────

(defn- absolutize [base href]
  (try (.-href (js/URL. href base)) (catch :default _ nil)))

(defn declared-feed-urls
  "HTML の <link rel=\"alternate\" type=\"application/rss+xml|atom+xml\" href=…> を拾う。
  属性順は媒体ごとにばらばらなので、link タグを切り出してから type/href を個別に見る。"
  [base html]
  (->> (re-seq #"(?i)<link\s[^>]*>" html)
       (filter #(re-find #"(?i)type=\"?application/(rss|atom)\+xml" %))
       (keep (fn [tag]
               (when-let [m (re-find #"(?i)href=\"([^\"]+)\"" tag)]
                 (absolutize base (second m)))))
       distinct
       (take 6)
       vec))

(defn- sequential-probe
  "候補 URL を順に試し、最初に ok になったものを返す（全滅なら最後の結果）。
  並列に叩かないのは、同一ホストへの同時多発リクエストを避けるため。"
  [urls]
  (letfn [(step [remaining last-result]
            (if (empty? remaining)
              (js/Promise.resolve last-result)
              (-> (probe-feed (first remaining))
                  (.then (fn [r] (if (:ok r)
                                   (js/Promise.resolve r)
                                   (step (rest remaining) r)))))))]
    (if (empty? urls)
      (js/Promise.resolve {:url nil :status 0 :kind :error :items 0 :ok false :error "no candidate URL"})
      (step (vec urls) nil))))

(defn discover-feed
  "homepage から feed URL を探す → Promise of probe result（:url が発見結果）。"
  [homepage]
  (-> (fetch-text homepage)
      (.then (fn [{:keys [body error]}]
               (let [declared (if (or error (str/blank? (str body)))
                                []
                                (declared-feed-urls homepage body))
                     guessed (keep #(absolutize homepage %) common-feed-paths)]
                 (sequential-probe (distinct (concat declared guessed))))))))

(defn check-one
  "1 outlet を評価する。--discover 時は :feed-url が無い/落ちたら homepage から探す。"
  [{:keys [id feed-url homepage]}]
  (-> (probe-feed feed-url)
      (.then (fn [r]
               (if (or (:ok r) (not discover?) (str/blank? (str homepage)))
                 (js/Promise.resolve (assoc r :id id))
                 (-> (discover-feed homepage)
                     (.then (fn [d] (assoc d :id id :discovered? (boolean (:ok d)))))))))))

;; ── batching / report ───────────────────────────────────────────────────────

(defn check-batch
  "concurrency 本ずつ順に流す。世界中の公共放送を一斉に叩くと、こちらが
  DoS まがいの挙動になるうえ相手側の rate limit で偽の failure が出る。"
  [outlets]
  (letfn [(step [acc remaining]
            (if (empty? remaining)
              (js/Promise.resolve acc)
              (let [[batch rest] (split-at concurrency remaining)]
                (-> (js/Promise.all (clj->js (map check-one batch)))
                    (.then (fn [rs]
                             (let [rs (js->clj rs :keywordize-keys true)]
                               (doseq [r rs]
                                 (println (str (if (:ok r) "  ok  " "  FAIL")
                                               " " (:id r)
                                               " status=" (:status r)
                                               " kind=" (name (or (:kind r) :error))
                                               " items=" (:items r)
                                               (when (:discovered? r) (str " discovered=" (:url r)))
                                               (when (:error r) (str " error=" (:error r))))))
                               (step (into acc rs) rest))))))))]
    (step [] (vec outlets))))

(def key-order
  [:id :name :kind :country :lang :access :homepage :feed-url :verified :section :note])

(defn render-entry [m]
  (let [pairs (concat (keep (fn [k] (when (contains? m k) [k (get m k)])) key-order)
                      (keep (fn [[k v]] (when-not (some #{k} key-order) [k v])) (sort m)))]
    (str "{" (str/join "\n  " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) pairs)) "}")))

(defn render-allowlist [header outlets]
  (str header "\n[" (str/join "\n\n " (map render-entry outlets)) "]\n"))

(defn file-header
  "元ファイルの先頭コメントブロック（`[` の手前まで）をそのまま持ち越す。
   選定基準や G4 不変条件の説明は人間が書いたもので、機械が消してよくない。"
  [text]
  (let [i (str/index-of text "\n[")]
    (if i (subs text 0 i) "")))

(defn today [] (subs (.toISOString (js/Date.)) 0 10))

(defn apply-result [outlet {:keys [ok status kind items error url discovered?]}]
  (assoc outlet
         :feed-url (if (and discovered? ok) url (:feed-url outlet))
         :verified (boolean ok)
         :note (str "verify-feeds " (today) ": "
                    (if ok
                      (str "HTTP " status ", " (name kind) ", " items " items."
                           (when discovered? " Feed URL discovered from the homepage this run."))
                      (cond
                        error (str "unreachable — " error ". Feed URL needs correcting before this outlet can yield articles.")
                        (not= 200 status) (str "HTTP " status " — not a usable feed endpoint.")
                        (= :html kind) "HTTP 200 but returned an HTML page, not RSS/Atom XML — the feed URL is a landing page, not a feed."
                        (zero? items) (str "HTTP 200, parsed as " (name kind) ", but 0 items — nothing to mirror.")
                        :else (str "HTTP " status ", kind=" (name (or kind :error)) ", items=" items "."))))))

(defn -main []
  (let [text (str (.readFileSync fs allowlist-path "utf8"))
        outlets (edn/read-string text)]
    (println (str "verifying " (count outlets) " feed(s) from " allowlist-path
                  " (concurrency " concurrency ", timeout " timeout-ms "ms"
                  (when discover? ", discovery ON") ")"))
    (-> (check-batch outlets)
        (.then
         (fn [results]
           (let [by-id (into {} (map (juxt :id identity) results))
                 ok-n (count (filter :ok results))]
             (println (str "\n" ok-n "/" (count results) " feeds verified live"))
             (if apply?
               (let [updated (mapv #(apply-result % (get by-id (:id %))) outlets)]
                 (.writeFileSync fs allowlist-path (render-allowlist (file-header text) updated))
                 (println (str "wrote " allowlist-path " (:verified/:note"
                               (when discover? "/:feed-url") " set from this run)")))
               (println "\n(report only — pass --apply to write :verified/:note back)"))))))))

(-main)
