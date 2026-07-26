#!/usr/bin/env nbb
;; scripts/verify-outlets.cljs — kawaraban 瓦版 — outlet を **その媒体自身のページ**で
;; 照合し、組織記録に provenance を与える（com-junkawasaki/root ADR-2607253400、
;; Radicle issue 6bcb348）。
;;
;;   nbb scripts/verify-outlets.cljs             # 測るだけ
;;   nbb scripts/verify-outlets.cljs --apply     # allowlist に書き戻す
;;
;; ## 何を直しているか
;;
;; `:news.outlet/sourcing` は allowlist の `:verified` から機械的に導出されていた。
;; だがその `:verified` が測っているのは **feed が items を返したか** の1点だけで、
;; 「この媒体が誰で、どの国の、どういう種類か」は一度も確認されていなかった。
;; つまり `:verified` な outlet が主張していたのは「ある日 feed が生きていた」ことだけ。
;;
;; とりわけ `:kind` が問題で、これは憲章の選定基準そのもの（state/public-broadcaster
;; または非営利・国際機関 press のみ、商業・広告収入媒体は不可）。「BBC は GB の
;; public-broadcaster である」は allowlist に人が書いた行のままだった。
;;
;; feed の生死は `:kawaraban.ingest/verified` に残る（そこが正しい置き場）。この
;; スクリプトが決めるのは **組織記録** の方。
;;
;; ## 何をもって確認とするか
;;
;; その媒体の `:homepage` を取得し、**媒体名がページに現れるか**を見る。現れたら
;; `:org-verified true` ＋ `:org-provenance`（URL）＋ `:org-checked`（日付）。
;;
;; 名称は素直に一致しないことが多いので段階的に試す:「BBC News — World」のような
;; 節名つきは区切り以降を落とし、`<title>` と本文の両方を見る。
;;
;; ## kind は「記録するだけ、推論しない」
;;
;; 種別を支持する逐語断片が見つかったら `:org-kind-evidence` に**そのまま**残すが、
;; **`:kind` を書き換えることは決してしない**。「public broadcaster」という語が
;; ページにあることは、その媒体が公共放送である証明ではない（他局を説明している
;; だけかもしれない）。憲章の選定基準を keyword 一致で自動確定させるのは、まさに
;; この issue が問題にしている「確認していないのに確認したことにする」の再演になる。
;;
;; この作法は `cloud-itonami-assoc-*` の 78 repo が既に到達している水準（取得日・
;; 手段・逐語引用・**見つからなかったものを推測せず省いた記録**）に倣ったもので、
;; 新しく考えたものではない。
;;
;; ## しないこと
;;
;; 403 を UA 偽装で抜けない。確認できない outlet を削除しない（行は残し、正直な
;; 理由を付ける）。全件緑を目標にしない — ooyake の official-url ですら正直な bot
;; からの到達率は約55%で、報道機関がそれを上回る理由は無い。

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '["node:fs" :as fs])

(def argv (vec *command-line-args*))
(def apply? (contains? (set argv) "--apply"))
(def allowlist-path (or (first (remove #(str/starts-with? % "--") argv))
                        "data/outlets/allowlist.edn"))
(def timeout-ms (js/parseInt (or (.. js/process -env -KAWARABAN_OUTLET_TIMEOUT_MS) "12000") 10))
(def concurrency 6)

(def user-agent
  "live_fetch/jvm-http-get と同じ名乗り。検証と本番取得で別 UA を使うと、UA で弾く
  媒体を検証だけ通してしまう。"
  "kawaraban/1.0 (+https://github.com/etzhayyim/com-etzhayyim-kawaraban; news-medium mirror bot, G4 headline+link+bounded-excerpt only)")

(def kind-phrases
  "種別ごとの手がかり語。**判定には使わず、逐語引用を切り出す位置を決めるためだけ**に
  使う。ここに一致したからといって :kind は変えない。"
  {"public-broadcaster" ["public broadcaster" "public service broadcast" "public-service broadcast"
                         "public broadcasting" "national broadcaster" "state broadcaster"
                         "licence fee" "license fee" "公共放送" "öffentlich-rechtlich"
                         "service public" "radiodifusión pública"]
   "wire-agency" ["news agency" "press agency" "wire service" "agence de presse"
                  "agencia de noticias" "通信社" "nachrichtenagentur"]
   "ngo-press" ["non-profit" "nonprofit" "not-for-profit" "charitable" "501(c)(3)"
                "foundation" "non-governmental" "intergovernmental"]})

(defn- fetch-text [url]
  (if (str/blank? (str url))
    (js/Promise.resolve {:error "no homepage"})
    (let [c (js/AbortController.)
          t (js/setTimeout #(.abort c) timeout-ms)]
      (-> (js/fetch url #js {:signal (.-signal c) :redirect "follow"
                             :headers #js {"User-Agent" user-agent
                                           "Accept" "text/html,application/xhtml+xml,*/*"}})
          (.then (fn [r] (.then (.text r) (fn [b] {:status (.-status r) :body b}))))
          (.catch (fn [e] {:error (or (.-message e) (str e))}))
          (.finally (fn [] (js/clearTimeout t)))))))

(defn strip-tags [html]
  (-> html
      (str/replace #"(?s)<script.*?</script>" " ")
      (str/replace #"(?s)<style.*?</style>" " ")
      (str/replace #"<[^>]*>" " ")
      (str/replace #"&[a-z]+;" " ")
      (str/replace #"\s+" " ")))

(defn normalize [s]
  (-> (str s) str/lower-case (.normalize "NFD")
      (str/replace #"[̀-ͯ]" "")
      (str/replace #"\s+" " ") str/trim))

(defn name-candidates
  "照合に使う名称の候補を、長い順に。`BBC News — World` のような節名つきは区切り以降を
  落とす。短すぎる断片（3文字以下）は誤爆するので使わない。"
  [nm]
  (let [base (str/trim (str nm))
        cut (first (str/split base #"\s+[—–|(]\s*"))]
    (->> [base cut]
         (map str/trim)
         (filter #(> (count %) 3))
         distinct
         vec)))

(defn name-found
  "ページ本文または <title> に媒体名が現れるか。現れた候補を返す（無ければ nil）。"
  [text title nm]
  (let [t (normalize text) ti (normalize title)]
    (first (filter (fn [c] (let [n (normalize c)]
                             (or (str/includes? t n) (str/includes? ti n))))
                   (name-candidates nm)))))

(defn kind-evidence
  "kind の手がかり語が本文にあれば、その前後を含む逐語断片を返す。無ければ nil。
  **判定はしない** — 返すのは引用だけ。"
  [raw-text kind]
  (let [t raw-text
        low (str/lower-case t)]
    (some (fn [phrase]
            (when-let [i (str/index-of low (str/lower-case phrase))]
              (let [from (max 0 (- i 90))
                    to (min (count t) (+ i (count phrase) 90))]
                (str/trim (subs t from to)))))
          (get kind-phrases kind []))))

(defn check-one [{:keys [id name homepage kind] :as entry}]
  (-> (fetch-text homepage)
      (.then (fn [{:keys [status body error]}]
               (cond
                 error {:id id :entry entry :ok false :reason (str "unreachable: " error)}
                 (not= 200 status) {:id id :entry entry :ok false :reason (str "HTTP " status)}
                 :else
                 (let [title (or (second (re-find #"(?is)<title[^>]*>(.*?)</title>" body)) "")
                       text (strip-tags body)
                       hit (name-found text title name)]
                   (if hit
                     {:id id :entry entry :ok true :url homepage :matched hit
                      :evidence (kind-evidence text kind)}
                     {:id id :entry entry :ok false
                      :reason "outlet name not found on its own homepage"})))))))

(defn check-batch [entries]
  (letfn [(step [acc remaining]
            (if (empty? remaining)
              (js/Promise.resolve acc)
              (let [[b r] (split-at concurrency remaining)]
                (-> (js/Promise.all (clj->js (map check-one b)))
                    (.then (fn [rs]
                             (let [rs (js->clj rs :keywordize-keys true)]
                               (doseq [x rs]
                                 (println (str (if (:ok x) "  ok  " "  FAIL") " " (:id x)
                                               (when (:ok x) (str " matched=" (pr-str (:matched x))
                                                                  (when (:evidence x) " +kind-evidence")))
                                               (when (:reason x) (str " — " (:reason x))))))
                               (step (into acc rs) r))))))))]
    (step [] (vec entries))))

(defn today [] (subs (.toISOString (js/Date.)) 0 10))

(def key-order
  [:id :name :kind :country :lang :access :homepage :feed-url :verified :section :note
   :org-verified :org-provenance :org-checked :org-kind-evidence :org-note])

(defn render-entry [m]
  (let [pairs (concat (keep (fn [k] (when (contains? m k) [k (get m k)])) key-order)
                      (keep (fn [[k v]] (when-not (some #{k} key-order) [k v])) (sort m)))]
    (str "{" (str/join "\n  " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) pairs)) "}")))

(defn apply-result [entry {:keys [ok url matched evidence reason]}]
  (if ok
    (cond-> (assoc entry :org-verified true
                         :org-provenance url
                         :org-checked (today)
                         :org-note (str "verify-outlets " (today) ": fetched the outlet's own page; "
                                        "its name appears there as " (pr-str matched) "."
                                        (if evidence
                                          " A kind-supporting quote was captured verbatim below — recorded as evidence, NOT used to set :kind."
                                          " No kind-supporting phrase was found on this page, so :kind remains unconfirmed.")))
      evidence (assoc :org-kind-evidence evidence)
      (not evidence) (dissoc :org-kind-evidence))
    (-> entry
        (assoc :org-verified false
               :org-checked (today)
               :org-note (str "verify-outlets " (today) ": " reason
                              ". The record is kept and stays unconfirmed — being unable to reach "
                              "an outlet is not evidence about what it is."))
        (dissoc :org-provenance :org-kind-evidence))))

(defn -main []
  (let [text (str (.readFileSync fs allowlist-path "utf8"))
        header (subs text 0 (str/index-of text "\n["))
        outlets (edn/read-string text)]
    (println (str "checking " (count outlets) " outlets against their own homepages"
                  " (timeout " timeout-ms "ms, concurrency " concurrency ")"))
    (-> (check-batch outlets)
        (.then
         (fn [results]
           (let [by-id (into {} (map (juxt :id identity) results))
                 ok (filter :ok results)
                 ev (count (filter :evidence results))]
             (println (str "\norganisationally verified: " (count ok) "/" (count results)
                           "  (" (Math/round (* 100.0 (/ (count ok) (max 1 (count results))))) "%)"
                           "; kind-supporting quote captured for " ev))
             (println (str "reasons: " (pr-str (frequencies (keep :reason results)))))
             (if-not apply?
               (println "\n(report only — pass --apply to write the allowlist back)")
               (let [updated (mapv #(apply-result % (get by-id (:id %))) outlets)]
                 (.writeFileSync fs allowlist-path
                                 (str header "\n[" (str/join "\n\n " (map render-entry updated)) "]\n"))
                 (println (str "wrote " allowlist-path))))))))))

(-main)
