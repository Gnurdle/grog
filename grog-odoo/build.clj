(ns build
  "tools.build tasks for grog-odoo.

  Produces `target/grog-odoo.jar` — a self-contained executable uberjar so a
  coworker can run the MCP server with `java -jar grog-odoo.jar` and needs no
  Clojure CLI / Maven.

  Usage:
    clojure -T:build uber       # build target/grog-odoo.jar
    clojure -T:build clean      # delete target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'grog-odoo/grog-odoo)
(def version "0.3.0")
(def class-dir "target/classes")
(def uber-file "target/grog-odoo.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis @basis]
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :ns-compile '[grog-odoo.main]})
    (b/uber {:class-dir class-dir
             :basis basis
             :uber-file uber-file}))
  (println "Built:" uber-file))