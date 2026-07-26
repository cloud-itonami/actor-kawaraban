#!/usr/bin/env nbb
;; scripts/ingest-cron.cljs — kawaraban 瓦版 — 6時間ごとの実収集を無人で回す
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
;; このスクリプトは **`~/.kawaraban/ingest-clone`** という専用 clone の中から走る。
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
  (println (str "ingest-cron: ABORT — " msg))
  (.exit js/process code))

(println (str "ingest-cron (kawaraban) — " (.toISOString (js/Date.)) " — " repo-dir))

;; 1. 既知の状態へ
(when (pos? (:code (run! "git" ["fetch" "--quiet" "origin"])))
  (fail! "git fetch failed — no network or no credential; nothing measured, nothing written" 1))
(run! "git" ["reset" "--quiet" "--hard" "origin/main"])
(run! "git" ["clean" "-qfd"])

;; 2. 測り直す（失敗したらここで止まる — 部分的な結果を commit しない）
(let [{:keys [code]} (run! "clojure" ["-M:wasm-orchestrator"])]
  (when (pos? code)
    (fail! (str "wasm-orchestrator exited " code
                " — it exits non-zero only when EVERY outlet errored, which is a"
                " systemic problem (network, gate, identity) rather than per-outlet"
                " flakiness. Nothing is committed.")
           code)))

;; 3. 書かれたものが datom として読めることを確かめる
(let [{:keys [code]} (run! "nbb" ["-e" "(require (quote [\"node:fs\" :as fs]) (quote [clojure.string :as str])) (let [d \"data/articles\" fl (filter #(str/ends-with? % \".edn\") (js->clj (.readdirSync fs d))) a (mapcat #(cljs.reader/read-string (.readFileSync fs (str d \"/\" %) \"utf8\")) fl)] (when-not (every? :news.article/id a) (.exit js/process 1)) (println (str \"archive ok: \" (count fl) \" file(s), \" (count a) \" article(s)\")))"])]
  (when (pos? code)
    (fail! "the archive does not parse as :news.article/* datoms" 1)))

;; 4. 差分があれば着地させる
(if (str/blank? (git-out ["status" "--porcelain" "--" "data/articles" "data/ingest"]))
  (println "ingest-cron: no change — no new articles since the last run")
  (do
    (run! "git" ["config" "user.name" "kawaraban-refresh"])
    (run! "git" ["config" "user.email" "kawaraban-refresh@localhost"])
    (run! "git" ["add" "data/articles" "data/ingest"])
    (when (pos? (:code (run! "git" ["commit" "-q" "-m"
                                    (str "chore(articles): scheduled mirror collection\n\n"
                                         "Automated by scripts/ingest-cron.cljs via launchd under the\n"
                                         "G8 authorization in BOOTSTRAP-ATTESTATION-live-ingest.md.\n"
                                         "Archive only: KAWARABAN_WASM_PDS_ALLOWLIST is unset, so no\n"
                                         "record was published anywhere.")])))
      (fail! "git commit failed" 1))
    (when (pos? (:code (run! "git" ["push" "--quiet" "origin" "HEAD:main"])))
      (fail! (str "git push failed — the commit exists only in this clone and the next run's"
                  " reset will discard it. Nothing is lost: the next run re-measures from"
                  " scratch.")
             1))
    (println "ingest-cron: pushed")))

(println "ingest-cron: done")
