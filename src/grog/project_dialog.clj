(ns grog.project-dialog
  "Append chat turns to the active project's `dialog/thread.edn` under its project
  home (`~/grog-projects/<proj>/dialog/thread.edn`). Each project owns its own
  dialog — no more edn-store `grog-memory/Projects/<proj>` indirection."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.projects :as projects])
  (:import (java.io File)))

(defn- thread-file
  "The project's `dialog/thread.edn` File (creates the dialog dir)."
  ^File [^String project-name]
  (let [d (projects/dialog-dir project-name)]
    (io/file d "thread.edn")))

(defn- read-thread-file
  "Parse thread.edn (map with :turns), or {} / {:turns []} if missing/invalid."
  [^File f]
  (try
    (let [s (slurp f :encoding "UTF-8")
          m (when (seq (str/trim s)) (edn/read-string {:eof nil} s))]
      (cond
        (nil? m) {}
        (map? m) m
        :else {}))
    (catch Exception _ {})))

(defn read-thread-raw
  "Read `{:turns […]}` for `project-name`, or `nil` if the file is missing."
  [^String project-name]
  (when (and project-name (not (str/blank? (str project-name))))
    (let [^File f (thread-file (str/trim project-name))]
      (when (.exists f)
        (read-thread-file f)))))

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
  "Append one message to the active project's dialog log. No-op if no active project.
  `role` is `:user` or `:assistant` (or strings). `content` is coerced to string."
  [role content]
  (when-let [pn (projects/resolve-active-project)]
    (let [^File f (thread-file pn)
          raw (read-thread-file f)
          turns (vec (if (sequential? (:turns raw)) (:turns raw) []))
          base (if (map? raw) raw {})
          r (cond
              (keyword? role) (name role)
              (string? role) role
              :else (str role))
          entry {:role r
                 :content (str content)
                 :at (System/currentTimeMillis)}]
      (spit f (with-out-str (pprint/pprint (assoc base :turns (conj turns entry))))
            :encoding "UTF-8"))))
