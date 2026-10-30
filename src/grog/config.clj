(ns grog.config
  "Loads `grog.edn` (no environment-variable overrides).

  User-level config lives in a **platform-aware config home** (`config-home-dir`):
    * `$GROG_CONFIG_HOME/grog.edn` when that env var is set,
    * otherwise: `${XDG_CONFIG_HOME:-~/.config}/grog/grog.edn` on every OS
      (Windows included — matching ECA's own `~/.config/eca`).

  Merge order (later wins): classpath `resources/grog.edn` → legacy
  `~/.config/grog/grog.edn` (only when it differs from the config home) → the
  user config home (the explicit `GROG_CONFIG_HOME` / `XDG_CONFIG_HOME` choice,
  so it always wins).

  There is deliberately NO `./grog.edn` (cwd) fragment any more. It silently
  overrode the user's own config: a saved model or appearance is written to the
  config home, so a stray cwd file made every save revert on the next reload."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.platform :as platform]
            [grog.providers :as providers]
            [grog.secrets :as secrets])
  (:import (java.io File)))

(defn windows?
  "True when running on a Microsoft Windows OS."
  []
  (platform/windows?))

(defn config-home-dir
  "Where grog keeps its user-level config and generated files (see
  `grog.platform/config-home-dir`)."
  ^File []
  (platform/config-home-dir))

(defn ensure-config-dir!
  "The config home, created if missing (see `grog.platform/ensure-config-dir!`)."
  ^File []
  (platform/ensure-config-dir!))

(defn deep-merge
  "Recursively merge maps; non-map values from `b` replace `a`."
  [a b]
  (merge-with (fn [x y]
                (if (and (map? x) (map? y))
                  (deep-merge x y)
                  y))
              a b))

(defn- config-debug!
  "One-line config-loading trace written to the **real** stderr so it survives
  even when the caller's `*out*`/`*err*` are rebound to the transcript pane.
  On a console run these lines go to the terminal; the desktop client redirects
  the backend's stderr into its per-instance log (`<base>.<pid>.log` — see
  clients/web/src/main/log.js)."
  [& xs]
  (.println System/err (str "[grog-config] " (apply str (interpose " " (map str xs))))))

(defn- slurp-edn [^File f]
  (when (and f (.exists f) (.isFile f))
    (try (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))
         (catch Exception _ nil))))

(defn- resource-edn [name]
  (when-let [r (io/resource name)]
    (try (edn/read-string {:eof nil} (slurp r :encoding "UTF-8"))
         (catch Exception _ nil))))

(defn- trace-fragment
  "Emit one debug line describing a config fragment lookup and its result.
  `label` is a short human name, `path` the File being inspected, `loaded?`
  whether a value was obtained (found + parsed), and optional `extra` details.
  ASCII-only (no em dashes) so the line is clean in any log/console."
  [label ^File path loaded? & [extra]]
  (config-debug! (str label
                      " path=" (or (some-> path .getPath) "nil")
                      " found=" (boolean (and path (.exists path)))
                      " loaded=" (boolean (some? loaded?))
                      " keys=" (when (map? loaded?) (count loaded?))
                      (when extra (str " " extra)))))

(defn- canonical-equal?
  "Resilient `File` path equality (falls back to string compare on error)."
  [^File a ^File b]
  (and a b
       (try (= (.getCanonicalPath a) (.getCanonicalPath b))
            (catch Exception _ (= (str a) (str b))))))

(defn- fragment-entries
  "Every config fragment we consider, in MERGE ORDER (later wins), as
  `[label ^File source-kw data legacy-same?]`. One source of truth for both the
  `[grog-config]` trace and `load-fragments` (used by `grog doctor`)."
  []
  (let [home-file (io/file (config-home-dir) "grog.edn")
        legacy-home-file (io/file (System/getProperty "user.home") ".config" "grog" "grog.edn")
        legacy-same? (canonical-equal? home-file legacy-home-file)]
    ;; legacy FIRST, home LAST: the config home is the user's explicit choice
    ;; (GROG_CONFIG_HOME / XDG_CONFIG_HOME) and must win. Legacy exists only as a
    ;; courtesy for installs that predate a moved config home — it used to be
    ;; merged AFTER, so the old default-location file overrode the new one.
    [["resource grog.edn" (some-> (io/resource "grog.edn") io/file) :classpath
      (resource-edn "grog.edn") false]
     ["legacy ~/.config/grog/grog.edn" legacy-home-file :legacy
      (when (and (.exists legacy-home-file)
                 (not (and (.exists home-file) legacy-same?)))
        (slurp-edn legacy-home-file))
      legacy-same?]
     ["home grog.edn" home-file :home (slurp-edn home-file) false]]))

(defn load-fragments
  "The config fragments that were FOUND and parsed, in merge order, as
  `{:source :classpath|:home|:legacy, :path <str>, :data <map>}`.

  This is what makes provenance observable: `grog doctor` walks it front-to-back
  to attribute each effective key to the last file that set it."
  []
  (vec (keep (fn [[_label f src data _same?]]
               (when (some? data)
                 {:source src
                  :path (or (some-> f .getPath) (str f))
                  :data data}))
             (fragment-entries))))

(defn load-merge!
  "Load and deep-merge all config fragments (does not touch the cache atom).
  Writes a `[grog-config]` trace to the debug log for every fragment it looks
  at (classpath resource, config-home grog.edn, legacy ~/.config/grog)."
  []
  (let [entries (fragment-entries)
        fragments (remove nil? (map (fn [[_ _ _ data _]] data) entries))]
    (config-debug! "config-home-dir=" (some-> (config-home-dir) .getPath)
                   " GROG_CONFIG_HOME=" (pr-str (System/getenv "GROG_CONFIG_HOME"))
                   " XDG_CONFIG_HOME=" (pr-str (System/getenv "XDG_CONFIG_HOME"))
                   " user.home=" (pr-str (System/getProperty "user.home")))
    (doseq [[label f _ data same?] entries]
      (trace-fragment label f data (when same? "(same as home-file - skipped)")))
    (config-debug! "merged fragments=" (count fragments)
                   " sources=" (pr-str (vec (keep identity
                                                  (map (fn [[_ _ src data _]]
                                                         (when data (name src)))
                                                       entries)))))
    (reduce deep-merge {} fragments)))

(defonce ^:private !cfg (atom nil))

(defn reload!
  "Re-read config files from disk (REPL / tests). Also registers any
  `:secrets :accounts` declared in the merged config so `/secret` and
  `with_api_key` know about them."
  []
  (let [c (load-merge!)]
    (reset! !cfg c)
    (secrets/refresh-known-accounts! c)
    c))

(defn grog
  "Merged configuration map."
  []
  (swap! !cfg
         (fn [cur]
           (or cur
               (let [c (load-merge!)]
                 (secrets/refresh-known-accounts! c)
                 c)))))

(defonce ^:private !llm-override (atom nil))

(defn set-llm-override!
  "Apply a session-scoped override map on top of the file-based `:llm` config.
   Use `(clear-llm-override!)` to revert to the on-disk config."
  [m]
  (reset! !llm-override m))

(defn clear-llm-override!
  "Remove any session-scoped `:llm` override set by `/model`."
  []
  (reset! !llm-override nil))

(defn effective-llm-cfg
  "File-based `:llm` config deep-merged with any session override."
  []
  (deep-merge (get-in (grog) [:llm] {}) (or @!llm-override {})))

(defn repo-root
  "The grog project/repo root (where deps.edn, SOUL.md, skills/ etc. live).
  Resolution order: the `grog.home` system property, then the `GROG_HOME` env
  var, then the process working directory. Used as ECA's
  `workspaceFolders` root and as the `/shell` working directory, so the tool
  model can address files by plain paths in the repo (no workspace containment).
  Returns a native path string: on Windows an MSYS-style `GROG_HOME` (`/c/...`)
  is translated to `C:\\...`."
  []
  (platform/msys-path->windows
   (or (some-> (System/getProperty "grog.home") str str/trim not-empty)
       (some-> (System/getenv "GROG_HOME") str str/trim not-empty)
       ".")))

(def ^:private ^String default-projects-dir "~/grog-projects")

(defn projects-dir
  "The projects home: where per-project context lives, **outside** the source
  tree. Resolved from `:projects {:dir …}` in grog.edn, defaulting to
  `~/grog-projects`. `~` is expanded to the user home (both `~/` and `~\\`
  forms); a relative path is resolved against the repo root. Returns a
  canonical `File` (may not exist yet). Uses a resilient canonicalization so an
  MSYS/Windows path quirk can never abort startup."
  ^File []
  (let [raw (or (some-> (get-in (grog) [:projects :dir]) str str/trim not-empty)
                default-projects-dir)
        expanded (platform/expand-home raw)
        f (io/file expanded)]
    (platform/canonical-file
     (if (platform/native-absolute? (str f))
       f
       (io/file (repo-root) (platform/fix-drive-relative (str f)))))))

(defn- non-blank
  "`v` as a trimmed string, or nil when blank/nil."
  [v]
  (some-> v str str/trim not-empty))

;; --- ONE model, not a big/small pair ---------------------------------------
;;
;; grog used to carry two model SLOTS (`:big-model` / `:small-model`), each with
;; its own transport, and `/big` `/small` switched between them (with `/relay`
;; running the small one as a frontline that deferred to the big one). It never
;; earned its keep: it doubled every picker, every config key and every event
;; field, and the relay cascade it existed for did not work well enough to use.
;; Removed 2026-10-26 — there is now ONE model: `:llm :url` + `:llm :model`, and
;; recents (see `recents`) to switch between the ones you use.
;;
;; The legacy keys are still READ, as a fallback, so an existing grog.edn keeps
;; working: `:big-model` fills in a `:url`/`:model` that `:llm` does not carry,
;; and `:small-model` is ignored outright (it belonged to the removed slot).

(defn- legacy-model-map
  "The legacy `:big-model` map from grog.edn, or {} — READ-ONLY compatibility.

  This is the old BIG SLOT's self-contained transport. A config written before
  2026-10-26 has the model there; nothing writes it any more."
  []
  (let [m (get (grog) :big-model)]
    (if (map? m) m {})))

(defn- legacy-llm-map
  "The effective `:llm` map with the legacy `:big-model` folded in UNDERNEATH it,
  plus any session override on top.

  `:llm` wins wherever it has a value; `:big-model` only fills the gaps. So a
  config that only ever set `:big-model` keeps its model, and one that sets both
  keeps `:llm` — the key a user reading their own file would expect to win.

  `:url` AND `:model` ARE ONE UNIT. `:big-model` is a self-contained transport
  (see `legacy-model-map`), so its model id belongs to ITS url and to no other.
  Merging it key-by-key produced cross-transport pairings out of perfectly
  ordinary configs: with `:llm {:url ollama}` and a `:big-model {:url openrouter
  :model aion-labs/aion-2.0}`, the `:url` came from `:llm` and the `:model` was
  filled from `:big-model` — an OpenRouter id grafted onto a local port, which
  cannot resolve and reads as a deliberate choice. So the legacy pair is taken
  WHOLE, and only when `:llm` names no transport of its own; once `:llm` has a
  `:url`, its model is the user's business and nothing is grafted in."
  []
  (let [llm (effective-llm-cfg)
        legacy (legacy-model-map)
        legacy (if (some-> (:url llm) str str/trim not-empty)
                 {}
                 (select-keys legacy [:url :model]))]
    (deep-merge legacy llm)))

(defn model-map
  "The single effective MODEL map: the legacy `:big-model` (if any) merged under
  the session-overridden `:llm` block. Empty map when neither is set."
  []
  (legacy-llm-map))

(defn- model-fits-transport?
  "True when `model` can plausibly be run against the configured transport.

  A model id and a transport are ONE choice. An id that names a DIFFERENT
  provider from the configured url is not \"a model this transport can run\" — it
  is a leftover from another endpoint, and honouring it produces the pairing the
  user sees as a bogus model: `openrouter/deepseek/…` sent to a local port looks
  deliberate and cannot resolve.

  So an id carrying a provider prefix is accepted only when that prefix is the
  configured transport's own catalogue id. A BARE id (no prefix) is accepted —
  it is transport-relative by definition, and that is the normal shape of
  `:llm :model` (see `grog.models/save-model!`),
  e.g. `moonshotai/kimi-k3` under an openrouter url. An unknown url (no catalogue
  entry) accepts the bare id and anything unqualified, but still refuses a
  foreign, recognisable provider prefix — the only case where the mismatch is
  certain."
  [model]
  (let [m (non-blank model)]
    (if (nil? m)
      false
      (let [i (str/index-of m "/")
            seg (when i (subs m 0 i))
            seg-entry (when seg (try (providers/by-id seg) (catch Throwable _ nil)))
            own-id (try (providers/id-for-url (get-in (model-map) [:url]))
                        (catch Throwable _ nil))]
        (cond
          ;; no prefix: transport-relative, always the configured transport's
          (nil? seg) true
          ;; the prefix matches the configured transport: its own id
          (and own-id seg (= (str/lower-case seg) (str/lower-case own-id))) true
          ;; a prefix that names some OTHER known provider: a foreign pairing
          seg-entry false
          ;; an org segment we do not recognise: treat as transport-relative
          ;; (openrouter catalog orgs look exactly like this)
          :else true)))))

(defn eca-model
  "The model id — `:llm :model`, with the legacy `:big-model :model` as a
  fallback (see `model-map`).

  nil when none is set (ECA then falls back to the `defaultModel` in its own
  ~/.config/eca/config.json).

  The `:eca :model` fallback is honoured only when the id FITS the configured
  transport (see `model-fits-transport?`). It is a leftover from the era when ECA
  owned the model, and it is commonly provider-QUALIFIED — so honouring it blindly
  paired `openrouter/deepseek/…` with whatever `:llm :url` says, e.g. a local
  server. Returning nil is the honest answer there: the user is told there is no
  model for this endpoint instead of being handed one that cannot run on it."
  []
  (or (non-blank (get-in (model-map) [:model]))
      (let [e (non-blank (get-in (grog) [:eca :model]))]
        (when (model-fits-transport? e) e))))

(defn eca-model-source
  "Which key `eca-model` took its value from: `:llm` (or `:big-model`, the
  legacy fallback), `:eca`, or nil.

  `grog.eca-config` needs this to qualify a config-sourced model correctly: its
  provider is then whatever `:llm :url` points at, and saying so explicitly
  stops an OpenRouter org like `deepseek/…` being read as a native provider of
  that name."
  []
  (cond
    (non-blank (get-in (model-map) [:model])) :llm
    (non-blank (get-in (grog) [:eca :model])) :eca
    :else nil))

;; --- recents: the models/providers you actually use -------------------------
;;
;; ONE flat LRU list of `{:url :model}` pairs, newest first, persisted in
;; grog.edn under `:llm :recents`. Picking a model (from the picker, or with
;; `/model`) pushes it to the front; anything past `:llm :recents-max` (default
;; 10) is dropped. That is the whole feature: the picker shows the shortlist you
;; really use instead of an unbounded catalogue you have to search every time.

(def ^:private default-recents-max 10)

(defn recents-max
  "How many recents to keep — `:llm :recents-max`, default 10. A non-positive
  value disables the list."
  []
  (let [v (get-in (grog) [:llm :recents-max])]
    (if (and (number? v) (pos? (long v)))
      (long v)
      default-recents-max)))

(defn recents
  "The recents list, newest first, as `[{:url <str> :model <str> :provider <str|nil>}]`.

  `:provider` is derived from `:url` through the shipped catalogue, so an entry
  can be LABELLED without the reader re-deriving it. Entries are validated and
  de-duplicated by `[url model]`: a hand-edited grog.edn with a junk row, a
  duplicate, or a missing key yields a clean list rather than an exception on a
  status line."
  []
  (let [raw (get-in (grog) [:llm :recents])
        rows (when (sequential? raw) raw)]
    (->> rows
         (keep (fn [r]
                 (when (map? r)
                   (let [u (non-blank (:url r))
                         m (non-blank (:model r))]
                     (when (and u m)
                       {:url u
                        :model m
                        :provider (or (non-blank (:provider r))
                                      (try (providers/id-for-url u)
                                           (catch Throwable _ nil)))})))))
         (reduce (fn [acc r]
                   (if (some #(and (= (:url %) (:url r)) (= (:model %) (:model r))) acc)
                     acc
                     (conj acc r)))
                 [])
         (vec))))

(defn model-label
  "A one-line `provider · model` label for a recents row or the live model.

  This is what the status line shows: the PROVIDER beside the model, because
  `qwen3.8-flash-next-unsloth-ud-q4_k_xl` on its own says nothing about where it
  is running, and the same id can exist on two endpoints.

  The provider carries a `:<port>` when the endpoint is on a port that is NOT
  that provider's catalogue default (a second Strata launch on 8081, say): two
  servers of the same kind must be tellable apart, and `strata · model` alone
  cannot say which one. The default port is left bare, so an ordinary 8080
  Strata still reads simply `strata · model`."
  [{:keys [provider url model]}]
  (let [base (or (non-blank provider)
                 (when url (try (providers/id-for-endpoint url) (catch Throwable _ nil))))
        p (when base (str base (when url (providers/port-suffix url))))
        m (non-blank model)]
    (cond
      (and p m) (str p " · " m)
      m         m
      p         p
      :else     "unset")))

(defn llm-provider
  "The explicit provider id for the configured model transport — `:llm :provider`,
  or nil.

  Normally the provider is DERIVED from `:llm :url` (`models/provider-prefix-for-url`),
  which is enough because a base URL names exactly one endpoint. It is persisted
  when the user picks a provider whose port they also chose (see
  `grog.models/set-provider!`), so the picker combo can show the actual selection
  rather than re-guessing it — and so the choice survives a hand-edit of the url.
  Never read for model qualification; that stays URL-derived."
  []
  (some-> (get-in (effective-llm-cfg) [:provider]) str str/trim not-empty))

(defn eca-provider-overrides
  "`:eca :providers` from grog.edn — provider entries grog merges into the
  generated ECA config, so a fresh install needs no `eca/config.json`.

  Values are ordinary ECA provider maps, e.g.
    :providers {\"openrouter\" {:api \"openai-chat\"
                               :url \"https://openrouter.ai/api/v1\"
                               :key \"${env:GROG_LLM_API_KEY}\"}}
  A `:key` should be an env REFERENCE, never a literal secret. Keys are
  normalized to strings (JSON keys)."
  []
  (let [p (get-in (grog) [:eca :providers])]
    (when (map? p)
      (into {} (map (fn [[k v]] [(name k) v])) p))))

(declare interpolate-env-var)

(defn eca-binary
  "`:eca :binary` from grog.edn — an explicit path or name of the ECA server
  binary (`eca server`). When set, grog uses it directly; otherwise it falls
  back to PATH + well-known install locations (see `grog.eca/resolve-eca-binary!`).
  Supports `${ENV}` interpolation and a leading `~` (home-relative)."
  []
  (let [v (get-in (grog) [:eca :binary])]
    (when-let [s (some-> v str str/trim not-empty interpolate-env-var not-empty)]
      (platform/expand-home s))))

(def ^:private default-eca-log-level "warn")

(defn eca-log-level
  "ECA's log verbosity, handed to `eca server` as `--log-level` (`:eca :log-level`
  in grog.edn). One of \"info\" \"warn\" \"error\" \"debug\"; anything else falls
  back to the default.

  Defaults to **warn**, not ECA's own \"info\": at info, ECA relays every MCP
  child's stderr into its log — and each server namespace carries a top-level
  `(set! *warn-on-reflection* true)`, so one launch dumps hundreds of Clojure
  reflection warnings into grog's log through the ECA stderr relay. Set
  \"info\" (or \"debug\") in grog.edn when you actually want that firehose."
  []
  (let [v (some-> (get-in (grog) [:eca :log-level]) str str/trim str/lower-case)]
    (if (contains? #{"info" "warn" "error" "debug"} v)
      v
      default-eca-log-level)))

(defn- interpolate-env-var
  "Replace `${ENV}` and `${ENV:-default}` in a string with environment variable values."
  [^String s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[_ var-spec]]
                   (let [[var-name default-val] (str/split var-spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))))

(defn llm-url
  "Chat completions POST URL: `:llm :url`, with the legacy `:big-model :url` as
  a fallback (see `model-map`)."
  []
  (let [v (get-in (model-map) [:url])]
    (or (some-> v str str/trim not-empty)
        (throw (ex-info "grog.edn missing required :llm :url"
                        {:path [:llm :url]})))))

(defn llm-model
  "Model id: `:llm :model`, with the legacy `:big-model :model` as a fallback
  (see `model-map`)."
  []
  (let [v (get-in (model-map) [:model])]
    (or (some-> v str str/trim not-empty)
        (throw (ex-info "grog.edn missing required :llm :model"
                        {:path [:llm :model]})))))

(defn named-models
  "Every NAMED model in grog.edn, as `[{:name <str> :url <str|nil> :model <str|nil>}]`,
  sorted by name.

  A NAME is what the user asked to pick a model with, and there are two places a
  name can live in the file:

    :llm :choices  [{:name \"workstation\" :url \"…\" :model \"…\"} …]
                   what the Models dialog writes, and the shape to hand-edit
    :llm :profiles {:strata {:url \"…\" :model \"…\"} …}
                   the legacy map — the example config ships it for every known
                   provider, so every existing install already has a dozen named
                   models in it

  Both are READ, and `:choices` wins a name clash (it is the newer, explicit
  shape; the writer drops the shadowed `:profiles` key in the same write). Folding
  them here rather than migrating on disk means `/model strata` keeps resolving
  exactly as it always did, a hand-written profiles block keeps working, and the
  file is only rewritten when the user actually edits a row.

  Raw rows with no url/name validation — `grog.models/choices` is the layer that
  validates and annotates them. This lives here because it is a pure read of the
  config map, and BOTH the CLI (`resolve-model-choice`) and the dialog need it:
  two readers of these two keys is how the console and the GUI drift.

  Each row carries `:source` (`\"choices\"` or `\"profiles\"`) so a caller can say
  where a name came from."
  []
  (let [llm (get-in (grog) [:llm])
        choices (get llm :choices)
        choices (when (sequential? choices) choices)
        profiles (get llm :profiles)
        profiles (when (map? profiles) profiles)
        rows (concat
              (keep (fn [m]
                      (when (map? m)
                        (when-let [n (some-> (:name m) str str/trim (str/replace #"\s+" "-")
                                             str/lower-case not-empty)]
                          {:name n :url (non-blank (:url m)) :model (non-blank (:model m))
                           :api-key (:api-key m) :source "choices"})))
                    choices)
              (keep (fn [[k v]]
                      (when (map? v)
                        (when-let [n (some-> (if (keyword? k) (name k) (str k))
                                            str str/trim (str/replace #"\s+" "-")
                                            str/lower-case not-empty)]
                          {:name n :url (non-blank (:url v)) :model (non-blank (:model v))
                           :api-key (:api-key v) :source "profiles"})))
                    profiles))
        seen (atom #{})]
    (->> rows
         (filter (fn [{:keys [name]}]
                   (if (contains? @seen name) false (do (swap! seen conj name) true))))
         (sort-by :name)
         (vec))))

(defn named-choice
  "The `named-models` row with this name (case- and whitespace-insensitive), or nil."
  [name]
  (when-let [n (some-> name str str/trim (str/replace #"\s+" "-") str/lower-case not-empty)]
    (first (filter #(= n (:name %)) (named-models)))))

(defn model-choices
  "The current model followed by the recents, as a numbered list
  `[{:n <int> :url <str> :model <str> :label <str> :current? <bool>}]`.

  The current model is always entry 1, so `/model 1` is a no-op and the numbering
  the user reads is the numbering `/model <n>` accepts. A current model that is
  ALSO in recents is not listed twice — its recent row is simply marked current."
  []
  (let [cur-url (try (llm-url) (catch Throwable _ nil))
        cur-model (try (llm-model) (catch Throwable _ nil))
        cur {:url cur-url :model cur-model}
        same? (fn [a b] (and (= (non-blank (:url a)) (non-blank (:url b)))
                             (= (non-blank (:model a)) (non-blank (:model b)))))
        rows (vec (cons cur (remove #(same? % cur) (recents))))]
    (vec (map-indexed
          (fn [i r]
            {:n (inc i)
             :url (:url r)
             :model (:model r)
             :label (model-label r)
             :current? (zero? i)})
          rows))))

(defn resolve-model-choice
  "Turn what the user typed after `/model` into a choice.

  `tail` is one of:
    \"\"        -> `{:kind :list}` — show the numbered list (`model-choices`)
    \"<n>\"     -> the n-th entry of `model-choices` (1 = the current model), as
                  `{:kind :choice :url .. :model .. :label ..}`, or
                  `{:kind :unknown}` when the number is out of range
    \"<name>\"  -> a NAMED model (`named-models`: `:llm :choices` or the legacy
                  `:llm :profiles`), else a raw model id, as
                  `{:kind :choice :url .. :model .. :label ..}`. A named model
                  brings its own `:url` and its own `:model`; a bare id keeps the
                  configured transport.

  A name that carries NO model is not a choice — it is a row the user should
  complete — so it comes back as `{:kind :incomplete ..}` with a message rather
  than as a pick that resolves to nothing (the \"bogus model\" report)."
  [tail]
  (let [t (str/trim (str (or tail "")))]
    (cond
      (str/blank? t)
      {:kind :list}

      (re-matches #"\d+" t)
      (let [n (parse-long t)
            choices (model-choices)
            hit (first (filter #(= n (:n %)) choices))]
        (if hit
          {:kind :choice :url (:url hit) :model (:model hit) :label (:label hit)
           :n n :from-recent? (not (:current? hit))}
          {:kind :unknown :n n :max (count choices)}))

      :else
      (let [fallback-url (try (llm-url) (catch Throwable _ nil))
            named (named-choice t)]
        (cond
          ;; a named model: its url and its model id travel together
          (and named (:model named))
          (let [u (or (:url named) fallback-url)
                m (:model named)]
            {:kind :choice :url u :model m
             :label (model-label {:url u :model m})
             :profile (:name named) :named? true})

          ;; a named model with no id: say so instead of picking nothing
          named
          {:kind :incomplete :name (:name named) :url (:url named)
           :msg (str "\"" (:name named) "\" has no model id — give it one in the "
                     "Models dialog (or :llm :choices in grog.edn)")}

          ;; anything else is a raw model id on the configured transport
          :else
          {:kind :choice :url fallback-url :model t
           :label (model-label {:url fallback-url :model t})})))))

(defn llm-api-key
  "API key for OpenAI-compatible providers — from the OS keyring, account
  `LLM_API_KEY`.

  There is deliberately NO inline key in grog.edn: `:llm :api-key` is not read
  at all, so a key VALUE can never live in a config file (a leftover one is
  ignored, not honoured). A secret belongs in the keyring:

      /secret set LLM_API_KEY <value>

  Returns nil when unset, or when the keyring is unreachable. (Sending a key to
  a local endpoint that ignores it is harmless, so there is no `:api-key false`
  switch any more — the field itself is gone.)"
  []
  (some-> (secrets/get-secret "LLM_API_KEY") not-empty))

(defn llm-auth-headers
  "Authorization headers. Bearer token when an API key is configured."
  []
  (when-let [key (llm-api-key)]
    {"Authorization" (str "Bearer " key)}))

(defn llm-max-tokens
  "Max tokens for LLM requests. Default nil (provider default)."
  []
  (let [v (get-in (effective-llm-cfg) [:max-tokens])]
    (when (and (number? v) (pos? (long v)))
      (long v))))

(defn llm-conn-timeout-ms
  "Connection timeout (ms) for LLM HTTP requests; :llm :conn-timeout-sec, default 60s."
  []
  (* 1000 (long (get-in (effective-llm-cfg) [:conn-timeout-sec] 60))))

(defn llm-socket-timeout-ms
  "Read timeout (ms) for LLM streaming reads — a safety net so a stalled HTTP
  body / pre-stream response can never block forever. :llm :socket-timeout-sec,
  default 300s."
  []
  (* 1000 (long (get-in (effective-llm-cfg) [:socket-timeout-sec] 300))))

(defn llm-stream-retries
  "How many times an LLM stream attempt that received NOTHING is re-sent before
  the turn fails. :llm :stream-retries, default 2 (three tries in total).

  Only a stall that got no data at all is retried: nothing was received, so
  re-sending cannot duplicate an answer, and a dropped connection or a cold
  endpoint just works. A stall AFTER data arrived is never retried — that would
  replay the text the user already watched stream in — and is instead reported
  with the partial text attached."
  []
  (max 0 (long (get-in (effective-llm-cfg) [:stream-retries] 2))))

(defn llm-temperature
  "Temperature for LLM requests. Default nil (provider default)."
  []
  (let [v (get-in (effective-llm-cfg) [:temperature])]
    (when (number? v) (double v))))

(defn llm-debug-payload?
  "When true, prints full request payload to stderr."
  []
  (true? (get-in (effective-llm-cfg) [:debug-payload])))

(defn llm-debug-response?
  "When true, prints the raw accumulated response content to stderr before rendering."
  []
  (true? (get-in (effective-llm-cfg) [:debug-response])))

(defn max-context-tokens
  "The user's context-budget OVERRIDE, or nil.

  THERE IS NO DEFAULT, and that is the point. This used to return 200000 when
  unset, which was a global constant pretending to be a per-model fact: the user
  runs different models with different windows, and 200k is a hard 400 against a
  32k local server and a needless truncation against a 1M model. The budget is
  now DERIVED from the endpoint's own window (`grog.models/context-budget`), and
  this key is only an override that may LOWER it — set it to bound spend, not to
  tell grog how big the model is.

  nil = no override. 0/negative/unset = nil (there is no 'disable = 0' any more;
  nil already means 'no override')."
  []
  (let [v (get-in (effective-llm-cfg) [:max-context-tokens])]
    (when (and (number? v) (not (boolean? v)) (pos? (long v)))
      (long v))))

(defn tool-search-threshold
  "How many tools a turn may send before the agent loop hides the MCP long tail
  behind `tool_search` (deferred/lazy tool loading). nil → the loop's own default
  (20). Set a LARGE value (e.g. 10000) to send every schema, i.e. to disable lazy
  loading — the A/B switch for scripts/tool-search-test.clj and grog's evals."
  []
  (let [v (get-in (effective-llm-cfg) [:tool-search-threshold])]
    (when (and (number? v) (pos? (long v))) (long v))))

(defn max-tool-result-chars
  "Max characters for individual tool results. Results longer than this are truncated
   with a note. Default 50000 (~10–15K tokens). Set to nil in grog.edn to disable."
  []
  (let [v (get-in (effective-llm-cfg) [:max-tool-result-chars])]
    (cond
      (nil? v) 50000
      (and (number? v) (pos? (long v))) (long v)
      :else nil)))

(defn llm-extra-payload
  "Provider-specific fields merged into every /v1/chat/completions request payload.
   E.g. OpenRouter {:transforms [\"middle-out\"]} or {:plugins {…}}.
   Deep-merged after the standard payload fields so it can override them."
  []
  (get-in (effective-llm-cfg) [:extra-payload]))

;; Alias for `llm-model` (config/model)
(defn model
  "Model id. Delegates to `llm-model`."
  []
  (llm-model))

(defn provider-name
  "Human-readable provider name for status lines."
  []
  (or (some-> (get-in (effective-llm-cfg) [:provider-name]) str str/trim not-empty)
      "OpenAI-compatible"))

(defonce ^:private !active-project (atom nil))

(defn active-project-name
  "Current session project for memory + dialog, or nil (not read from grog.edn)."
  []
  @!active-project)

(defn set-active-project!
  "Set session project to a non-blank string, or `nil` to leave project mode."
  [name-or-nil]
  (reset! !active-project
          (when name-or-nil
            (let [s (str/trim (str name-or-nil))]
              (when-not (str/blank? s) s)))))

(defn active-project-status-line
  []
  (if-let [p (active-project-name)]
    (str "In project: " p " — context under " (.getPath (projects-dir)) "/" p)
    "No active project — prompt \"chat>\"; /project lists dirs; /project <name> to enter"))

(defn cli-cfg []
  (:cli (grog) {}))

(defn chat-history-turns
  "Max prior user/assistant pairs kept in chat (`:cli :chat-history-turns`).
  `0` — stateless; `nil`/omit — unlimited (session can grow large).
  Coerces positive integer strings; invalid values are treated as unlimited (`nil`)."
  []
  (let [v (:chat-history-turns (cli-cfg))]
    (cond
      (nil? v) nil
      (and (number? v) (zero? (long v))) 0
      (and (number? v) (pos? (long v))) (long v)
      (string? v) (when-let [n (parse-long (str/trim v))]
                    (cond (zero? n) 0 (pos? n) n :else nil))
      :else nil)))

(defn chat-show-thinking?
  "When true, print reasoning/thinking traces if present. Config `:cli :chat-show-thinking`;
  omitted uses JVM console detection."
  []
  (let [v (:chat-show-thinking (cli-cfg))]
    (cond
      (false? v) false
      (true? v) true
      :else (some? (System/console)))))

(defn chat-stream-live-thinking?
  "When true (default) and `chat-show-thinking?`, stream reasoning/thinking traces
  incrementally. Set `:cli :chat-stream-live-thinking false` to buffer and print once."
  []
  (not (false? (:chat-stream-live-thinking (cli-cfg)))))

(defn chat-stream-live-content?
  "When true (default), stream assistant answer tokens as they arrive **only when**
  `:format-markdown` is false (plain cyan). When `:format-markdown` is true, the reply is
  buffered and rendered once so GFM tables and full ANSI Markdown work. Set
  `:cli :chat-stream-live-content false` to always buffer until the round completes."
  []
  (not (false? (:chat-stream-live-content (cli-cfg)))))

(defn chat-stream-live-markdown?
  "When true (default false) and `:format-markdown` is true, stream assistant answer
  tokens block-by-block through the Markdown renderer. Paragraphs and fenced code blocks
  emit as soon their boundary is recognized; tables, list continuations, and other
  blocks may remain buffered until a terminator (blank line, closing fence) arrives.
  Set `:cli :chat-stream-live-markdown true` to enable."
  []
  (true? (:chat-stream-live-markdown (cli-cfg))))

(defn format-markdown?
  "When true (default), assistant replies are rendered as CommonMark with ANSI styles.
  Answer text is buffered for the round (not token-streamed) so layout, pipe tables, etc. are correct.
  Set `:cli :format-markdown false` for plain cyan text with optional live streaming per
  `:chat-stream-live-content`."
  []
  (not (false? (:format-markdown (cli-cfg)))))

(defn chat-tool-loop-limit
  "Max successive tool rounds (each LLM request after tool results counts as one step).
  **Omit** `:cli :chat-tool-loop-limit` (or set `null` in merged EDN) for **no limit** — the loop runs until the model returns text (or error).
  If set, must be a **positive integer** (no upper cap)."
  []
  (let [v (:chat-tool-loop-limit (cli-cfg))]
    (when (and (number? v) (pos? (long v)))
      (long v))))

(defn- chron-cfg []
  (:chron (grog) {}))

(defn chron-scheduler-enabled?
  "True when `:chron {:enabled true}` and `:tasks` is non-empty."
  []
  (let [c (chron-cfg)]
    (and (true? (:enabled c))
         (sequential? (:tasks c))
         (seq (:tasks c)))))

(defn chron-tasks
  "Task maps: `:id` (string), `:instruction` (string), and either `:every-minutes` (number) or `:interval-seconds` (number)."
  []
  (vec (filter map? (:tasks (chron-cfg)))))

(defn jobs-thread-context-turns
  "`:jobs {:max-thread-turns N}` — dialog turns loaded for jobs/chron (default 40)."
  []
  (let [v (get-in (grog) [:jobs :max-thread-turns])]
    (if (and (number? v) (pos? (long v)))
      (long v)
      40)))

(defn- skills-cfg []
  (:skills (grog) {}))

(defn skills-configured?
  "True when `:skills :roots` is a non-empty sequence of paths (absolute or repo-root-relative)."
  []
  (let [roots (:roots (skills-cfg))]
    (boolean (and (sequential? roots) (seq roots)))))

(defn skills-roots
  "Non-blank paths from `:skills :roots` (strings), in order."
  []
  (->> (:roots (skills-cfg) [])
       (map #(str/trim (str %)))
       (remove str/blank?)
       vec))

(defn skills-max-body-chars
  "Max characters returned by read_skill for the body; default 65536, cap 500000."
  []
  (let [v (:max-body-chars (skills-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 500000 (long v))
      65536)))

(defn skills-prompt-skill-lines
  "Max skill one-liners injected into the system prompt; default 16, cap 64."
  []
  (let [v (:prompt-skill-lines (skills-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 64 (long v))
      16)))

(defn- with-api-key-cfg []
  (:with-api-key (grog) {}))

(defn with-api-key-allowed-accounts
  "Secret names the model may pass as `with_api_key` :secret_name. Any stored
  secret name is acceptable here (declared or not); this list — not the
  `:secrets {:accounts …}` registry — is the real gate.
  Config: `:allowed-secrets` (preferred) and/or legacy `:allowed-accounts` — merged and deduplicated."
  []
  (let [cfg (with-api-key-cfg)]
    (->> (concat (:allowed-secrets cfg []) (:allowed-accounts cfg []))
         (map #(str/trim (str %)))
         (remove str/blank?)
         distinct
         vec)))

(defn with-api-key-url-prefixes
  "If non-empty, `with_api_key` URLs must start with one of these strings (after trim)."
  []
  (when-let [xs (:allowed-url-prefixes (with-api-key-cfg))]
    (when (and (sequential? xs) (seq xs))
      (->> xs (map #(str/trim (str %))) (remove str/blank?) vec))))

(defn with-api-key-max-response-chars
  "Max response body chars returned to the model; default 256000, cap 2e6."
  []
  (let [v (:max-response-chars (with-api-key-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 2000000 (long v))
      256000)))

(defn with-api-key-allow-http?
  "When true, http:// URLs are allowed (default false — https only)."
  []
  (true? (:allow-insecure-http (with-api-key-cfg))))

(defn with-api-key-configured?
  "True when :with-api-key :allowed-secrets and/or :allowed-accounts is non-empty.
  Entries need not be declared in `:secrets {:accounts …}` — the allowlist itself
  is what authorizes a name."
  []
  (boolean (seq (with-api-key-allowed-accounts))))

(defn- babashka-cfg []
  (:babashka (grog) {}))

(defn babashka-configured?
  "Babashka is always enabled (a given) — `run_babashka` is exposed to the model."
  []
  true)

(defn babashka-command
  "Shell command for Babashka (default `bb`). Override with `:babashka :command`."
  []
  (or (some-> (:command (babashka-cfg)) str str/trim not-empty) "bb"))

(defn babashka-max-script-chars
  []
  (let [v (:max-script-chars (babashka-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 500000 (long v))
      128000)))

(defn babashka-default-timeout-sec
  []
  (let [v (:timeout-seconds (babashka-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 300 (long v))
      30)))

(defn babashka-max-timeout-sec
  []
  300)

(defn babashka-max-stdout-chars
  []
  (let [v (:max-stdout-chars (babashka-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 2000000 (long v))
      256000)))

(defn babashka-max-stderr-chars
  []
  (let [v (:max-stderr-chars (babashka-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 256000 (long v))
      32768)))

(defn- http-status-in-chain
  [^Throwable e]
  (loop [t e]
    (when t
      (or (when-let [d (ex-data t)]
            (or (:status d)
                (when (map? (:object d)) (:status (:object d)))))
          (recur (.getCause t))))))

(defn warn-if-model-missing!
  "No-op: model availability is server-side for OpenAI-compatible providers."
  []
  nil)

(defn print-llm-failure-hint!
  "Print LLM failure diagnostics."
  [^Throwable e]
  (try
    (let [st (http-status-in-chain e)
          m (model)
          url (llm-url)]
      (binding [*out* *err*]
        (println "")
        (cond
          (some? st)
          (do (println "grog: LLM HTTP" st "-" (.getMessage e))
              (println "       :llm :model" (pr-str m) "— :url" url)
              (when (and (= 401 st) (not (llm-api-key)))
                (println "       No API key found. Set :llm :api-key or store LLM_API_KEY in the secret store (/secret set LLM_API_KEY <key>).")))
          :else
          (do (println "grog: LLM request failed:" (.getMessage e))
              (println "       :llm :model" (pr-str m) "— :url" url)))
        (println "")))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Operational knobs — configured in grog.edn (env vars remain explicit
;; overrides for one-off runs; the config file is the source of truth).
;; ---------------------------------------------------------------------------

(defn- mcp-cfg [] (:mcp (grog) {}))

(defn mcp-idle-timeout-ms
  "Stop a running MCP server after this much idle time (`:mcp :idle-timeout-ms`,
  default 900000 = 15 min). Env override: `GROG_MCP_IDLE_TIMEOUT_MS`."
  []
  (or (some-> (System/getenv "GROG_MCP_IDLE_TIMEOUT_MS") str str/trim parse-long)
      (let [v (:idle-timeout-ms (mcp-cfg))]
        (when (and (number? v) (pos? (long v))) (long v)))
      900000))

(defn- terminal-cfg [] (:terminal (grog) {}))

(defn shell-command
  "Shell for the terminal window and `/shell` (`:terminal :shell`).
  Default: `$SHELL`, else `bash`."
  ^String []
  (or (some-> (:shell (terminal-cfg)) str str/trim not-empty)
      (some-> (System/getenv "SHELL") str str/trim not-empty)
      "bash"))

(defn ollama-host
  "Ollama base URL used to list local models (`:llm :ollama-host`).
  Default: `$OLLAMA_HOST`, else `http://localhost:11434`."
  ^String []
  (or (some-> (get-in (grog) [:llm :ollama-host]) str str/trim not-empty)
      (some-> (System/getenv "OLLAMA_HOST") str str/trim not-empty)
      "http://localhost:11434"))

(defn tessdata-dir
  "Tesseract data dir override (`:imaging :tessdata`), or nil to use the
  default/`$TESSDATA_PREFIX`."
  []
  (or (some-> (get-in (grog) [:imaging :tessdata]) str str/trim not-empty)
      (some-> (System/getenv "TESSDATA_PREFIX") str str/trim not-empty)))
