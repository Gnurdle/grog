(ns grog_mcp.http
  "Streamable-HTTP MCP endpoints for the grog bundle — ONE JVM, bound to
  127.0.0.1, **one listener per server key**.

  Why this exists: ECA accepts an MCP server entry as either `:command`
  (stdio — ECA spawns the child) or `:url` (Streamable HTTP — ECA dials out);
  see ECA's `features/tools/mcp.clj` (~line 145, `->transport`). This one
  process serves the whole toolset and every ECA attaches to it, rather than
  every ECA spawning its own stdio JVMs (13 per tab).

  Why one port PER SERVER KEY rather than a single merged endpoint: ECA names
  tools `<config-key>__<tool>` (hence `grog-memory__assoc_get`,
  `grog-search__brave_web_search`). A single merged `grog-mcp` entry would
  rename all ~81 tools and invalidate the user's approved-tools allowlist.
  Per-key listeners keep every tool name byte-identical to the stdio layout
  while still consolidating to one JVM.

  Transport parity is by construction: this uses **plumcp** — the same Clojure
  MCP library ECA uses as its *client*
  (`plumcp.core.client.http-client-transport/make-streamable-http-transport`),
  here in its server role (`plumcp.core.server.http-ring-transport`) on the
  JDK's built-in HTTP server. Verified by handshake: initialize → 2025-03-26,
  tools/list → 81 tools.

  Localhost-only: plumcp's stock JDK server binds 0.0.0.0, so we supply our own
  `:ring-server` that binds 127.0.0.1 explicitly — these tools read keys, mail,
  git and the filesystem; they must never be exposed to the network.

  Usage:  clojure -M:http [--base-port 9700]
  Beacon: one line per server on stderr —
    grog-mcp http READY <server-id> 127.0.0.1:<port>/mcp
  then blocks forever."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog_mcp.main :as bundle]
            [plumcp.core.api.entity-gen :as eg]
            [plumcp.core.api.entity-support :as es]
            [plumcp.core.api.mcp-server :as msrv]
            [plumcp.core.impl.impl-capability :as ic]
            [plumcp.core.impl.method-handler :as mh]
            [plumcp.core.protocol :as p]
            [plumcp.core.support.http-server-java :as hsj]
            [plumcp.core.util :as u]
            [plumcp.core.util-java :as uj]
            ;; loads the cheshire JSON codec ECA's client also speaks
            [plumcp.core.util.json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(def default-base-port 9700)

(defn- tool-item
  "One grog tool spec {:name :description :schema :fn} -> a plumcp tools
  capability item (tool definition + `(fn [kwargs]) -> call-tool-result`).

  CRITICAL: grog specs carry `:schema` as a JSON **string** (inherited from the
  Java-SDK bundle). Providers validate `function.parameters` as a JSON Schema
  OBJECT, so shipping the string verbatim makes every request 400 —
  \"Expected object, received string\" / \"must be a JSON Schema object\" — and
  leaves the model unable to see parameter names (hence tools being called with
  empty arguments: 'Missing required :sql', 'path is required'). Parse it here.

  The MCP `tools/call` params carry the tool's arguments under `:arguments`;
  grog tool fns take that map and return a string, which plumcp's handler
  wrapper normalises to a text CallToolResult (and converts a thrown exception
  into an isError result)."
  [{:keys [name description schema] tool-fn :fn}]
  (let [schema (cond
                 (string? schema) (try (json/parse-string schema true)
                                       (catch Throwable _
                                         {:type "object" :properties {}}))
                 (map? schema) schema
                 :else {:type "object" :properties {}})]
    (-> (eg/make-tool name schema {:description description})
        (ic/make-tools-capability-item
         (mh/make-call-tool-handler
          (fn [kwargs] (tool-fn (or (:arguments kwargs) kwargs))))))))

(defn- localhost-ring-server
  "plumcp's `:ring-server` hook: start the MCP Ring handler on a JDK HTTP
  server bound to 127.0.0.1 (NOT 0.0.0.0), returning the running server.

  Mirrors `plumcp.core.support.http-server-java/run-http-server` exactly
  except for the bind address; accepts either an options map or kwargs."
  [ring-handler & args]
  (let [opts (if (map? (first args)) (first args) (apply hash-map args))
        {:keys [port executor error-handler]} opts
        port (int (or port default-base-port))
        executor (or executor uj/virtual-executor)
        error-handler (or error-handler u/print-stack-trace)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
    (doto server
      (.createContext "/"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (try
                            (let [ring-request (hsj/make-ring-request exchange port)
                                  ring-response (ring-handler ring-request)]
                              (hsj/send-http-response! ring-response exchange))
                            (catch Throwable e
                              (error-handler e))))))
      (.setExecutor executor)
      (.start))
    (reify p/IStoppable
      (stop! [_] (.stop ^HttpServer server 0)))))

(defn start-one!
  "Start one endpoint serving a single server-id's tools. Returns the running
  server (pass to plumcp's `stop-server`)."
  [id port]
  (let [specs (bundle/collect-tools [id])]
    (msrv/run-server {:info (es/make-info (str (name id)) "0.1.0")
                      :instructions (str "grog " (name id) " tools over localhost Streamable HTTP.")
                      :primitives {:tools (mapv tool-item specs)}
                      :transport :http
                      :port (int port)
                      :ring-server localhost-ring-server
                      :print-banner? false})))

(defn start-all!
  "Start one endpoint per server key (sorted, deterministic ports:
  base-port, base-port+1, …). Returns {server-id url}."
  [{:keys [base-port] :or {base-port default-base-port}}]
  (into {}
        (map-indexed
         (fn [idx id]
           (let [port (+ (long base-port) idx)]
             (start-one! id port)
             (binding [*out* *err*]
               (println (str "grog-mcp http READY " (name id)
                             " http://127.0.0.1:" port "/mcp")))
             [(name id) (str "http://127.0.0.1:" port "/mcp")]))
         (sort (keys bundle/servers)))))

(defn start!
  "Debug/all-in-one mode: ONE endpoint serving every tool (tool names change
  under a single ECA key — see the ns docstring; prefer `start-all!`)."
  [{:keys [port] :or {port default-base-port}}]
  (let [specs (bundle/collect-tools (keys bundle/servers))]
    (msrv/run-server {:info (es/make-info "grog-mcp" "0.1.0")
                      :instructions
                      (str "grog tool bundle: " (count specs)
                           " tools from " (count bundle/servers)
                           " servers, served over localhost Streamable HTTP.")
                      :primitives {:tools (mapv tool-item specs)}
                      :transport :http
                      :port (int port)
                      :ring-server localhost-ring-server
                      :print-banner? false})))

(defn -main [& args]
  (let [base (or (some (fn [a]
                         (when-let [m (re-matches #"^--base-port[= ]?(.+)$" (str a))]
                           (parse-long (second m))))
                       args)
                 (some (fn [a] (when (re-matches #"^\d+$" (str a)) (parse-long (str a)))) args)
                 default-base-port)]
    (if (some #(= "--all" (str %)) args)
      (do (start! {:port base})
          (binding [*out* *err*]
            (println (str "grog-mcp http READY 127.0.0.1:" base "/mcp"))))
      (start-all! {:base-port base}))
    ;; block the main thread forever — the servers run on their own executor
    @(promise)))