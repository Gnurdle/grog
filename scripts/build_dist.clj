#!/usr/bin/env bb
;; build_dist -- ONE entry point that produces a shippable grog (decision E1).
;;
;;   bb scripts/build_dist.clj [--skip-jars] [--no-package] [--version X.Y.Z]
;;
;; What it produces:
;;   target/grog-spine.jar            the headless backend (what the client owns)
;;   grog_mcp/target/grog-mcp-<v>.jar the MCP tool bundle (POI/PDFBox/OCR/keyring…)
;;   clients/web/resources/public/js/main.js + css/output.css   the renderer bundle
;;   dist/grog-<v>/                   a runnable directory (jars + web assets + manifest)
;;   dist/grog-<v>-<os>-<arch>.tar.gz a portable tarball
;;   an installer (NSIS / AppImage) IF electron-builder is installed in clients/web
;;
;; One collective version (E2) lives in ./VERSION; it is stamped into the jar
;; classpath as grog-version.edn so a running spine — and `grog doctor` — can
;; say which build it is (and detect jar-vs-app mismatches).
;;
;; Nothing here is on a timer: all updates are manual (E5).
(ns build-dist
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def root (str (fs/canonicalize ".")))
(def version-file (fs/file root "VERSION"))

(defn- say [& xs] (println (str "==> " (str/join " " xs))))
(defn- die [msg] (binding [*out* *err*] (println (str "build_dist: " msg))) (System/exit 1))

(defn- arg-value [args flag]
  (when-let [i (first (keep-indexed #(when (= flag %2) %1) args))]
    (nth args (inc i) nil)))

(defn resolve-version [args]
  (or (arg-value args "--version")
      (when (fs/exists? version-file) (str/trim (slurp version-file)))
      (do (spit version-file "0.1.0\n")
          (say "created VERSION (0.1.0) — edit it to release")
          "0.1.0")))

(defn sh!
  "Run argv in `dir`, streaming output. Throws on a non-zero exit."
  [dir & args]
  (let [args (mapv str args)]
    (say "$" (str/join " " args))
    (let [r (apply p/shell {:dir dir :out :inherit :err :inherit :continue true} args)]
      (when-not (zero? (:exit r))
        (die (str "command failed (" (:exit r) "): " (str/join " " args)))))))

(defn- npm-cmd [] (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win") "npm.cmd" "npm"))

(defn- os-tag []
  (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win") "windows" "linux"))

(defn- arch-tag []
  (let [a (str/lower-case (System/getProperty "os.arch"))]
    (cond (#{"amd64" "x86_64"} a) "x64"
          (#{"aarch64" "arm64"} a) "arm64"
          :else a)))

(defn- newest [dir re]
  (->> (fs/list-dir dir)
       (filter #(re-matches re (str (fs/file-name %))))
       (sort-by #(fs/last-modified-time %) #(compare %2 %1))
       first))

(defn build! [{:keys [version skip-jars? skip-web?]}]
  (let [web (fs/file root "clients/web")]
    (if skip-jars?
      (say "skipping jar builds (--skip-jars)")
      (do
        (say "building the MCP tool bundle")
        (sh! (fs/file root "grog_mcp") "clojure" "-T:build" "uber" ":version" (pr-str version))
        (say "building the spine")
        (sh! root "clojure" "-T:build" "spine" ":version" (pr-str version))))
    (if skip-web?
      (say "skipping the renderer build (--skip-web)")
      (do (say "building the renderer bundle")
          (sh! web (npm-cmd) "run" "build")))
    true))

;; What a user gets: the whole tree plus both jars, minus everything that is
;; rebuildable or machine-specific. node_modules is deliberately absent - the
;; getting-started guide has the user fetch the app runtime with `npm install`,
;; which keeps the download ~145 MB instead of ~450 MB.
(def ^:private tar-excludes
  ["--exclude=.git" "--exclude=node_modules" "--exclude=dist"
   "--exclude=.cpcache" "--exclude=.shadow-cljs" "--exclude=.clj-kondo"
   "--exclude=./target/classes" "--exclude=./target/spine-classes"
   "--exclude=./grog_mcp/target/classes"
   "--exclude=*.tar.gz" "--exclude=*.zip"])

(def ^:private zip-excludes
  [".git/*" "node_modules/*" "*/node_modules/*" "dist/*"
   ".cpcache/*" ".shadow-cljs/*" ".clj-kondo/*"
   "target/classes/*" "target/spine-classes/*" "grog_mcp/target/classes/*"
   "*.tar.gz" "*.zip"])

(defn assemble! [{:keys [version for-os]}]
  (let [out (fs/file root "dist")
        base (str "grog-" version "-" for-os "-" (arch-tag))
        tgz (fs/file out (str base ".tar.gz"))
        zp (fs/file out (str base ".zip"))
        spine (fs/file root "target/grog-spine.jar")
        mcp (newest (fs/file root "grog_mcp/target") #"grog-mcp-.*\.jar")]
    (when-not (fs/exists? spine) (die "spine jar missing - run without --skip-jars"))
    (when-not mcp (die "grog-mcp jar missing - run without --skip-jars"))
    (fs/create-dirs out)
    (fs/delete-if-exists tgz)
    (fs/delete-if-exists zp)
    (say "packing the bundle (tree + both jars, no node_modules)")
    (apply sh! root "tar" "-czf" (str tgz) (concat tar-excludes ["."]))
    (say "tarball:" (str tgz))
    (if (fs/which "zip")
      (do (apply sh! root "zip" "-q" "-r" (str zp) "." "-x" zip-excludes)
          (say "zip:" (str zp)))
      (say "zip not installed - skipping the .zip (the tarball is equivalent)"))
    (spit (fs/file out (str base "-manifest.edn"))
          (pr-str {:product "grog" :version version
                   :os for-os :arch (arch-tag)
                   :built (str (java.time.Instant/now))
                   :contains ["target/grog-spine.jar"
                              (str "grog_mcp/target/" (fs/file-name mcp))
                              "clients/web/resources/public/ (built)"
                              "scripts/grog-client[.bat]"]
                   :next "extract, then follow doc/windows-quick-start.md"}))
    (say "dist dir:" (str out))
    out))

(defn package! [{:keys [version for-os]}]
  (let [web (fs/file root "clients/web")
        eb-bin (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
                 "electron-builder.cmd" "electron-builder")
        eb (fs/file web "node_modules/.bin" eb-bin)]
    (if (fs/exists? eb)
      (do (say "electron-builder present: producing installers")
          ;; stamp the collective version WITHOUT rewriting package.json, and
          ;; build for the requested target (Windows from Linux needs Wine)
          (apply sh! web (str eb) "--publish" "never"
                 (str "-c.extraMetadata.version=" version)
                 (if (= "windows" for-os) ["--win" "nsis"] [])))
      (do (say "electron-builder NOT installed in clients/web - skipping installers.")
          (say "  enable: (cd clients/web && npm install)   ; adds electron-builder")
          (say "  the bundle above is already runnable (the user runs npm install once)")))))

(defn -main [& args]
  (let [version (resolve-version args)
        for-os (or (arg-value args "--target") (os-tag))
        opts {:version version
              :for-os for-os
              :skip-jars? (boolean (some #{"--skip-jars"} args))
              :skip-web? (boolean (some #{"--skip-web"} args))}]
    (when-not (#{"windows" "linux"} for-os)
      (die (str "--target must be windows or linux (got " (pr-str for-os) ")")))
    (say (str "grog build_dist  version " version
              "  building on " (os-tag) "/" (arch-tag)
              "  for " for-os "/" (arch-tag)))
    (build! opts)
    (assemble! opts)
    (when-not (some #{"--no-package"} args) (package! opts))
    (say "done.")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
