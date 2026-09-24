(ns build
  "tools.build tasks for grog-alpaca.

  Produces `target/grog-alpaca.jar` — a self-contained executable uberjar so a
  teammate can run the MCP server with `java -jar grog-alpaca.jar` and needs no
  Clojure CLI / Maven.

  Usage:
    clojure -T:build uber       # build target/grog-alpaca.jar
    clojure -T:build clean      # delete target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'grog-alpaca/grog-alpaca)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/grog-alpaca.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis @basis]
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :ns-compile '[grog-alpaca.main]})
    (b/uber {:class-dir class-dir
             :basis basis
             :uber-file uber-file}))
  (println "Built:" uber-file))
