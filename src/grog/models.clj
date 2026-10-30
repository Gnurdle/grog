(ns grog.models
  "Read/write of the :llm (model/provider) section of grog.edn for the Models
  settings tab, plus fetching the available model lists from OpenRouter and a
  local Ollama server. Writes are atomic and preserve every other top-level key."
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.platform :as platform]
            [grog.providers :as providers])
  (:import (java.net URL)))

;; Defined further down (the context-window section), but `save-model-url!` — which
;; lives up here with the other writers — drops a stale cached window when the
;; transport changes, so the name is declared before its definition.
(declare forget-context-window! endpoint-context-window context-budget context-budget-for
         remember-context-window!
         local-url? local-transport?
         provider-prefix-for-url slot-info live-model-id clear-model! choices choice-for)

(defn- grogedn-file
  "The user's grog.edn — the CONFIG HOME, the same file `grog.config` reads.

  This deliberately does NOT use `./grog.edn`. It once did, which meant a model
  save from the settings GUI wrote into whatever directory grog happened to run
  from — for a packaged client that is `resources/` INSIDE the app bundle, which
  on an AppImage mount (and a per-machine Windows install) is READ-ONLY, so the
  save died with \"Read-only file system\"; and even when it succeeded it wrote a
  file nothing reads, so the model silently never changed."
  ^java.io.File []
  (io/file (platform/config-home-dir) "grog.edn"))

(defn- read-map
  "Whole grog.edn map (best effort); nil if unreadable/missing."
  []
  (try
    (let [f (grogedn-file)]
      (when (.exists f)
        (edn/read-string {:readers *data-readers*} (slurp f))))
    (catch Throwable _ nil)))

(defn- persist!
  "Atomically write `(f whole-grog.edn-map)` back to the config-home grog.edn,
  preserving every other top-level key. Creates the config home if it does not
  exist yet (fresh install).

  Returns the map it WROTE (not the value read back from disk), so a caller that
  needs to report what it saved does not have to re-read the file.

  It also RELOADS `grog.config`. That used to be left to each caller, and the ones
  that forgot were the bug: `save-model-url!` wrote `[:llm :url]` and left the
  cached config reading the OLD url, so the very next step — resolving the pick
  that had just been saved (`resolve-llm`, which reads `config/llm-url`), pushing
  the recents row, labelling the status line — worked from a transport the user
  had already replaced. The config file said Strata; the session still believed
  OpenRouter. Doing it here means the cache and the file cannot disagree, whichever
  writer ran."
  [f]
  (let [existing (or (read-map) {})
        updated (f existing)
        file (grogedn-file)
        tmp (io/file (str file ".tmp"))]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit tmp (with-out-str (pp/pprint updated)))
    (io/copy tmp file)
    (when (.exists tmp) (.delete tmp))
    (try (config/reload!) (catch Throwable _ nil))
    updated))

(defn llm-config
  "The current :llm map from grog.edn (empty if absent)."
  []
  (or (:llm (read-map)) {}))

(defn save-llm!
  "Persist the whole :llm map into grog.edn atomically, preserving other keys."
  [m]
  (persist! #(assoc % :llm m))
  m)

(defn save-fields!
  "Merge `updates` (a map of top-level :llm keys) into the saved config."
  [updates]
  (save-llm! (merge (llm-config) updates)))

(defn save-profile!
  "Add/replace a named profile under :llm :profiles."
  [name profile]
  (save-llm! (assoc-in (llm-config) [:profiles (keyword name)] profile)))

(defn remove-profile!
  "Remove a named profile from :llm :profiles."
  [name]
  (save-llm! (update (llm-config) :profiles #(dissoc (or % {}) (keyword name)))))

(defn profile-names
  "Sorted names of configured profiles."
  []
  (sort (map name (keys (or (:profiles (llm-config)) {})))))

(defn save-model!
  "Persist `m` as the single model id in grog.edn atomically, preserving every
  other key: `[:llm :model]`.

  The id is the RAW, transport-relative catalog id (a plain
  `moonshotai/kimi-k3` under an openrouter URL), so it resolves against
  `[:llm :url]`."
  [m]
  (persist! #(assoc-in % [:llm :model] (str m)))
  m)

(defn save-model-url!
  "Persist the model TRANSPORT url in grog.edn atomically: `[:llm :url]`.

  The cached context window belongs to the OLD endpoint, so a change of url drops
  it (`forget-context-window!`) and the next budget asks the new endpoint what it
  accepts — a local server started at 32k must not inherit a remote 1M window."
  [url]
  (let [u (some-> url str str/trim not-empty)
        old (try (config/llm-url) (catch Throwable _ nil))]
    (persist! (fn [m]
                (if u
                  (assoc-in m [:llm :url] u)
                  (update m :llm (fn [cur] (dissoc (or cur {}) :url))))))
    (when (and old (not= (str/trim (str old)) (str u)))
      (forget-context-window! old))
    url))

(defn save-provider!
  "Persist the chosen provider `id` in grog.edn atomically: `[:llm :provider]`
  (or remove it when `id` is blank). This is a LABEL for the picker, not the
  transport — `:llm :url` remains the single source of truth for where a turn
  posts. Stored so a pick that also chose a port can be shown back as the exact
  selection (see `grog.config/llm-provider`)."
  [id]
  (let [p (some-> id str str/trim not-empty)]
    (persist! (fn [m]
                (if p
                  (assoc-in m [:llm :provider] p)
                  (update m :llm (fn [cur] (dissoc (or cur {}) :provider))))))
    id))

(defn provider-for-endpoint
  "The provider id a configured base URL belongs to.

  An EXPLICIT `:llm :provider` wins — but only when it actually MATCHES `url`
  (same host), which is what makes it trustworthy: it is the user's own pick and
  the only thing that can distinguish two local servers of the same kind (two
  Strata launches differ only by port, and the port is not in the catalogue), yet
  a stale id must never label an endpoint it does not point at. When there is no
  explicit id — or it belongs to another host — the catalogue/host guess is used.
  Never throws."
  [url]
  (let [explicit (try (config/llm-provider) (catch Throwable _ nil))]
    (or (when (and explicit (providers/host-matches? explicit url))
          explicit)
        (provider-prefix-for-url url))))

(defn set-provider!
  "Apply a provider pick to grog.edn and return the resulting model info.

  `pick` is `{:id <catalogue id>}` / `{:url <explicit base>}`, optionally with
  `:port` (a local server the user runs on a chosen port) and `:model`. This is
  the SINGLE implementation shared by the server's `set-provider` RPC and the
  CLI's `/provider`, so the GUI and the console cannot drift.

  Rules, in order:
    * the transport url is always written (`save-model-url!`), port included;
    * an explicit `:model` wins; else the endpoint is asked what it serves
      (`live-model-id`, whose hint is the catalogue `:sample`);
    * when the transport CHANGED and nothing names a model, the old id is
      DROPPED rather than paired with the new endpoint (a foreign id on a local
      port looks deliberate and cannot work — the \"bogus model\" report);
    * the provider id is persisted only when the user actually chose one, so a
      plain url edit does not stamp a derived guess into the file.

  Never throws for a blank pick; returns nil then."
  [pick]
  (let [{:keys [id url port model]} (or pick {})
        entry (when id (try (providers/by-id id) (catch Throwable _ nil)))
        old-url (try (config/llm-url) (catch Throwable _ nil))
        base (some-> (or url (:url entry)) str str/trim not-empty)
        ;; A PORT is only meaningful for a LOCAL server. Applying one to a remote
        ;; base would fabricate `openrouter.ai:8081` — a stale port in the field
        ;; must never do that, so a non-loopback base ignores it.
        with-port (when base
                    (if (providers/loopback-url? base)
                      (providers/url-with-port base port)
                      base))
        new-url (some-> with-port str str/trim not-empty)]
    (when (str/blank? (str new-url))
      (throw (ex-info "set-provider needs :id or :url" {:id id :url url})))
    (save-model-url! new-url)
    (let [new-model (or (some-> model str str/trim not-empty)
                        (live-model-id new-url (:sample entry)))]
      (cond
        (some-> new-model str str/trim not-empty)
        (save-model! new-model)

        (not= (some-> old-url str str/trim) (some-> new-url str str/trim))
        (clear-model!)))
    ;; persist the provider LABEL only when the user picked one by id
    (when (some-> id str str/trim not-empty)
      (save-provider! id))
    (slot-info nil)))

(defn save-eca-model!
  "Persist `m` as the model id (`[:llm :model]`) in grog.edn atomically — the
  GLOBAL default for the GUI's chat.

  Called only when the user ticks \"set as global default\" in the models dialog
  (`grog.client.loop`'s `set-model-fn` with `:global true`). A plain pick writes
  the PROJECT manifest instead (`grog.projects/set-project-model!`), so one
  project's choice never rewrites every other project (or every future session)."
  [m]
  (save-model! m))

;; --- recents: the LRU shortlist ---------------------------------------------
;;
;; The picker used to be the ONLY way to change models, and it is a full
;; catalogue: every model the endpoint advertises, plus a search box. That is the
;; right tool for FINDING a model you have never used, and the wrong one for
;; getting back to the two or three you switch between. `recents` is that
;; shortlist — an LRU list in grog.edn (`:llm :recents`), newest first, capped at
;; `:llm :recents-max` (default 10).
;;
;; LRU, not MRU-only: a pick you use again moves back to the FRONT, so the list
;; stays ordered by use rather than by first-seen, and the entry that falls off
;; the end is the one you have not touched for longest. That is the whole rule —
;; there is no second list and no per-provider bookkeeping.

(defn- reload-config!
  "Re-read grog.edn so a write made through this namespace is visible to
  `grog.config` immediately. The config is a cached snapshot, so a persisted
  change is invisible to `config/recents` (and everything else) until this runs.
  Best effort — a bad config must never fail the caller."
  []
  (try (config/reload!) (catch Throwable _ nil)))

(defn clear-model!
  "REMOVE `[:llm :model]` from grog.edn, leaving the transport in place.

  This is the honest answer for a transport pick that cannot name a model — a
  local server that is not running, and a catalogue entry that ships no `:sample`
  (see `live-model-id`). The alternative, keeping the PREVIOUS model, is not
  neutral: that id belonged to the OLD transport, so the config would pair, say,
  a remote model id with a local endpoint. Nothing works, the picker shows a
  confident-looking id, and the failure names the model rather than the pairing.
  A blank is visibly blank — the user sees there is a model to choose.

  Returns nil (the model is now unset)."
  []
  (persist! #(update % :llm (fn [cur] (dissoc (or cur {}) :model))))
  (reload-config!)
  nil)

(defn push-recent!
  "Record a `[url model]` pick at the FRONT of `:llm :recents`, de-duplicating and
  capping at `:llm :recents-max`.

  Re-picking an entry already in the list MOVES it to the front rather than
  adding a second copy — that is what makes the cap an LRU bound and not a
  first-in-first-out one. Returns the new recents list."
  ([url model] (push-recent! url model nil))
  ([url model provider]
   (let [u (some-> url str str/trim not-empty)
         m (some-> model str str/trim not-empty)]
     (if (or (nil? u) (nil? m))
       (try (config/recents) (catch Throwable _ []))
       (let [entry (cond-> {:url u :model m}
                     (some-> provider str str/trim not-empty)
                     (assoc :provider (str/trim (str provider))))
             capped (fn [xs] (vec (take (max 0 (long (config/recents-max))) xs)))
             written (persist!
                      (fn [cfg]
                        (let [cur (get-in cfg [:llm :recents])
                              cur (if (sequential? cur) (vec cur) [])
                              kept (remove #(and (map? %)
                                                 (= u (some-> (:url %) str str/trim not-empty))
                                                 (= m (some-> (:model %) str str/trim not-empty)))
                                           cur)]
                          (assoc-in cfg [:llm :recents] (capped (into [entry] kept))))))]
         (reload-config!)
         (vec (get-in written [:llm :recents])))))))

(defn forget-recent!
  "Drop a `[url model]` entry from `:llm :recents`. Returns the new recents list."
  [url model]
  (let [u (some-> url str str/trim not-empty)
        m (some-> model str str/trim not-empty)]
    (if (or (nil? u) (nil? m))
      (try (config/recents) (catch Throwable _ []))
      (let [written (persist!
                     (fn [cfg]
                       (let [cur (get-in cfg [:llm :recents])
                             cur (if (sequential? cur) (vec cur) [])]
                         (assoc-in cfg [:llm :recents]
                                   (vec (remove #(and (map? %)
                                                      (= u (some-> (:url %) str str/trim not-empty))
                                                      (= m (some-> (:model %) str str/trim not-empty)))
                                                cur))))))]
        (reload-config!)
        (vec (get-in written [:llm :recents]))))))

(defn clear-recents!
  "Empty `:llm :recents`. Returns the (now empty) list."
  []
  (let [written (persist! #(assoc-in % [:llm :recents] []))]
    (reload-config!)
    (vec (get-in written [:llm :recents]))))

;; --- fetching available models ----------------------------------------------
;;
;; There is deliberately no per-provider fetcher here. The configured provider is
;; listed from its own OpenAI-compatible `GET <:llm :url>/models`, and ollama from
;; its `/api/tags`. Nothing in this section names a specific host.

(declare fetch-provider-models)

(def ^:private placeholder-model-ids
  "Ids that NAME NO MODEL — they are placeholders a catalogue put there to have
  something to show. `local-model` is the one that has bitten: it is llama.cpp's
  and Strata's documented-ish stand-in, it is shipped as `:sample` by several
  local entries, and no running server answers to it.

  Persisting one of these as the model is WORSE than persisting nothing: the
  config then looks configured, the picker and the status line both show a
  confident id, and every turn fails against the endpoint — with a message about
  the model, not about the placeholder. A blank is honest and visible."
  #{"local-model" "localmodel" "model" "local" "default" "unset" "none" "n/a"})

(defn placeholder-model-id?
  "True when `m` is a placeholder rather than a model id (see
  `placeholder-model-ids`). Case- and whitespace-insensitive."
  [m]
  (let [s (some-> m str str/trim not-empty)]
    (boolean (and s (contains? placeholder-model-ids (str/lower-case s))))))

(defn live-model-id
  "The id an endpoint ACTUALLY serves, for a pick that did not name one.

  The provider catalogue's `:sample` is a hint, not a contract — it drifts, and
  for a machine-local server it is often a placeholder nobody ever registered
  (`:strata` ships `\"local-model\"`, which Strata 0.1.50 does NOT answer to:
  with no `aliases` in its launch config every request naming it is queued
  against the loaded model and stalls). Persisting that placeholder as the
  slot's model id is how a slot ends up pointing at an id the endpoint cannot
  resolve.

  So: ask the endpoint what it serves (`GET <url>/models`) and take the first id.
  When it does not answer, the HINT is only usable for a REMOTE endpoint, where
  the catalogue's `:sample` is a real catalog id. For a LOCAL endpoint an
  unreachable server means the id is genuinely unknown — a local launch serves
  whatever it was started with, and there is no generic id to guess — so the
  answer is nil, i.e. LEAVE IT BLANK. A placeholder hint is never honoured, local
  or not. Never throws."
  [url hint]
  (let [live (some-> (try (first (fetch-provider-models url)) (catch Throwable _ nil))
                     str str/trim not-empty)
        h (some-> hint str str/trim not-empty)]
    (cond
      live                      live
      (placeholder-model-id? h) nil
      (local-url? url)          nil
      :else                     h)))

(defn slot-live-model-id
  "`live-model-id` for the configured transport (`config/llm-url`)."
  [_slot hint]
  (live-model-id (try (config/llm-url)
                      (catch Throwable _ nil))
                 hint))

(defn fetch-provider-models
  "The model ids the CONFIGURED provider advertises, via its OpenAI-compatible
  `GET <url>/models`.

  With no argument, `url` is `config/llm-url`; pass a url to list a different
  endpoint (a recents entry's provider, say).

  There is nothing provider-specific here on purpose: openrouter, fireworks, a
  self-hosted server and everything else in between expose that endpoint and the
  `{:data [{:id …}]}` shape, so the same call works for all of them. The
  Authorization header is sent when a key resolves (a public listing ignores it).

  Best effort: `[]` on failure, offline, or an unparseable body."
  ([] (fetch-provider-models (try (config/llm-url) (catch Throwable _ nil))))
  ([url]
   (try
     (let [base (-> (str url) str/trim (str/replace #"/+$" ""))
           key  (try (config/llm-api-key) (catch Throwable _ nil))
           resp (http/get (str base "/models")
                          (cond-> {:as :json :throw-exceptions false
                                   :socket-timeout 15000 :conn-timeout 5000}
                            (seq key) (assoc :headers {"Authorization" (str "Bearer " key)})))]
       (->> (get-in resp [:body :data])
            (keep (fn [m] (some-> (:id m) str str/trim not-empty)))
            (distinct)
            (sort)))
     (catch Throwable _ []))))

;; --- the endpoint's real context window --------------------------------------
;;
;; THE BUG THIS EXISTS FOR. grog's context budget was a CONSTANT: `:llm
;; :max-context-tokens` defaulted to 200000 (grog.config) and 120000 in the
;; loop's own fallback. Neither number knows what the endpoint will accept. Point
;; grog at a local server serving a 32k window and the budget is still 200k, so
;; the loop happily builds a 38k-token prompt and the server answers
;;   400 invalid request: request exceeds the context window: prompt (38683
;;       tokens) leaves no room to answer in the context (32768);
;;       requests are never truncated
;; (Strata, launch-iq3_s-32k.server.log — the user's own report). grog's
;; compaction never fired, because its budget was three times the window.
;;
;; The fix is to ASK. Both OpenAI-compatible servers grog talks to report the
;; window, in two shapes:
;;   * `/models`  -> `{:data [{:id … :meta {:n_ctx N}}]}`   (Strata, llama.cpp)
;;                   or `{:data [{:context_length N}]}`     (OpenRouter-shaped)
;;   * `/props`   -> `{:default_generation_settings {:n_ctx N}}` (llama.cpp)
;; The value is cached per (url, MODEL) — not per url. A catalogue endpoint
;; (OpenRouter, Fireworks) serves hundreds of models with wildly different
;; windows over one url, so a url-keyed cache would answer for the wrong model
;; the moment the user switches — the original bug in a smaller box.
;;
;; The cache is also PERSISTED (see `windows-file`), because the second way to
;; learn a window is a real 400 that names it, and a number learned that way is
;; worth keeping across restarts: it is the provider's own word, not a guess.
;;
;; AND WHEN NOTHING IS KNOWN, NOTHING IS GUESSED. `context-budget` returns nil,
;; which means "no proactive trim" — the conversation goes out intact and the
;; loop's overflow RECOVERY (grog.agent.loop) handles a refusal by distilling and
;; re-sending the same step, which also teaches this cache the real ceiling. A
;; default number here would be the original bug re-planted.

(defonce ^:private window-cache* (atom nil))   ; nil = not yet loaded from disk

(defn- pos-int
  "`v` as a positive long, or nil. Rejects negatives, non-numbers and booleans —
  `(pos? (long true))` throws, and a junk field must not."
  [v]
  (when (and (number? v) (not (boolean? v)) (pos? (long v)))
    (long v)))

(defn- window-key
  "The cache key for a (url, model) pair, normalised so the same endpoint spelled
  with or without a trailing slash is the same endpoint."
  [url model]
  [(str/replace (str/trim (str url)) #"/+$" "")
   (some-> model str str/trim not-empty)])

(defn- windows-file
  "Where learned windows live: `context-windows.edn` in the config home. Separate
  from grog.edn on purpose — this is a CACHE of observed facts (and may be
  deleted at any time), not user configuration."
  ^java.io.File []
  (io/file (platform/config-home-dir) "context-windows.edn"))

(defn- load-windows!
  "The persisted window cache, read once. Best effort: a missing or corrupt file
  is an empty cache, never an error — a cache must not be why grog fails to start."
  []
  (or @window-cache*
      (let [m (try
                (let [f (windows-file)]
                  (when (.exists f) (edn/read-string (slurp f))))
                (catch Throwable _ nil))
            m (when (map? m) m)]
        (reset! window-cache* (or m {}))
        @window-cache*)))

(defn- save-windows!
  "Persist the cache. Best effort — a read-only config home loses the cache, not
  the turn."
  []
  (try
    (let [f (windows-file)]
      (when-let [parent (.getParentFile f)] (.mkdirs parent))
      (spit f (with-out-str (pp/pprint @window-cache*))))
    (catch Throwable _ nil)))

(defn- entry-id [e] (some-> (or (:id e) (:model e) (:name e)) str str/trim not-empty))

(defn parse-window-response
  "The context window a `/models` or `/props` BODY reports for `model`, or nil.

  Pure — takes a parsed body — so the parser is testable with no server. Two
  shapes matter, and WHICH ENTRY they come from matters as much as the field:
    {:data [{:meta {:n_ctx N}}]}                Strata/llama.cpp — a real window
    {:data [{:context_length N}]}               OpenRouter-shaped catalogue
    {:default_generation_settings {:n_ctx N}}   /props — one server, one model

  A CATALOGUE IS NOT A SERVER. OpenRouter's `/models` lists ~468 models, each
  with its OWN `context_length`; taking the first entry's would clamp grog's
  budget to some unrelated model's window — the fix would be worse than the bug.
  So `model` selects the entry, with one deliberate exception: when the endpoint
  lists EXACTLY ONE model it is a single-model local launch, and its window is
  the served one even though the configured id may be a placeholder that matches
  no id at all (Strata's `local-model`). Many entries and no id match → nil, i.e.
  do not guess."
  ([body] (parse-window-response body nil))
  ([body model]
   (when (map? body)
     (let [entries (let [d (:data body)] (when (sequential? d) (vec d)))
           m (some-> model str str/trim not-empty)
           entry (cond
                   (nil? entries)          nil
                   (= 1 (count entries))   (first entries)      ; one server, one model
                   (some? m)               (first (filter #(= m (entry-id %)) entries))
                   :else                   nil)]
       (or (-> entry :meta :n_ctx pos-int)
           (-> entry :meta :n_ctx_train pos-int)
           (-> entry :context_length pos-int)
           ;; /props has no :data at all — always the served model.
           (-> body :default_generation_settings :n_ctx pos-int)
           (-> body :n_ctx pos-int))))))

(defn- get-json
  "GET `url`, parsed as JSON, best effort: nil on any failure (offline, non-2xx,
  unparseable). Never throws — a window probe must never be why a turn died."
  [url timeout-ms]
  (try
    (let [resp (http/get url {:as :json :throw-exceptions false
                              :socket-timeout timeout-ms :conn-timeout 3000})]
      (when (and (<= 200 (long (:status resp)) 299) (map? (:body resp)))
        (:body resp)))
    (catch Throwable _ nil)))

(defn cached-context-window
  "The window already KNOWN for (url, model) — from a probe or a learned refusal —
  without going to the network. nil when nothing is known."
  [url model]
  (let [u (some-> url str str/trim not-empty)]
    (when u
      ;; `get`, NOT `get-in`: the key IS a vector ([url model]), and `get-in`
      ;; would treat it as a path and descend into the url string, returning nil
      ;; for a value that is right there.
      (get (load-windows!) (window-key u model)))))

(defn remember-context-window!
  "Record that (url, model) accepts `window` tokens. Used in two places:

  * after a successful probe (`endpoint-context-window`), and
  * after a REAL overflow whose body NAMED the limit — the provider's own number
    beats any probe, and it is how the second and every later turn knows the
    window even when the endpoint will not report one at all.

  Returns the window. Ignores junk (a nil/zero value records nothing), and never
  throws."
  [url model window]
  (let [u (some-> url str str/trim not-empty)
        w (pos-int window)]
    (when (and u w)
      (load-windows!)
      (swap! window-cache* assoc (window-key u model) w)
      (save-windows!))
    w))

(defn forget-context-window!
  "Drop the learned window for (url, model) — or, with one argument, every model
  on `url` — or, with NO argument, the whole cache. So a relaunched local server
  is re-probed rather than remembered at its old size."
  ([] (reset! window-cache* {}) (save-windows!))
  ([url]
   (let [u (some-> url str str/trim not-empty)]
     (when u
       (load-windows!)
       (swap! window-cache* (fn [m] (into {} (remove (fn [[[kurl _] _]]
                                                       (= kurl (str/replace (str u) #"/+$" "")))
                                                     m))))
       (save-windows!))))
  ([url model]
   (let [u (some-> url str str/trim not-empty)]
     (when u
       (load-windows!)
       (swap! window-cache* dissoc (window-key u model))
       (save-windows!)))))

(defn endpoint-context-window
  "The context window (url, model) will accept, in tokens, or nil when it cannot
  be learned.

  Resolution order — each step is a real source, not an assumption:
    1. the CACHE (learned from a probe, or from a real overflow that named the
       limit — see `remember-context-window!`);
    2. `/models`, which carries the id AND the window in one call;
    3. `/props`, which llama.cpp serves and some launches answer more promptly
       after a restart.

  Cached per (url, MODEL): one url can serve a whole catalogue, and a window
  answered for the wrong model is the original bug wearing a smaller hat. A probe
  is repeated at most once per (url, model) per process — a nil result is cached
  too, so an endpoint that never reports a window is not probed on every turn.

  Returns nil rather than guessing; nil means 'we do not know', which the caller
  turns into 'no proactive trim, recover on evidence instead'."
  ([] (endpoint-context-window (try (config/llm-url) (catch Throwable _ nil))))
  ([url] (endpoint-context-window url (try (config/llm-model) (catch Throwable _ nil))))
  ([url model]
   (let [u (some-> url str str/trim not-empty)]
     (when u
       (load-windows!)
       (if-let [hit (cached-context-window u model)]
         hit
         (let [base (str/replace u #"/+$" "")
               probed (or (parse-window-response (get-json (str base "/models") 8000) model)
                          (parse-window-response (get-json (str base "/props") 8000) model))
               ;; Remember a nil too (as an explicit "asked, no answer") so a
               ;; silent endpoint costs one probe per session, not one per turn.
               w (or (pos-int probed)
                     (do (load-windows!)
                         (swap! window-cache* assoc (window-key u model) nil)
                         nil))]
           (when w (remember-context-window! u model w))
           w))))))

(def ^:private min-completion-reserve
  "Never reserve less than this for the answer — a prompt that fills the window
  to the last token leaves the model no room to reply, which is the SAME 400
  from the other side (`prompt (...) + max tokens (...) exceeds the context`)."
  2048)

(def ^:private max-completion-reserve
  "…and never more than this, or a large window would have its usable prompt
  budget needlessly halved."
  32768)

(defn completion-reserve
  "Room to leave for the ANSWER in a window of `window` tokens.

  Uses `:llm :max-tokens` when the user set one (that is the reservation they
  asked for), else an eighth of the window clamped to
  [min-completion-reserve, max-completion-reserve] — so a 32k window reserves
  4k and a 131k window reserves 16k, both comfortably above what a streamed
  tool-call turn needs."
  [window]
  (let [w (pos-int window)
        configured (try (config/llm-max-tokens) (catch Throwable _ nil))]
    (cond
      (pos-int configured) (min (long configured) (max 1 (long (- w min-completion-reserve))))
      (nil? w) min-completion-reserve
      :else (-> (quot w 8)
                (max min-completion-reserve)
                (min max-completion-reserve)))))

(defn clamp-budget
  "`budget` tokens, lowered to fit inside `window` once `reserve` is held back.

  Pure, so the arithmetic is testable: a nil window or a nil budget passes
  through unchanged (that is the 'we could not learn the window, keep exactly
  the old behaviour' path), and the result is never below 1."
  [budget window reserve]
  (let [b (pos-int budget)
        w (pos-int window)]
    (cond
      (nil? b) nil
      (nil? w) b
      :else (max 1 (min (long b) (- (long w) (long (or reserve 0))))))))

(defn context-budget
  "The context budget a turn should actually use, in tokens — DERIVED, not
  configured.

    window KNOWN  -> window - (room for the answer), i.e. `clamp-budget` of the
                     window. This is the per-model number the user asked for: a
                     32k server gets ~28k, a 1M model gets ~1M, with nothing to
                     edit in grog.edn when the model changes.
    window UNKNOWN -> nil, and nil means NO PROACTIVE TRIM. That is deliberate.
                     A guessed number is what caused the original failure two
                     ways: too big (a 200k budget against a 32k window → a hard
                     400) and too small (a 200k budget silently eliding the
                     middle of a 1M-token conversation). When we do not know, we
                     send the conversation intact and let `loop` recover on the
                     first REAL overflow — which also TEACHES the window
                     (`remember-context-window!`), so the guess is never made
                     twice.

  `:llm :max-context-tokens` is an OVERRIDE for the user who wants a smaller
  budget than the window allows (to bound cost, say). It can only LOWER the
  budget, never raise it past the endpoint's window, and it is never a default:
  unset means 'derive it'.

  `fallback` exists ONLY so an old call site with a hardcoded number keeps
  compiling; it is the LAST resort, used only when the window is unknown AND no
  override is set. New code should call `(context-budget)` and treat nil as
  'unknown, do not guess'. Callers that pass no fallback get nil in that case.

  Never throws."
  ([] (context-budget nil))
  ([fallback]
   (context-budget-for (try (config/llm-url) (catch Throwable _ nil))
                       (try (config/llm-model) (catch Throwable _ nil))
                       fallback)))

(defn context-budget-for
  "`context-budget`, for an EXPLICIT (url, model).

  This exists because a SESSION pins its own transport: the model a user picked
  can differ from the file config (that is the whole point of `session-llm`'s
  pinning), and a budget derived from the config's endpoint would then describe a
  model the turn is not talking to. Ask about the transport the turn will POST to.

  Same rules: the window (minus room to answer) clamped by any override, or nil
  when the window is unknown. Never throws."
  ([url model] (context-budget-for url model nil))
  ([url model fallback]
   (let [override (try (config/max-context-tokens) (catch Throwable _ nil))]
     (try
       (let [window (endpoint-context-window url model)
             cap (when window (clamp-budget window window (completion-reserve window)))]
         (cond
           (and override cap) (min (long override) (long cap))
           override (long override)
           cap cap
           :else (pos-int fallback)))
       (catch Throwable _ (or override (pos-int fallback)))))))

(defn fetch-ollama-models
  "Return a sorted list of local Ollama model names (best effort; empty if Ollama
  isn't running)."
  []
  (try
    (let [base (config/ollama-host)
          resp (http/get (str base "/api/tags")
                         {:as :json :throw-exceptions false :socket-timeout 5000 :conn-timeout 3000})]
      (->> (:models (:body resp))
           (map :name)
           (remove nil?)
           (sort)))
    (catch Throwable _ [])))

;; --- ECA model id qualification --------------------------------------------
;;
;; grog talks to ECA over JSON-RPC, and ECA resolves every model by its
;; `provider/name` prefix (see `full-model->provider+model` in ECA's shared.clj).
;; A bare id like `qwen3.5:4b-tweaked` or an un-prefixed OpenRouter catalog id
;; like `moonshotai/kimi-k3` makes ECA treat the whole string — or `moonshotai`
;; — as the provider, then fail with:
;;   "API url not found. Make sure you have provider '<x>' configured properly."
;; These helpers consistently qualify ids before grog sends them to ECA.

(def ^:private eca-provider-segments
  "Provider prefixes ECA can resolve natively (must match ECA's provider names)."
  #{"ollama" "openrouter" "moonshot" "openai" "anthropic" "google" "xai"
    "deepseek" "github-copilot" "litellm" "lmstudio" "mistral" "azure"
    "bedrock" "z-ai"})

;; Fully provider-qualified model ids ECA knows about, populated from its
;; `config/updated` notification (`:chat :models`). Lets grog qualify a raw id by
;; exact/match against real ECA models instead of guessing — in particular it
;; disambiguates OpenRouter catalog orgs that collide with native provider names
;; (`deepseek/deepseek-v4-flash-0731` ⇒ `openrouter/deepseek/deepseek-v4-flash-0731`).
(defonce eca-model-catalog* (atom nil))

(defn register-eca-catalog!
  "Record the latest ECA model catalog (a seq of `provider/model` strings)."
  [model-ids]
  (let [ids (keep #(when (seq (str/trim (str %))) (str/trim (str %))) model-ids)]
    (reset! eca-model-catalog* (set ids)))
  model-ids)

;; Per-model IMAGE-INPUT capability, from ECA's `providers/list` (or the
;; `providers/updated` notification). ECA's `config/updated` carries only model
;; IDS, so this is the only source that says whether a model can actually SEE an
;; image. Used to warn a user who attaches an image to a text-only model, where
;; ECA would otherwise drop it silently.
(defonce ^:private vision* (atom {}))

(defn register-provider-vision!
  "Record image-input capability from an ECA providers payload — a map
  `{:providers [{:id <provider> :models [{:id <model> :capabilities {:vision bool}}]}]}`
  (the shape of both `providers/list` and `providers/updated`). Returns the map
  of `<provider>/<model>` → boolean it recorded."
  [providers-result]
  (let [m (into {}
                (for [{pid :id :keys [models]} (:providers providers-result)
                      {mid :id :keys [capabilities]} models
                      :when (and pid mid)]
                  [(str pid "/" mid) (boolean (:vision capabilities))]))]
    (when (seq m) (reset! vision* m))
    m))

(defn vision-for
  "`true`/`false` when ECA reported image input for `model-id`, `nil` when
  unknown (no providers payload yet, or the id doesn't match)."
  [model-id]
  (get @vision* (some-> model-id str)))

(defn provider-prefix-for-url
  "Guess the ECA provider prefix from an OpenAI-compatible base URL, or nil.

  The shipped catalogue (`grog.providers`) is consulted FIRST: a base that
  matches a known entry takes that entry's canonical id, which is the only way
  a host like `integrate.api.nvidia.com` (-> `nvidia`, not `integrate`) or
  `api.z.ai` (-> `zai`) comes out right. Anything the catalogue does not know
  falls back to the host heuristic, so ANY OpenAI-compatible endpoint still
  works — grog generates the provider entry from `:llm :url` (see
  `grog.eca-config/ensure-provider`), it only has to be named consistently:

      https://api.fireworks.ai/inference/v1  ->  fireworks
      https://openrouter.ai/api/v1           ->  openrouter
      http://localhost:11434/v1              ->  ollama

  Public because `grog.eca-config` uses it to qualify a model that came from the
  `:llm` block: `:llm :model` is a RAW catalog id, and its provider is whatever
  `:llm :url` points at."
  ^String [url]
  (when url
    (let [u (str/lower-case (str url))]
      (or
       ;; The catalogue knows this base — take its canonical id before the host
       ;; heuristic can mangle it. Matched with OR without the port so a second
       ;; local server of a known kind (Strata on 8081) is still `strata`.
       (providers/id-for-endpoint url)
       (cond
         (or (str/includes? u "11434")
             (str/includes? u "localhost")
             (str/includes? u "127.0.0.1")) "ollama"
         (str/includes? u "openrouter.ai") "openrouter"
         (str/includes? u "api.kimi.com") "moonshot"
         (str/includes? u "api.deepseek.com") "deepseek"
         (str/includes? u "api.anthropic.com") "anthropic"
         (str/includes? u "api.openai.com") "openai"
         (str/includes? u "generativelanguage.googleapis.com") "google"
         (str/includes? u "api.x.ai") "xai"
         :else nil)
       ;; Unknown host — name the provider after it, minus the noise labels:
       ;;   api.fireworks.ai        -> fireworks
       ;;   inference.acme-llm.com  -> acme-llm
       ;; IP literals are left alone (no sensible provider name in "192").
       (let [host (some-> (re-find #"(?i)^[a-z][a-z0-9+.-]*://(?:[^@/]+@)?([^/:?#]+)"
                                   (str url))
                          second)]
         (when (and host (seq host) (not (re-matches #"\d+(\.\d+){3}" host)))
           (let [label (->> (str/split (str/lower-case host) #"\.")
                            (remove #(contains? #{"api" "www" "inference" "v1" "openai-compatible"} %))
                            first)]
             (when (and label (re-matches #"[a-z][a-z0-9-]*" label))
               label))))))))

(defn qualify-eca-model
  "Translate a raw model id — as stored in grog.edn, picked from a transport, or
  typed by the user — into the provider-qualified id ECA understands
  (`provider/model`).

  Rules, in order, when the input isn't blank:
  1. Exactly matches a model in ECA's catalog → returned unchanged.
  2. An explicit `source` wins: the picker transport (`ollama` / `openrouter`),
     or the provider grog derived from `:llm :url`. A raw catalog id must be
     scoped to it, even when its first segment collides with a native provider
     name (`deepseek/…`).
  3. ECA's catalog knows it as `openrouter/<id>` or `ollama/<id>` → prefixed.
  4. The id already carries a known ECA provider prefix → returned unchanged.
  5. Otherwise it is scoped to the provider `:llm :url` points at — whatever
     host that is (openrouter.ai, api.fireworks.ai, a local server, …). A
     multi-segment id that merely LOOKS like a path
     (`moonshotai/kimi-k3`, `accounts/fireworks/models/…`) is a catalog id that
     lost its prefix, and gets exactly the same treatment.

  There is deliberately NO hard-coded fallback to `openrouter`: that is one host
  among many, and assuming it sent every user on another OpenAI-compatible
  endpoint to \"API url not found … provider 'openrouter'\". When the URL names
  no provider, the id is left alone rather than scoped to something wrong.

  Returns nil for blank input."
  ([model] (qualify-eca-model model nil nil))
  ([model source] (qualify-eca-model model source nil))
  ([model source url]
   (let [m (str/trim (str (or model "")))
         seg (first (str/split m #"/"))
         src (some-> source str str/lower-case)
         prov (provider-prefix-for-url url)
         catalog @eca-model-catalog*]
     (cond
       (str/blank? m) nil

       ;; already exactly what ECA exposes
       (contains? catalog m) m

       ;; An explicit provider wins over every guess. That means a picker
       ;; transport (`ollama`/`openrouter`) AND the provider grog derived from
       ;; `:llm :url` — the latter matters because rule 4 below would otherwise
       ;; read an OpenRouter org that shares a name with a native provider
       ;; (`deepseek/deepseek-v4.1-flash` ⇒ a nonexistent "deepseek" provider)
       ;; before ECA's catalogue has arrived to disambiguate it.
       (contains? eca-provider-segments src)
       (if (str/starts-with? m (str src "/"))
         m
         (str src "/" m))

       (and prov (contains? catalog (str prov "/" m)))
       (str prov "/" m)

       ;; Ollama's catalogue — only meaningful when ollama IS the provider. A
       ;; REMOTE model whose id happens to match a local one (`qwen2.5-coder:7b`
       ;; is served by OpenRouter and installed in your ollama) must not be
       ;; scoped to ollama just because your ECA lists it there.
       (and (= prov "ollama") (contains? catalog (str "ollama/" m)))
       (str "ollama/" m)

       ;; already carries a known ECA provider prefix (native provider or manual)
       (contains? eca-provider-segments (str/lower-case (str seg))) m

       ;; Everything else — a bare id, or a multi-segment catalog id that lost
       ;; its prefix — is scoped to the provider `:llm :url` points at. NOT
       ;; hard-coded to `openrouter`.
       :else
       (if prov
         (str prov "/" m)
         m)))))

;; --- cached catalogue for the model picker ----------------------------------
;;
;; The Electron settings dialog asks the server for a source's model list. The
;; OpenRouter/Ollama lists come from HTTP, and the server reads requests on a
;; single loop, so a slow fetch must never happen inside a request: that would
;; stall every other call for the length of a network timeout. Instead the
;; request answers instantly with whatever is already cached, a background
;; thread refreshes it, and the result is broadcast as a `models` notification.

;; source -> vector of model ids. nil = never fetched (an empty vector means
;; fetched, and that source really has none right now).
(defonce ^:private catalogue* (atom {}))

;; Sources with a fetch currently running, so repeated clicks cannot stack up
;; duplicate network calls.
(defonce ^:private inflight* (atom #{}))

(defn- model-url*
  "The model transport url, or nil (never throws — a config with no url at all is
  a valid, if unconfigured, state)."
  []
  (try (config/llm-url) (catch Throwable _ nil)))

(defn- model-provider
  "The provider the model transport url points at, as a name, or nil."
  []
  (provider-prefix-for-url (model-url*)))

(defn picker-source->provider
  "Translate a picker SOURCE into the PROVIDER to qualify a model with.

  The picker's sources name PLACES, not companies:
    \"local\"  -> the provider the configured transport points at when that
                  transport IS local (strata, vllm, lmstudio…), else \"ollama\"
    \"remote\" -> the provider the model transport url points at
                  (openrouter, fireworks, …)
  A provider name, nil, or anything else passes through unchanged.

  \"local\" used to mean \"ollama\" unconditionally. That mislabels every pick made
  against another local server — a Strata model recorded as an ollama one — and
  it fed the wrong provider into qualification, so the same id could be scoped to
  an endpoint the user is not even running. The configured transport is the truth
  about where a local model is; ollama is only the fallback for an unset or
  non-local transport.

  Without this the source is lost, and qualification falls back to guessing —
  which scoped a REMOTE model to ollama whenever its id happened to also exist
  in the local ollama (`qwen2.5-coder:7b` is on OpenRouter *and* in your ollama)."
  ([source] (picker-source->provider source nil))
  ([source _slot]
   (case (str source)
     "local"  (if (local-transport?)
                (or (model-provider) "ollama")
                "ollama")
     "remote" (model-provider)
     (let [s (str source)]
       (when (seq s) s)))))

(defn local-url?
  "True when `url` is a server on THIS machine — loopback only.

  A private-range address is deliberately NOT local: a model server on the LAN is
  somewhere else, and treating it as \"this machine\" would make the local tab
  list ollama's models instead of the ones that server actually serves."
  [url]
  (let [u (str/lower-case (str url))]
    (boolean (or (str/includes? u "localhost")
                 (str/includes? u "127.0.0.1")
                 (str/includes? u "[::1]")
                 (str/includes? u "0.0.0.0")))))

(defn local-transport?
  "True when the CONFIGURED transport (`:llm :url`) is a local server.

  This is what tells a `local`-tab pick apart: the id then belongs to whichever
  local server the user points at (Strata, vLLM, LM Studio, ollama…), so it must
  not be repointed at ollama's port on the assumption that `local` means ollama."
  []
  (local-url? (model-url*)))

(defn- source-fetcher
  "The fetch fn for a picker `source`, or nil when there is none.

  There are only two sources, and neither is a host name:
    :local   this machine — ollama, OR whatever local transport is configured
    :remote  the model transport — its OpenAI-compatible `/models`
  A provider NAME is still accepted, so an older client asking for \"openrouter\"
  gets the right list rather than nothing.

  `local` USED to mean ollama unconditionally, which is wrong as soon as the
  configured transport is itself local (Strata, vLLM, LM Studio…): the Local tab
  then listed a DIFFERENT server's models — usually none, since ollama is often
  not running — while the header said the endpoint was Strata. The tab a user is
  looking at must list the models of the endpoint they are pointed at, so a local
  transport is asked directly and ollama is the fallback for when it is not."
  [source]
  (let [s (name source)]
    (cond
      (= s "local")             (if (local-transport?)
                                  #(fetch-provider-models (model-url*))
                                  fetch-ollama-models)
      (= s "remote")            #(fetch-provider-models (model-url*))
      (= s (model-provider))    #(fetch-provider-models (model-url*))
      :else                     nil)))

(defn fetch-async!
  "Refresh `source` on a background thread, unless that fetch is already running.
  Calls `(done! source models)` when it lands. Returns true when a fetch was
  actually started."
  [source done!]
  (let [k (name source)]
    (if-let [fetch (source-fetcher source)]
      (if (contains? @inflight* k)
        false
        (do
          (swap! inflight* conj k)
          (future
            (let [ms (try (vec (fetch)) (catch Throwable _ []))]
              (swap! catalogue* assoc k ms)
              (swap! inflight* disj k)
              (try (done! source ms) (catch Throwable _ nil))))
          true))
      false)))

(defn catalogue
  "What the picker can show right now, keyed by SOURCE — `:local` and `:remote`.

  Each source starts as nil (never fetched). ECA's own catalogue is deliberately
  NOT a source here: which models ECA can resolve is an implementation detail of
  the agent, not somewhere a user picks a model."
  []
  (let [local? (local-url? (model-url*))
        loading (vec (sort (map name @inflight*)))
        fetched (into {} (map (fn [[k v]] [(keyword k) v])) @catalogue*)]
    (merge (if local? {:local nil} {:local nil :remote nil})
           {:loading loading}
           fetched)))

(defn model-sources
  "The picker's sources, as `[[id label] …]` — LOCAL and REMOTE.

  `local` is a server on this machine — ollama, or whatever local transport is
  configured (Strata, vLLM, LM Studio…). `remote` is the provider the model
  transport url points at: openrouter for one user, fireworks for the next — the
  LABEL does not change, because what the user is choosing is where the model
  runs, not which company hosts it.

  `remote` is offered only when there IS one to browse. grog has a single
  transport, so when that transport is already local the remote tab would list
  the SAME server again — two tabs onto one list, which is noise, and worse, it
  would label a local model as remote. Selecting a remote provider (Settings'
  provider combo) is what makes the tab appear. There is no `eca` source: that
  was an implementation detail leaking into a user-facing choice."
  []
  (if (local-transport?)
    [["local" "Local"]]
    [["local" "Local"] ["remote" "Remote"]]))

(defn slot-info
  "The picker state for a client: the transport url, the resolved model id, its
  derived provider, whether it is local, the sources it offers, and the recents
  shortlist. (Named `slot-info` for the old big/small slots, which are gone —
  there is one model now; the key is kept so an older client still finds it.)"
  [_slot]
  (let [u (model-url*)]
    {:slot "model"
     :url u
     :model (try (config/eca-model) (catch Throwable _ nil))
     ;; the EXPLICIT provider when the user chose one, else derived from the url;
     ;; `:provider` is a bare id, `:provider-label` carries the `:<port>` when the
     ;; endpoint is not on that provider's default port (two Strata servers).
     :provider (provider-for-endpoint u)
     :provider-label (let [p (provider-for-endpoint u)]
                       (when p (str p (providers/port-suffix u))))
     :port (providers/port-of-url u)
     :local? (local-url? u)
     :sources (model-sources)
     ;; WHAT THE ENDPOINT WILL ACCEPT, and what a turn will therefore use. A
     ;; client can show `context-window` beside the model — the same reason the
     ;; provider is shown: "32k" vs "1M" changes how a session must be run, and
     ;; the number is otherwise invisible until the server 400s.
     :context-window (try (endpoint-context-window u (config/eca-model)) (catch Throwable _ nil))
     :context-budget (try (context-budget-for u (config/eca-model)) (catch Throwable _ nil))
     ;; the NAME the user gave this url+model, when they gave one — the whole
     ;; point of a choice, and the thing the status line should show rather than a
     ;; url the user never chose to read.
     :choice (try (choice-for u (config/eca-model)) (catch Throwable _ nil))
     :recents (try (config/recents) (catch Throwable _ []))
     :recents-max (try (config/recents-max) (catch Throwable _ 10))
     :label (try (config/model-label {:url u
                                      :provider (provider-for-endpoint u)
                                      :model (config/eca-model)})
                 (catch Throwable _ nil))}))

;; =============================================================================
;; NAMED MODEL CHOICES — the unit a user actually selects
;; =============================================================================
;;
;; THE RULE, taken from the report it exists for: *"the baked in models should be
;; used as a template, and user needs to be able to specify/edit a URL, and give
;; that a NAME that can then be used to pick the model."*
;;
;; What made the old selection unusable is that a model was stored as a PAIR OF
;; LOOSE FIELDS — `[:llm :url]` and `[:llm :model]` — plus an LRU of those pairs
;; (`:recents`) and a map of provider presets (`:llm :profiles`). None of those
;; has a name. So the best the UI could put in front of a user was a transport
;; combo, a port box and a raw catalogue id: three controls that only make sense
;; together, no way to say "use the workstation model", and no way to keep a
;; thing you configured on purpose. Every bug this file already documents — the
;; bogus model, the port that would not stick, the recents row that switched the
;; id but not the endpoint — is that split showing itself in the UI.
;;
;; A CHOICE is one record, and it is what the user picks:
;;
;;     {:name "workstation" :url "http://localhost:8081/v1" :model "qwen3-32b"}
;;
;; The name is the key (case-insensitive, and it is what `/model <name>` takes);
;; the url is the transport; the model id is transport-relative. Selecting one
;; sets BOTH halves in one operation, because a model id and its endpoint are one
;; decision.
;;
;; The shipped catalogue (`resources/providers.edn`) is the TEMPLATE source:
;; starting from "Strata" pre-fills its base url and reads the model off the
;; running server (`live-model-id`). A template is a pre-fill, not a cage — once
;; saved, the url is the user's own text, and a choice whose url drifts away from
;; the catalogue keeps working (its derived `:provider` is simply dropped).
;;
;; `:llm :profiles` — the map the example config and every existing install
;; carries — is FOLDED IN as choices rather than migrated on disk: it holds
;; exactly the same three facts, `/model strata` must keep resolving the way it
;; always did, and the file is only rewritten when the user actually edits a row
;; (at which point the shadowed `:profiles` key is dropped in the same write, so
;; grog.edn says one thing about `strata`, not two).

(def choice-name-re
  "A choice name: a letter, then letters/digits/`-`/`_`/`.`. Deliberately
  restricted — a name is a KEY the user types into `/model <name>` and reads in a
  list, so it must be one unambiguous token, never a number, never a path."
  #"[a-z][a-z0-9._-]{0,63}")

(defn choice-name
  "`s` as a valid choice name, or nil. Trimmed, lower-cased, inner whitespace
  folded to `-` (typing `Work Mac` gives `work-mac`), validated against
  `choice-name-re`. Never throws."
  [s]
  (let [raw (some-> s str str/trim not-empty)
        s (when raw (-> raw str/lower-case (str/replace #"\s+" "-")))]
    (when (and s (re-matches choice-name-re s)) s)))

(defn- choice-url
  "A normalised endpoint url for a choice, or nil for junk.

  Trimmed; trailing slashes removed; a scheme supplied when the user typed a bare
  host (`localhost:8081/v1` — an overwhelmingly common edit). NOTHING ELSE is
  rewritten: the path is exactly the user's business, because
  `api.z.ai/api/paas/v4` and `api.groq.com/openai/v1` are not interchangeable
  with `/v1`, and a wrong path is a 404 on the first prompt."
  [s]
  (when-let [raw (some-> s str str/trim (str/replace #"/+$" "") not-empty)]
    ;; re-FIND, not re-matches: the pattern names a PREFIX (a scheme followed by
    ;; ://), and re-matches demands a whole-string match — which prefixed
    ;; `http://` onto urls that already had one and saved `http://http://host`
    ;; as the endpoint. Caught by driving the dialog's own save-choice call.
    (let [u (if (re-find #"(?i)^[a-z][a-z0-9+.-]*://" raw) raw (str "http://" raw))]
      (try (let [^java.net.URI x (java.net.URI. u)]
             (when (and (#{"http" "https"} (str/lower-case (.getScheme x)))
                        (seq (.getHost x))
                        ;; a doubled scheme (`http://http://x`) is a half-typed
                        ;; field, not an endpoint — saving it produces a
                        ;; connection failure nobody can explain.
                        (<= (count (re-seq #"://" u)) 1))
               u))
           (catch Throwable _ nil)))))

(defn normalize-choice
  "`m` as a validated choice row, or nil when it names nothing usable.

  A row needs a valid name and at least one of a usable url / a model id (the
  latter meaning \"this model on the transport already configured\"). A
  placeholder id (`local-model` and friends, see `placeholder-model-ids`) is
  dropped rather than kept: a blank model is visibly blank and fixable, a
  placeholder looks like a decision and cannot work. Junk rows — a hand-edited
  grog.edn, a half-typed dialog save — fall out here instead of becoming the
  reason a session 400s."
  [m]
  (when (map? m)
    (let [n (choice-name (:name m))
          u (choice-url (:url m))
          mid (some-> (:model m) str str/trim not-empty)]
      (when (and n (or u (and mid (not (placeholder-model-id? mid)))))
        (cond-> {:name n :url u :model (when-not (placeholder-model-id? mid) mid)}
          ;; the shipped-catalogue kind the url belongs to, when it still matches
          ;; one: what lets a row say "strata" beside a url the user moved to
          ;; another port, and what keeps the provider label honest
          u (assoc :provider (try (providers/id-for-endpoint u) (catch Throwable _ nil)))
          ;; which config key this name came from — only the legacy map is worth
          ;; saying, so the list can mark those rows
          (= "profiles" (:source m)) (assoc :legacy "profiles")
          (:api-key m) (assoc :api-key (:api-key m))
          (:legacy m) (assoc :legacy (:legacy m)))))))

(defn choices
  "Every named model choice, sorted by name and validated.

  The rows come from `grog.config/named-models` — ONE reader that folds `:llm
  :choices` (what the dialog writes) over the legacy `:llm :profiles` map (what
  the example config and every existing install carries), so the CLI's `/model
  <name>` and this list can never disagree about what a name means.

  ONE list, one vocabulary — the user sees named models; where a row came from is
  shown as `:legacy` but never changes how it is picked."
  []
  (keep normalize-choice (config/named-models)))

(defn find-choice
  "The choice named `name` (case- and whitespace-insensitive), or nil."
  [name]
  (when-let [n (choice-name name)]
    (first (filter #(= n (:name %)) (choices)))))

(defn choice-templates
  "The shipped catalogue as templates, in file order — the list a new choice
  starts from. Each carries `:url :sample :note :docs :key-env :local?` and
  whether it is the endpoint configured RIGHT NOW (so \"start from what I am
  already running\" is obvious).

  A template is a pre-fill and nothing more."
  []
  (mapv (fn [{:keys [id label kind url sample note docs key-env]}]
          {:id id :label label :kind (some-> kind name) :url url
           :sample (some-> sample str not-empty) :note note :docs docs
           :key-env key-env :local? (= :local kind)
           :current? (try (= (str (config/llm-url)) (str url))
                          (catch Throwable _ false))})
        (providers/entries)))

(defn choice-template
  "The one template with this catalogue `id`, or whose `:url` matches `s` — nil
  when nothing does."
  [s]
  (when-let [x (some-> s str str/trim not-empty)]
    (or (providers/by-id x)
        (providers/by-url x))))

(defn save-choice!
  "Write `m` as a named choice — the ONLY writer of `:llm :choices`.

  Validates first, and throws `ex-info` whose MESSAGE is what the dialog should
  show: a name that cannot be typed into `/model`, a url that is present but
  unusable, a placeholder model id. Those are the exact three ways a user can
  half-fill the form, and each gets a sentence rather than a saved row that fails
  later. A BLANK url is legal and means \"this model on the configured
  transport\" — which is why a present-but-junk url must be an error rather than
  silently normalising to nil: that would quietly turn \"my server on 8081\" into
  \"whatever is configured\".

  Same name -> REPLACED. When the row being replaced lives in the legacy
  `:profiles` map, that key is dropped in the same write, so the new
  `:choices` row is the only thing the file says about that name.

  Saves a choice; does NOT select it (that is `use-choice!`). Returns
  `{:choice <row> :choices [the stored rows]}`."
  [m]
  (let [n (choice-name (:name m))
        _ (when (nil? n)
            (throw (ex-info (str "the name " (pr-str (:name m)) " is not usable — start with a letter, "
                                 "then letters, digits, '-', '_' or '.' (e.g. \"workstation\", \"strata-8081\")")
                            {:name (:name m) :reason :name})))
        raw-u (:url m)
        u (choice-url raw-u)
        _ (when (and (some-> raw-u str str/trim not-empty) (nil? u))
            (throw (ex-info (str "that is not a URL I can use: " (pr-str raw-u)
                                 " — e.g. \"http://localhost:8081/v1\" or \"https://openrouter.ai/api/v1\"."
                                 " (Leave it blank to mean the transport you are already on.)")
                            {:url raw-u :reason :url})))
        mid (some-> (:model m) str str/trim not-empty)
        _ (when (placeholder-model-id? mid)
            (throw (ex-info (str "\"" mid "\" names no model — it is the placeholder some servers ship with. "
                                 "Pick an id from the endpoint's list, or leave the model blank and let grog read it.")
                            {:model mid :reason :model})))
        _ (when (and (nil? u) (nil? mid))
            (throw (ex-info "a choice needs a URL, or a model id to run on the transport you are already on"
                            {:name n :reason :empty})))
        row (normalize-choice (assoc m :name n :url u :model mid))
        written (persist!
                 (fn [cfg]
                   (let [cur (get-in cfg [:llm :choices])
                         cur (if (sequential? cur) (vec cur) [])
                         ;; stored rows are de-duplicated by name on the way in,
                         ;; so rows hand-written with duplicates collapse to the
                         ;; one just saved rather than shadowing each other
                         kept (vec (remove #(= n (choice-name (:name %))) cur))
                         profiles (get-in cfg [:llm :profiles])
                         profiles (when (map? profiles) profiles)
                         shadowed (when profiles
                                    (filter #(= n (str/lower-case (if (keyword? %) (name %) (str %))))
                                            (keys profiles)))
                         llm (-> (get cfg :llm {})
                                 (assoc :choices (into kept [row])))]
                     (assoc cfg :llm (if (seq shadowed)
                                       (apply dissoc (dissoc llm :profiles) shadowed)
                                       llm)))))]
    (reload-config!)
    {:choice row :choices (get-in written [:llm :choices])}))

(defn remove-choice!
  "Delete the choice named `name`. A row that also exists in the legacy
  `:profiles` map clears that key in the same write — the user clicked remove,
  and it must STAY removed rather than reappear from the legacy map on the next
  read.

  Returns the remaining `:llm :choices` rows."
  [name]
  (let [n (choice-name name)]
    (when (nil? n)
      (throw (ex-info (str "no model choice named " (pr-str name)) {:name name})))
    (let [written (persist!
                   (fn [cfg]
                     (let [cur (get-in cfg [:llm :choices])
                           cur (if (sequential? cur) (vec cur) [])
                           kept (vec (remove #(= n (choice-name (:name %))) cur))
                           profiles (get-in cfg [:llm :profiles])
                           profiles (when (map? profiles) profiles)
                           shadowed (when profiles
                                      (filter #(= n (str/lower-case (if (keyword? %) (name %) (str %))))
                                              (keys profiles)))
                           llm (-> (get cfg :llm {}) (assoc :choices kept))]
                       (assoc cfg :llm (if (seq shadowed)
                                         (apply dissoc (dissoc llm :profiles) shadowed)
                                         llm)))))]
      (reload-config!)
      (vec (get-in written [:llm :choices])))))

(defn choice-info
  "A choice, annotated for the UI:

    :provider        the catalogue kind (`strata`, `openrouter`, …) or nil
    :port            the port in force — the ONLY thing that tells two local
                     servers of one kind apart
    :current?        is this exactly what a session would run right now?
    :label           `name · provider · model` — the row a user reads
    :reachable?      did `GET <url>/models` answer? nil until asked
    :models          the ids it advertises, when probed
    :context-window  what that endpoint will accept, when probed

  Read-only and never throws: a choice aimed at a server that is DOWN is still a
  choice, and still the very row the user wants to open and fix the port on."
  ([c] (choice-info c {}))
  ([c {:keys [probe] :as _opts}]
   (when-let [c (normalize-choice c)]
     (let [u (:url c)
           cur-url (try (config/llm-url) (catch Throwable _ nil))
           cur-model (try (config/eca-model) (catch Throwable _ nil))
           eff-url (or u cur-url)
           prov (or (:provider c)
                    (try (provider-for-endpoint eff-url) (catch Throwable _ nil)))
           live (when (and probe u) (try (fetch-provider-models u) (catch Throwable _ [])))
           win (when (and probe u (:model c))
                 (try (endpoint-context-window u (:model c)) (catch Throwable _ nil)))]
       (cond-> (assoc c :provider prov
                         :port (providers/port-of-url eff-url)
                         :current? (and (= (some-> eff-url str) (some-> cur-url str))
                                       (= (:model c) cur-model))
                         :label (str (:name c) " · "
                                     (config/model-label {:url eff-url :provider prov
                                                          :model (:model c)})))
         (some? live) (assoc :models (vec live) :reachable? (boolean (seq live)))
         (some? win)  (assoc :context-window win))))))

(defn choice-for
  "The NAMED choice that matches a `[url model]` pair, or nil.

  This is what lets the status line say `workstation · strata:8081 · qwen3-32b`
  instead of a url and an id the user never chose to read: the pair a session runs
  is matched back to the name the user gave it. Exact match on both halves (the
  same normalisation `choices` applies), so it can never claim a name for a
  transport someone hand-edited out from under it."
  [url model]
  (let [u (some-> url str (str/replace #"/+$" "") str/trim not-empty)
        m (some-> model str str/trim not-empty)]
    (when (and u m)
      (first (keep (fn [c] (when (and (= u (some-> (:url c) str (str/replace #"/+$" "")))
                                      (= m (:model c)))
                             (:name c)))
                   (choices))))))

(defn choices-info
  "The whole list annotated (see `choice-info`).

  `{:probe true}` ALSO asks every endpoint what it serves and what window it
  accepts. That is one network call per choice, so the dialog asks for it
  explicitly (\"check them\") and a plain read never pays for it."
  ([] (choices-info {}))
  ([opts] (mapv #(choice-info % opts) (choices))))

(defn seed-choice!
  "Create a choice from a shipped-catalogue template — the \"baked in models are a
  template\" half of the request.

  `template` is a catalogue id (`\"ollama\"`) or a url. The name defaults to that
  id, with the port appended (`strata-8081`) when the url is NOT on that kind's
  default port — two launches of one server must not collide on one name. The
  model comes from the RUNNING endpoint when it answers, else the template's
  `:sample` for a remote endpoint, else blank; never a placeholder
  (`live-model-id` implements exactly that order). The user edits any of it after.

  Returns what `save-choice!` returns."
  [template & [name]]
  (let [t (or (choice-template template)
              (throw (ex-info (str "no baked-in template for " (pr-str template)
                                   " — pick one from the list, or type the URL yourself")
                              {:template template})))
        base (choice-url (:url t))
        nm (or (choice-name name)
               (let [id (str (:id t))
                     p (providers/port-of-url base)
                     dp (some-> (:url t) providers/port-of-url)]
                 (choice-name (if (and p (not= p dp)) (str id "-" p) id))))
        mid (live-model-id base (:sample t))]
    (save-choice! {:name nm :url base :model mid})))

(defn probe-choice
  "Ask one choice's endpoint what it serves — its model ids and its context
  window. This is the dialog's \"what is actually there\" button: it turns a
  greyed-out row into either a list to click or a clear 'nothing is listening'.

  Never throws; `{:reachable? false}` is the answer for a server that is down."
  [name]
  (when-let [c (find-choice name)]
    (choice-info c {:probe true})))

;; --- browsing a CHOICE's endpoint ------------------------------------------
;;
;; The `models` RPC browses the CONFIGURED transport, keyed by the PLACE a tab is
;; named after (`local`/`remote`). A saved choice is a fourth kind of place: its
;; own url, whether or not it is the one grog currently runs. Keying those fetches
;; by url means the dialog can open ANY row, list what that endpoint serves, and
;; click an id into the editor — without touching the live transport, and without
;; the two places colliding in one cache (a Strata on 8080 and one on 8081 are the
;; same PLACE and different servers).
;;
;; A slow endpoint must never block the server's single request loop, so this
;; reuses the same answer-now / fetch-in-the-background shape as the tabs: the
;; first call says `:loading true`, the list arrives as a `models` broadcast
;; keyed by that same url.

(defn- url-source-key [url] (str "url:" (some-> url str (str/replace #"/+$" ""))))

(defn fetch-url-async!
  "Refresh `url`'s model list on a background thread. Calls `(done! url ids)` when
  it lands; returns true when a fetch was actually started (a repeat click while
  one is running starts nothing)."
  [url done!]
  (let [u (some-> url str str/trim (str/replace #"/+$" "") not-empty)]
    (if (nil? u)
      false
      (let [k (url-source-key u)]
        (if (contains? @inflight* k)
          false
          (do
            (swap! inflight* conj k)
            (future
              (let [ms (try (vec (fetch-provider-models u)) (catch Throwable _ []))]
                (swap! catalogue* assoc k ms)
                (swap! inflight* disj k)
                (try (done! u ms) (catch Throwable _ nil))))
            true))))))

(defn url-catalogue
  "What is known about `url` right now, for the browse list:

    {:url <base> :models [...] | nil :loading <bool>}

  `:models` nil means never fetched (the caller should expect `fetch-url-async!`
  to deliver it shortly)."
  [url]
  (let [u (some-> url str str/trim (str/replace #"/+$" "") not-empty)]
    (when u
      {:url u
       :models (get @catalogue* (url-source-key u))
       :loading (boolean (contains? @inflight* (url-source-key u)))})))

(defn use-choice
  "Resolve the choice named `name` into what a pick needs:

      {:name .. :url .. :model .. :provider .. :label ..}

  A name that does not exist, and a choice with no model id, are BOTH errors with
  a message the dialog can show: the endpoint may be up, but there is nothing
  NAMED to ask, and picking a row that resolves to nothing is how a \"bogus
  model\" gets born.

  This resolves; it does not write. The write belongs to the pick path
  (`grog.client.loop`'s set-model, which the GUI's `set-model` RPC and `/model`
  both go through) because that is the code that knows whether the model belongs
  to the PROJECT manifest or to grog.edn, and that is what updates the running
  session's pinned transport and pushes the recents row. Duplicating it here is
  how the GUI and the console drift."
  [name]
  (let [c (or (find-choice name)
              (throw (ex-info (str "no model choice named " (pr-str name)
                                   " — the choices are: "
                                   (str/join ", " (map :name (choices))))
                              {:name name :reason :unknown})))
        _ (when (nil? (:model c))
            (throw (ex-info (str "\"" (:name c) "\" has no model id — open it, pick one from the "
                                 "endpoint's list (or type the id it answers to), and save")
                            {:name (:name c) :reason :model})))
        url (or (:url c) (try (config/llm-url) (catch Throwable _ nil)))]
    {:name (:name c) :url url :model (:model c) :provider (:provider c)
     :label (config/model-label {:url url :provider (:provider c) :model (:model c)})}))

