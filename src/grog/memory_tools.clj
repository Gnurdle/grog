(ns grog.memory-tools
  "Ollama tools for persistent memory under `:edn-store` in grog.edn (tree of `.edn` files).
  Logical keys are `namespace` + `key`. On disk: `grog-memory/<url-encoded-ns>/<url-encoded-key>.edn`.
  When a **session active project** is set (`/project <name>` in chat), paths become
  `grog-memory/Projects/<url-encoded-project>/<url-encoded-ns>/<url-encoded-key>.edn`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.config :as cfg]
            [grog.edn-store :as store])
  (:import [java.net URLDecoder URLEncoder]))

(def ^:private ^String mem-root "grog-memory")
(def ^:private ^String namespace-marker-key "__grog_namespace__")

(defn- enc-seg ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- dec-seg ^String [^String s]
  (URLDecoder/decode s "UTF-8"))

(defn- mem-keypath [namespace memory-key]
  (if-let [p (cfg/active-project-name)]
    [mem-root "Projects" (enc-seg p) (enc-seg namespace) (enc-seg memory-key)]
    [mem-root (enc-seg namespace) (enc-seg memory-key)]))

(defn- ns-dir-segments [namespace]
  (if-let [p (cfg/active-project-name)]
    [mem-root "Projects" (enc-seg p) (enc-seg namespace)]
    [mem-root (enc-seg namespace)]))

(defn- parse-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                 (catch Exception _ {}))
        :else {}))

(defn- coerce-seg-value
  "Normalize tool arg to non-blank trimmed string (keywords/symbols use `name`, not `str`)."
  [x]
  (when (some? x)
    (let [s (cond (keyword? x) (name x)
                  (symbol? x) (name x)
                  :else (str x))
          t (str/trim s)]
      (when-not (str/blank? t) t))))

(defn- arg-namespace [m]
  (or (coerce-seg-value (:namespace m))
      (coerce-seg-value (:Namespace m))
      (coerce-seg-value (get m "namespace"))
      (coerce-seg-value (get m "Namespace"))))

(defn- arg-memory-key [m]
  (or (coerce-seg-value (:key m))
      (coerce-seg-value (:Key m))
      (coerce-seg-value (get m "key"))
      (coerce-seg-value (get m "Key"))))

(defn- ns-and-mem-key [m]
  (let [ns (arg-namespace m)
        k (arg-memory-key m)]
    (when-not ns
      (throw (ex-info "namespace is required" {})))
    (when-not k
      (throw (ex-info "key is required" {})))
    [ns k]))

(defn- namespace-only [m]
  (let [ns (arg-namespace m)]
    (when-not ns
      (throw (ex-info "namespace is required" {})))
    ns))

(defn- truthy-delete-entire-namespace? [m]
  (let [x (or (:delete_entire_namespace m)
              (:deleteEntireNamespace m)
              (get m "delete_entire_namespace")
              (get m "deleteEntireNamespace"))]
    (cond (boolean? x) x
          (string? x) (#{"true" "1" "yes"} (str/lower-case (str/trim ^String x)))
          (number? x) (not (zero? (long x)))
          :else false)))

(defn- require-store! []
  (when-not (store/configured?)
    (throw (ex-info "edn-store not configured — set :edn-store {:root \"…\"} in grog.edn" {}))))

(defn- value->tool-string [v]
  (cond
    (nil? v) nil
    (string? v) v
    :else (pr-str v)))

(defn tool-specs
  "Ollama function tools; register only when `:edn-store` is configured."
  []
  [{:type "function"
    :function
    {:name "memory_save"
     :description (str "Persist a value in durable memory under :edn-store (one .edn file per namespace+key). "
                       "With an active project, files live under Projects/<project>/…. "
                       "`value` should be plain text or JSON as a string.")
     :parameters {:type "object"
                  :required ["namespace" "key" "value"]
                  :properties {:namespace {:type "string"}
                               :key {:type "string"}
                               :value {:type "string"}}}}}
   {:type "function"
    :function
    {:name "memory_load"
     :description "Load one value from memory by namespace + key. Returns JSON with :value or :found false."
     :parameters {:type "object"
                  :required ["namespace" "key"]
                  :properties {:namespace {:type "string"}
                               :key {:type "string"}}}}}
   {:type "function"
    :function
    {:name "memory_list_keys"
     :description (str "List keys under a namespace. Optional key_prefix filters key names; "
                       "optional positive limit caps count. Each entry includes full :value.")
     :parameters {:type "object"
                  :required ["namespace"]
                  :properties {:namespace {:type "string"}
                               :key_prefix {:type "string"}
                               :limit {:type "integer"}}}}}
   {:type "function"
    :function
    {:name "memory_create_namespace"
     :description (str "Optional marker file for a namespace (reserved key __grog_namespace__). "
                       "Idempotent if marker already exists.")
     :parameters {:type "object"
                  :required ["namespace"]
                  :properties {:namespace {:type "string"}
                               :description {:type "string"}}}}}
   {:type "function"
    :function
    {:name "memory_delete"
     :description (str "Delete one key or the whole namespace. With an active project, that namespace is under "
                       "Projects/<project>/….")
     :parameters {:type "object"
                  :required ["namespace"]
                  :properties {:namespace {:type "string"}
                               :key {:type "string"}
                               :delete_entire_namespace {:type "boolean"
                                                         :description "also accepted: deleteEntireNamespace (camelCase)"}}}}}])

(defn tool-log-summary
  [tool-name arguments]
  (let [m (parse-args arguments)]
    (case tool-name
      "memory_list_keys"
      (select-keys m [:namespace :key_prefix :limit])
      "memory_create_namespace"
      (select-keys m [:namespace :description])
      "memory_delete"
      (select-keys m [:namespace :Namespace :key :Key
                      :delete_entire_namespace :deleteEntireNamespace])
      (select-keys m [:namespace :key]))))

(defn run-memory-save!
  [arguments]
  (try
    (require-store!)
    (let [m (parse-args arguments)
          [ns mem-k] (ns-and-mem-key m)
          v (or (:value m) (:Value m) (get m "value") (get m "Value"))
          v (if (string? v) v (json/generate-string v))]
      (store/write-leaf! (mem-keypath ns mem-k) v)
      (json/generate-string {:ok true :namespace ns :key mem-k}))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-memory-load!
  [arguments]
  (try
    (require-store!)
    (let [m (parse-args arguments)
          [ns mem-k] (ns-and-mem-key m)
          v (store/read-leaf (mem-keypath ns mem-k))]
      (if (nil? v)
        (json/generate-string {:found false :namespace ns :key mem-k})
        (json/generate-string {:found true :namespace ns :key mem-k :value (value->tool-string v)})))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-memory-list-keys!
  [arguments]
  (try
    (require-store!)
    (let [m (parse-args arguments)
          ns (namespace-only m)
          key-prefix (or (some-> (:key_prefix m) str)
                          (some-> (get m "key_prefix") str)
                          "")
          lim-raw (or (:limit m) (get m "limit"))
          cap (when (number? lim-raw)
                (let [n (long lim-raw)]
                  (when (pos? n) n)))
          files (store/list-edn-files-in-subdir (ns-dir-segments ns))
          parsed (keep (fn [^java.io.File f]
                         (let [nm (.getName f)
                               stem (subs nm 0 (- (count nm) 4))
                               mem-k (try (dec-seg stem) (catch Exception _ nil))]
                           (when mem-k
                             (when (str/starts-with? mem-k key-prefix)
                               (when-let [v (store/read-leaf (mem-keypath ns mem-k))]
                                 {:key mem-k :value (value->tool-string v)})))))
                       files)
          out (vec (if cap (take cap parsed) parsed))]
      (json/generate-string {:namespace ns
                             :key_prefix key-prefix
                             :limit cap
                             :count (count out)
                             :keys out}))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-memory-create-namespace!
  [arguments]
  (try
    (require-store!)
    (let [m (parse-args arguments)
          ns (namespace-only m)
          desc (or (some-> (:description m) str)
                   (some-> (get m "description") str)
                   "")
          kp (mem-keypath ns namespace-marker-key)
          existing (store/read-leaf kp)]
      (if (some? existing)
        (json/generate-string {:ok true :namespace ns :already_existed true})
        (do
          (store/write-leaf! kp (if (str/blank? desc)
                                    {:grog.namespace/marker true}
                                    {:grog.namespace/marker true :description desc}))
          (json/generate-string {:ok true :namespace ns :created true :marker_key namespace-marker-key}))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-memory-delete!
  [arguments]
  (try
    (require-store!)
    (let [m (parse-args arguments)
          ns (namespace-only m)
          del-all? (truthy-delete-entire-namespace? m)
          mem-k (arg-memory-key m)]
      (when (and (not del-all?) (str/blank? mem-k))
        (throw (ex-info "key is required unless delete_entire_namespace is true" {})))
      (if del-all?
        (let [files (store/list-edn-files-in-subdir (ns-dir-segments ns))
              n (count files)]
          (store/delete-subtree! (ns-dir-segments ns))
          (json/generate-string {:ok true
                                 :namespace ns
                                 :delete_entire_namespace true
                                 :keys_deleted (long n)}))
        (let [deleted? (store/delete-leaf! (mem-keypath ns mem-k))]
          (json/generate-string {:ok true
                                 :namespace ns
                                 :key mem-k
                                 :keys_deleted (long (if deleted? 1 0))}))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))
