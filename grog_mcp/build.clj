(ns build
  "Build tasks for the grog-mcp bundle (`clojure -T:build uber`).

  Produces ONE jar holding every tool server (src + vendor-src + all the heavy
  deps: POI, PDFBox, BoofCV, sqlite, keyring, postgres, cheshire, clj-http, the
  MCP SDK and plumcp). That jar is what eca_config points every MCP entry at, so
  the install needs no Clojure CLI, no 13 project trees, and no repo paths.

  Deliberately NO AOT: the bundle resolves each server's tool specs at runtime
  (`requiring-resolve` of `<server>.main/tool-spec|tools|build-tools`), so the
  sources must be present as sources. `java -cp <jar> clojure.main -m
  grog_mcp.main --server <id>` is the invocation (see `spec` in
  grog.eca-config)."
  (:require [clojure.tools.build.api :as b]))

(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/grog-mcp-%s.jar" version))
(def src-dirs ["src" "vendor-src"])

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Copy the bundle's sources (server namespaces live in vendor-src) into the
  class dir, then merge the whole classpath into one jar."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (basis)})
  (println "wrote" uber-file (str "(" (quot (.length (java.io.File. uber-file)) 1048576) " MB)"))
  uber-file)