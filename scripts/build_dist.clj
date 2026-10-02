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
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
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

(def ^:private windows?
  (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "win"))

(defn- where-on-path
  "Windows last resort: ask where.exe (respects PATHEXT + PATH, so it finds
  shims our fixed extension list might miss). First existing hit or nil.
  Spawns raw ProcessBuilder - not sh! - to avoid recursing into ourselves."
  [prog]
  (when windows?
    (try
      (let [p (.start (java.lang.ProcessBuilder. ["where.exe" (str prog)]))
            out (slurp (.getInputStream p))]
        (.waitFor p)
        (->> (str/split-lines out)
             (map str/trim)
             (filter #(and (seq %) (fs/exists? %)))
             first))
      (catch Exception _ nil))))

(defn- find-on-path
  "PATH search that also tries the Windows shim extensions.

  Babashka's program resolver matches the bare name only, so on Windows a
  `clojure` that is really `clojure.cmd` (scoop shim) or `clojure.bat` (the
  official installer) fails with 'Cannot resolve program: clojure' - even though
  the shell finds it fine. Resolve it to a real file first.

  Falls back to the name as given, so anything the resolver CAN handle (and any
  absolute path) still works.

  A program that already IS a path (absolute, or containing a separator) is
  returned untouched - there is nothing to look up, and babashka.fs/file would
  even throw on an absolute child."
  [prog]
  (if (or (str/includes? (str prog) "/")
          (str/includes? (str prog) "\\")
          (.isAbsolute (java.io.File. (str prog))))
    (str prog)
    ;; Windows: try the executable extensions BEFORE the bare name. An
    ;; extensionless `clojure` may be a POSIX script (the Clojure tools zip
    ;; ships one) which CreateProcess rejects with error=193; the .cmd/.bat is
    ;; the runnable one. On POSIX the bare name is the only candidate.
    (let [exts (if windows? [".exe" ".cmd" ".bat" ".com" ""] [""])
        dirs (remove str/blank?
                     (str/split (or (System/getenv "PATH") "")
                                (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))]
    (or (some (fn [d]
                (some (fn [e]
                        (let [f (fs/file d (str prog e))]
                          (when (fs/exists? f) (str f))))
                      exts))
              dirs)
        ;; nil = not on PATH (and where.exe agrees). sh! turns this into a
        ;; clean die with a hint; returning the bare name would only defer to
        ;; ProcessBuilder, which on Windows does no PATH/PATHEXT search at all
        ;; and throws the raw 'Cannot run program' IOException instead.
        (where-on-path prog)))))

(defn sh!
  "Run argv in `dir`, streaming output. Throws on a non-zero exit."
  [dir & args]
  (let [args (mapv str args)
        ;; resolve the program, not the rest of argv: babashka passes arguments
        ;; through untouched, and going via `cmd /c` would let cmd re-parse them
        ;; (it would strip the quotes from e.g. :version "0.1.0").
        prog (find-on-path (first args))]
    (when-not prog
      ;; Windows gotcha: scoop's `clojure` package installs only a PowerShell
      ;; MODULE - no clojure.exe/.cmd exists, PowerShell autoloads a clojure
      ;; function - so no file search can ever find it and ProcessBuilder
      ;; cannot run it. clj-deps shims deps.exe as clojure/clj instead.
      (die (str "program not found on PATH: " (first args)
                (if (contains? #{"clojure" "clj" "deps"} (first args))
                  (str " - on Windows, scoop's `clojure` package is PowerShell-only"
                       " (no executable); run: scoop install clj-deps"
                       " (scoop uninstall clojure first if it is installed)")
                  ""))))
    (let [args (into [prog] (rest args))]
      (say "$" (str/join " " args))
    ;; Skip babashka's own resolver: we already resolved the program, and its
    ;; Windows path re-runs fs/which (bare-name-only, executable? check) which
    ;; is precisely what was throwing 'Cannot resolve program: ...'. Ours
    ;; returns absolute paths, so ProcessBuilder needs no help finding them.
    (let [r (apply p/shell {:dir dir :out :inherit :err :inherit :continue true
                            :program-resolver (fn [{:keys [program]}] program)}
                   args)]
      (when-not (zero? (:exit r))
        (die (str "command failed (" (:exit r) "): " (str/join " " args))))))))

(defn- npm-cmd [] (if windows? "npm.cmd" "npm"))

(defn- os-tag [] (if windows? "windows" "linux"))

(defn- arch-tag []
  (let [a (str/lower-case (System/getProperty "os.arch"))]
    (cond (#{"amd64" "x86_64"} a) "x64"
          (#{"aarch64" "arm64"} a) "arm64"
          :else a)))

(defn- newest [dir re]
  ;; nil instead of throwing when the dir doesn't exist (fresh checkout +
  ;; --skip-jars): assemble! then reports its own 'jar missing' die message.
  (when (fs/directory? dir)
    (->> (fs/list-dir dir)
       (filter #(re-matches re (str (fs/file-name %))))
       (sort-by #(fs/last-modified-time %) #(compare %2 %1))
       first)))

(defn- web-deps-ready?
  "npm run build resolves shadow-cljs from node_modules/.bin - absent there,
  npm's only signal is the cryptic \"'shadow-cljs' is not recognized\"."
  [web]
  (let [bin (fs/file web "node_modules/.bin")]
    (and (fs/directory? bin)
         (boolean (some #(fs/exists? (fs/file bin %))
                        ["shadow-cljs" "shadow-cljs.cmd" "shadow-cljs.ps1"])))))

(defn- ensure-web-deps!
  "node_modules is gitignored, so a fresh clone has none: bootstrap it here.
  The jar side self-provisions (clojure fetches Maven deps on its own); this
  is the web side's equivalent, keeping bb dist ONE entry point (E1). Re-checks
  after install and dies if still missing (scripts blocked, offline, ...)."
  [web]
  (when-not (web-deps-ready? web)
    (say "node_modules lacks shadow-cljs - running npm install (once per clone)")
    (sh! web (npm-cmd) "install")
    (when-not (web-deps-ready? web)
      (die (str "npm install completed but shadow-cljs is still missing -"
                " check npm's output above for blocked scripts or network errors")))))

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
      (do (ensure-web-deps! web)
          (say "building the renderer bundle")
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
        ;; RELATIVE archive names on purpose: tar reads a leading `C:\...` as a
        ;; REMOTE archive (host `C`) and dies with "Cannot connect to C: resolve
        ;; failed". tar and zip run with :dir root, so a relative name lands in
        ;; dist\ just the same, and no tar flavour parses it as a host.
        tgz-rel (str "dist/" base ".tar.gz")
        zp-rel (str "dist/" base ".zip")
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
    (apply sh! root "tar" "-czf" tgz-rel (concat tar-excludes ["."]))
    (say "tarball:" (str tgz))
    (if (fs/which "zip")
      (do (apply sh! root "zip" "-q" "-r" zp-rel "." "-x" zip-excludes)
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

(def ^:private win-code-sign-url
  "https://github.com/electron-userland/electron-builder-binaries/releases/download/winCodeSign-2.6.0/winCodeSign-2.6.0.7z")

(defn- seven-zip
  "The 7-Zip we ship with (a dependency of electron-builder), for `host` OS."
  [host-windows?]
  (fs/file root "clients/web/node_modules/7zip-bin"
           (if host-windows? "win" "linux") "x64"
           (if host-windows? "7za.exe" "7za")))

(defn- ensure-win-code-sign-cache!
  "Seed the electron-builder `winCodeSign` cache ourselves, minus the macOS tree.

  Why this exists: electron-builder unpacks that archive with 7za, but it
  contains two macOS SYMLINKS (darwin/10.12/lib/libcrypto.dylib and
  libssl.dylib). Creating symlinks needs SeCreateSymbolicLinkPrivilege, which an
  ordinary account does not hold, so extraction fails with 'A required privilege
  is not held by the client' - and electron-builder retries four times before
  dying. That must never be the operator's problem to solve by hand.

  The macOS payload is irrelevant on Windows: what a Windows build needs from
  this archive is rcedit-x64.exe (icon + version stamping) and
  windows-10/x64/signtool.exe. So we fetch the archive and unpack it ourselves
  with `-xr!darwin`, straight into the cache directory electron-builder reads.
  It then skips its own download AND extraction entirely (verified)."
  []
  (when windows?
    (let [cache (fs/file (System/getenv "LOCALAPPDATA")
                         "electron-builder" "Cache" "winCodeSign" "winCodeSign-2.6.0")]
      (if (fs/exists? (fs/file cache "rcedit-x64.exe"))
        (say "winCodeSign cache already seeded")
        (do
          (say "seeding the winCodeSign cache (macOS symlinks excluded)")
          (let [seven-z (seven-zip windows?)]
            (when-not (fs/exists? seven-z)
              (die "7zip-bin is missing - run: cd clients/web && npm install"))
            (let [tmp (fs/create-temp-dir)
                  archive (fs/file tmp "winCodeSign-2.6.0.7z")]
              (try
                (let [r (curl/get win-code-sign-url {:as :stream})]
                  (when-not (= 200 (:status r))
                    (die (str "could not download winCodeSign (" (:status r) ")")))
                  (with-open [in (:body r) out (io/output-stream (str archive))]
                    (io/copy in out)))
                (fs/create-dirs cache)
                ;; -xr!darwin drops the only symlinked entries, so nothing here
                ;; ever asks Windows for a privilege we do not have.
                (sh! tmp (str seven-z) "x" "-bd" "-y" (str archive)
                     (str "-o" cache) "-xr!darwin")
                (finally (fs/delete-tree tmp))))
            (when-not (fs/exists? (fs/file cache "rcedit-x64.exe"))
              (die "winCodeSign cache seeding produced no rcedit - cannot package"))))))))

(defn package! [{:keys [version for-os]}]
  (let [web (fs/file root "clients/web")
        eb-bin (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
                 "electron-builder.cmd" "electron-builder")
        eb (fs/file web "node_modules/.bin" eb-bin)]
    (if (fs/exists? eb)
      (do (say "electron-builder present: producing installers")
          ;; Cross-building a Windows target from Linux: electron-builder only
          ;; reaches for Wine to *sign and rewrite* the exe (the winCodeSign /
          ;; rcedit step). We ship unsigned, so skip it and the build stays
          ;; wine-free. Building ON Windows keeps the metadata and icon.
          (let [cross? (and (= "windows" for-os) (not= "windows" (os-tag)))
                extra (cond-> []
                        (= "windows" for-os) (into ["--win" "nsis"])
                        cross? (into ["-c.win.signAndEditExecutable=false"]))]
            (when cross?
              (say "cross-building for windows: disabling exe signing/editing (no Wine needed)"))
            ;; Native Windows build: make the winCodeSign cache usable up front.
            ;; electron-builder cannot unpack it on an ordinary account (macOS
            ;; symlinks) and would otherwise burn four retries before failing.
            (when (and (= "windows" for-os) (not cross?))
              (ensure-win-code-sign-cache!))
            (apply sh! web (str eb) "--publish" "never"
                   (str "-c.extraMetadata.version=" version) extra)))
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
    ;; The deliverable is the INSTALLER (one artifact: grog-<v>-setup.exe). The
    ;; portable tarball/zip are a developer convenience only, so they are
    ;; opt-in via --bundle rather than part of the shipping path.
    (when (some #{"--bundle"} args) (assemble! opts))
    (when-not (some #{"--no-package"} args) (package! opts))
    ;; Report the artifact that was actually produced rather than predicting a
    ;; name: electron-builder's ${arch} for AppImage is x86_64, not x64.
    (let [dist (fs/file root "clients/web/dist")
          artifact (->> (when (fs/directory? dist) (fs/list-dir dist))
                        (filter #(re-matches
                                   (re-pattern
                                     (str "grog-" (java.util.regex.Pattern/quote version)
                                          ".*\\.(exe|AppImage|deb)$"))
                                   (str (fs/file-name %))))
                        (sort-by #(fs/last-modified-time %) #(compare %2 %1))
                        first)]
      (if artifact
        (do (say "")
            (say "ARTIFACT:" (str artifact))
            (say "  copy that one file to the target and run it"))
        (say "done.")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
