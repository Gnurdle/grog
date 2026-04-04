(ns build
  (:require [clojure.tools.build.api :as b]))

(def uber-file "target/grog.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn sync-resources
  "Copy `resources/` → `target/classes` (e.g. `grog.edn`).
  Run: clojure -T:build sync-resources"
  [_]
  (let [class-dir "target/classes"]
    (.mkdirs (java.io.File. class-dir))
    (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
    (println "Synced resources/ →" class-dir)))

(defn uber
  "Build `target/grog.jar` — run: clojure -T:build uber"
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})
        class-dir "target/classes"]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'grog.core})
    (println "Built" uber-file)))
