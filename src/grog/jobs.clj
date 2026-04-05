(ns grog.jobs
  "Project-scoped job queue in edn-store (`grog-jobs/queue.edn`). Each job has a goal; Grog runs the
  tool loop with full project context (SOUL + thread + memory paths) and persists findings."
  (:require [clojure.string :as str]
            [grog.chat-context :as chat]
            [grog.config :as cfg]
            [grog.edn-store :as store]
            [grog.project-dialog :as pd])
  (:import [java.net URLEncoder]
           [java.util UUID]))

(def ^:private mem-root "grog-memory")

(defn- enc ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn queue-keypath ^clojure.lang.PersistentVector [^String project-name]
  [mem-root "Projects" (enc project-name) (enc "grog-jobs") (enc "queue")])

(defn findings-keypath ^clojure.lang.PersistentVector [^String project-name ^String job-id]
  [mem-root "Projects" (enc project-name) (enc "grog-jobs") (enc (str "findings-" job-id))])

(defn read-queue
  [^String project-name]
  (when (store/configured?)
    (store/read-leaf (queue-keypath project-name))))

(defn write-queue!
  [^String project-name data]
  (store/write-leaf! (queue-keypath project-name) data))

(defn ensure-queue!
  [^String project-name]
  (when (and (store/configured?) (not (str/blank? project-name)))
    (when (nil? (read-queue project-name))
      (write-queue! project-name {:items []}))))

(defn add-job!
  [^String project-name ^String goal]
  (cond
    (str/blank? (str/trim (or goal "")))
    {:ok false :error "goal is empty"}
    (not (store/configured?))
    {:ok false :error "edn-store not configured"}
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
  (if-not (store/configured?)
    {:ok false :error "edn-store not configured"}
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
                                  "Work autonomously with tools. Persist durable notes under `memory_*` "
                                  "in this project as needed (e.g. namespace **`grog-jobs`**, key **`notes-" tid "`**).\n\n"
                                  "End with a clear **Findings** section: what you did, evidence, and outcomes.")
                    msgs (conj (vec base) {:role "user" :content user-msg})
                    runner (requiring-resolve 'grog.core/run-tool-loop-on-messages)
                    res (runner msgs {:answer-prefix "\n\n[job] "})]
                (if (:ok res)
                  (do
                    (store/write-leaf! (findings-keypath pn tid)
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
                    (assoc res :job-id tid :project pn)))))))))))
