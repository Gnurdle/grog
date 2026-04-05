(ns grog.chat-context
  "Shared Ollama message construction: SOUL, project banner, skills, oracle system blocks."
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.oracle :as oracle]
            [grog.skills :as skills]
            [grog.soul :as soul]))

(defn wrap-soul-as-system-prompt [raw]
  (str "## Persistent instructions (SOUL.md)\n\n"
       raw
       "\n\n---\nTreat the above as standing rules for your replies unless the user clearly overrides them for a single turn."))

(defn system-messages
  "Vector of `{:role \"system\" :content …}` for the current session (uses `active-project-name`)."
  []
  (try
    (vec
     (concat
      (when-let [t (some-> (soul/read-text) str str/trim not-empty)]
        [{:role "system" :content (wrap-soul-as-system-prompt t)}])
      (when-let [p (config/active-project-name)]
        [{:role "system"
          :content (str "Active project: **" p "**. `memory_*` tools persist under `Projects/" p "/…` in the configured edn-store; your turns are also logged to `Projects/" p "/dialog/thread.edn`.")}])
      (when-let [blk (some-> (skills/system-prompt-block) str str/trim not-empty)]
        [{:role "system" :content blk}])
      (when-let [blk (some-> (oracle/system-prompt-block) str str/trim not-empty)]
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
