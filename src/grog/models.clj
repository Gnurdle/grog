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
  exist yet (fresh install)."
  [f]
  (let [existing (or (read-map) {})
        updated (f existing)
        file (grogedn-file)
        tmp (io/file (str file ".tmp"))]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit tmp (with-out-str (pp/pprint updated)))
    (io/copy tmp file)
    (when (.exists tmp) (.delete tmp)))
  nil)

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

(defn save-slot-model!
  "Persist `m` as the model id of a model SLOT (`:big` / `:small`) in grog.edn
  atomically, preserving every other key: `[:big-model :model]` or
  `[:small-model :model]`.

  The id is the RAW, transport-relative catalog id (a plain
  `moonshotai/kimi-k3` under an openrouter URL), so it resolves against
  `config/slot-url` for that slot."
  [slot m]
  (let [k (if (= :small slot) :small-model :big-model)]
    (persist! #(assoc-in % [k :model] (str m)))
    m))

(defn save-slot-url!
  "Persist a model SLOT's TRANSPORT url in grog.edn atomically:
  `[:big-model :url]` or `[:small-model :url]`. A nil/blank `url` clears the
  slot's own transport so it falls back to the shared `:llm :url`."
  [slot url]
  (let [k (if (= :small slot) :small-model :big-model)
        u (some-> url str str/trim not-empty)]
    (persist! (fn [m]
                (if u
                  (assoc-in m [k :url] u)
                  (update m k (fn [cur] (dissoc (or cur {}) :url))))))
    url))

(defn save-eca-model!
  "Persist `m` as the BIG slot's model id (`[:big-model :model]`) in grog.edn
  atomically — the GLOBAL big default for the GUI's chat.

  Called only when the user ticks \"set as global default\" in the models dialog
  (`grog.client.loop`'s `set-model-fn` with `:global true`). A plain pick writes
  the PROJECT manifest instead (`grog.projects/set-project-model!`), so one
  project's choice never rewrites every other project (or every future session)."
  [m]
  (save-slot-model! :big m))

(defn save-eca-small-model!
  "Persist `m` as the SMALL slot's model id (`[:small-model :model]`) in
  grog.edn atomically — the GLOBAL small default, the counterpart of
  `save-eca-model!`.

  Also called only for the \"set as global default\" tick. There is no delete
  here on purpose: the global small default is set or left unset, and unsetting
  it is an explicit config edit, not something a dialog should do by accident."
  [m]
  (save-slot-model! :small m))

;; --- fetching available models ----------------------------------------------
;;
;; There is deliberately no per-provider fetcher here. The configured provider is
;; listed from its own OpenAI-compatible `GET <:llm :url>/models`, and ollama from
;; its `/api/tags`. Nothing in this section names a specific host.

(defn fetch-provider-models
  "The model ids the CONFIGURED provider advertises, via its OpenAI-compatible
  `GET <url>/models`.

  With no argument, `url` is the BIG slot's transport (`config/big-model-url`);
  pass a url to list a DIFFERENT slot's provider (the small slot's own endpoint).

  There is nothing provider-specific here on purpose: openrouter, fireworks, a
  self-hosted server and everything else in between expose that endpoint and the
  `{:data [{:id …}]}` shape, so the same call works for all of them. The
  Authorization header is sent when a key resolves (a public listing ignores it).

  Best effort: `[]` on failure, offline, or an unparseable body."
  ([] (fetch-provider-models (try (config/big-model-url) (catch Throwable _ nil))))
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
       ;; The catalogue knows this exact base — take its canonical id before the
       ;; host heuristic can mangle it.
       (providers/id-for-url url)
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

(defn- slot-url*
  "A slot's transport url, or nil (never throws — a config with no url at all is
  a valid, if unconfigured, state)."
  [slot]
  (try (config/slot-url slot) (catch Throwable _ nil)))

(defn- slot-provider
  "The provider a SLOT's transport url points at, as a name, or nil."
  [slot]
  (provider-prefix-for-url (slot-url* slot)))

(defn picker-source->provider
  "Translate a picker SOURCE into the PROVIDER to qualify a model with, for a
  given SLOT (default `:big`).

  The picker's sources name PLACES, not companies:
    \"local\"  -> \"ollama\"             (that is what a local server is, here)
    \"remote\" -> the provider the SLOT's transport url points at
                  (openrouter, fireworks, …)
  A provider name, nil, or anything else passes through unchanged.

  Without this the source is lost, and qualification falls back to guessing —
  which scoped a REMOTE model to ollama whenever its id happened to also exist
  in the local ollama (`qwen2.5-coder:7b` is on OpenRouter *and* in your ollama)."
  ([source] (picker-source->provider source :big))
  ([source slot]
   (case (str source)
     "local"  "ollama"
     "remote" (slot-provider slot)
     (let [s (str source)]
       (when (seq s) s)))))

(defn- local-url?
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

(defn- source-fetcher
  "The fetch fn for a picker `source` on a SLOT, or nil when there is none.

  There are only two sources, and neither is a host name:
    :local   this machine        — ollama's `/api/tags`
    :remote  the SLOT's transport — its OpenAI-compatible `/models`
  A provider NAME is still accepted, so an older client asking for \"openrouter\"
  gets the right list rather than nothing."
  [slot source]
  (let [s (name source)]
    (cond
      (= s "local")               fetch-ollama-models
      (= s "remote")              #(fetch-provider-models (slot-url* slot))
      (= s (slot-provider slot))  #(fetch-provider-models (slot-url* slot))
      :else                       nil)))

(defn fetch-async!
  "Refresh `source` for a SLOT (`:big`/`:small`) on a background thread, unless
  that fetch is already running. Calls `(done! source models)` when it lands.
  Returns true when a fetch was actually started."
  [slot source done!]
  (let [k [slot source]]
    (if-let [fetch (source-fetcher slot source)]
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
  "What the picker can show for a SLOT right now, keyed by SOURCE — `:local` and
  `:remote`.

  Each source starts as nil (never fetched). Keyed per slot so the big and small
  slots browse their OWN transports independently. ECA's own catalogue is
  deliberately NOT a source here: which models ECA can resolve is an
  implementation detail of the agent, not somewhere a user picks a model."
  [slot]
  (let [local? (local-url? (slot-url* slot))
        loading (vec (sort (for [[s src] @inflight* :when (= s slot)]
                             (name src))))
        fetched (into {} (for [[[s src] v] @catalogue* :when (= s slot)]
                           [(keyword src) v]))]
    (merge (if local? {:local nil} {:local nil :remote nil})
           {:loading loading}
           fetched)))

(defn model-sources
  "The picker's sources for a SLOT, as `[[id label] …]` — LOCAL and REMOTE.

  `local` is the machine you are on (ollama). `remote` is whatever provider the
  SLOT's transport url points at: openrouter for one user, fireworks for the
  next — the LABEL does not change, because what the user is choosing is where
  the model runs, not which company hosts it.

  When the slot's transport already IS local, only `local` is offered — two tabs
  onto the same list is noise. There is no `eca` source: that was an
  implementation detail leaking into a user-facing choice."
  [slot]
  (if (local-url? (slot-url* slot))
    [["local" "Local"]]
    [["local" "Local"] ["remote" "Remote"]]))

(defn slot-info
  "Per-slot picker state for a client: the slot's transport url, its resolved
  model id, its derived provider, whether it is local, and the sources it
  offers."
  [slot]
  (let [u (slot-url* slot)]
    {:slot (name slot)
     :url u
     :model (if (= :small slot)
              (try (config/eca-small-model) (catch Throwable _ nil))
              (try (config/eca-model) (catch Throwable _ nil)))
     :provider (provider-prefix-for-url u)
     :local? (local-url? u)
     :sources (model-sources slot)}))
