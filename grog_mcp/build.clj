(ns build
  "Build tasks for the grog-mcp bundle (`clojure -T:build uber`).

  Produces ONE jar holding every tool server (src + the sibling projects via
  `:local/root` + all the heavy
  deps: POI, PDFBox, BoofCV, sqlite, keyring, postgres, cheshire, clj-http, the
  MCP SDK and plumcp). That jar is what eca_config points every MCP entry at, so
  the install needs no Clojure CLI, no 13 project trees, and no repo paths.

  Deliberately NO AOT: the bundle resolves each server's tool specs at runtime
  (`requiring-resolve` of `<server>.main/tool-spec|tools|build-tools`), so the
  sources must be present as sources. `java -cp <jar> clojure.main -m
  grog_mcp.main --server <id>` is the invocation (see `spec` in
  grog.eca-config)."
  (:require [clojure.tools.build.api :as b]))

(def default-version "0.2.1")
(def class-dir "target/classes")
;; Just the bundle's own sources: the 12 servers arrive on the classpath as
;; `:local/root` deps (see deps.edn), NOT as a vendored copy.
(def src-dirs ["src"])

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Copy the bundle's own sources into the class dir, then merge the whole
  classpath into one jar (the 11 servers come in via `:local/root`).

  `:version` overrides the jar name's version (build_dist stamps the collective
  version here): clojure -T:build uber :version '\"1.2.3\"'. eca_config finds the
  bundle by globbing target/grog-mcp-*.jar and taking the newest, so the name
  only has to be unique, not fixed."
  [{:keys [version] :or {version default-version}}]
  (clean nil)
  (let [uber-file (format "target/grog-mcp-%s.jar" version)]
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis (basis)})
    (println "wrote" uber-file (str "(" (quot (.length (java.io.File. uber-file)) 1048576) " MB)"))
    uber-file))