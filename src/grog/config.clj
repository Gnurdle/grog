(ns grog.config
  "Loads `grog.edn` only (no environment-variable overrides).

  Merge order (later wins): classpath `resources/grog.edn` →
  `~/.config/grog/grog.edn` → `./grog.edn` in the current working directory."
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
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

(defn model
  "`:ollama :model` from grog.edn (required)."
  []
  (req-str [:ollama :model] ":ollama :model"))

(defn ollama-url
  "`:ollama :url` from grog.edn (required)."
  []
  (req-str [:ollama :url] ":ollama :url"))

(defn secret
  "Looks up `[:secrets k]` in merged config; blank strings are ignored."
  [k]
  (some-> (get-in (grog) [:secrets k]) str str/trim not-empty))

(defn brave-search-configured?
  "True when `:secrets :brave-search-api-key` is set (Brave web search tool enabled for Ollama)."
  []
  (boolean (secret :brave-search-api-key)))

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
  `0` — stateless; `nil`/omit — unlimited (session can grow large)."
  []
  (:chat-history-turns (cli-cfg)))

(defn chat-show-thinking?
  "When true, print Ollama `:thinking` if present. Config `:cli :chat-show-thinking`;
  omitted uses JVM console detection."
  []
  (let [v (:chat-show-thinking (cli-cfg))]
    (cond
      (false? v) false
      (true? v) true
      :else (some? (System/console)))))

(defn chat-stream-live-thinking?
  "When true (default) and `chat-show-thinking?`, use Ollama `stream: true` so reasoning
  prints incrementally. Set `:cli :chat-stream-live-thinking false` to buffer and print once."
  []
  (not (false? (:chat-stream-live-thinking (cli-cfg)))))

(defn chat-stream-live-content?
  "When true (default), stream assistant answer tokens to the terminal as they arrive.
  Set `:cli :chat-stream-live-content false` to buffer the reply and print once."
  []
  (not (false? (:chat-stream-live-content (cli-cfg)))))

(defn format-markdown?
  "When true (default), assistant replies are rendered as CommonMark with ANSI styles.
  Answer text is buffered for the whole round when streaming would otherwise print tokens,
  so layout is correct. Set `:cli :format-markdown false` for plain cyan text."
  []
  (not (false? (:format-markdown (cli-cfg)))))

(defn chat-tool-loop-limit
  "Max successive tool rounds (model returns `tool_calls` → Grog runs them → model again).
  Config `:cli :chat-tool-loop-limit` — positive integer, default 8, capped at 1000."
  []
  (let [v (:chat-tool-loop-limit (cli-cfg))]
    (if (and (number? v) (pos? (long v)))
      (min 1000 (long v))
      8)))

(defn- http-status-in-chain
  [^Throwable e]
  (loop [t e]
    (when t
      (or (when-let [d (ex-data t)]
            (or (:status d)
                (when (map? (:object d)) (:status (:object d)))))
          (recur (.getCause t))))))

(defn ollama-installed-model-names
  "Set of model names from Ollama `GET /api/tags`, or nil if not 200."
  []
  (try
    (let [resp (http/get (str (str/trim (ollama-url)) "/api/tags")
                         {:as :json :throw-exceptions false})
          raw (:body resp)]
      (when (= 200 (:status resp))
        (->> (:models raw)
             (keep :name)
             (map str)
             set)))
    (catch Exception _ nil)))

(defn warn-if-ollama-model-missing!
  []
  (try
    (when-let [have (ollama-installed-model-names)]
      (let [want (model)]
        (when-not (have want)
          (binding [*out* *err*]
            (println "")
            (println "grog: warning: Ollama has no model" (pr-str want) "(see `ollama ls`).")
            (when (seq have)
              (println "        Models present:" (str/join ", " (sort have))))
            (println "        Install with: ollama pull" want)
            (println "        Or change :ollama :model in grog.edn")
            (println "")))))
    (catch Exception _ nil)))

(defn print-ollama-failure-hint!
  [^Throwable e]
  (try
    (let [st (http-status-in-chain e)
          m (model)
          url (ollama-url)
          have (try (ollama-installed-model-names) (catch Exception _ nil))]
      (binding [*out* *err*]
        (println "")
        (cond
          (= 404 st)
          (do (println "grog: Ollama HTTP 404 — often the configured :model is missing.")
              (println "       :ollama :model" (pr-str m) "— :url" url)
              (println "       Run: ollama pull" m)
              (when (seq have)
                (println "       This server reports models:" (str/join ", " (sort have)))))

          (some? st)
          (println "grog: Ollama HTTP" st "-" (.getMessage e))

          :else
          (println "grog: Ollama request failed:" (.getMessage e)))
        (println "")))
    (catch Exception _ nil)))
