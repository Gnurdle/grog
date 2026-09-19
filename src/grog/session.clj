(ns grog.session
  "Per-project session locks so two grog instances never work the same project at
  once. A project's state — memory db, dialog, per-project server configs,
  generated rules — is a shared-on-disk resource; two writers corrupt each other.

  A lock is a small EDN file in the project's state dir:
      ~/grog-projects/<proj>/state/.session.lock    ->  {:pid N :host \"h\" :since ms}

  `holder` reports the lock only when ANOTHER **live** session on this host owns
  it; a lock left by a dead pid is stale and reclaimed by the next `claim!`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [grog.projects :as projects])
  (:import (java.io File)
           (java.lang ProcessHandle)
           (java.net InetAddress)))

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

(defn- alive? [pid]
  (try
    (let [p (ProcessHandle/of (long pid))]
      (boolean (and (.isPresent p) (.isAlive (.get p)))))
    (catch Exception _ false)))

(defn holder
  "The lock map when `proj` is held by ANOTHER live session, else nil.
  A lock from this very process, or from a dead pid on this host, is not a
  conflict. A lock naming a different host is treated as held (we can't check
  liveness there)."
  [proj]
  (when-let [l (read-lock proj)]
    (let [pid (:pid l)
          mine? (= (long (or pid -1)) (.pid (ProcessHandle/current)))
          same-host? (= (:host l) (host))]
      (cond
        mine?            nil
        (not same-host?) l                      ; foreign host: assume held
        (alive? pid)     l
        :else            nil))))                ; stale (dead pid)

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
        (when (and l (= (long (or (:pid l) -1)) (.pid (ProcessHandle/current))))
          (when-let [^File f (lock-file p)] (io/delete-file f true))))
      (catch Exception _ nil))
    (swap! !held disj p)))

(defn release-all!
  "Release every lock this session claimed (call on exit)."
  []
  (doseq [p @!held] (release! p))
  (reset! !held #{}))