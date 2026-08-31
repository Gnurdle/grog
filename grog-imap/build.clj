(ns build
  "tools.build tasks for grog-imap.

  Produces `target/grog-imap.jar` — a self-contained executable uberjar so a
  coworker can run the MCP server with `java -jar grog-imap.jar` and needs no
  Clojure CLI / Maven.

  Usage:
    clojure -T:build uber       # build target/grog-imap.jar
    clojure -T:build clean      # delete target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'grog-imap/grog-imap)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/grog-imap.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis @basis]
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :ns-compile '[grog-imap.main]})
    (b/uber {:class-dir class-dir
             :basis basis
             :uber-file uber-file}))
  (println "Built:" uber-file))
