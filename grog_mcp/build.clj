(ns build
  "tools.build tasks for grog-mcp — the consolidated MCP server bundle.

  Produces `target/grog-mcp.jar` — a self-contained executable uberjar that
  registers all ~44 Clojure grog tools on ONE McpServer (one JVM, one process,
  one classpath). A teammate runs it with `java -jar grog-mcp.jar` — no Clojure
  CLI / Maven needed.

  Note: we deliberately do NOT `compile-clj` AOT — the bundle's hyphens in the
  main ns (grog-mcp.main) trip the tools.build AOT driver when the sibling
  artifacts' namespaces are mounted (class-FQN collision). JIT loading at
  runtime is correct and safe for an uberjar: the namespace resolves when the
  server boots.

  Usage:
    clojure -T:build uber       # build target/grog-mcp.jar
    clojure -T:build clean      # delete target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'grog-mcp/grog-mcp)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/grog-mcp.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path ".cpcache"}))

(defn uber [_]
  (clean nil)
  (let [basis @basis]
    ;; project source must be explicitly staged into class-dir for uber
    (b/copy-dir {:src-dirs ["src" "vendor-src"] :target-dir class-dir})
    (b/uber {:class-dir class-dir
             :basis basis
             :uber-file uber-file}))
  (println "Built:" uber-file))