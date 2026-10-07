(ns grog.reap
  "Reap STRAY grog-mcp processes left behind by earlier runs.

  grog launches MCP java processes under WRAPPERS (`clojure -M:http` on
  Windows, or `bash -lc \"java …\"`), so when a grog-server dies hard — a
  force-kill, the Task Manager, a Windows service stop that skips shutdown
  hooks — the JVMs are orphaned and stay resident. Nothing reaps them, so they
  pile up across uninstall/install (each install drops another jar; old JVMs
  keep running old jars).

  A process is a STRAY only if it names the grog-mcp bundle AND is not in the
  current process's tree AND is not a descendant of a LIVE grog-server — so a
  running server's own MCP endpoint is never reaped. Then it is tree-killed with
  `grog.platform/kill-handle-tree!`.

  Deliberately conservative: it matches only `grog[_-]mcp` (the bundle jar, the
  `grog_mcp.main` / `grog_mcp.http` namespaces), never a bare `java`/`clojure`,
  so it cannot touch unrelated work. Safe to run at grog-server startup and from
  `grog doctor --reap`."
  (:require [clojure.string :as str]
            [grog.platform :as platform])
  (:import (java.lang ProcessHandle)))

(def ^:private bundle-re
  "The grog-mcp bundle, wherever it shows up: jar (`grog-mcp-*.jar`), ns
  (`grog_mcp.main` / `grog_mcp.http`)."
  #"(?i)grog[_-]mcp")

(def ^:private server-re
  "A running grog-server (JVM `-m grog.server` or the native `grog-server`)."
  #"(?i)grog[.\-_]server")

(defn- cmdline [^ProcessHandle h]
  (try (or (.orElse (.commandLine (.info h)) "") "") (catch Throwable _ "")))

(defn- all-handles
  "Every live ProcessHandle. `ProcessHandle/allProcesses` is a java Stream, not
  seqable, and must be closed — so slurp it through its iterator."
  []
  (try
    (with-open [s (ProcessHandle/allProcesses)]
      (vec (iterator-seq (.iterator s))))
    (catch Throwable _ [])))

(defn- descendants-of [^ProcessHandle h]
  (try
    (with-open [ds (.descendants h)]
      (mapv (fn [^ProcessHandle c] (.pid c)) (iterator-seq (.iterator ds))))
    (catch Throwable _ [])))

(defn- protected-pids
  "PIDs we must NOT kill: the current process + all its descendants, and the
  descendants of every LIVE grog-server (so a running server keeps its own MCP
  endpoint). Only genuinely orphaned grog-mcp processes remain."
  []
  (let [handles (all-handles)
        servers (filter (fn [^ProcessHandle h] (re-find server-re (cmdline h))) handles)
        me (ProcessHandle/current)]
    (into (into #{(.pid me)} (descendants-of me))
          (mapcat descendants-of servers))))

(defn strays
  "Vector of {:pid :cmdline} for grog-mcp processes that are NOT protected (see
  `protected-pids`). Read-only — nothing is killed (use `kill-strays!`)."
  []
  (let [own (protected-pids)]
    (->> (all-handles)
         (remove (fn [^ProcessHandle h] (contains? own (.pid h))))
         (keep (fn [^ProcessHandle h]
                 (let [cmd (cmdline h)]
                   (when (and (re-find bundle-re cmd) (not (re-find server-re cmd)))
                     {:pid (.pid h) :cmdline cmd}))))
         vec)))

(defn kill-strays!
  "Tree-kill every stray grog-mcp process. Returns {:found N :killed M}. Best
  effort — never throws. `strays` may be supplied to skip re-scanning."
  ([]
   (kill-strays! (strays)))
  ([strays]
   (let [killed (atom 0)]
     (doseq [{:keys [pid]} strays]
       (when-let [h (try (.orElse (ProcessHandle/of (long pid)) nil) (catch Throwable _ nil))]
         (try (platform/kill-handle-tree! h) (catch Throwable _ nil))
         (swap! killed inc)))
     {:found (count strays) :killed @killed})))
