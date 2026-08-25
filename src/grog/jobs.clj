(ns grog.jobs
  "Project-scoped job queue under the project home (`~/grog-projects/<proj>/jobs/`).
  Each job has a goal; Grog runs the tool loop with full project context (SOUL +
  thread + memory paths) and persists findings. Projects own their jobs — no more
  edn-store `grog-memory/Projects/<proj>` indirection."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.chat-context :as chat]
            [grog.config :as cfg]
            [grog.project-dialog :as pd]
            [grog.projects :as projects])
  (:import (java.io File)
           (java.util UUID)))

(defn- queue-file ^File [^String project-name]
  (io/file (projects/jobs-dir project-name) "queue.edn"))

(defn- findings-file ^File [^String project-name ^String job-id]
  (io/file (projects/jobs-dir project-name) (str "findings-" job-id ".edn")))

(defn- read-edn [^File f]
  (try
    (let [s (slurp f :encoding "UTF-8")
          m (when (seq (str/trim s)) (edn/read-string {:eof nil} s))]
      (if (map? m) m {}))
    (catch Exception _ {})))

(defn- write-edn! [^File f value]
  (.mkdirs (.getParentFile f))
  (spit f (with-out-str (pprint/pprint value)) :encoding "UTF-8"))

(defn read-queue
  [^String project-name]
  (when-let [^File f (queue-file project-name)]
    (when (.exists f)
      (read-edn f))))

(defn write-queue!
  [^String project-name data]
  (write-edn! (queue-file project-name) data))

(defn ensure-queue!
  [^String project-name]
  (when-not (str/blank? project-name)
    (when (nil? (read-queue project-name))
      (write-queue! project-name {:items []}))))

(defn add-job!
  [^String project-name ^String goal]
  (cond
    (str/blank? (str/trim (or goal "")))
    {:ok false :error "goal is empty"}
    :else
    (let [pn (str/trim project-name)
          g (str/trim goal)]
      (if (str/blank? pn)
        {:ok false :error "project name is empty"}
        (do
          (ensure-queue! pn)
          (let [data (or (read-queue pn) {:items []})
                id (str (UUID/randomUUID))
                item {:id id :goal g :status :pending :enqueued-at (System/currentTimeMillis)}
                items (conj (vec (:items data)) item)]
            (write-queue! pn (assoc data :items items))
            {:ok true :id id :project pn}))))))

(defn list-items
  [^String project-name]
  (vec (:items (read-queue project-name) [])))

(defn- item-status [item]
  (let [s (:status item)]
    (or (when (keyword? s) s)
        (when (string? s) (keyword s))
        :pending)))

(defn- first-pending [items]
  (first (filter #(= :pending (item-status %)) items)))

(defn- replace-item [items job-id f]
  (mapv (fn [i]
          (if (= (:id i) job-id)
            (f i)
            i))
        items))

(defn run-next-job!
  "Set active project, load context, run one pending job via `grog.core/run-tool-loop-on-messages`.
  Returns that map plus `:job-id` / `:project` when applicable."
  [^String project-name]
  (let [pn (str/trim project-name)]
    (if (str/blank? pn)
      {:ok false :error "project name is empty"}
      (do
        (cfg/set-active-project! pn)
        (ensure-queue! pn)
        (let [data (read-queue pn)
              items (vec (:items data))
              job (first-pending items)]
          (if-not job
            {:ok false :error "no pending jobs" :project pn}
            (let [tid (:id job)
                  goal (str (:goal job))
                  appendix (pd/thread-as-system-appendix pn :max-turns (cfg/jobs-thread-context-turns))
                  base (chat/messages-with-project-context pn (str (or appendix "")))
                  user-msg (str "## Project job (automated)\n\n"
                                "**Job id:** `" tid "`\n\n"
                                "**Goal:**\n" goal "\n\n"
                                "Work autonomously with tools. Persist durable notes via `assoc_store` "
                                "in this project as needed (e.g. key **`grog-jobs-notes-" tid "`**).\n\n"
                                "End with a clear **Findings** section: what you did, evidence, and outcomes.")
                  msgs (conj (vec base) {:role "user" :content user-msg})
                  runner (requiring-resolve 'grog.core/run-tool-loop-on-messages)
                  res (runner msgs {:answer-prefix "\n\n[job] "})]
              (if (:ok res)
                (do
                  (write-edn! (findings-file pn tid)
                              {:job-id tid
                               :goal goal
                               :findings (str (:content res ""))
                               :completed-at (System/currentTimeMillis)})
                  (write-queue! pn (assoc data :items (replace-item items tid
                                                                     #(assoc % :status :done
                                                                             :completed-at (System/currentTimeMillis)))))
                  (try
                    (pd/append-turn! :user (str "[job] " goal))
                    (pd/append-turn! :assistant (str (:content res "")))
                    (catch Exception _))
                  (assoc res :job-id tid :project pn))
                (do
                  (write-queue! pn (assoc data :items (replace-item items tid
                                                                     #(assoc % :status :failed
                                                                             :error (:error res)))))
                  (assoc res :job-id tid :project pn))))))))))
