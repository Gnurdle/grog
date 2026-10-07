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

  `/secret` accepts **any** account name. The built-in accounts plus anything
  registered with `refresh-known-accounts!` (from `:secrets {:accounts …}` in
  grog.edn) are *known* names, used only for the listing and as the default
  `with_api_key` allowlist — they are an advisory, **not** a gate. A name you
  invented in a config file (e.g. `:password-secret \"ODOO_PROD_PASSWORD\"`) is
  settable, readable and removable without being declared anywhere first."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.platform :as platform])
  (:import [com.github.javakeyring Keyring BackendNotSupportedException PasswordAccessException]))

(def ^:private ^String service-id "grog")

(def brave-search-api-account "BRAVE_SEARCH_API")

(def llm-api-account "LLM_API_KEY")

(def gitlab-token-account "GITLAB_TOKEN")

(def ^:private base-known-secret-defs
  "Accounts Grog knows about out of the box; used for `/secret` list and validation."
  [{:account brave-search-api-account
    :description "Brave Search API subscription token (header X-Subscription-Token)"}
   {:account llm-api-account
    :description "LLM API key for OpenAI-compatible providers (OpenRouter, OpenAI, Groq, etc.)"}
   {:account gitlab-token-account
    :description "GitLab Personal Access Token (header PRIVATE-TOKEN) for the grog-gitlab MCP"}
   {:account "ALPACA_API_KEY"
    :description "Alpaca API key id (grog-alpaca; APCA-API-KEY-ID)"}
   {:account "ALPACA_SECRET_KEY"
    :description "Alpaca API secret (grog-alpaca; APCA-API-SECRET-KEY)"}])

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
  "The built-in account definitions."
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

(def ^:private secret-verbs
  #{"set" "rm" "del" "delete" "file" "backend" "list"})

(defn redact-secret-command
  "Mask the secret VALUE in a `/secret …` command line so it can be echoed into
  the transcript, logged, and persisted without leaking the credential.

  Handles both explicit (`/secret set KEY VALUE`) and short (`/secret KEY
  VALUE`) forms; a bare `/secret`, `/secret file`, `/secret backend`, `/secret
  list`, and `/secret rm KEY` carry no secret and pass through unchanged. Any
  non-`/secret` line is returned as-is, so this is safe to apply to every turn."
  [s]
  (let [t (some-> s str)]
    (if (nil? t)
      s
      (if-let [[_ pre k] (re-matches #"(?is)^(\s*/secret\s+(?:set\s+)?)(\S+)\s+\S.*$" t)]
        (if (contains? secret-verbs (str/lower-case k))
          t
          (str pre k " ***redacted***"))
        t))))

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

(defn secret-ledger-file
  "The ledger of secret ACCOUNT NAMES grog itself has written — names only,
  never values.

  The OS keyring has no 'list my accounts' API, so this is the only way the
  factory reset (`grog.reset`) can know which credentials grog created and
  should therefore remove — without touching keys the user stored by other means."
  ^java.io.File []
  (io/file (platform/config-home-dir) "secret-ledger.edn"))

(defn- read-ledger
  "The ledger as a set of account names (strings), or nil if absent/unreadable."
  []
  (try
    (let [f (secret-ledger-file)]
      (when (.exists f)
        (let [v (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))]
          (set (filter string? v)))))
    (catch Throwable _ nil)))

(defn ledger-accounts
  "Account names grog has written to the secret store (a set of strings)."
  []
  (or (read-ledger) #{}))

(defn- note-in-ledger!
  "Record `account` in the ledger (best effort — the ledger must never break a
  secret write). The ledger holds NAMES only, never values, so it needs no
  special permissions."
  [^String account]
  (try
    (let [f (secret-ledger-file)
          accounts (conj (ledger-accounts) (str account))]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (pr-str (into (sorted-set) accounts)) :encoding "UTF-8"))
    (catch Throwable _ nil)))

(defn clear-ledger!
  "Remove the ledger file (the factory reset calls this after removing the
  accounts it names)."
  []
  (try (let [f (secret-ledger-file)] (when (.exists f) (.delete f)))
       (catch Throwable _ nil)))

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
  (let [f (secrets-file)
        cur (or (read-secret-file) {})
        next (cond-> cur
               (some? value) (assoc account value)
               (nil? value)  (dissoc account))
        tmp (io/file (str (.getPath f) ".tmp"))]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
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

(defn- note-unknown-account!
  "One advisory line for an account grog has not heard of.

  This used to be a HARD REJECT (`set-secret!` refused any account outside the
  curated list), which was backwards: the store is YOURS, and a name you invented
  in a config file — `:password-secret \"ODOO_PROD_PASSWORD\"`, an MCP server's
  expected env var — has to be settable without first being declared somewhere.
  The only thing the gate actually bought was catching a typo, so it is now an
  advisory and nothing more."
  [^String account]
  (when-not (known-account? account)
    (binding [*out* *err*]
      (println (str "grog: note: '" account "' is not a declared account — proceeding anyway. "
                    "(Declared: " (str/join ", " (sort (known-account-set))) ")")))))

(defn set-secret!
  "Persist `password` for `account` under service `grog`.

  ANY account name is accepted — see `note-unknown-account!`. (The model-facing
  allowlist is a different thing and stays: `with_api_key` only ever uses
  accounts listed in `:with-api-key :allowed-secrets`.)

  Writes to the OS keyring, silently falling back to the secrets file when no
  keyring backend is available. Returns
  `{:backend :keyring|:file :reason str|nil}`."
  [^String account ^String password]
  (when (str/blank? account)
    (throw (ex-info "account (key) is required" {})))
  (when (str/blank? password)
    (throw (ex-info "value must be non-empty" {})))
  (note-unknown-account! account)
  (note-in-ledger! account)
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

  ANY account name is accepted — see `note-unknown-account!`; an undeclared
  name is a no-op advisory, never a rejection. Returns
  `{:keyring :deleted|:absent|:unavailable :file :removed|:absent}`."
  [^String account]
  (when (str/blank? account)
    (throw (ex-info "account (key) is required" {})))
  (note-unknown-account! account)
  (let [had-file? (some? (file-secret account))
        _ (when had-file? (write-secret-file! account nil))
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
  (let [b (backend-status)
        declared (set (map :account (all-known-secret-defs)))
        extra (->> (or (read-secret-file) {}) keys (remove declared) sort)]
    (println (str "Defined secrets (service " service-id "):"))
    (doseq [{:keys [account description]} (all-known-secret-defs)]
      (println "  " account "— " description)
      (println "     store:" (if (keyring-set? account) "set" "unset")))
    (when (seq extra)
      (println)
      (println "Other secrets in the file store (undeclared — still fully usable):")
      (doseq [a extra]
        (println "  " a "— store:" (if (keyring-set? a) "set" "unset"))))
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