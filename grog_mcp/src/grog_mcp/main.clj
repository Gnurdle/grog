(ns grog_mcp.main
  "grog-mcp — the consolidated MCP server.

  One JVM, one McpServer: registers the ~44 tools from ALL the Clojure grog MCP
  servers (babashka, big, fetch, rss, search, project-search, office, imaging,
  odoo, imap) as in-process tools — one boot, one classpath, one process to
  supervise.

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
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog-mcp.memory])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$JsonSchema
            McpSchema$TextContent McpSchema$ImageContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.util Base64]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The server registry — id -> {:name :version :tools (fn returning specs)}
;; ---------------------------------------------------------------------------

(def servers
  ;; NOTE: resolved at RUNTIME (requiring-resolve) rather than compile-time
  ;; requires — the AOT analyzer can mis-read a sibling namespace name as a
  ;; class FQN when the project is mounted on the classpath, so we avoid static
  ;; references entirely and `require` + resolve each ns when the bundle boots.
  ;;
  ;; (A remote "big model as a tool" server once lived here as `:grog-big`; that
  ;; path was abandoned and the server and its `big_model_ask` tool were
  ;; removed. Re-approaching it, if ever, should look different.)
  {:grog-babashka       {:name "grog-babashka" :version "0.1.0" :tools 'grog-babashka.main/tool-spec}
   :grog-fetch          {:name "grog-fetch" :version "0.1.0" :tools 'grog-fetch.main/tool-spec}
   :grog-rss            {:name "grog-rss" :version "0.1.0" :tools 'grog-rss.main/tool-spec}
   :grog-search         {:name "grog-search" :version "0.1.0" :tools 'grog-search.main/tool-spec}
   :grog-project-search {:name "grog-project-search" :version "0.1.0" :tools 'grog-project-search.main/tool-spec}
   :grog-office         {:name "grog-office" :version "0.1.0" :tools 'grog-office.main/tools}
   :grog-imaging        {:name "grog-imaging" :version "0.1.0" :tools 'grog-imaging.main/tools}
   :grog-odoo           {:name "grog-odoo" :version "0.4.0" :tools 'grog-odoo.main/build-tools}
   :grog-imap           {:name "grog-imap" :version "0.1.0" :tools 'grog-imap.main/build-tools}
   :grog-gitlab         {:name "grog-gitlab" :version "0.2.0" :tools 'grog-gitlab.main/build-tools}
   :grog-alpaca         {:name "grog-alpaca" :version "0.1.0" :tools 'grog-alpaca.main/build-tools}
   :grog-memory         {:name "grog-memory" :version "1.30.0" :tools 'grog-mcp.memory/tools}})

(def server-ids (set (keys servers)))

;; ---------------------------------------------------------------------------
;; MCP helpers (same pattern as the individual servers)
;; ---------------------------------------------------------------------------

(defn- text-content ^McpSchema$TextContent [^String s] (McpSchema$TextContent. s))
(defn- text-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- file->image-block
  "Read `path` and build an MCP ImageContent block, or nil. Never throws."
  ^McpSchema$ImageContent [^String path]
  (try
    (let [f (io/file path)]
      (when (.isFile f)
        (with-open [in (io/input-stream f)]
          (let [b64 (.encodeToString (Base64/getEncoder) (.readAllBytes in))
                l (str/lower-case path)
                mt (cond (or (str/ends-with? l ".jpg") (str/ends-with? l ".jpeg")) "image/jpeg"
                         (str/ends-with? l ".webp") "image/webp"
                         (str/ends-with? l ".gif") "image/gif"
                         :else "image/png")]
            ;; ctor is (audience, priority, data, mimeType) — see McpSchema records
            (McpSchema$ImageContent. nil nil b64 mt)))))
    (catch Throwable _ nil)))

(defn- result-content
  "Content blocks for a finished tool result: always the text; when the tool is
  flagged `:image-out?` and the text is a SUCCESSFUL JSON result naming an output
  file (`out_path`, or `path` — `write_workspace_png` uses `path`), that file is
  appended as an MCP image block. ECA turns such a block into a ChatImageContent
  the client renders inline. Image failures are swallowed so the text is never
  lost to a bad image."
  ^java.util.List [^String text image-out?]
  (let [blocks (java.util.ArrayList.)]
    (.add blocks (text-content text))
    (when image-out?
      (try
        (let [m (json/parse-string text)
              p (or (get m "out_path") (get m "path"))]
          (when (and (nil? (get m "error")) (string? p))
            (when-let [^McpSchema$ImageContent img (file->image-block p)]
              (.add blocks img))))
        (catch Throwable _ nil)))
    blocks))

(defn- tool
  "Wrap a single tool-spec map {:name :description :schema :fn} as an MCP AsyncToolSpecification.
  A spec may also carry `:image-out? true` — its result then includes the image
  file it produced (see `result-content`)."
  [{:keys [name description schema image-out?] tool-fn :fn}]
  (McpServerFeatures$AsyncToolSpecification.
    ;; CRITICAL: `:schema` arrives as a JSON **string**. The SDK's String
    ;; constructor (Tool/String,String,String) ships it verbatim, so
    ;; `function.parameters` reaches providers as a string — and strict
    ;; providers reject the ENTIRE request ("Expected object, received string" /
    ;; "must be a JSON Schema object"), which surfaces as an unexplained 400
    ;; (sometimes mislabelled as context overflow by the failover chain) plus
    ;; tools being called with empty arguments, because the model never sees
    ;; parameter names. Build a real JsonSchema object instead. Keys may be
    ;; strings (parsed JSON) or keywords (a caller passing a literal map), so
    ;; both are accepted.
    (McpSchema$Tool.
     name description
     (let [m (if (string? schema) (json/parse-string schema) schema)
           jget (fn [k] (or (get m k) (get m (keyword k))))]
       (McpSchema$JsonSchema.
        (jget "type")
        (jget "properties")
        (some-> (jget "required") vec)
        (jget "additionalProperties"))))
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try
                (.success sink (McpSchema$CallToolResult.
                                (result-content (str (tool-fn arguments)) image-out?)
                                false))
                (catch Throwable t
                  ;; `:faultString` matters: XML-RPC faults (Odoo permission and
                  ;; validation errors) carry their only useful text there, and
                  ;; without it every one of them reads as "Odoo XML-RPC fault".
                  (.success sink (error-result
                                  (str "Error executing tool " name ": "
                                       (or (:message (ex-data t))
                                           (:faultString (ex-data t))
                                           (.getMessage t))))))))))))))

;; ---------------------------------------------------------------------------
;; Server construction
;; ---------------------------------------------------------------------------

(defn collect-tools
  "Flatten the tool specs of the given server ids (public: `grog-mcp.http`
  reuses this so the HTTP endpoint serves exactly the stdio toolset)."
  [ids]
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
        ;; REALIZE the tool specs FIRST — this is the slow step. The bundle is
        ;; deliberately non-AOT, so `collect-tools` compiles each server's
        ;; namespace at runtime (seconds). `collect-tools` returns a LAZY
        ;; mapcat, so without this `vec` that compilation happens inside the
        ;; `doseq` below — i.e. AFTER the transport exists.
        ;;
        ;; The StdioServerTransportProvider answers requests the moment it is
        ;; constructed, so a client that issues `tools/list` in the first seconds
        ;; used to get a PARTIAL list and cache it: measured 0 tools immediately
        ;; after `initialize`, 9 for the real client, 80 after the dust settled.
        ;; That is the "the model cannot see the odoo/imap/… tools" bug.
        ;; Compile everything, THEN open the transport.
        specs (vec (collect-tools ids))
        transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-mcp" "0.2.1")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    ;; registering already-realised specs is fast (no compilation here)
    (doseq [t specs]
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