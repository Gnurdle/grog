(ns grog-imap.core-test
  "Library-level tests for grog-imap.core: config/account model, strict named
  selection, session registry + reconnect, and high-level mailbox/message ops
  exercised against the shared in-process fake IMAP server."
  (:require [clojure.test :refer [deftest is testing]]
            [grog-imap.core :as imap]
            [grog-imap.protocol :as protocol]
            [grog-imap.support :as support]))

(def test-config-json
  (str "{ \"accounts\": ["
       "  {\"name\": \"gmail\", \"host\": \"imap.gmail.com\", \"port\": 993,"
       "   \"tls\": true, \"user\": \"me@gmail.com\"},"
       "  {\"name\": \"local\", \"host\": \"127.0.0.1\", \"port\": 143,"
       "   \"user\": \"u\", \"read-only\": false}"
       "]}"))

(defn- fake-config
  "Config pointing at a fresh fake server on `port`."
  [port]
  {:accounts [{:name "dev" :host "127.0.0.1" :port port :user "u"}]})

;;; ---------------------------------------------------------------------------
;;; config / account model
;;; ---------------------------------------------------------------------------

(deftest config-loading
  (testing "inline JSON"
    (let [config (imap/load-config test-config-json)]
      (is (= ["gmail" "local"] (mapv :name (imap/accounts config))))))
  (testing "defaults and normalization"
    (let [config (imap/load-config test-config-json)]
      (is (true? (:read-only (imap/get-account config "gmail"))))
      (is (false? (:read-only (imap/get-account config "local"))))
      (is (true? (:tls (imap/get-account config "gmail"))))
      (is (= 993 (:port (imap/get-account config "gmail"))))
      (is (imap/read-only? (imap/get-account config "gmail")))
      (is (not (imap/read-only? (imap/get-account config "local"))))))
  (testing "file path"
    (let [f (java.io.File/createTempFile "grog-imap-config" ".json")]
      (spit f test-config-json)
      (let [config (imap/load-config (.getAbsolutePath f))]
        (is (= 2 (count (imap/accounts config)))))))
  (testing "config map passes through"
    (let [config (fake-config 1)]
      (is (= config (imap/load-config config)))))
  (testing "missing env / blank source throws"
    (is (thrown? clojure.lang.ExceptionInfo (imap/load-config "")))))

(deftest strict-account-allowlist
  (let [config (imap/load-config test-config-json)]
    (is (= "gmail" (:name (imap/get-account config "gmail"))))
    (is (thrown? clojure.lang.ExceptionInfo (imap/get-account config "bogus")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (imap/connect-account! {:accounts []} "nope" {:password "x"})))))

;;; ---------------------------------------------------------------------------
;;; session registry / connection lifecycle
;;; ---------------------------------------------------------------------------

(deftest session-lifecycle
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)]
    (is (nil? (imap/connection "dev")))
    (let [c1 (imap/connect-account! config "dev" {:password "pw"})]
      (is (imap/connected? c1))
      (is (identical? c1 (imap/connection "dev")))
      (is (identical? c1 (imap/ensure-connected! config "dev" {:password "pw"})))
      (is (= :disconnected (imap/disconnect-account! "dev")))
      (is (nil? (imap/connection "dev"))))
    (imap/disconnect-all!)))

(deftest credential-source-can-be-fn
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" (fn [_] {:password "pw"}))]
    (is (imap/connected? conn))
    (imap/disconnect-all!)))

(deftest connect-without-credential-throws
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (imap/connect-account! config "dev" nil)))
    (imap/disconnect-all!)))

(deftest xoauth2-connect
  (let [{:keys [port]} (support/start-fake-server)
        config {:accounts [{:name "oauth"
                            :host "127.0.0.1" :port port
                            :user "u@example.com" :sasl :xoauth2}]}
        conn (imap/connect-account! config "oauth" {:access-token "tok123"})]
    (is (imap/connected? conn))
    (imap/disconnect-all!)))

(deftest xoauth2-without-token-throws
  (let [{:keys [port]} (support/start-fake-server)
        config {:accounts [{:name "oauth"
                            :host "127.0.0.1" :port port
                            :user "u@example.com" :sasl :xoauth2}]}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (imap/connect-account! config "oauth" {:password "pw"})))
    (imap/disconnect-all!)))

(deftest reconnect-on-drop
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        c1 (imap/connect-account! config "dev" {:password "pw"})]
    (is (imap/connected? c1))
    ;; kill the underlying connection out from under the registry
    (protocol/disconnect c1)
    (is (not (imap/connected? c1)))
    (let [c2 (imap/ensure-connected! config "dev" {:password "pw"})]
      (is (imap/connected? c2))
      (is (not (identical? c1 c2))))
    (imap/disconnect-all!)))

;;; ---------------------------------------------------------------------------
;;; high-level ops against the fake server
;;; ---------------------------------------------------------------------------

(deftest mailbox-ops
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (testing "select returns mailbox state"
        (let [st (imap/select conn "INBOX")]
          (is (= 5 (:messages st)))
          (is (= 3 (:recent st)))
          (is (true? (:read-write st)))))
      (testing "list-mailboxes decorates LIST output"
        (let [boxes (imap/list-mailboxes conn)]
          (is (= 2 (count boxes)))
          (is (= "INBOX" (:name (first boxes))))
          (is (= "/" (:delimiter (first boxes))))
          (is (contains? (:attributes (first boxes)) :hasnochildren))))
      (testing "mailbox-status"
        (let [st (imap/mailbox-status conn "INBOX")]
          (is (= 5 (:messages st)))
          (is (= 2 (:unseen st)))))
      (testing "create/rename/delete mailbox"
        (is (= :done (imap/create-mailbox conn "Sent")))
        (is (= :done (imap/rename-mailbox conn "Sent" "Old-Sent")))
        (is (= :done (imap/delete-mailbox conn "Old-Sent"))))
      (finally
        (imap/disconnect-all!)))))

(deftest message-ops
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (testing "search returns message numbers"
        (is (= [1 2 3] (imap/search conn :all))))
      (testing "fetch normalizes message maps"
        (let [msgs (imap/fetch conn 1 :uid :flags :rfc822.size)]
          (is (= 1 (count msgs)))
          (let [m (first msgs)]
            (is (= 1 (:seq m)))
            (is (= 42 (:uid m)))
            (is (= [:seen] (:flags m)))
            (is (= 1234 (:size m))))))
      (testing "flag ops"
        (is (= :done (imap/mark-read conn 1)))
        (is (= :done (imap/mark-unread conn 1)))
        (is (= :done (imap/set-flags conn 1 [:flagged]))))
      (testing "move/copy/append/expunge"
        (is (= :done (imap/move conn 1 "Trash")))
        (is (= :done (imap/copy conn 2 "Trash")))
        (is (= :done (imap/append conn "INBOX" "Subject: x\r\n\r\nbody" :flags [:seen])))
        (is (= :done (imap/expunge conn))))
      (testing "delete-messages (store + expunge)"
        (is (= :done (imap/delete-messages conn 1))))
      (finally
        (imap/disconnect-all!)))))

(deftest error-paths
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (testing "server NO response throws"
        (is (thrown? clojure.lang.ExceptionInfo (imap/select conn "Nope"))))
      (testing "unknown account throws"
        (is (thrown? clojure.lang.ExceptionInfo
                     (imap/connect-account! config "ghost" {:password "x"}))))
      (finally
        (imap/disconnect-all!)))))

(deftest examine-is-read-only
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (let [st (imap/examine conn "INBOX")]
        (is (= 5 (:messages st)))
        (is (false? (:read-write st))))
      (finally
        (imap/disconnect-all!)))))

(deftest uid-variants
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (testing "uid-fetch normalizes message maps"
        (let [msgs (imap/uid-fetch conn 42 :uid :flags)]
          (is (= 1 (count msgs)))
          (is (= 42 (-> msgs first :uid)))))
      (testing "uid-search returns UIDs"
        (is (= [42 43] (imap/uid-search conn :all))))
      (testing "uid-move / uid-copy"
        (is (= :done (imap/uid-move conn 42 "Trash")))
        (is (= :done (imap/uid-copy conn 42 "Trash"))))
      (finally
        (imap/disconnect-all!)))))

(deftest fetch-envelope-and-body
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (testing "envelope"
        (let [m (first (imap/fetch conn 1 :envelope))]
          (is (= 42 (:uid m)))
          (is (= "Test Subject" (nth (:envelope m) 1)))))
      (testing "body[]"
        (let [m (first (imap/fetch conn 1 :body))]
          (is (= "Subject: hi\r\n\r\nbody" (:body m)))))
      (finally
        (imap/disconnect-all!)))))

(deftest search-vector-criteria
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      (is (= [1 2 3] (imap/search conn ["FROM" "\"x@y.com\"" "SINCE" "1-Jan-2024"])))
      (is (= [1 2 3] (imap/search conn :unseen)))
      (finally
        (imap/disconnect-all!)))))

(deftest append-single-literal
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)
        conn (imap/connect-account! config "dev" {:password "pw"})]
    (try
      ;; no flags: APPEND <mailbox> <content> is a single-literal command
      (is (= :done (imap/append conn "INBOX" "Subject: bare\r\n\r\nbody")))
      (finally
        (imap/disconnect-all!)))))

