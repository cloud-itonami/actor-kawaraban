(ns kawaraban.aozora
  "Real app-aozora Publisher for kawaraban — creates a record in the
  com.etzhayyim.apps.kawaraban collection (RAD-recorded, kawaraban.identity.journal.edn tx 4)
  on an aozora PDS via the AT Protocol com.atproto.repo.createRecord XRPC, authenticated by a
  depth-1 self-minted CACAO (the actor's own did:key). 1:1 port of `tashikame.aozora`
  (ADR-2607110200) — see that namespace's docstring for the full design rationale.

  I/O is injected: an http-fn (default JDK java.net.http, no dependency) and a JSON pair
  passed by the caller, so this namespace stays dependency-free.

  Publication is kawaraban's own SPEECH (ADR-2606281500, autonomous-by-default) — bounded by
  the structural gates already enforced upstream (route/validate + cell state machines), NOT
  by a per-post operator/Council approval."
  (:require [kotoba.lang.text :as str]
            [kawaraban.cacao :as cacao]
            [kawaraban.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]
           [java.util UUID]))

(def default-pds
  "Matches the RAD identity journal's pre-recorded :rad/aozora-pds (tx 4)."
  "https://pds.aozora.app")

(def ^:private shared-client
  "One pooled HttpClient (not a fresh one per call -- ADR-2607110200 addendum 2) with an
  explicit request timeout. Discovered the hard way: the pre-fix version built a bare
  `(HttpClient/newHttpClient)` with NO `.timeout(...)` on the request, unlike
  `kawaraban.methods.live-fetch/jvm-http-get`'s already-explicit 10s -- a single
  unresponsive createRecord/createSession call against the shared operator graph (which
  can legitimately take up to ~2 minutes under heavy novelty load, see that ADR's
  addendum 1) had no client-side ceiling at all, so a genuinely stuck request hung the
  whole batch indefinitely instead of failing loudly and letting the caller move on to
  the next outlet. 120s is comfortably above the worst observed real latency (~130s was
  the pre-fold extreme; steady state is single digits) while still bounded."
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 20))
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.build)))

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.timeout (Duration/ofSeconds 120))
                   (.build))
          resp (.send shared-client req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn mint-session!
  "app-aozora-pds auth (self-sovereign CACAO): mint a CACAO for `identity`'s OWN did:key,
  exchange it at createSession for an HS256 session JWT. The PDS enforces session DID ==
  repo DID, so every subsequent createRecord under this session is scoped to `identity`
  alone — this is the one seam that makes per-actor (per-outlet) identities work: each
  identity mints its OWN session, never a shared/server-held one (G7 no-server-key)."
  [{:keys [pds identity json-write json-read http-fn] :or {pds default-pds http-fn jvm-http-fn}}]
  (let [now   (str (Instant/now))
        graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
        cacao (cacao/mint identity
                          {:cap :cap/transact :scope graph}
                          {:aud pds :nonce (str (UUID/randomUUID))
                           :issued-at now
                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                        :method  :post
                        :headers {"Content-Type" "application/json"}
                        :body    (json-write {:cacao cacao})})
        sbody (json-read (:body sess))
        jwt   (get sbody "accessJwt")]
    (when-not (and (= 200 (:status sess)) jwt)
      (throw (ex-info "aozora createSession failed" {:status (:status sess) :body (:body sess)})))
    jwt))

(defn create-record!
  "One com.atproto.repo.createRecord call under an already-minted `jwt` session for
  `identity`. `record` may carry :rkey (default \"self\") and :collection (default
  publisher/collection) — both are stripped from the body before send."
  [{:keys [pds identity json-write json-read http-fn] :or {pds default-pds http-fn jvm-http-fn}}
   jwt record]
  (let [now   (str (Instant/now))
        coll  (or (:collection record) publisher/collection)
        rec   (-> (dissoc record :rkey :collection)
                  (assoc :createdAt now :actor (:did identity)))
        resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                        :method  :post
                        :headers {"Content-Type" "application/json"
                                  "Authorization" (str "Bearer " jwt)}
                        :body    (json-write {:repo       (:did identity)
                                              :collection coll
                                              :rkey       (or (:rkey record) "self")
                                              :record     rec})})
        rbody (json-read (:body resp))]
    (when-not (= 200 (:status resp))
      (throw (ex-info "aozora createRecord failed" {:status (:status resp) :body (:body resp)})))
    {:uri (get rbody "uri") :cid (get rbody "cid")}))

(defn set-profile!
  "Create/replace this identity's app.bsky.actor.profile (rkey \"self\") — the human-facing
  displayName/description shown on its aozora.app profile page. Used so a per-outlet mirror
  actor visibly self-identifies as an automated mirror (G9 mirror-not-impersonation), never
  as the outlet itself."
  [opts display-name description]
  (let [jwt (mint-session! opts)]
    (create-record! opts jwt
                    {:collection "app.bsky.actor.profile"
                     :rkey "self"
                     :$type "app.bsky.actor.profile"
                     :displayName display-name
                     :description description})))

(defn aozora-publisher
  "Returns a `kawaraban.publisher/Publisher` that creates kawaraban article/issue records on
  the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :json-write  :json-read  injected JSON fns (e.g. clojure.data.json/write-str / read-str)
    :http-fn     optional override (default jvm-http-fn)
  One session is minted per publish! call (not cached) — see `mint-session!`'s docstring for
  why that's what makes per-actor identities safe to use independently of one another."
  [{:keys [identity json-write json-read] :as opts}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      (let [jwt (mint-session! opts)]
        (create-record! opts jwt record)))))
