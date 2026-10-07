(ns grog-imap.oauth-authorize
  "One-time interactive OAuth consent for a Google Gmail account.

  Usage (from the grog-imap directory):
    clojure -M -m grog-imap.oauth-authorize <client-id> <store-account-name>

  Prints a URL -> open it -> sign in with the Gmail account -> grant access.

  The refresh token is written straight into grog's secret store (OS keyring,
  service \"grog\") under the account name you pass — never to a file, and never
  printed, so it never transits the model/MCP context. Point the mail account at
  that name with `:refresh-secret` in its metadata."
  (:require [clojure.string :as str]
            [grog-imap.oauth :as oauth])
  (:import [com.github.javakeyring Keyring]))

(def ^:private ^String keyring-service "grog")

(defn store-refresh-token!
  "Write `token` to the OS keyring (service grog) under `account`. Returns the account."
  [account token]
  (with-open [^Keyring kr (Keyring/create)]
    (.setPassword kr keyring-service (str account) (str token)))
  account)

(defn -main [& [client-id account]]
  (let [client-id (or (not-empty client-id)
                      (not-empty (System/getenv "GOOGLE_OAUTH_CLIENT_ID")))
        account   (or (not-empty account)
                      (not-empty (System/getenv "GROG_IMAP_REFRESH_ACCOUNT")))]
    (when (str/blank? client-id)
      (println "Missing OAuth client ID. Pass it as the 1st arg or set GOOGLE_OAUTH_CLIENT_ID.")
      (System/exit 1))
    (when (str/blank? account)
      (println "Missing store account name. Pass it as the 2nd arg (e.g. IMAP_GMAIL_REFRESH) or set")
      (println "GROG_IMAP_REFRESH_ACCOUNT. That name is what /secret stores the refresh token under.")
      (System/exit 1))
    (println "Authorizing Google client" client-id "...")
    (println "Open the printed URL, sign in, and grant access.")
    (let [tokens (oauth/authorize! {:provider :google :client-id client-id})
          token  (:refresh_token tokens)]
      (when (str/blank? token)
        (println "No refresh_token in the response — nothing stored.")
        (System/exit 1))
      (store-refresh-token! account token)
      (println "OK. Refresh token stored in the OS keyring (service grog, account" account ") — NOT printed here.")
      (println "Point the mail account at it with:  :refresh-secret" account))))
