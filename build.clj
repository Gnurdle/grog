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

(defn spine
  "Build `target/grog-spine.jar` — the headless backend the desktop clients own.
  Launched as: `java -cp grog-spine.jar clojure.main -m grog.server`.

  NO AOT on purpose: parts of the tree object to ahead-of-time compilation (the
  `uber` task dies in compile-clj with `Cannot open <nil> as a Reader`), and it
  isn't needed — clojure.main loads namespaces from source inside the jar, which
  is the same shape the MCP tool bundle uses. This is what makes the client
  installable without a Clojure CLI or the source tree on the target machine.

  `:version` (optional) stamps `grog-version.edn` onto the classpath so the
  running spine — and `grog doctor` — can report which build they are. Writing
  it into the class dir (not `resources/`) keeps the working tree clean.

  Run: clojure -T:build spine :version '\"1.2.3\"'"
  [{:keys [version]}]
  (let [basis (b/create-basis {:project "deps.edn"})
        class-dir "target/spine-classes"
        out "target/grog-spine.jar"]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (when version
      (spit (str class-dir "/grog-version.edn")
            (pr-str {:version (str version)
                     :built (str (java.time.Instant/now))})))
    (b/uber {:class-dir class-dir
             :uber-file out
             :basis basis})
    (println "Built" out (str "(" (quot (.length (java.io.File. out)) 1048576) " MB)")
             (when version (str "  version " version)))
    out))

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
