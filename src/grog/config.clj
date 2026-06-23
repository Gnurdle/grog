(ns grog.config
  "Loads `grog.edn` only (no environment-variable overrides).

  Merge order (later wins): classpath `resources/grog.edn` →
  `~/.config/grog/grog.edn` → `./grog.edn` in the current working directory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.secrets :as secrets])
  (:import (java.io File)))

(defn deep-merge
  "Recursively merge maps; non-map values from `b` replace `a`."
  [a b]
  (merge-with (fn [x y]
                (if (and (map? x) (map? y))
                  (deep-merge x y)
                  y))
              a b))

(defn- slurp-edn [^File f]
  (when (and f (.exists f) (.isFile f))
    (try (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))
         (catch Exception _ nil))))

(defn- resource-edn [name]
  (when-let [r (io/resource name)]
    (try (edn/read-string {:eof nil} (slurp r :encoding "UTF-8"))
         (catch Exception _ nil))))

(defn load-merge!
  "Load and deep-merge all config fragments (does not touch the cache atom)."
  []
  (let [home-file (io/file (System/getProperty "user.home") ".config" "grog" "grog.edn")
        cwd-file (io/file "grog.edn")
        fragments (remove nil?
                    [(resource-edn "grog.edn")
                     (slurp-edn home-file)
                     (slurp-edn cwd-file)])]
    (reduce deep-merge {} fragments)))

(defonce ^:private !cfg (atom nil))

(defn reload!
  "Re-read config files from disk (REPL / tests)."
  []
  (reset! !cfg (load-merge!)))

(defn grog
  "Merged configuration map."
  []
  (swap! !cfg (fn [cur] (or cur (load-merge!)))))

(defn- req-str [path label]
  (or (some-> (get-in (grog) path) str str/trim not-empty)
      (throw (ex-info (str "grog.edn missing required " label)
                      {:path path}))))

(defn workspace-root
  "`:workspace :default-root` from grog.edn, default `\".\"` (used to resolve relative SOUL.md)."
  []
  (let [r (get-in (grog) [:workspace :default-root])]
    (if (str/blank? (str r))
      "."
      (str/trim (str r)))))

(defn- interpolate-env-var
  "Replace `${ENV}` and `${ENV:-default}` in a string with environment variable values."
  [^String s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[_ var-spec]]
                   (let [[var-name default-val] (str/split var-spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))))

(defn- cfg-str [path]
  (some-> (get-in (grog) path) str str/trim not-empty))

(defn llm-url
  "Chat completions POST URL. Use :llm :url."
  []
  (req-str [:llm :url] ":llm :url"))

(defn llm-model
  "Model id. Use :llm :model."
  []
  (req-str [:llm :model] ":llm :model"))

(defn llm-api-key
  "API key for OpenAI-compatible providers. Supports `${ENV}` and `${ENV:-default}` interpolation.
   Reads :llm :api-key (inline, not recommended) or OS keyring LLM_API_KEY."
  []
  (or (some-> (cfg-str [:llm :api-key]) interpolate-env-var not-empty)
      (some-> (secrets/get-secret "LLM_API_KEY") not-empty)))

(defn llm-auth-headers
  "Authorization headers. Bearer token when an API key is configured."
  []
  (when-let [key (llm-api-key)]
    {"Authorization" (str "Bearer " key)}))

(defn llm-max-tokens
  "Max tokens for LLM requests. Default nil (provider default)."
  []
  (let [v (get-in (grog) [:llm :max-tokens])]
    (when (and (number? v) (pos? (long v)))
      (long v))))

(defn llm-temperature
  "Temperature for LLM requests. Default nil (provider default)."
  []
  (let [v (get-in (grog) [:llm :temperature])]
    (when (number? v) (double v))))

(defn llm-debug-payload?
  "When true, prints full request payload to stderr."
  []
  (true? (get-in (grog) [:llm :debug-payload])))

(defn llm-debug-response?
  "When true, prints the raw accumulated response content to stderr before rendering."
  []
  (true? (get-in (grog) [:llm :debug-response])))

(defn max-context-tokens
  "Context token budget. When set, oldest non-system messages are dropped before each
   request so the total stays under this limit. Rough estimate (~4 chars/token).
   Default 200000 to stay safely under common 256K–262K provider limits."
  []
  (let [v (get-in (grog) [:llm :max-context-tokens])]
    (cond
      (nil? v) 200000
      (and (number? v) (pos? (long v))) (long v)
      :else nil)))

(defn max-tool-result-chars
  "Max characters for individual tool results. Results longer than this are truncated
   with a note. Default 50000 (~10–15K tokens). Set to nil in grog.edn to disable."
  []
  (let [v (get-in (grog) [:llm :max-tool-result-chars])]
    (cond
      (nil? v) 50000
      (and (number? v) (pos? (long v))) (long v)
      :else nil)))

(defn llm-extra-payload
  "Provider-specific fields merged into every /v1/chat/completions request payload.
   E.g. OpenRouter {:transforms [\"middle-out\"]} or {:plugins {…}}.
   Deep-merged after the standard payload fields so it can override them."
  []
  (get-in (grog) [:llm :extra-payload]))

;; Backward-compat alias — old code calls config/model
(defn model
  "Model id. Delegates to `llm-model`."
  []
  (llm-model))

(defn provider-name
  "Human-readable provider name for status lines."
  []
  (or (some-> (cfg-str [:llm :provider-name]) not-empty)
      "OpenAI-compatible"))

(defn oracle-url
  "`:oracle :url` — OpenAI-compatible chat completions POST URL."
  []
  (some-> (get-in (grog) [:oracle :url]) str str/trim not-empty))

(defn oracle-model
  "`:oracle :model` — remote model id (e.g. grok-2-latest)."
  []
  (some-> (get-in (grog) [:oracle :model]) str str/trim not-empty))

(defn oracle-max-tokens
  "`:oracle :max-tokens` — default 4096, capped at 128000."
  []
  (let [v (get-in (grog) [:oracle :max-tokens])]
    (if (and (number? v) (pos? (long v)))
      (min 128000 (long v))
      4096)))

(defn oracle-temperature
  "`:oracle :temperature` — default 0.5."
  []
  (let [v (get-in (grog) [:oracle :temperature])]
    (if (number? v) (double v) 0.5)))

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
    (str "In project: " p " — prompt \"" p " >\"; memory under Projects/" p "/…")
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
  "True when `:skills :roots` is a non-empty sequence of paths (under workspace when relative)."
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
  "Keyring secret names the model may pass as `with_api_key` :secret_name (each must be `secrets/known-account?`).
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
  "True when :with-api-key :allowed-secrets and/or :allowed-accounts is non-empty and every entry is a known keyring account."
  []
  (let [accts (with-api-key-allowed-accounts)]
    (boolean
      (and (seq accts)
           (every? #(secrets/known-account? %) accts)))))

(defn- babashka-cfg []
  (:babashka (grog) {}))

(defn babashka-configured?
  "True when `:babashka :enabled` is true — exposes `run_babashka` to the model."
  []
  (true? (:enabled (babashka-cfg))))

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
                (println "       No API key found. Set :llm :api-key or store LLM_API_KEY in OS keyring.")))
          :else
          (do (println "grog: LLM request failed:" (.getMessage e))
              (println "       :llm :model" (pr-str m) "— :url" url)))
        (println "")))
    (catch Exception _ nil)))
