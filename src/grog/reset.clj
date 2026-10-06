(ns grog.reset
  "The airbag: a FACTORY RESET that removes what grog made, and nothing else.

  Three kinds of grog-made state are in scope:
    1. the getting-started project (`grog.bootstrap`) — grog's own artifact;
    2. grog's user-level state under the config home — generated session configs,
       the appearance/state scratch, the secret FILE store, the secret ledger;
    3. the credentials grog itself wrote to the OS keyring — read from the secret
       ledger (NAMES only) and deleted.

  Safety rules, deliberately conservative:
    * NOTHING is destroyed outright: the config home and the getting-started
      project are MOVED into a timestamped backup beside the config home, so a
      reset is recoverable.
    * the user's other projects — including a developer's own `grog` project —
      are never touched.
    * the reset prints its PLAN and changes nothing until run with `--yes`.
    * secret VALUES are never copied or exported anywhere. The keyring entries
      grog created are deleted, so they must be re-entered with `/secret`
      afterwards; only the config home's files survive in the backup.

  Entry points: the `/reset [--yes]` chat command (`grog.core`) and
  `clojure -M -m grog.reset [--yes]`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.bootstrap :as bootstrap]
            [grog.platform :as platform]
            [grog.projects :as projects]
            [grog.secrets :as secrets])
  (:import (java.io File)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(defn- stamp
  ^String []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (LocalDateTime/now)))

(defn plan
  "What a reset WOULD remove. Reads the filesystem; changes nothing."
  []
  (let [home (platform/config-home-dir)
        proj (projects/project-dir projects/bootstrap-project-name)
        sessions (io/file home "sessions")]
    {:config-home (.getPath home)
     :config-home-exists? (boolean (.exists home))
     :getting-started-project (some-> proj .getPath)
     :project-exists? (boolean (and proj (.exists proj)))
     :session-configs (if (.isDirectory sessions) (count (or (.listFiles sessions) [])) 0)
     :secret-accounts (vec (sort (secrets/ledger-accounts)))
     :backup-dir (str (.getPath home) ".reset-" (stamp))}))

(defn- copy-tree!
  "Recursively copy a file or directory."
  [^File src ^File dst]
  (if (.isDirectory src)
    (do (.mkdirs dst)
        (doseq [^File c (or (.listFiles src) (make-array File 0))]
          (copy-tree! c (io/file dst (.getName c)))))
    (do (when-let [p (.getParentFile dst)] (.mkdirs p))
        (io/copy src dst))))

(defn- delete-tree!
  "Recursively delete a file or directory (best effort)."
  [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [^File c (or (.listFiles f) (make-array File 0))]
        (delete-tree! c)))
    (.delete f)))

(defn perform!
  "Perform the reset. Returns a report map. Safe when parts are already absent."
  []
  (let [p (plan)
        home (platform/config-home-dir)
        proj (projects/project-dir projects/bootstrap-project-name)
        backup (io/file (:backup-dir p))
        accounts (secrets/ledger-accounts)
        deleted (atom [])
        project-existed? (boolean (and proj (.exists proj)))
        config-existed? (boolean (.exists home))]
    (.mkdirs backup)
    ;; 1. keep a copy of the getting-started project before it is removed
    (when project-existed? (copy-tree! proj (io/file backup "ouroboros-project")))
    ;; 2. remove the credentials grog itself created (names from the ledger;
    ;;    NO values are exported anywhere)
    (doseq [a accounts]
      (try (secrets/delete-secret! a) (swap! deleted conj a)
           (catch Throwable _ nil)))
    ;; 3. move the config home aside (same parent → a plain rename), then drop
    ;;    the getting-started project and let grog come up fresh
    (let [config-moved? (if config-existed?
                          (.renameTo home (io/file backup "config-home"))
                          false)]
      (when project-existed? (delete-tree! proj))
      ;; if the last-used marker pointed at the getting-started project, clear
      ;; it so the next start re-resolves cleanly
      (let [marker (io/file (projects/projects-home) ".active-project")]
        (try (when (and (.exists marker)
                        (= projects/bootstrap-project-name (str/trim (slurp marker))))
               (.delete marker))
             (catch Throwable _ nil)))
      ;; ASK the next launch for onboarding. A reset must not delete the user's
      ;; projects, so `first-run?` is false on any real box — without this the
      ;; onboarding would never come back after a reset.
      (bootstrap/request-onboarding!)
      {:backup (.getPath backup)
       :config-home-moved? config-moved?
       :project-removed? project-existed?
       :secrets-deleted (vec (sort @deleted))})))

(def ^:private confirm-words
  "Words that confirm a reset. A bare `yes` counts — typing a confirmation word
  IS the confirmation; `--yes` is just the tidy spelling."
  #{"--yes" "-y" "--force" "-f" "--confirm" "yes" "y" "force" "confirm"})

(defn- yes?
  [args]
  (let [toks (->> (str/split (str args) #"\s+")
                  (map str/lower-case)
                  (remove str/blank?))]
    (boolean (some confirm-words toks))))

(defn run-command!
  "Chat-facing driver: print the plan, or perform it when `args` says --yes.
  Prints to `*out*` (the caller binds it to the transcript pane). Always true."
  [args]
  (if-not (yes? args)
    (let [p (plan)]
      (println "grog factory reset — PLAN (nothing changed yet)")
      (println)
      (println "  config home ........... " (:config-home p)
               (if (:config-home-exists? p) "" "(absent)"))
      (println "  session configs ........" (:session-configs p))
      (println "  getting-started project" (:getting-started-project p)
               (if (:project-exists? p) "" "(absent)"))
      (println "  secrets grog stored ... "
               (if (seq (:secret-accounts p))
                 (str/join ", " (:secret-accounts p))
                 "(none)"))
      (println)
      (println "  backup dir ............. " (:backup-dir p))
      (println)
      (println "Nothing is destroyed: the config home and the getting-started project are")
      (println "MOVED into the backup dir above. Secret VALUES are not copied anywhere —")
      (println "credentials grog created are deleted and must be re-entered with /secret.")
      (println "Your other projects (including any developer 'grog' project) are untouched.")
      (println)
      (println "Run again with  /reset yes  (or /reset --yes)  to actually do it, then restart grog.")
      (println "After a reset, the next launch shows the getting-started onboarding again."))
    (let [r (perform!)]
      (println "grog factory reset — DONE")
      (println "  backup .................. " (:backup r))
      (println "  config home moved ....... " (:config-home-moved? r))
      (println "  getting-started project removed " (:project-removed? r))
      (println "  secrets deleted ......... "
               (if (seq (:secrets-deleted r))
                 (str/join ", " (:secrets-deleted r))
                 "(none)"))
      (println)
      (println "Restart grog: it comes up fresh and shows the getting-started onboarding")
      (println "(the reset asks for it; your other projects are left alone).")))
  true)

(defn -main
  "CLI: `clojure -M -m grog.reset [--yes]`."
  [& args]
  (run-command! (str/join " " args))
  (shutdown-agents))
