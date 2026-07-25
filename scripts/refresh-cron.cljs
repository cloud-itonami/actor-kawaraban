#!/usr/bin/env nbb
;; scripts/refresh-cron.cljs — kawaraban 瓦版 — 週次の feed 再測定を無人で回す
;; （com-junkawasaki/root ADR-2607253200）。
;;
;; launchd（`scripts/launchd/com.etzhayyim.kawaraban.refresh-feeds.plist`）から
;; 呼ばれる。GitHub Actions を使わない経路 — `.github/workflows/` への書き込みには
;; OAuth token の `workflow` scope が要り、このワークスペースのトークンは持っていない。
;; 等価な Actions 版は `.github/workflows-pending/refresh-feeds.yml` に温存してあり、
;; scope が付いた日に `git mv` すれば置き換えられる。どちらもこのスクリプトと同じ
;; `verify-feeds.cljs` を同じ引数で叩くだけなので、二重メンテにはならない。
;;
;; ## 専用 clone で動く
;;
;; このスクリプトは **`~/.kawaraban/refresh-clone`** という専用 clone の中から走る。
;; west 管理下の共有 checkout（`orgs/etzhayyim/com-etzhayyim-kawaraban`）では走らせない
;; — 無人ジョブが `git reset --hard` / `git clean -fd` する場所と、人や他セッションが
;; 編集する場所を同じにしてはいけない（root CLAUDE.md「共有 checkout には直接
;; commit/push しない」）。
;;
;; この危険は机上の話ではない: 2026-07-25、このスクリプト自身をまだ commit する前に
;; clone 内でテスト実行したところ、下の `clean -fd` が untracked だった自分自身を
;; 消した。共有 checkout で同じことが起きれば、消えるのは他人の未コミット作業になる。
;;
;; ## 毎回 origin/main から始める
;;
;; 前回の実行が途中で落ちていても残骸を引き継がないように、まず `fetch` +
;; `reset --hard origin/main` + `clean -fd` で既知の状態に戻す。無人ジョブが積み上げた
;; 半端な状態を「変更」として commit するのが一番たちが悪い。
;;
;; ## 測定が壊れたら何も書かない
;;
;; `verify-feeds.cljs` 自身が、verified 数が前回比 70% を割ったら書き込みを拒否して
;; 非ゼロ終了する。ここではその終了コードをそのまま尊重し、commit にも push にも
;; 進まない。ネットワークの死んだマシンが「全 feed 死亡」を測定結果として commit
;; するのが、この無人化で一番避けたい事故。
;;
;; 副次的だが重要な効果として、**このジョブは verify-feeds.cljs を毎週実際に実行する
;; 唯一の経路**でもある。同スクリプトは長らくどのテストからも呼ばれておらず、paren を
;; 1つ落としたまま parse エラーで壊れているのに `clojure -M:test` も audit も緑のまま
;; だった（2026-07-25 に発覚）。壊れていれば、以後はこのジョブが失敗して気付ける。

(require '[clojure.string :as str]
         '["node:child_process" :as cp]
         '["node:path" :as path])

(def repo-dir
  "このスクリプトが置かれている clone のルート。plist が絶対パスで叩くので cwd では
  決められず、`process.argv[1]` も nbb バイナリ自身を指す（実測: `/opt/homebrew` に
  解決されて全ファイルが見つからなくなった）。nbb が束縛する `*file*` が正しい出所。"
  (path/resolve (path/dirname *file*) ".."))

(defn- run!
  "同期実行して {:code n} を返す。出力は親の stdout にそのまま流す
  （launchd のログファイルが唯一の観測窓なので握り潰さない）。"
  [cmd args]
  (println (str "$ " cmd " " (str/join " " args)))
  (let [r (.spawnSync cp cmd (clj->js args)
                      #js {:cwd repo-dir :stdio "inherit" :env (.. js/process -env)})]
    {:code (or (.-status r) 1)}))

(defn- git-out [args]
  (let [r (.spawnSync cp "git" (clj->js args) #js {:cwd repo-dir :encoding "utf8"})]
    (str/trim (or (.-stdout r) ""))))

(defn- fail! [msg code]
  (println (str "refresh-cron: ABORT — " msg))
  (.exit js/process code))

(println (str "refresh-cron (kawaraban) — " (.toISOString (js/Date.)) " — " repo-dir))

;; 1. 既知の状態へ
(when (pos? (:code (run! "git" ["fetch" "--quiet" "origin"])))
  (fail! "git fetch failed — no network or no credential; nothing measured, nothing written" 1))
(run! "git" ["reset" "--quiet" "--hard" "origin/main"])
(run! "git" ["clean" "-qfd"])

;; 2. 測り直す（失敗したらここで止まる — 部分的な結果を commit しない）
(let [{:keys [code]} (run! "nbb" ["scripts/verify-feeds.cljs" "data/outlets/allowlist.edn"
                                  "--discover" "--apply"])]
  (when (pos? code)
    (fail! (str "verify-feeds exited " code
                " — either the run failed or its collapse guard refused to write."
                " The allowlist is untouched, which is the correct outcome.")
           code)))

;; 3. 書かれたものが読めることを確かめる
(let [{:keys [code]} (run! "nbb" ["-e" "(require (quote [\"node:fs\" :as fs])) (let [v (cljs.reader/read-string (.readFileSync fs \"data/outlets/allowlist.edn\" \"utf8\"))] (when-not (and (vector? v) (seq v) (every? :id v)) (.exit js/process 1)) (println (str \"allowlist ok: \" (count v) \" outlets, \" (count (filter :verified v)) \" verified\")))"])]
  (when (pos? code)
    (fail! "the rewritten allowlist does not parse as a vector of entries with :id" 1)))

;; 4. 差分があれば着地させる
(if (str/blank? (git-out ["status" "--porcelain" "--" "data/outlets/allowlist.edn"]))
  (println "refresh-cron: no change — every feed measured the same as last run")
  (do
    (run! "git" ["config" "user.name" "kawaraban-refresh"])
    (run! "git" ["config" "user.email" "kawaraban-refresh@localhost"])
    (run! "git" ["add" "data/outlets/allowlist.edn"])
    (when (pos? (:code (run! "git" ["commit" "-q" "-m"
                                    (str "chore(outlets): scheduled feed re-measurement\n\n"
                                         "Automated by scripts/refresh-cron.cljs via launchd\n"
                                         "(ADR-2607253200). :verified/:note/:feed-url reflect what the\n"
                                         "feeds actually returned on this run; see each entry's :note.")])))
      (fail! "git commit failed" 1))
    (when (pos? (:code (run! "git" ["push" "--quiet" "origin" "HEAD:main"])))
      (fail! (str "git push failed — the commit exists only in this clone and the next run's"
                  " reset will discard it. Nothing is lost: the next run re-measures from"
                  " scratch.")
             1))
    (println "refresh-cron: pushed")))

(println "refresh-cron: done")
