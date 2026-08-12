(ns grog-search.main
  "grog-search — a standalone MCP server (over stdio) exposing **Brave Web Search** so an
  ECA-driven agent loop can search the public web.

  This is the \"Keep -> grog MCP\" path for grog's `brave_web_search` tool (see
  doc/gap-analysis-grog-vs-eca.md §6.5). The old grog GUI shipped Brave in its own
  tool loop (`grog.brave`); once the GUI was rewired onto ECA, ECA's model loop only
  sees MCP servers, so the tool went missing. This server restores it with the same
  behaviour and the same secret storage:

    * API key: OS keyring, service `grog`, account `BRAVE_SEARCH_API`
      (same as the original; also writable from grog chat with `/secret BRAVE_SEARCH_API <key>`).
    * Tool: `brave_web_search` — query (required), count (1-10, default 5).

  ECA discovers this over stdio like the other grog servers:
    :mcpServers
      {\"grog-search\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-search' && clojure -M:mcp -m grog-search.main\"]}}
  "
  (:require [clojure.data.json :as json]
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
(def ^:private brave-search-api-account "BRAVE_SEARCH_API")
(def ^:private api-url "https://api.search.brave.com/res/v1/web/search")

;; Keyring/create can block forever without a working Secret Service / D-Bus
;; (e.g. SSH session). Mirror grog.secrets' time-bounded read so a hung OS
;; backend cannot freeze the server's first tool call.
(defonce ^:private !keyring-unreachable (atom false))

(defn- fetch-secret-blocking! ^String [^String account]
  (with-open [^Keyring kr (Keyring/create)]
    (try
      (let [^String p (.getPassword kr service-id account)]
        (some-> p str str/trim not-empty))
      (catch PasswordAccessException _ nil))))

(defn- api-key
  "Brave subscription token from the OS keyring, or nil if missing/unsupported."
  ^String []
  (when-not @!keyring-unreachable
    (try
      (let [f (future
                (try
                  (fetch-secret-blocking! brave-search-api-account)
                  (catch BackendNotSupportedException _ ::unsupported)
                  (catch Exception _ ::error)))
            v (deref f 4000 ::timeout)]
        (cond
          (= ::timeout v)
          (do (reset! !keyring-unreachable true)
              (binding [*out* *err*]
                (println "grog-search: OS keyring did not respond within 4s; secret reads disabled for this process (D-Bus / Secret Service / desktop session?)."))
              nil)
          (= ::unsupported v) nil
          (= ::error v) nil
          :else v))
      (catch Exception _ nil))))

(defn- search-configured? []
  (boolean (some-> (api-key) not-empty)))

;; ---------------------------------------------------------------------------
;; Search implementation (ported from grog.brave)
;; ---------------------------------------------------------------------------

(defn- parse-search-args
  "Parse Brave tool arguments (map, JSON string, or Jackson map) to
  {:query str :count long}. `arguments` arrives as a `java.util.Map` from the
  MCP SDK, which is not a Clojure map, so handle it explicitly."
  [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(str k) v])) arguments)
            (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
            :else {})
        q (or (:query m) (get m "query")
              (get m :query))
        c (or (:count m) (get m "count")
              (get m :count))]
    {:query (some-> q str str/trim not-empty)
     :count (if (number? c) (max 1 (min 10 (long c))) 5)}))

(defn- format-hit [i hit]
  (str (inc i) ". **" (or (get hit "title") (get hit :title "(no title)")) "**\n   "
       (or (get hit "url") (get hit :url) "") "\n   "
       (str/trim (or (get hit "description") (get hit :description) ""))))

(defn- format-results-body [body]
  (let [results (vec (get-in body ["web" "results"]))]
    (if (empty? results)
      "No web results returned."
      (->> results
           (map-indexed format-hit)
           (str/join "\n\n")))))

(defn- run-search!
  "Execute a Brave web search. Returns a string for the model (or an error
  explanation)."
  [arguments]
  (if-let [key (api-key)]
    (let [{:keys [query count]} (parse-search-args arguments)]
      (if (str/blank? query)
        "brave_web_search error: missing or empty `query` parameter."
        (try
          (let [resp (http/get api-url
                               {:headers {"X-Subscription-Token" key
                                           "Accept" "application/json"}
                                :query-params {"q" query "count" count}
                                :as :string
                                :throw-exceptions false})
                st (:status resp)
                ^String raw (or (:body resp) "")
                kbytes (/ (alength (.getBytes raw "UTF-8")) 1024.0)]
            (binding [*out* *err*]
              (printf "grog-search: brave_web_search retrieved %.1f kB\n" kbytes)
              (flush))
            (cond
              (= 200 st)
              (try
                (let [body (json/read-str raw)]
                  (str "Brave web search results for query: " query "\n\n" (format-results-body body)))
                (catch Exception e
                  (str "brave_web_search: invalid JSON in response: " (.getMessage e))))

              (= 422 st)
              (try
                (let [m (json/read-str raw)]
                  (str "Brave API 422: " (pr-str (or (get m "message") m))))
                (catch Exception _
                  (str "Brave API HTTP 422: " (pr-str raw))))

              :else
              (str "Brave API HTTP " st ": " (pr-str raw))))
          (catch Exception e
            (str "brave_web_search failed: " (.getMessage e))))))
    (str "brave_web_search is not configured: store the token in the OS secret store "
         "(service \"grog\", account \"BRAVE_SEARCH_API\"), e.g. chat command /secret.")))

;; ---------------------------------------------------------------------------
;; MCP server wiring (same pattern as grog-odoo / grog-office)
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
  {:name "brave_web_search"
   :description "Search the public web via Brave Search. Use for current events, facts you are unsure about, or anything that needs up-to-date sources. Pass a concise search query."
   :schema (json/write-str {:type :object
                            :properties {:query {:type :string
                                                 :description "Search query (keywords or question)."}
                                         :count {:type :integer
                                                 :description "Max results to return (1–10, default 5)."}}
                            :required ["query"]})
   :fn run-search!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-search" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  ;; stdio MCP server: block forever; the client (ECA) owns our lifecycle and
  ;; kills the process when the session ends.
  (loop []
    (Thread/sleep 1000)
    (recur)))