(ns grog-imap.oauth
  "OAuth2 support for IMAP (Google / Microsoft), zero-dependency: java.net.http
  + clojure.data.json only.

  Two jobs:

  * **One-time consent** — `authorize!` prints a browser URL, waits on a
    loopback callback, and returns the token response (including
    `refresh_token`) for the user to store as a secret.
  * **Token refresh** — `access-token!` caches a short-lived access token and
    refreshes it from `:refresh-token` as needed, so `core` can authenticate
    with XOAUTH2.

  `:token-url` / `:auth-url` may be overridden (useful for tests/custom IdPs).

  Tokens are secrets: this namespace never logs them, and they must never be
  placed in account metadata or MCP tool arguments/results."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------------------
;;; provider defaults
;;; ---------------------------------------------------------------------------

(def google-auth-url "https://accounts.google.com/o/oauth2/v2/auth")
(def google-token-url "https://oauth2.googleapis.com/token")
(def google-mail-scope "https://mail.google.com/")

(def microsoft-auth-url "https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
(def microsoft-token-url "https://login.microsoftonline.com/common/oauth2/v2.0/token")
(def microsoft-imap-scope "https://outlook.office.com/IMAP.AccessAsUser.All offline_access")

(defn- default-auth-url [provider]
  (case provider
    :google google-auth-url
    :microsoft microsoft-auth-url
    (throw (ex-info "Unknown OAuth provider" {:provider provider}))))

(defn- default-token-url [provider]
  (case provider
    :google google-token-url
    :microsoft microsoft-token-url
    (throw (ex-info "Unknown OAuth provider" {:provider provider}))))

(defn- default-scope [provider]
  (case provider
    :google google-mail-scope
    :microsoft microsoft-imap-scope
    (throw (ex-info "Unknown OAuth provider" {:provider provider}))))

;;; ---------------------------------------------------------------------------
;;; encoding helpers
;;; ---------------------------------------------------------------------------

(defn- url-encode ^String [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- b64url ^String [^bytes b]
  (-> (.encodeToString (java.util.Base64/getUrlEncoder) b)
      (str/replace #"=+$" "")))

(defn- random-bytes ^bytes [^long n]
  (let [b (byte-array n)
        r (java.security.SecureRandom.)]
    (.nextBytes r b)
    b))

(defn- pkce-pair
  "PKCE S256 challenge pair: {:code-verifier :code-challenge}."
  []
  (let [verifier (b64url (random-bytes 32))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String verifier "US-ASCII"))]
    {:code-verifier verifier
     :code-challenge (b64url digest)}))

(defn- query-string [params]
  (str/join "&" (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v))))
                     params)))

;;; ---------------------------------------------------------------------------
;;; HTTP
;;; ---------------------------------------------------------------------------

(defn- post-form
  "POST `params` as an application/x-www-form-urlencoded body to `url`.
  Returns the parsed JSON response map."
  [^String url params]
  (let [body (query-string params)
        ^java.net.http.HttpClient client (.build (java.net.http.HttpClient/newBuilder))
        req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                .build)
        ^java.net.http.HttpResponse resp
        (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (json/read-str (.body resp) :key-fn keyword)))

;;; ---------------------------------------------------------------------------
;;; consent (one-time)
;;; ---------------------------------------------------------------------------

(defn- oauth-config
  "Normalize an oauth config map with provider defaults applied."
  [{:keys [provider] :as oauth}]
  {:pre [(keyword? provider)]}
  (merge {:auth-url (default-auth-url provider)
          :token-url (default-token-url provider)
          :scope (default-scope provider)}
         oauth))

(defn authorize-url
  "Build the browser authorization URL for `oauth` (a map with :provider,
  :client-id, :redirect-uri, optional :scope/:auth-url). Returns
  {:url ... :code-verifier ...} (keep the verifier to exchange the code)."
  [{:keys [provider client-id redirect-uri] :as oauth}]
  (let [{:keys [auth-url scope]} (oauth-config oauth)
        pkce (pkce-pair)
        params (cond-> {"client_id" client-id
                        "redirect_uri" redirect-uri
                        "response_type" "code"
                        "scope" scope
                        "code_challenge" (:code-challenge pkce)
                        "code_challenge_method" "S256"}
                 (= provider :google) (assoc "access_type" "offline"
                                             "prompt" "consent"))
        url (str auth-url "?" (query-string params))]
    {:url url :code-verifier (:code-verifier pkce)}))

(defn exchange-code!
  "Exchange the authorization `code` for tokens. Returns the token JSON
  response (contains :access_token, :expires_in, and on first consent
  :refresh_token). Pass an `:http-post` fn in `oauth` to override the default
  HTTP transport (used by tests)."
  [{:keys [client-id client-secret redirect-uri http-post] :as oauth} code verifier]
  (let [{:keys [token-url]} (oauth-config oauth)
        post (or http-post post-form)
        params (cond-> {"client_id" client-id
                        "code" code
                        "redirect_uri" redirect-uri
                        "grant_type" "authorization_code"
                        "code_verifier" verifier}
                 client-secret (assoc "client_secret" client-secret))]
    (post token-url params)))

(defn refresh-token!
  "Exchange `refresh-token` for a fresh access token. Returns the token JSON
  response. Pass an `:http-post` fn in `oauth` to override the default HTTP
  transport (used by tests)."
  [{:keys [client-id client-secret http-post] :as oauth} refresh-token]
  (let [{:keys [token-url]} (oauth-config oauth)
        post (or http-post post-form)
        params (cond-> {"client_id" client-id
                        "grant_type" "refresh_token"
                        "refresh_token" refresh-token}
                 client-secret (assoc "client_secret" client-secret))]
    (post token-url params)))

(defn- callback-handler
  "HttpHandler that extracts `?code=` from the redirect and delivers it to the
  promise `p` (delivering an ex-info when the code is missing)."
  [p]
  (reify com.sun.net.httpserver.HttpHandler
    (handle [_ exchange]
      (let [^com.sun.net.httpserver.HttpExchange exchange exchange
            uri (str (.getRequestURI exchange))
            code (some->> (str/split uri #"code=") second)]
        (if code
          (do (deliver p code)
              (.sendResponseHeaders exchange 200 0))
          (do (deliver p (ex-info "No code in callback" {:uri uri}))
              (.sendResponseHeaders exchange 400 0)))
        (.close exchange)))))

(defn- await-code
  "Block until the loopback callback server receives a redirect with a `code`."
  [^com.sun.net.httpserver.HttpServer server]
  (let [p (promise)]
    (.createContext server "/callback" (callback-handler p))
    (let [code @p]
      (if (instance? Exception code) (throw code) code))))

(defn- start-callback-server
  "Start an HTTP server on 127.0.0.1:<ephemeral port>; returns {:server :port}."
  []
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn authorize!
  "Run the one-time interactive OAuth consent: prints the browser URL, waits
  for the loopback redirect, exchanges the code, and returns the token response
  (access_token + refresh_token + expires_in). The `:redirect-uri` should be
  http://127.0.0.1:<port>/callback; if omitted it is derived from the ephemeral
  port, which works for Google's Desktop client and for Microsoft public
  clients that allow loopback."
  [{:keys [redirect-uri] :as oauth}]
  (let [{:keys [server port]} (start-callback-server)
        redirect (or redirect-uri (str "http://127.0.0.1:" port "/callback"))
        {:keys [url code-verifier]} (authorize-url (assoc oauth :redirect-uri redirect))]
    (try
      (println "Open this URL in a browser and authorize access:")
      (println url)
      (let [code (await-code server)]
        (exchange-code! (assoc oauth :redirect-uri redirect) code code-verifier))
      (finally
        (.stop ^com.sun.net.httpserver.HttpServer server 0)))))

;;; ---------------------------------------------------------------------------
;;; access token cache
;;; ---------------------------------------------------------------------------

(defonce ^:private token-cache (atom {}))

(defn reset-token-cache!
  "Clear the in-memory access-token cache (e.g. on config change; used by tests)."
  []
  (reset! token-cache {}))

(defn access-token!
  "Return a valid access token for `oauth`, refreshing via `:refresh-token`
  when the cached token is missing or within 60s of expiry. Access tokens are
  cached in memory only, keyed by client-id + refresh-token."
  [{:keys [client-id refresh-token] :as oauth}]
  (when-not refresh-token
    (throw (ex-info "OAuth2 requires :refresh-token (obtain it via authorize!)"
                    {})))
  (let [key [client-id refresh-token]
        now (quot (System/currentTimeMillis) 1000)
        cached (get @token-cache key)]
    (if (and cached (> (:expires-at cached) (+ now 60)))
      (:access-token cached)
      (let [resp (refresh-token! oauth refresh-token)
            tok (or (:access_token resp)
                    (throw (ex-info (str "OAuth2 token refresh failed: "
                                         (:error_description resp (:error resp "unknown")))
                                    {:error (:error resp)})))]
        (swap! token-cache assoc key
               {:access-token tok
                :expires-at (+ now (long (or (:expires_in resp) 3600)))})
        tok))))
