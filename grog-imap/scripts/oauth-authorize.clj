(ns grog-imap.oauth-authorize
  "One-time interactive OAuth consent for a Google Gmail account.

  Usage:
    clojure -M scripts/oauth-authorize.clj <client-id>

  Prints a URL -> open it -> sign in with the Gmail account -> grant access.
  Writes the token JSON (contains refresh_token) to ~/.grog-imap-google by
  default (override with GROG_IMAP_TEST_TOKEN_FILE). The token is written to a
  file and never printed, so it never transits the model/MCP context."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [grog-imap.oauth :as oauth]))

(defn -main [& [client-id]]
  (let [client-id (or client-id (System/getenv "GOOGLE_OAUTH_CLIENT_ID"))
        out-path (or (System/getenv "GROG_IMAP_TEST_TOKEN_FILE")
                     (str (System/getProperty "user.home") "/.grog-imap-google"))]
    (when (str/blank? client-id)
      (println "Missing OAuth client ID. Pass it as an arg or set GOOGLE_OAUTH_CLIENT_ID.")
      (System/exit 1))
    (println "Authorizing Google client" client-id "...")
    (println "Open the printed URL, sign in, and grant access.")
    (let [tokens (oauth/authorize! {:provider :google :client-id client-id})]
      (spit out-path (json/write-str tokens))
      (println "OK. Refresh token saved to" out-path ", NOT printed here."))))

(-main)