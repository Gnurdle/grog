(ns grog-imap.oauth-test
  "Tests for grog-imap.oauth. Token-refresh/exchange logic is tested with an
  injected :http-post stub; the real java.net.http transport gets one smoke
  test against a plain ServerSocket HTTP stub."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [grog-imap.oauth :as oauth]))

(def post-form #'grog-imap.oauth/post-form)

;; the access-token cache is process-global; isolate each test
(use-fixtures :each (fn [f] (oauth/reset-token-cache!) (f)))

;;; ---------------------------------------------------------------------------
;;; injected HTTP stub
;;; ---------------------------------------------------------------------------

(defn- stub-oauth
  "OAuth config whose :http-post records calls and returns `responses` (a
  function of call-count or a fixed map). Returns [config calls-atom]."
  [response-fn]
  (let [calls (atom [])]
    [(fn post-stub [url params]
       (swap! calls conj {:url url :params params})
       (response-fn (count @calls)))
     calls]))

(defn- stubbed-oauth
  "Build an oauth config wired to the stub; returns [config calls-atom]."
  [response-fn]
  (let [[post calls] (stub-oauth response-fn)]
    [(assoc {:provider :google :client-id "cid" :client-secret "cs"
             :token-url "https://example.test/token"}
            :http-post post)
     calls]))

;;; ---------------------------------------------------------------------------
;;; refresh / access token
;;; ---------------------------------------------------------------------------

(deftest refresh-token!
  (let [[o calls] (stubbed-oauth (fn [_] {:access_token "tok-1" :expires_in 3600}))
        resp (oauth/refresh-token! o "rt-1")]
    (is (= "tok-1" (:access_token resp)))
    (is (= 3600 (:expires_in resp)))
    (let [{:keys [url params]} (first @calls)]
      (is (= "https://example.test/token" url))
      (is (= "cid" (get params "client_id")))
      (is (= "cs" (get params "client_secret")))
      (is (= "refresh_token" (get params "grant_type")))
      (is (= "rt-1" (get params "refresh_token"))))))

(deftest access-token-caching
  (let [[o calls] (stubbed-oauth (fn [_] {:access_token "tok-1" :expires_in 3600}))
        o (assoc o :refresh-token "rt-1")]
    (is (= "tok-1" (oauth/access-token! o)))
    (is (= 1 (count @calls)))
    ;; second call within expiry must not hit the transport again
    (is (= "tok-1" (oauth/access-token! o)))
    (is (= 1 (count @calls)))))

(deftest access-token-refreshes-after-expiry
  (let [[o calls] (stubbed-oauth (fn [n] {:access_token (str "tok-" n) :expires_in 0}))
        o (assoc o :refresh-token "rt-1")]
    (is (= "tok-1" (oauth/access-token! o)))
    ;; expires_in 0 -> cached token is already expired -> refresh again
    (is (= "tok-2" (oauth/access-token! o)))
    (is (= 2 (count @calls)))))

(deftest access-token-error
  (let [[o calls] (stubbed-oauth (fn [_] {:error "invalid_grant"
                                          :error_description "bad refresh token"}))
        o (assoc o :refresh-token "bad")]
    (is (thrown? clojure.lang.ExceptionInfo (oauth/access-token! o)))
    (is (= 1 (count @calls)))))

(deftest access-token-requires-refresh-token
  (is (thrown? clojure.lang.ExceptionInfo
               (oauth/access-token! {:provider :google :client-id "x"}))))

;;; ---------------------------------------------------------------------------
;;; consent
;;; ---------------------------------------------------------------------------

(deftest exchange-code!
  (let [[o calls] (stubbed-oauth (fn [_] {:access_token "tok-1"
                                          :refresh_token "rt-1"
                                          :expires_in 3600}))
        resp (oauth/exchange-code! o "authcode" "verifier")]
    (is (= "rt-1" (:refresh_token resp)))
    (is (= "tok-1" (:access_token resp)))
    (let [{:keys [params]} (first @calls)]
      (is (= "authorization_code" (get params "grant_type")))
      (is (= "authcode" (get params "code")))
      (is (= "verifier" (get params "code_verifier")))
      (is (= "cid" (get params "client_id"))))))

(deftest authorize-url-builds
  (let [{:keys [url code-verifier]}
        (oauth/authorize-url {:provider :google
                              :client-id "cid"
                              :redirect-uri "http://127.0.0.1:9999/callback"})]
    (is (str/includes? url "https://accounts.google.com/o/oauth2/v2/auth"))
    (is (str/includes? url "client_id=cid"))
    (is (str/includes? url "redirect_uri=http%3A%2F%2F127.0.0.1%3A9999%2Fcallback"))
    (is (str/includes? url "access_type=offline"))
    (is (str/includes? url "code_challenge_method=S256"))
    (is (re-find #"code_challenge=[A-Za-z0-9_-]+" url))
    (is (pos? (count code-verifier)))
    (is (not (str/includes? url code-verifier))
        "the challenge is the S256 hash, not the verifier")))

(deftest authorize-url-microsoft
  (let [{:keys [url]}
        (oauth/authorize-url {:provider :microsoft
                              :client-id "cid"
                              :redirect-uri "http://localhost/callback"})]
    (is (str/includes? url "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))
    (is (str/includes? url "scope=https%3A%2F%2Foutlook.office.com%2FIMAP.AccessAsUser.All"))
    (is (not (str/includes? url "access_type")))))

;;; ---------------------------------------------------------------------------
;;; real HTTP transport smoke test (ServerSocket stub)
;;; ---------------------------------------------------------------------------

(defn- start-http-stub
  "One-shot HTTP/1.1 server: reads the request head, then replies with
  `status` and `body`. Returns {:port port}."
  [^String status ^String body]
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        t (Thread.
           (fn []
             (try
               (let [sock (.accept ss)]
                 (with-open [sock sock
                             in (java.io.BufferedReader.
                                 (java.io.InputStreamReader.
                                  (.getInputStream sock) "UTF-8"))
                             out (java.io.BufferedWriter.
                                  (java.io.OutputStreamWriter.
                                   (.getOutputStream sock) "UTF-8"))]
                   ;; consume the request head up to the blank line
                   (loop []
                     (let [line (.readLine in)]
                       (when (and line (pos? (count line)))
                         (recur))))
                   (.write out (str "HTTP/1.1 " status "\r\n"
                                    "Content-Type: application/json\r\n"
                                    "Content-Length: " (count body) "\r\n"
                                    "Connection: close\r\n\r\n" body))
                   (.flush out)))
               (catch Exception _))))
        _ (.setDaemon t true)]
    (.start t)
    {:port port}))

(deftest post-form-real-http
  (let [{:keys [port]} (start-http-stub "200 OK" (json/write-str {:access_token "http-tok"
                                                                  :expires_in 3600}))
        resp (post-form (str "http://127.0.0.1:" port "/token")
                        {"grant_type" "refresh_token"
                         "refresh_token" "rt-1"})]
    (is (= "http-tok" (:access_token resp)))
    (is (= 3600 (:expires_in resp)))))
