(ns grog.chron
  "Scheduled tasks while chat is running (`:chron` in grog.edn). Each tick prints a banner on stderr
  and runs the Ollama tool loop with current session context (project + thread when applicable)."
  (:require [clojure.string :as str]
            [grog.chat-context :as chat]
            [grog.config :as cfg]
            [grog.edn-store :as store]
            [grog.project-dialog :as pd])
  (:import [java.util.concurrent Executors TimeUnit]))

(defonce ^:private !scheduler (atom nil))
(defonce ^:private !busy (atom false))

(defn- task-interval-seconds [task]
  (cond
    (and (number? (:interval-seconds task)) (pos? (long (:interval-seconds task))))
    (max 15 (long (:interval-seconds task)))
    (and (number? (:every-minutes task)) (pos? (long (:every-minutes task))))
    (max 60 (* 60 (long (:every-minutes task))))
    :else
    3600))

(defn- task-id [task]
  (str/trim (str (or (:id task) (:name task) "chron-task"))))

(defn- chron-last-run-keypath [^String task-id]
  ["grog-chron" "last-run" task-id])

(defn- write-last-run! [^String task-id summary]
  (when (store/configured?)
    (try
      (store/write-leaf! (chron-last-run-keypath task-id)
                         {:at (System/currentTimeMillis)
                          :summary (if summary (subs summary 0 (min 8000 (count summary))) "")})
      (catch Exception _ nil))))

(defn- run-chron-body! [tid instr]
  (binding [*out* *err*]
    (println "")
    (println "════════ grog chron:" tid "════════")
    (flush)
    (let [proj (cfg/active-project-name)
          appendix (when proj
                     (pd/thread-as-system-appendix proj :max-turns (cfg/jobs-thread-context-turns)))
          base (chat/messages-with-project-context proj (or appendix ""))
          user-msg (str "## Chron task `" tid "`\n\n"
                        (when proj (str "Active project: **" proj "**.\n\n"))
                        "Execute this recurring check. Be concise but actionable.\n\n"
                        instr)
          msgs (conj (vec base) {:role "user" :content user-msg})
          runner (requiring-resolve 'grog.core/run-tool-loop-on-messages)
          res (runner msgs {:answer-prefix "\n\n[chron] "})]
      (if (:ok res)
        (do (println (:content res))
            (write-last-run! tid (:content res))
            (when proj
              (try
                (pd/append-turn! :user (str "[chron] " tid))
                (pd/append-turn! :assistant (str (:content res "")))
                (catch Exception _))))
        (println "grog: chron error:" (:error res)))
      (println "════════ end chron ════════")
      (flush))))

(defn- run-one-task! [task]
  (let [tid (task-id task)
        instr (str/trim (str (or (:instruction task) (:prompt task) "")))]
    (when (str/blank? instr)
      (binding [*out* *err*] (println "grog: chron: task" (pr-str tid) "has no :instruction — skipping")))
    (when-not (str/blank? instr)
      (when (compare-and-set! !busy false true)
        (try
          (run-chron-body! tid instr)
          (finally
            (reset! !busy false)))))))

(defn stop!
  "Shut down the scheduler (idempotent)."
  []
  (when-let [{:keys [^java.util.concurrent.ScheduledExecutorService executor futures]} @!scheduler]
    (doseq [^java.util.concurrent.ScheduledFuture f @futures]
      (when f (.cancel f false)))
    (when executor (.shutdownNow executor)))
  (reset! !scheduler nil))

(defn start!
  "Start periodic tasks from config. No-op if disabled or already running."
  []
  (when (cfg/chron-scheduler-enabled?)
    (when-not @!scheduler
      (let [tasks (cfg/chron-tasks)
            ^java.util.concurrent.ScheduledExecutorService ex (Executors/newScheduledThreadPool 1)
            futs (atom [])]
        (doseq [task tasks]
          (let [sec (long (task-interval-seconds task))
                tid (task-id task)
                fut (.scheduleAtFixedRate ex
                                          ^Runnable (fn [] (try (run-one-task! task) (catch Throwable t
                                                                                      (binding [*out* *err*]
                                                                                        (println "grog: chron" tid "exception:" (.getMessage t))))))
                                          (long sec)
                                          (long sec)
                                          TimeUnit/SECONDS)]
            (swap! futs conj fut)))
        (reset! !scheduler {:executor ex :futures futs})
        (binding [*out* *err*]
          (println "grog: chron started —" (count tasks) "task(s)")
          (flush))))))

(defn status-line
  []
  (if (cfg/chron-scheduler-enabled?)
    (if @!scheduler
      (str "chron: running — " (count (cfg/chron-tasks)) " task(s)")
      "chron: enabled in config but not started (start chat)")
    "chron: off — configure :chron with :enabled true and a :tasks vector in grog.edn"))
