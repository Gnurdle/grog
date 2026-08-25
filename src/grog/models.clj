(ns grog.models
  "Read/write of the :llm (model/provider) section of grog.edn for the Models
  settings tab, plus fetching the available model lists from OpenRouter and a
  local Ollama server. Writes are atomic and preserve every other top-level key."
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.net URL)))

(defn- grogedn-file ^java.io.File [] (io/file "grog.edn"))

(defn- read-map
  "Whole grog.edn map (best effort); nil if unreadable/missing."
  []
  (try
    (let [f (grogedn-file)]
      (when (.exists f)
        (edn/read-string {:readers *data-readers*} (slurp f))))
    (catch Throwable _ nil)))

(defn- persist!
  "Atomically write `(f whole-grog.edn-map)` back to grog.edn, preserving every
  other top-level key."
  [f]
  (let [existing (or (read-map) {})
        updated (f existing)
        file (grogedn-file)
        tmp (io/file (str file ".tmp"))]
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

(defn save-eca-model!
  "Persist the GUI/ECA model as `:eca :model` in grog.edn atomically, preserving
  every other key — this is the model the GUI's ECA chat uses."
  [m]
  (persist! #(assoc-in % [:eca :model] (str m)))
  m)

;; --- fetching available models (OpenRouter + local Ollama) ------------------

(defn fetch-openrouter-models
  "Return a sorted, deduped list of model ids available on OpenRouter (best
  effort; empty on failure/offline)."
  []
  (try
    (let [resp (http/get "https://openrouter.ai/api/v1/models"
                         {:as :json :throw-exceptions false :socket-timeout 15000 :conn-timeout 5000})]
      (->> (get-in resp [:body :data])
           (map :id)
           (remove nil?)
           (distinct)
           (sort)))
    (catch Throwable _ [])))

(defn fetch-ollama-models
  "Return a sorted list of local Ollama model names (best effort; empty if Ollama
  isn't running)."
  []
  (try
    (let [base (or (System/getenv "OLLAMA_HOST") "http://localhost:11434")
          resp (http/get (str base "/api/tags")
                         {:as :json :throw-exceptions false :socket-timeout 5000 :conn-timeout 3000})]
      (->> (:models (:body resp))
           (map :name)
           (remove nil?)
           (sort)))
    (catch Throwable _ [])))

(defn fetch-models
  "Combine OpenRouter + Ollama model lists, labelled for the picker."
  []
  (concat (map #(hash-map :model % :source "openrouter") (fetch-openrouter-models))
          (map #(hash-map :model % :source "ollama") (fetch-ollama-models))))

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

(defn- provider-prefix-for-url
  "Guess the ECA provider prefix from an OpenAI-compatible base URL, or nil."
  ^String [url]
  (when url
    (let [u (str/lower-case (str url))]
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
        :else nil))))

(defn qualify-eca-model
  "Translate a raw model id — as stored in grog.edn, picked from a transport, or
  typed by the user — into the provider-qualified id ECA understands
  (`provider/model`).

  Rules, in order, when the input isn't blank:
  1. Exactly matches a model in ECA's catalog → returned unchanged.
  2. An explicit picker `source` (`ollama` / `openrouter`) wins — the raw ids
     returned by the OpenRouter fetch must be scoped to `openrouter/…` even when
     their org slug collides with a native provider name (`deepseek/…`, `openai/…`).
  3. A catalog lookup resolves the input as `openrouter/<id>` or `ollama/<id>`.
  4. The id already carries a known ECA provider prefix is returned unchanged.
  5. A multi-segment id whose first segment isn't a provider is an OpenRouter
     catalog id that lost its prefix (`moonshotai/kimi-k3`) → `openrouter/…`.
  6. Otherwise a bare id is scoped to the grog `:llm :url` default provider
     (localhost → `ollama/…`, openrouter.ai → `openrouter/…`, …).

  Returns nil for blank input."
  ([model] (qualify-eca-model model nil nil))
  ([model source] (qualify-eca-model model source nil))
  ([model source url]
   (let [m (str/trim (str (or model "")))
         seg (first (str/split m #"/"))
         src (some-> source str str/lower-case)
         multi? (> (count (str/split m #"/")) 1)
         catalog @eca-model-catalog*]
     (cond
       (str/blank? m) nil

       ;; already exactly what ECA exposes
       (contains? catalog m) m

       ;; explicit picker transport wins over every guess
       (#{"ollama" "openrouter"} src)
       (if (str/starts-with? m (str src "/"))
         m
         (str src "/" m))

       ;; catalog knows this id under a concrete provider
       (contains? catalog (str "openrouter/" m))
       (str "openrouter/" m)

       (contains? catalog (str "ollama/" m))
       (str "ollama/" m)

       ;; already carries a known ECA provider prefix (native provider or manual)
       (contains? eca-provider-segments (str/lower-case (str seg))) m

       ;; multi-segment non-provider first segment → OpenRouter catalog id
       multi?
       (str "openrouter/" m)

       :else
       (if-let [p (provider-prefix-for-url url)]
         (str p "/" m)
         m)))))
