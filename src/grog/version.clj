(ns grog.version
  "What build is this?

  `scripts/build_dist.clj` stamps one collective version into the jars as
  `grog-version.edn` on the classpath (written by `build.clj`'s `spine` task).
  Nothing read it — the client now shows it on the startup screen."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- blank->nil [x]
  (some-> x str str/trim not-empty))

(defn build-info
  "The build stamp the jar carries — `{:version \"0.2.0\" :built \"…\"}` — or nil
  when running from source with no stamp."
  []
  (try
    (when-let [r (io/resource "grog-version.edn")]
      (let [m (edn/read-string (slurp r))]
        (when (map? m) m)))
    (catch Throwable _ nil)))

(defn version
  "The build's version string, or nil when unknown.

  Falls back to a `VERSION` file in the working directory: a source tree has no
  jar stamp, and `VERSION` at the repo root is the value the build reads."
  []
  (or (blank->nil (:version (build-info)))
      (try
        (let [f (io/file "VERSION")]
          (when (.exists f) (blank->nil (slurp f))))
        (catch Throwable _ nil))))

(defn label
  "One short line for a UI — `grog 0.2.0` — or nil when the version is unknown."
  []
  (when-let [v (version)]
    (str "grog " v)))
