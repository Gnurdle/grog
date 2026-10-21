(ns grog.secrets
  "OS-backed secrets via [java-keyring](https://github.com/javakeyring/java-keyring)
  (macOS Keychain, Windows Credential Manager, Linux Secret Service / KWallet).

  Credentials are addressed by **service** (fixed to `\"grog\"`) and **account**
  (e.g. `BRAVE_SEARCH_API`, `LLM_API_KEY`) and live ONLY in the OS keyring.

  A secret VALUE is never written to a file. There is deliberately NO `.edn`
  fallback: a key in a plaintext config file is a leak waiting to be committed,
  backed up, or synced — and it will be. When no OS secret backend is reachable
  (headless, SSH, a container), a write FAILS LOUDLY instead of silently
  downgrading to a file.

  Secret NAMES (never values) may appear in `.edn`: `secret-ledger.edn` records
  the accounts grog created, and `:secrets {:accounts […]}` in grog.edn declares
  names for the listing + `with_api_key` allowlist. A name is not a secret.

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
  #{"set" "rm" "del" "delete" "backend" "list"})

(defn redact-secret-command
  "Mask the secret VALUE in a `/secret …` command line so it can be echoed into
  the transcript, logged, and persisted without leaking the credential.

  Handles both explicit (`/secret set KEY VALUE`) and short (`/secret KEY
  VALUE`) forms; a bare `/secret`, `/secret backend`, `/secret list`, and
  `/secret rm KEY` carry no secret and pass through unchanged. Any non-`/secret`
  line is returned as-is, so this is safe to apply to every turn."
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
(defn backend-status
  "`{:backend :keyring :reason str|nil}` — the keyring, or why it is unreachable.

  This only REPORTS a broken keyring; it never downgrades storage anywhere else."
  []
  (if @!keyring-unreachable
    {:backend :keyring
     :reason "OS secret backend did not respond / is unsupported"}
    {:backend :keyring}))

;; --- ledger (NAMES only — never values) ------------------------------------

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
  unsupported / error. Reads the OS keyring ONLY (time-bounded so a hung backend
  cannot freeze the JVM); a secret value never lives in a file."
  ^String [^String account]
  (when-not (str/blank? account)
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
                (binding [*out* *err*]
                  (println "grog: OS keyring did not respond within"
                           (long (/ keyring-read-timeout-ms 1000))
                           "s; secrets are unavailable. grog will NOT fall back to a file."))
                nil)
            (= ::unsupported v)
            (do (reset! !keyring-unreachable true) nil)
            (= ::error v) nil
            :else v))
        (catch Exception _ nil)))))

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

(defn unquote-value
  "Strip ONE matching pair of surrounding quotes — single or double — from a
  command-line value, then trim.

  Quoting is shell habit and here it is a footgun: storing the quotes as part of
  the credential makes the provider reject a key that looks correct in every
  listing, with an error that names nothing. Only a MATCHED pair is removed: an
  unbalanced leading quote is left alone, because a secret may legitimately begin
  with a quote character and silently deleting one corrupts it.

  Written with (int c) = 34 / 39 rather than quote character literals on purpose —
  the function that handles quotes should not itself depend on escaping them."
  [s]
  (let [t (str/trim (str s))
        n (count t)
        c (int (first t))]
    (if (and (>= n 2)
             (or (= c 34) (= c 39))          ; 34 = double quote, 39 = single quote
             (= c (int (last t))))
      (str/trim (subs t 1 (dec n)))
      t)))

(defn set-secret!
  "Persist `password` for `account` under service `grog`.

  A surrounding QUOTE PAIR on `password` is stripped first (see `unquote-value`),
  so `/secret set LLM_API_KEY \"sk-...\"` stores the key and not the quoting.

  ANY account name is accepted — see `note-unknown-account!`. (The model-facing
  allowlist is a different thing and stays: `with_api_key` only ever uses
  accounts listed in `:with-api-key :allowed-secrets`.)

  Writes to the OS keyring ONLY. If no backend is available the write THROWS —
  grog will never store a secret value in a file. Returns `{:backend :keyring}`."
  [^String account ^String password]
  (let [password (unquote-value password)]
    (when (str/blank? account)
      (throw (ex-info "account (key) is required" {})))
    (when (str/blank? password)
      (throw (ex-info "value must be non-empty" {})))
    (note-unknown-account! account)
    (try
      (with-open [^Keyring kr (Keyring/create)]
        (.setPassword kr service-id account password))
      (reset! !keyring-unreachable false)
      (note-in-ledger! account)
      {:backend :keyring}
      (catch BackendNotSupportedException e
        (throw (ex-info (str "no OS secret backend available (" (.getMessage e)
                             "). grog will not write a secret to a file — fix the keyring "
                             "(Secret Service / KWallet on Linux, Credential Manager on Windows, "
                             "Keychain on macOS) and try again.")
                        {:account account})))
      (catch PasswordAccessException e
        (throw (ex-info (str "the OS secret backend rejected the write ("
                             (.getMessage e) "). grog will not fall back to a file.")
                        {:account account})))
      (catch Exception e
        (throw (ex-info (str "could not write the secret to the OS keyring ("
                             (.getMessage e) "). grog will not fall back to a file.")
                        {:account account}))))))

(defn delete-secret!
  "Remove `account` from the OS keyring (best effort).

  ANY account name is accepted — see `note-unknown-account!`; an undeclared
  name is a no-op advisory, never a rejection. Returns
  `{:keyring :deleted|:absent|:unavailable}`."
  [^String account]
  (when (str/blank? account)
    (throw (ex-info "account (key) is required" {})))
  (note-unknown-account! account)
  (let [kr (try
             (with-open [^Keyring kr (Keyring/create)]
               (.deletePassword kr service-id account)
               :deleted)
             (catch PasswordAccessException _ :absent)
             (catch Exception _ :unavailable))]
    {:keyring kr}))

(defn- keyring-set? [^String account]
  (boolean (some-> (get-secret account) not-empty)))

(defn print-known-secrets-summary!
  "Print the KNOWN secret keys — NAME and PURPOSE only. Values are NEVER printed.

  The list is the built-in accounts, any declared in `:secrets {:accounts …}`,
  and any account grog itself created (the ledger) — so a key you stored shows
  up even if it was never declared. Whether each is set is shown; its value
  never is."
  []
  (let [b (backend-status)
        defs (all-known-secret-defs)
        desc-of (into {} (map (juxt :account :description) defs))
        names (distinct (concat (map :account defs)
                                (sort (ledger-accounts))))]
    (println (str "Known secrets (service " service-id
                  ") — names only; values are never shown:"))
    (doseq [acct names]
      (println (format "  %-22s %-8s %s"
                       acct
                       (if (keyring-set? acct) "[set]" "[unset]")
                       (or (desc-of acct) "(no declared description)"))))
    (println)
    (println "Backend:" (str "OS keyring"
                            (when-let [r (:reason b)] (str " — " r))))
    (println "Set:    /secret set <KEY> <value>")
    (println "Remove: /secret rm <KEY>")))

(defn startup-status-line
  "One line for chat startup (Brave / keyring hint). Does not open the keyring —
  that can block without D-Bus."
  []
  (str "Secrets: service \"" service-id "\""
       (if @!keyring-unreachable
         " — OS keyring UNREACHABLE (secrets unavailable)"
         " — OS keyring")
       "; /secret set <KEY> <value>, /secret rm <KEY>"))