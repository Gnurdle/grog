(ns grog.secrets
  "OS-backed secrets via [java-keyring](https://github.com/javakeyring/java-keyring)
  (macOS Keychain, Windows Credential Manager, Linux Secret Service / KWallet)
  with a **file fallback** so secrets keep working on headless/remote setups
  where no OS secret backend is reachable (SSH sessions, WSL, containers).

  Credentials are addressed by **service** (fixed to `\"grog\"`) and **account**
  (e.g. `BRAVE_SEARCH_API`, `LLM_API_KEY`).

  Storage priority:
    1. OS keyring — used whenever the backend responds.
    2. `secrets.edn` in the platform config home (see `grog.platform/config-home-dir`)
       — used when the OS backend is unsupported, unreachable, or rejects writes.
       The file is created with owner-only permissions where the OS supports it
       and lives **outside** the repo (never committed).

  Only **known** accounts may be set/read via `/secret`: the built-in set plus
  any registered with `refresh-known-accounts!` (from `:secrets {:accounts …}`
  in grog.edn)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.platform :as platform])
  (:import [com.github.javakeyring Keyring BackendNotSupportedException PasswordAccessException]))

(def ^:private ^String service-id "grog")

(def brave-search-api-account "BRAVE_SEARCH_API")

(def llm-api-account "LLM_API_KEY")

(def ^:private base-known-secret-defs
  "Accounts Grog knows about out of the box; used for `/secret` list and validation."
  [{:account brave-search-api-account
    :description "Brave Search API subscription token (header X-Subscription-Token)"}
   {:account llm-api-account
    :description "LLM API key for OpenAI-compatible providers (OpenRouter, OpenAI, Groq, etc.)"}])

(def ^:private !extra-secret-defs
  "User-registered account defs (from grog.edn `:secrets {:accounts […]}`), added
  by `refresh-known-accounts!`."
  (atom []))

(declare secrets-file)

(defn all-known-secret-defs
  "Built-in plus user-registered secret account definitions."
  []
  (into base-known-secret-defs @!extra-secret-defs))

(defn known-secret-defs
  "Backward-compatible accessor for the built-in account definitions."
  []
  base-known-secret-defs)

(defn register-known-accounts!
  "Replace the user-registered secret accounts with `accounts` (any seq of
  `{:account … :description …}` maps; strings are treated as accounts with no
  description). Blank/duplicate entries are dropped."
  [accounts]
  (let [defs (->> (or accounts [])
                  (keep (fn [a]
                          (if (map? a)
                            (let [acct (some-> (:account a) str str/trim not-empty)
                                  desc (some-> (:description a) str str/trim not-empty)]
                              (when acct {:account acct :description (or desc acct)}))
                            (when-let [acct (some-> a str str/trim not-empty)]
                              {:account acct :description acct}))))
                  distinct
                  vec)]
    (reset! !extra-secret-defs defs)
    defs))

(defn refresh-known-accounts!
  "Given the merged grog config map, register `:secrets {:accounts [...]}` (so
  `/secret` and `with_api_key` know about user-defined secret names). Call after
  loading/reloading config."
  [cfg]
  (register-known-accounts! (get-in cfg [:secrets :accounts]))
  cfg)

(defn- known-account-set []
  (set (map :account (all-known-secret-defs))))

(defn known-account?
  [^String account]
  (boolean (when account ((known-account-set) account))))

(defonce ^:private keyring-read-timeout-ms 4000)

;; Keyring/create can block forever without a working Secret Service / D-Bus (e.g. SSH session).
(defonce ^:private !keyring-unreachable (atom false))

;; Mirrors what storage backend is currently working, for /secret status + GUI.
(defonce ^:private !backend (atom :keyring))

(defn backend-status
  "`{:backend :keyring|:file :reason str|nil :path str}` — which secret backend is
  currently in use. `:keyring` until proved unreachable; `:file` once the OS
  backend fails (e.g. headless Linux) so the file fallback takes over."
  []
  (if @!keyring-unreachable
    {:backend :file
     :reason "OS secret backend did not respond / is unsupported"
     :path (.getPath (secrets-file))}
    {:backend :keyring
     :path (.getPath (secrets-file))}))

;; --- file fallback ---------------------------------------------------------

(defn secrets-file
  "The fallback secrets file: `<config-home>/secrets.edn` (outside the repo)."
  ^java.io.File []
  (io/file (platform/config-home-dir) "secrets.edn"))

(defn- harden-file! [^java.io.File f]
  ;; Best effort: owner-only read/write, no other users, on platforms that
  ;; support POSIX-style permissions (Windows ignores most of these).
  (try (doto f
         (.setReadable true true)
         (.setWritable true true)
         (.setExecutable false false))
       (catch Throwable _ f))
  f)

(defn- read-secret-file
  "File contents as `{account password}` (string keys), or nil."
  []
  (try
    (let [f (secrets-file)]
      (when (.exists f)
        (let [v (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))]
          (into {} (filter (fn [[k x]] (and (string? k) (string? x)))) v))))
    (catch Throwable _ nil)))

(defn- file-secret ^String [account]
  (some-> (read-secret-file) (get account) str/trim not-empty))

(defn- write-secret-file!
  "Persist the whole file map (add/update `account`, or drop it when `value` is
  nil). Creates the config home if needed, writes atomically, restricts perms."
  [^String account ^String value]
  (let [dir (platform/config-home-dir)
        f (secrets-file)
        cur (or (read-secret-file) {})
        next (cond-> cur
               (some? value) (assoc account value)
               (nil? value)  (dissoc account))
        tmp (io/file (str (.getPath f) ".tmp"))]
    (.mkdirs dir)
    (harden-file! tmp)
    (spit tmp (pr-str (into (sorted-map) next)) :encoding "UTF-8")
    (io/copy tmp f)
    (harden-file! f)
    (when (.exists tmp) (.delete tmp))
    f))

;; --- OS keyring access -----------------------------------------------------

(defn- fetch-secret-blocking!
  "Open keyring and read one password; may block. Used only inside a capped `future`."
  ^String [^String account]
  (with-open [^Keyring kr (Keyring/create)]
    (try
      (let [^String p (.getPassword kr service-id account)]
        (some-> p str str/trim not-empty))
      (catch PasswordAccessException _ nil))))

(defn get-secret
  "Return the secret for `account` under service `grog`, or nil if missing /
  unsupported / error. Tries the OS keyring first (time-bounded so a hung backend
  cannot freeze the JVM); falls back to the secrets file when the keyring is
  unreachable or unsupported (headless Linux, WSL, SSH)."
  ^String [^String account]
  (when-not (str/blank? account)
    (or
     (when-not @!keyring-unreachable
       (try
         (let [f (future
                   (try
                     (fetch-secret-blocking! account)
                     (catch BackendNotSupportedException _ ::unsupported)
                     (catch Exception _ ::error)))
               v (deref f keyring-read-timeout-ms ::timeout)]
           (cond
             (= ::timeout v)
             (do (reset! !keyring-unreachable true)
                 (reset! !backend :file)
                 (binding [*out* *err*]
                   (println "grog: OS keyring did not respond within"
                            (long (/ keyring-read-timeout-ms 1000))
                            "s; using file secret store."
                            "(" (.getPath (secrets-file)) ")"))
                 nil)
             (= ::unsupported v)
             (do (reset! !keyring-unreachable true)
                 (reset! !backend :file)
                 nil)
             (= ::error v) nil
             :else v))
         (catch Exception _ nil)))
     (file-secret account))))

(defn set-secret!
  "Persist `password` for `account` under service `grog`. `account` must be a
  known secret name. Writes to the OS keyring, silently falling back to the
  secrets file when no keyring backend is available. Returns
  `{:backend :keyring|:file :reason str|nil}`."
  [^String account ^String password]
  (when (str/blank? account)
    (throw (ex-info "account (key) is required" {})))
  (when (str/blank? password)
    (throw (ex-info "value must be non-empty" {})))
  (when-not (known-account? account)
    (let [known (sort (known-account-set))]
      (throw (ex-info (str "unknown secret key " (pr-str account) "; known: " (str/join ", " known))
                      {:account account :known known}))))
  (try
    (with-open [^Keyring kr (Keyring/create)]
      (.setPassword kr service-id account password))
    (reset! !keyring-unreachable false)
    (reset! !backend :keyring)
    {:backend :keyring}
    (catch BackendNotSupportedException e
      (write-secret-file! account password)
      (reset! !backend :file)
      {:backend :file :reason (str "no OS secret backend: " (.getMessage e))})
    (catch PasswordAccessException e
      (write-secret-file! account password)
      (reset! !backend :file)
      {:backend :file :reason (.getMessage e)})
    (catch Exception e
      (write-secret-file! account password)
      (reset! !backend :file)
      {:backend :file :reason (.getMessage e)})))

(defn delete-secret!
  "Remove `account` from the OS keyring (best effort) and the secrets file.
  `account` must be a known secret name. Returns
  `{:keyring :deleted|:absent|:unavailable :file :removed|:absent}`."
  [^String account]
  (when (str/blank? account)
    (throw (ex-info "account (key) is required" {})))
  (when-not (known-account? account)
    (throw (ex-info (str "unknown secret key " (pr-str account))
                    {:account account :known (sort (known-account-set))})))
  (let [had-file? (some? (file-secret account))
        _ (write-secret-file! account nil)
        kr (try
             (with-open [^Keyring kr (Keyring/create)]
               (.deletePassword kr service-id account)
               :deleted)
             (catch PasswordAccessException _ :absent)
             (catch Exception _ :unavailable))]
    (when (= :deleted kr)
      (reset! !backend :keyring))
    {:keyring kr :file (if had-file? :removed :absent)}))

(defn- keyring-set? [^String account]
  (boolean (some-> (get-secret account) not-empty)))

(defn print-known-secrets-summary!
  "Print known secret keys and whether each is set in the active store. Never
  prints values."
  []
  (let [b (backend-status)]
    (println (str "Defined secrets (service " service-id "):"))
    (doseq [{:keys [account description]} (all-known-secret-defs)]
      (println "  " account "— " description)
      (println "     store:" (if (keyring-set? account) "set" "unset")))
    (println)
    (println "Backend:" (if (= :keyring (:backend b))
                          "OS keyring"
                          (str "file fallback (" (:path b) ")")))
    (println "Set with:  /secret set <KEY> <value>   (or /secret <KEY> <value>)")
    (println "Remove:    /secret rm <KEY>")
    (println "Locations: /secret file   /secret backend")))

(defn startup-status-line
  "One line for chat startup (Brave / keyring hint). Does not open the keyring —
  that can block without D-Bus."
  []
  (str "Secrets: service \"" service-id "\""
       (if @!keyring-unreachable
         (str " — file store active (" (.getPath (secrets-file)) ")")
         " — OS keyring (file store fallback on headless/remote)")
       "; /secret set <KEY> <value>, /secret rm <KEY>"))