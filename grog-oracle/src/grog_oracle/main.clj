(ns grog-oracle.main
  "grog-oracle — a standalone MCP server (over stdio) exposing the **oracle** tool so
  an ECA-driven agent loop can consult a stronger remote model (OpenAI-compatible
  `/chat/completions`).

  This restores grog's `oracle` tool on the ECA/MCP surface. The old grog GUI
  shipped oracle in its own tool loop (`grog.oracle`); once the GUI was rewired
  onto ECA, ECA's model loop only sees MCP servers, so oracle went missing. This
  server restores it with the same behaviour and the same secret storage:

    * API key: OS keyring, service `grog`, account `ORACLE_API_KEY`
      (writable from grog chat with `/secret ORACLE_API_KEY <key>`).
    * Endpoint/model: from `grog.edn` `:oracle {:url … :model …}` (absolute or
      repo-root-relative path, resolved via GROG_HOME / cwd), or override with
      env `GROG_ORACLE_URL` / `GROG_ORACLE_MODEL`.
    * Tool: `oracle` — `query` (required), self-contained.

  ECA discovers this over stdio like the other grog servers:
    :mcpServers
      {\"grog-oracle\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-oracle' && clojure -M:mcp -m grog-oracle.main\"]}}
  "
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http])
  (:import [com.github.javakeyring BackendNotSupportedException Keyring PasswordAccessException]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private service-id "grog")
(def ^:private oracle-api-account "ORACLE_API_KEY")

(defn- repo-root
  "The grog repo root, resolved like grog.config (GROG_HOME env, grog.home prop, else cwd)."
  ^String []
  (or (some-> (System/getProperty "grog.home") str str/trim not-empty)
      (some-> (System/getenv "GROG_HOME") str str/trim not-empty)
      "."))

(defn- edn-file
  "Find a grog.edn: absolute/as-given, else under <repo-root>."
  ^java.io.File []
  (let [candidates [(io/file (System/getProperty "user.home") ".config" "grog" "grog.edn")
                    (io/file (repo-root) "grog.edn")]]
    (first (filter #(.isFile ^java.io.File %) candidates))))

(defn- slurp-edn [^java.io.File f]
  (when (and f (.exists f))
    (try
      (clojure.edn/read-string (slurp f :encoding "UTF-8"))
      (catch Exception _ nil))))

(defn- oracle-config
  "Deep-merged :oracle map from grog.edn(s), overridable via env."
  []
  (let [base (or (some-> (edn-file) slurp-edn :oracle) {})]
    (merge
      base
      (when-let [u (some-> (System/getenv "GROG_ORACLE_URL") str str/trim not-empty)]
        {:url u})
      (when-let [m (some-> (System/getenv "GROG_ORACLE_MODEL") str str/trim not-empty)]
        {:model m}))))

(defonce ^:private !keyring-unreachable (atom false))

(defn- fetch-secret-blocking! ^String [^String account]
  (with-open [^Keyring kr (Keyring/create)]
    (try
      (let [^String p (.getPassword kr service-id account)]
        (some-> p str str/trim not-empty))
      (catch PasswordAccessException _ nil))))

(defn- api-key
  "Oracle bearer token from the OS keyring, or nil (with a time-bounded read)."
  ^String []
  (when-not @!keyring-unreachable
    (try
      (let [f (future
                (try
                  (fetch-secret-blocking! oracle-api-account)
                  (catch BackendNotSupportedException _ ::unsupported)
                  (catch Exception _ ::error)))
            v (deref f 4000 ::timeout)]
        (cond
          (= ::timeout v)
          (do (reset! !keyring-unreachable true)
              (binding [*out* *err*]
                (println "grog-oracle: OS keyring did not respond within 4s; secret reads disabled (D-Bus / Secret Service?)."))
              nil)
          (= ::unsupported v) nil
          (= ::error v) nil
          :else v))
      (catch Exception _ nil))))

(defn- oracle-configured? []
  (boolean
    (when (and (some-> (get (oracle-config) :url) str str/trim not-empty)
               (some-> (get (oracle-config) :model) str str/trim not-empty))
      (some-> (api-key) not-empty))))

;; ---------------------------------------------------------------------------
;; Oracle implementation (ported from grog.oracle)
;; ---------------------------------------------------------------------------

(defn- parse-oracle-args
  "Parse the `query` argument (map, Jackson map, or JSON string)."
  [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(str k) v])) arguments)
            (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
            :else {})
        q (or (:query m) (get m "query") (get m :query))]
    {:query (some-> q str str/trim not-empty)}))

(defn- extract-completion-text [parsed]
  (when (map? parsed)
    (some-> parsed :choices first :message :content str str/trim not-empty)))

(defn- run-oracle!
  "Send one self-contained query to the remote model. Returns a string for the model."
  [arguments]
  (let [{:keys [query]} (parse-oracle-args arguments)]
    (cond
      (not (oracle-configured?))
      (str "oracle is not configured: set :oracle {:url … :model …} in grog.edn and "
           "store the API token in the OS keyring as \"" oracle-api-account "\" "
           "(e.g. /secret in grog chat).")

      (str/blank? query)
      "oracle error: missing or empty `query`."

      :else
      (try
        (let [cfg (oracle-config)
              url (str/trim (str (:url cfg)))
              model (str/trim (str (:model cfg)))
              api-key (api-key)
              max-tokens (let [v (:max-tokens cfg)] (when (and (number? v) (pos? (long v))) (long v)))
              temperature (let [v (:temperature cfg)] (when (number? v) (double v)))
              body-map (cond-> {:model model
                                :messages [{:role "user" :content query}]}
                         max-tokens (assoc :max_tokens max-tokens)
                         temperature (assoc :temperature temperature))
              resp (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"
                                         "Accept" "application/json"}
                               :body (json/generate-string body-map)
                               :as :json
                               :throw-exceptions false})
              st (:status resp)
              parsed (:body resp)]
          (cond
            (= 200 st)
            (let [text (some-> (extract-completion-text parsed) str/trim not-empty)]
              (if (str/blank? text)
                "oracle: remote model returned an empty reply."
                (str "## Oracle reply\n\n" text)))

            (and (map? parsed) (map? (:error parsed)))
            (str "oracle error: " (or (some-> parsed :error :message str) (pr-str (:error parsed))))

            :else
            (str "oracle HTTP " st ": " (pr-str parsed))))
        (catch Exception e
          (str "oracle failed: " (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; MCP server wiring (same pattern as grog-search)
;; ---------------------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  "Build an async MCP tool spec from {:name :description :schema-json :fn}."
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try
                (.success sink (text-result (fn arguments)))
                (catch Throwable t
                  (.success sink (error-result
                                  (str "Error executing tool " name ": "
                                       (or (:message (ex-data t)) (.getMessage t))))))))))))))

(defn- tool-spec []
  {:name "oracle"
   :description (str "Consult the oracle — a stronger remote model configured in grog.edn :oracle. "
                     "Send ONE self-contained query (all context it needs; it does not see other chat turns). "
                     "Use after a real attempt when you still lack depth, the user wants expert-level help, "
                     "or you are materially uncertain on something high-stakes. Do not use for small talk, "
                     "obvious answers, or work solvable with brave_web_search / assoc_* / files alone.")
   :schema (json/generate-string {:type :object
                            :properties {:query {:type :string
                                                 :description "Single, self-contained question for the remote model."}}
                            :required ["query"]})
   :fn run-oracle!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-oracle" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop []
    (Thread/sleep 1000)
    (recur)))
