(ns grog.project-dialog
  "Append chat turns to `grog-memory/Projects/<project>/dialog/thread.edn` when an active project is set."
  (:require [clojure.string :as str]
            [grog.config :as cfg]
            [grog.edn-store :as store])
  (:import [java.net URLEncoder]))

(def ^:private ^String mem-root "grog-memory")

(defn- enc ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- thread-keypath [^String project-name]
  [mem-root "Projects" (enc project-name) "dialog" "thread"])

(defn read-thread-raw
  "Read `{:turns […]}` for `project-name` from edn-store, or `nil` if missing/off."
  [^String project-name]
  (when (and (store/configured?) (some-> project-name str str/trim not-empty))
    (store/read-leaf (thread-keypath (str/trim project-name)))))

(defn thread-as-system-appendix
  "Format prior dialog turns as markdown for an extra `system` message (jobs/chron reload context)."
  [^String project-name & {:keys [max-turns] :or {max-turns 40}}]
  (when-let [m (read-thread-raw project-name)]
    (let [turns (vec (or (:turns m) []))
          cap (max 1 (long max-turns))
          slice (if (> (count turns) cap) (vec (take-last cap turns)) turns)]
      (when (seq slice)
        (str "## Prior dialog in this project (last " (count slice) " turns)\n\n"
             (str/join "\n\n---\n\n"
                       (map (fn [x]
                              (str "**" (str/trim (str (:role x))) "**\n\n"
                                   (str/trim (str (:content x)))))
                            slice)))))))

(defn append-turn!
  "Append one message to the project dialog log. No-op if edn-store is off or no active project.
  `role` is `:user` or `:assistant` (or strings). `content` is coerced to string."
  [role content]
  (when (and (store/configured?) (some? (cfg/active-project-name)))
    (let [pn (cfg/active-project-name)
          kp (thread-keypath pn)
          raw (store/read-leaf kp)
          turns (vec (if (and (map? raw) (sequential? (:turns raw))) (:turns raw) []))
          base (if (map? raw) raw {})
          r (cond
              (keyword? role) (name role)
              (string? role) role
              :else (str role))
          entry {:role r
                 :content (str content)
                 :at (System/currentTimeMillis)}]
      (store/write-leaf! kp (assoc base :turns (conj turns entry))))))
