(ns grog.secrets
  "OS-backed secrets via [java-keyring](https://github.com/javakeyring/java-keyring)
  (macOS Keychain, Windows Credential Manager, Linux Secret Service / KWallet).

  Credentials are addressed by **service** (fixed to `\"grog\"`) and **account** (e.g. `BRAVE_SEARCH_API`, `ORACLE_API_KEY`).

  Only **known** accounts (see `known-secret-defs`) may be set via `/secret` in chat."
  (:require [clojure.string :as str])
  (:import [com.github.javakeyring Keyring BackendNotSupportedException PasswordAccessException]))

(def ^:private ^String service-id "grog")

(def brave-search-api-account "BRAVE_SEARCH_API")

(def oracle-api-account "ORACLE_API_KEY")

(def god-api-account "GOD_API_KEY")

(def known-secret-defs
  "Accounts Grog knows about; used for `/secret` list and validation."
  [{:account brave-search-api-account
    :description "Brave Search API subscription token (header X-Subscription-Token)"}
   {:account oracle-api-account
    :description "Oracle (strong remote model) API Bearer token — tool `oracle`, OpenAI-style chat completions"}
   {:account god-api-account
    :description "Legacy keyring name for the same oracle token; prefer ORACLE_API_KEY"}])

(defn- known-account-set []
  (set (map :account known-secret-defs)))

(defn known-account?
  [^String account]
  (boolean (when account ((known-account-set) account))))

(defn get-secret
  "Return the password for `account` under service `grog`, or nil if missing / unsupported / error."
  ^String [^String account]
  (when-not (str/blank? account)
    (try
      (with-open [^Keyring kr (Keyring/create)]
        (try
          (let [^String p (.getPassword kr service-id account)]
            (some-> p str str/trim not-empty))
          (catch PasswordAccessException _ nil)))
      (catch BackendNotSupportedException _ nil)
      (catch Exception _ nil))))

(defn set-secret!
  "Persist `password` for `account` under service `grog`. `account` must be in `known-secret-defs`.
  Throws on unknown account, blank password, or keyring errors."
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
    nil
    (catch BackendNotSupportedException e
      (throw (ex-info (str "no OS secret backend: " (.getMessage e)) {})))
    (catch PasswordAccessException e
      (throw (ex-info (.getMessage e) {})))
    (catch Exception e
      (throw (ex-info (.getMessage e) {})))))

(defn- keyring-set? [^String account]
  (boolean (some-> (get-secret account) not-empty)))

(defn print-known-secrets-summary!
  "Print known secret keys and whether each is set in the OS keyring. Never prints values."
  []
  (println (str "Defined secrets (OS service " service-id "):"))
  (doseq [{:keys [account description]} known-secret-defs]
    (println " " account "—" description)
    (println "   keyring:" (if (keyring-set? account) "set" "unset")))
  (println "Set with: /secret <KEY> <value> — value may contain spaces."))

(defn- try-backend-label
  []
  (try
    (with-open [^Keyring kr (Keyring/create)]
      (str (.getKeyringStorageType kr)))
    (catch Exception _ nil)))

(defn startup-status-line
  "One line for chat startup (Brave / keyring hint)."
  []
  (if-let [b (try-backend-label)]
    (str "Secrets store: " b " (service \"" service-id "\"); /secret lists " brave-search-api-account ", " oracle-api-account ", …")
    (str "Secrets store: no OS backend — use a platform with a keyring (or install Secret Service); /secret lists keys")))
