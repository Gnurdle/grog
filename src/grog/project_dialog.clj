(ns grog.project-dialog
  "Append chat turns to `grog-memory/Projects/<project>/dialog/thread.edn` when an active project is set."
  (:require [grog.config :as cfg]
            [grog.edn-store :as store])
  (:import [java.net URLEncoder]))

(def ^:private ^String mem-root "grog-memory")

(defn- enc ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- thread-keypath [^String project-name]
  [mem-root "Projects" (enc project-name) "dialog" "thread"])

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
