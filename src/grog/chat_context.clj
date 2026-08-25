(ns grog.chat-context
  "Shared LLM message construction: SOUL, project banner + loaded project context,
  skills system blocks."
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.projects :as projects]
            [grog.skills :as skills]
            [grog.soul :as soul]))

(defn wrap-soul-as-system-prompt [raw]
  (str "## Persistent instructions (SOUL.md)\n\n"
       raw
       "\n\n---\nTreat the above as standing rules for your replies unless the user clearly overrides them for a single turn."))

(defn- project-context-message
  "The Active project system message: points the model at the project's home
  directory (outside the source tree) and includes any loaded context."
  []
  (let [p (projects/resolve-active-project)]
    {:role "system"
     :content
     (str "Active project: **" p "**. Its context lives in the project home at `"
          (some-> (projects/project-dir p) .getPath)
          "` (outside the source tree, under grog's projects dir). "
          "The active project's directory is your working scope; its primary "
          "working dir is " (some-> (projects/project-root p) .getPath) ".\n\n"
          "### Project context\n\n"
          (or (projects/load-context)
              "(no context loaded)"))}))

(defn system-messages
  "Vector of `{:role \"system\" :content …}` for the current session (uses `active-project-name`)."
  []
  (try
    (vec
     (concat
      (when-let [t (some-> (soul/read-text) str str/trim not-empty)]
        [{:role "system" :content (wrap-soul-as-system-prompt t)}])
      (when-let [m (project-context-message)]
        [m])
      (when-let [blk (some-> (skills/system-prompt-block) str str/trim not-empty)]
        [{:role "system" :content blk}])))
    (catch Exception e
      (binding [*out* *err*]
        (println "grog: SOUL not applied:" (.getMessage e)))
      nil)))

(defn history->messages
  [system-msgs history]
  (vec (concat system-msgs
               (mapcat (fn [{:keys [user assistant]}]
                         [{:role "user" :content user}
                          {:role "assistant" :content assistant}])
                       history))))

(defn recent-history-for-cap
  [history cap]
  (cond
    (nil? cap) history
    (and (number? cap) (zero? (long cap))) []
    (and (number? cap) (pos? (long cap))) (vec (take-last cap history))
    :else history))

(defn chat-context-messages
  [history]
  (history->messages (system-messages)
                     (recent-history-for-cap history (config/chat-history-turns))))

(defn messages-with-project-context
  "Like `system-messages` plus optional loaded project dialog (for jobs/chron). `project-name` should match active project for memory paths."
  [project-name thread-appendix]
  (vec (concat (system-messages)
               (when (and project-name
                          (not (str/blank? (str project-name)))
                          (not (str/blank? thread-appendix)))
                 [{:role "system" :content thread-appendix}]))))
