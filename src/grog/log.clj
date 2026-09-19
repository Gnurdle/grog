(ns grog.log
  "Per-instance GUI logging, done portably in-process.

  Every running grog writes its OWN log — `<base>.<pid>.log` — so concurrent
  instances never interleave (one shared file made multi-instance debugging
  useless). `<base>` defaults to `~/grog-ui`; override with the `GROG_LOG` env
  var (a legacy `…/grog-ui.log` value is accepted and its extension stripped).
  On startup the oldest instance logs are pruned, keeping `GROG_UI_LOG_KEEP`
  (default 5).

  This lives in the JVM, so it needs **no shell**: the PID is the JVM's own
  (`ProcessHandle/current`), the file/dir work is `babashka.fs`, and `System.out`
  / `System.err` (plus Clojure's `*out*` / `*err*` roots) are tee'd to BOTH the
  console and the log file. The `grog-ui` / `grog-ui.bat` launchers therefore
  just find `clojure` and start the app — no redirection, no rotation scripts,
  no platform-specific PID tricks."
  (:require [babashka.fs :as fs]
            [grog.config :as config])
  (:import (java.io FileOutputStream OutputStream PrintStream PrintWriter)))

(defonce ^:private !path (atom nil))

(defn path
  "This instance's log file path, or nil if `install!` hasn't run."
  []
  @!path)

(defn- pid ^long []
  (.pid (java.lang.ProcessHandle/current)))

(defn- base-path
  "The log base (no extension): `:log :dir` in grog.edn (default `~/grog-ui`)."
  ^String []
  (config/log-base))

(defn instance-log-path
  "This instance's log path: `<base>.<pid>.log`."
  ^String []
  (str (base-path) "." (pid) ".log"))

(defn- keep-count []
  (max 1 (long (config/log-keep))))

(defn- prune!
  "Delete the oldest `<base>.*.log` files, keeping the newest `keep` (the file
  this instance is about to create counts as newest). Best effort."
  [^String base ^long keep]
  (try
    (let [dir  (or (fs/parent base) (fs/cwd))
          name (str (fs/file-name base))
          logs (->> (fs/glob dir (str name ".*.log"))
                    (sort-by fs/last-modified-time (fn [a b] (compare b a))))]
      (doseq [f (drop keep logs)]
        (try (fs/delete-if-exists f) (catch Throwable _))))
    (catch Throwable _)))

(defn- tee
  "An OutputStream that mirrors writes to `console` (best effort — a closed or
  absent console must never break logging) and `file`."
  ^OutputStream [^OutputStream console ^OutputStream file]
  (proxy [OutputStream] []
    (write
      ([b]          (try (.write console b) (catch Throwable _))
                    (try (.write file b) (catch Throwable _)))
      ([b off len]  (try (.write console b off len) (catch Throwable _))
                    (try (.write file b off len) (catch Throwable _))))
    (flush [] (try (.flush console) (catch Throwable _))
              (try (.flush file) (catch Throwable _)))
    ;; never close the console stream; just make sure the log is flushed
    (close [] (try (.flush file) (catch Throwable _)))))

(defn install!
  "Set up this instance's log and tee stdout/stderr into it. Idempotent; returns
  the log path. Safe to call before any other output."
  ^String []
  (or @!path
      (let [p    (instance-log-path)
            keep (keep-count)
            ;; open the log FIRST so it counts as the newest file when pruning
            file (FileOutputStream. p true)
            _    (prune! (base-path) keep)
            out  (PrintStream. (tee System/out file) true "UTF-8")
            err  (PrintStream. (tee System/err file) true "UTF-8")]
        (System/setOut out)
        (System/setErr err)
        ;; Clojure's *out*/*err* roots were captured at boot, so point them at
        ;; the tee too (the GUI later rebinds them per-thread as needed).
        (alter-var-root #'*out* (fn [_] (PrintWriter. out true)))
        (alter-var-root #'*err* (fn [_] (PrintWriter. err true)))
        (reset! !path p)
        (println (str "=== grog-ui launch: " (java.time.LocalDateTime/now)
                      " pid=" (pid) " log=" p " ==="))
        p)))
