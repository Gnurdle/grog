(ns grog.models
  "Read/write of the :llm (model/provider) section of grog.edn for the Models
  settings tab, plus fetching the available model lists from OpenRouter and a
  local Ollama server. Writes are atomic and preserve every other top-level key."
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
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

(defn llm-config
  "The current :llm map from grog.edn (empty if absent)."
  []
  (or (:llm (read-map)) {}))

(defn save-llm!
  "Persist the whole :llm map into grog.edn atomically, preserving other keys."
  [m]
  (let [existing (or (read-map) {})
        updated (assoc existing :llm m)
        f (grogedn-file)
        tmp (io/file (str f ".tmp"))]
    (spit tmp (with-out-str (pp/pprint updated)))
    (io/copy tmp f)
    (when (.exists tmp) (.delete tmp)))
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

;; --- oracle config ---------------------------------------------------------

(defn oracle-config
  "The current :oracle map from grog.edn (empty if absent)."
  []
  (or (:oracle (read-map)) {}))

(defn save-oracle!
  "Persist the whole :oracle map into grog.edn atomically, preserving other keys."
  [m]
  (let [existing (or (read-map) {})
        updated (assoc existing :oracle m)
        f (grogedn-file)
        tmp (io/file (str f ".tmp"))]
    (spit tmp (with-out-str (pp/pprint updated)))
    (io/copy tmp f)
    (when (.exists tmp) (.delete tmp)))
  m)

(defn save-oracle-fields!
  "Merge `updates` into the saved :oracle config."
  [updates]
  (save-oracle! (merge (oracle-config) updates)))

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
