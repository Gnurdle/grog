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
            [grog.secrets :as secrets])
  (:import (java.io File)))

(defn windows?
  "True when running on a Microsoft Windows OS."
  []
  (platform/windows?))

(defn config-home-dir
  "Where grog keeps its user-level config and generated/secrets files (see
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

(defn eca-model
  "The model the ECA agent runs — `:eca :model`, else `:llm :model`.

  ONE MODEL BY DEFAULT: `:llm :model` is the model, and both grog's own calls
  and the agent use it. `:eca :model` exists only to run the agent on a
  DIFFERENT model than grog's own calls — set it and it wins.

  nil when neither is set (ECA then falls back to the `defaultModel` in its own
  ~/.config/eca/config.json)."
  []
  (or (some-> (get-in (grog) [:eca :model]) str str/trim not-empty)
      (some-> (get-in (grog) [:llm :model]) str str/trim not-empty)))

(defn eca-model-source
  "Which key `eca-model` took its value from: `:eca`, `:llm`, or nil.

  `grog.eca-config` needs this to qualify a `:llm`-sourced model correctly: its
  provider is then whatever `:llm :url` points at, and saying so explicitly
  stops an OpenRouter org like `deepseek/…` being read as a native provider of
  that name."
  []
  (cond
    (some-> (get-in (grog) [:eca :model]) str str/trim not-empty) :eca
    (some-> (get-in (grog) [:llm :model]) str str/trim not-empty) :llm
    :else nil))

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

(defn- interpolate-env-var
  "Replace `${ENV}` and `${ENV:-default}` in a string with environment variable values."
  [^String s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[_ var-spec]]
                   (let [[var-name default-val] (str/split var-spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))))

(defn llm-url
  "Chat completions POST URL. Use :llm :url."
  []
  (let [v (get-in (effective-llm-cfg) [:url])]
    (or (some-> v str str/trim not-empty)
        (throw (ex-info "grog.edn missing required :llm :url"
                        {:path [:llm :url]})))))

(defn llm-model
  "Model id. Use :llm :model."
  []
  (let [v (get-in (effective-llm-cfg) [:model])]
    (or (some-> v str str/trim not-empty)
        (throw (ex-info "grog.edn missing required :llm :model"
                        {:path [:llm :model]})))))

(defn llm-api-key
  "API key for OpenAI-compatible providers. Supports `${ENV}` and `${ENV:-default}` interpolation.
   Reads :llm :api-key (inline, not recommended) or OS keyring LLM_API_KEY.

   An ABSENT `:llm :api-key` means \"fall back to the keyring\" — a user who only
   ever ran `/secret set LLM_API_KEY …` has no `:api-key` at all. (This used to
   return nil without consulting the keyring, so their key was silently ignored.)

   To genuinely disable the key — a backend that needs none, e.g. local Ollama —
   set `:api-key false` (in the config, or as a session override)."
  []
  (let [override @!llm-override
        explicit? (contains? override :api-key)
        key-src (if explicit?
                  (:api-key override)
                  (:api-key (get-in (grog) [:llm] {})))]
    (cond
      (false? key-src)
      nil

      :else
      (or (some-> (some-> key-src str str/trim not-empty)
                  interpolate-env-var
                  not-empty)
          (when-not explicit?
            (some-> (secrets/get-secret "LLM_API_KEY") not-empty))))))

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
  "Context token budget. When set, oldest non-system messages are dropped before each
   request so the total stays under this limit. Rough estimate (~4 chars/token).
   Default 200000 to stay safely under common 256K–262K provider limits."
  []
  (let [v (get-in (effective-llm-cfg) [:max-context-tokens])]
    (cond
      (nil? v) 200000
      (and (number? v) (pos? (long v))) (long v)
      :else nil)))

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
