(ns grog.session
  "Per-project session locks so two grog instances never work the same project at
  once. A project's state — memory db, dialog, per-project server configs,
  generated rules — is a shared-on-disk resource; two writers corrupt each other.

  A lock is a small EDN file in the project's state dir:
      ~/grog-projects/<proj>/state/.session.lock    ->  {:pid N :host \"h\" :since ms}

  `holder` reports the lock only when ANOTHER live session on this host owns
  it. A lock whose pid is dead is stale — and so is one whose pid has been
  *recycled*: existence alone does not prove the process is still the one that
  took the lock (Linux hands pids back out). Both are reclaimed by `claim!`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.projects :as projects])
  (:import (java.io File)
           (java.lang ProcessHandle)
           (java.net InetAddress)
           (java.time Instant)))

(defonce ^:private !held (atom #{}))

(defn- host ^String []
  (try (.getHostName (InetAddress/getLocalHost))
       (catch Exception _ "localhost")))

(defn- lock-file
  "The lock file for `proj` (does NOT create anything), or nil for a blank name."
  ^File [proj]
  (when-let [d (projects/project-dir proj)]
    (io/file d "state" ".session.lock")))

(defn- me []
  {:pid   (.pid (ProcessHandle/current))
   :host  (host)
   :since (System/currentTimeMillis)})

(defn- read-lock [proj]
  (try
    (let [^File f (lock-file proj)]
      (when (and f (.exists f)) (edn/read-string (slurp f :encoding "UTF-8"))))
    (catch Exception _ nil)))

(defn- lock-pid
  "The pid a lock names, or nil. A lock without a positive integer `:pid` is
  not a lock — there is nothing to test — so it can never report a holder."
  [l]
  (let [p (:pid l)]
    (when (and (integer? p) (pos? (long p))) (long p))))

(defn- normalize-host [^String h]
  (when h
    (let [s (str/lower-case (str/trim h))
          i (.indexOf s ".")]
      (if (pos? i) (subs s 0 i) s))))

(defn- same-host?
  "True when lock host `h` names this machine — or names nothing. Tolerates
  FQDN-vs-short drift (`caney` vs `caney.lan`), because hostname drift used to
  shove a local lock into the foreign-host branch and fake a holder. A lock
  with no `:host` is treated as local so it still gets the pid test instead of
  being reported held outright."
  [h]
  (or (nil? h) (= (normalize-host h) (normalize-host (host)))))

(defn- process-start
  "The instant `pid` started, or nil when the JVM can't determine it."
  [pid]
  (try
    (-> (ProcessHandle/of (long pid))
        (.orElse nil)
        (.info)
        (.startInstant)
        (.orElse nil))
    (catch Exception _ nil)))

(defn- alive?
  "True only when `pid` exists AND is the process that actually took the lock
  at `since-ms`.

  Existence alone is not enough: Linux recycles pids. Observed on this box —
  the grog session of 2026-09-23 died holding pid 2466, and by 09:04:23 the
  next morning pid 2466 belonged to Slack (`Chrome_IOThread`), so a bare
  liveness test reported the project open in \"another grog session (pid 2466)\".

  A process that started AFTER the lock was written cannot be its author, so
  that lock is stale no matter that the pid itself is live."
  [pid since-ms]
  (try
    (let [p (ProcessHandle/of (long pid))]
      (boolean
       (and (.isPresent p)
            (.isAlive (.get p))
            (let [st (process-start pid)]
              ;; no start time available -> fall back to bare existence
              (or (nil? st)
                  (not (pos? (long since-ms)))
                  (not (.isAfter st (Instant/ofEpochMilli (long since-ms)))))))))
    (catch Exception _ false)))

(defn holder
  "The lock map when `proj` is held by ANOTHER live session, else nil.

  Lock test, in order:
    1. the file must parse to a map that names a positive `:pid` — no pid, no
       lock, and therefore never a conflict;
    2. a lock naming this process is ours, not a conflict;
    3. a lock naming another host is reported held (liveness unverifiable there);
    4. otherwise the pid must still exist AND still be the same process that
       wrote the lock — dead or recycled pid both mean the lock is stale/free."
  [proj]
  (when-let [l (read-lock proj)]
    (when-let [pid (lock-pid l)]
      (cond
        (= pid (.pid (ProcessHandle/current))) nil       ; ours
        (not (same-host? (:host l)))           l        ; foreign host: assume held
        (alive? pid (long (or (:since l) 0)))  l        ; live AND same process
        :else                                  nil))))   ; stale: dead or recycled

(defn holder-desc
  "Human-readable owner of lock `l` (e.g. \"pid 1234\"), or nil."
  [l]
  (when l
    (str "pid " (:pid l)
         (when (and (:host l) (not= (:host l) (host))) (str " on " (:host l))))))

(defn claim!
  "Take the lock for `proj` (overwriting any stale/foreign one) and remember it
  as ours so `release-all!` can clean up on exit. Returns `proj`."
  [proj]
  (when (seq (str proj))
    (try
      (when-let [^File f (lock-file proj)]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str (me)) :encoding "UTF-8"))
      (catch Exception _ nil))
    (swap! !held conj (str proj)))
  proj)

(defn release!
  "Release our lock on `proj` (only when we still own it)."
  [proj]
  (let [p (str proj)]
    (try
      (let [l (read-lock p)]
        (when (and l (= (lock-pid l) (.pid (ProcessHandle/current))))
          (when-let [^File f (lock-file p)] (io/delete-file f true))))
      (catch Exception _ nil))
    (swap! !held disj p)))

(defn release-all!
  "Release every lock this session claimed (call on exit)."
  []
  (doseq [p @!held] (release! p))
  (reset! !held #{}))