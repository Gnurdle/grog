(ns grog.tasks
  "Per-project task list, stored under the project home:
  `~/grog-projects/<proj>/tasks.edn`.

  Tasks are either **TODO** (one-shot things to do) or **recurring** (checks that
  should be re-done at an interval). They are ALWAYS user-instigated: nothing here
  runs on its own. The agent surfaces them on demand (`/tasks`, or when asked
  \"what needs doing?\") as reminders, and the user decides what to run."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.io File)
           (java.util UUID)))

(defn- projects-dir ^File [^String project-name]
  (let [p (str/trim (str project-name))]
    (io/file (System/getProperty "user.home") "grog-projects" p)))

(defn- tasks-file ^File [^String project-name]
  (io/file (projects-dir project-name) "tasks.edn"))

(defn- read-tasks-file [^String project-name]
  (let [f (tasks-file project-name)]
    (if (.exists f)
      (try
        (let [s (slurp f :encoding "UTF-8")
              v (when (seq (str/trim s)) (edn/read-string {:eof nil} s))]
          (if (map? v) v {}))
        (catch Exception _ {}))
      {})))

(defn- write-tasks-file! [^String project-name data]
  (let [f (tasks-file project-name)]
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (pprint/pprint {:tasks (vec (:tasks data))}))
          :encoding "UTF-8")
    data))

(defn read-tasks
  "All tasks for the project as a vector (newest first)."
  [project-name]
  (vec (:tasks (read-tasks-file project-name))))

(defn add-task!
  "Add a task to the project's list.

  `opts`:
    :kind      :todo (default) or :recurring
    :detail    optional longer description
    :due       optional epoch ms (when it's due / next reminder)
    :interval  optional seconds (recurring frequency; sets a future `:due`)"
  [project-name title & {:keys [kind detail due interval]}]
  (let [pn (str/trim (str project-name))
        title (str/trim (str (or title "")))]
    (cond
      (str/blank? pn)
      {:ok false :error "project name is empty"}

      (str/blank? title)
      {:ok false :error "title is empty"}

      :else
      (let [now (System/currentTimeMillis)
            kind (if (= :recurring kind) :recurring :todo)
            interval (when (and (number? interval) (pos? (long interval)))
                       (long interval))
            due (or due (when (and interval now) (+ now (* 1000 interval))))
            item {:id (str (UUID/randomUUID))
                  :kind kind
                  :title title
                  :detail (some-> detail str str/trim not-empty)
                  :due (when (and (number? due) (pos? (long due))) (long due))
                  :interval interval
                  :status :open
                  :created now}]
        (write-tasks-file! pn {:tasks (conj (read-tasks pn) item)})
        {:ok true :id (:id item) :project pn}))))

(defn mark-done!
  "Mark a task done by id."
  [project-name id]
  (let [pn (str/trim (str project-name))
        tasks (read-tasks pn)
        next (mapv (fn [t]
                     (if (= id (:id t))
                       (assoc t :status :done :done-at (System/currentTimeMillis))
                       t))
                   tasks)]
    (write-tasks-file! pn {:tasks next})
    {:ok true :project pn}))

(defn delete-task!
  "Remove a task by id."
  [project-name id]
  (let [pn (str/trim (str project-name))
        tasks (read-tasks pn)
        kept (vec (remove #(= id (:id %)) tasks))]
    (write-tasks-file! pn {:tasks kept})
    {:ok true :removed (not= (count tasks) (count kept)) :project pn}))

(defn open-tasks
  "Tasks with status :open."
  [project-name]
  (filter #(= :open (:status %)) (read-tasks project-name)))

(defn due-tasks
  "Open tasks that are due now or overdue (have a :due <= now)."
  [project-name]
  (let [now (System/currentTimeMillis)]
    (filter (fn [t]
              (and (= :open (:status t))
                   (some? (:due t))
                   (<= (long (:due t)) now)))
            (read-tasks project-name))))

(defn upcoming-tasks
  "Open tasks ordered by due (nil-due last), optionally capped."
  [project-name & {:keys [limit]}]
  (let [sorted (sort-by (fn [t] (or (:due t) Long/MAX_VALUE))
                        (open-tasks project-name))]
    (if (and limit (pos? (long limit)))
      (take (long limit) sorted)
      sorted)))