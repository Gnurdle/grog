(ns grog_mcp.main
  "grog-mcp — the consolidated MCP server.

  One JVM, one McpServer: registers the ~44 tools from ALL the Clojure grog MCP
  servers (babashka, big, fetch, rss, search, project-search, office, imaging,
  odoo, imap) as in-process tools. This replaces the 9 separate stdio JVMs at
  startup — one boot, one classpath, one process to supervise.

  Rationale & tradeoffs:
    * Per-MCP composability is preserved: each tool is defined in its own
      grog-*.main namespace, and the bundle just collects them (see :servers).
    * `grog-memory` (Python) intentionally stays separate — SQLite/FastMCP was
      the cleanest zero-dep impl, and it guards the memory store from a native
      crash in the JVM bundle.
    * Failure isolation is reduced: a native crash (e.g. Tesseract) now takes
      the bundle down instead of one tool. Use --server <id> to run a single
      server standalone when you want that isolation back.

  Usage:
    clojure -M:run -m grog_mcp.main                 # all tools, one server
    clojure -M:run -m grog_mcp.main --server grog-fetch   # only fetch's tools
  ECA config correspondingly registers ONE mcpServers entry (\"grog-mcp\") pointing
  at this process instead of the individual grog-* entries."
  (:require [cheshire.core :as json]
            [grog-mcp.memory])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The server registry — id -> {:name :version :tools (fn returning specs)}
;; ---------------------------------------------------------------------------

(def servers
  ;; NOTE: resolved at RUNTIME (requiring-resolve) rather than compile-time
  ;; requires — the AOT analyzer mis-reads `grog-big.main` as a class FQN when
  ;; the sibling artifact is mounted on the classpath, so we avoid static
  ;; references entirely and `require` + resolve each ns when the bundle boots.
  {:grog-babashka       {:name "grog-babashka" :version "0.1.0" :tools 'grog-babashka.main/tool-spec}
   :grog-big            {:name "grog-big" :version "0.1.0" :tools 'grog-big.main/tool-spec}
   :grog-fetch          {:name "grog-fetch" :version "0.1.0" :tools 'grog-fetch.main/tool-spec}
   :grog-rss            {:name "grog-rss" :version "0.1.0" :tools 'grog-rss.main/tool-spec}
   :grog-search         {:name "grog-search" :version "0.1.0" :tools 'grog-search.main/tool-spec}
   :grog-project-search {:name "grog-project-search" :version "0.1.0" :tools 'grog-project-search.main/tool-spec}
   :grog-office         {:name "grog-office" :version "0.1.0" :tools 'grog-office.main/tools}
   :grog-imaging        {:name "grog-imaging" :version "0.1.0" :tools 'grog-imaging.main/tools}
   :grog-odoo           {:name "grog-odoo" :version "0.3.0" :tools 'grog-odoo.main/build-tools}
   :grog-imap           {:name "grog-imap" :version "0.1.0" :tools 'grog-imap.main/build-tools}
   :grog-gitlab         {:name "grog-gitlab" :version "0.2.0" :tools 'grog-gitlab.main/build-tools}
   :grog-memory         {:name "grog-memory" :version "1.30.0" :tools 'grog-mcp.memory/tools}})

(def server-ids (set (keys servers)))

;; ---------------------------------------------------------------------------
;; MCP helpers (same pattern as the individual servers)
;; ---------------------------------------------------------------------------

(defn- text-content ^McpSchema$TextContent [^String s] (McpSchema$TextContent. s))
(defn- text-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  "Wrap a single tool-spec map {:name :description :schema :fn} as an MCP AsyncToolSpecification."
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

;; ---------------------------------------------------------------------------
;; Server construction
;; ---------------------------------------------------------------------------

(defn- collect-tools [ids]
  (mapcat (fn [id]
            (let [{:keys [tools]} (get servers id)]
              (try
                ;; requiring-resolve returns the VAR; deref explicitly
                (let [val (deref (requiring-resolve tools))
                      ;; a vector value is ALSO an IFn — only call non-collection IFns
                      specs (if (and (ifn? val)
                                     (not (map? val))
                                     (not (vector? val))
                                     (not (sequential? val)))
                              (val)
                              val)]
                  (cond (map? specs) [specs]
                        (sequential? specs) (vec specs)
                        :else []))
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "grog_mcp: tool collection failed for" id ":" (.getMessage t)))
                  []))))
          ids))

(defn mcp-server
  "Build the consolidated McpServer. `only` (optional keyword) registers one server's tools."
  [& [only]]
  (let [ids (if only [(keyword only)] (keys servers))
        _ (when (not-every? server-ids ids)
            (throw (ex-info (str "unknown --server; choose from " (sort (map name server-ids))) {})))
        transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-mcp" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    (doseq [t (collect-tools ids)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& args]
  (let [only (some (fn [a] (when (re-matches #"^--server" (str a))
                             (nth args (inc (.indexOf args a)))))
                   args)]
    (mcp-server only)
    (loop []
      (Thread/sleep 1000)
      (recur))))