(ns grog-imap.protocol-test
  "Unit + in-process e2e tests for grog-imap.protocol.

  Unit tests cover the response tokenizer/parser, command encoding, the
  read/completion plumbing (via string-backed fake conns), and the SASL
  AUTHENTICATE path. A tiny in-process IMAP server (real sockets) proves the
  connect -> login -> select -> list -> fetch -> search round trip."
  (:require [clojure.test :refer [deftest is testing]]
            [grog-imap.protocol :as imap]
            [grog-imap.support :as support]))

;;; ---------------------------------------------------------------------------
;;; access to private fns under test
;;; ---------------------------------------------------------------------------

(def tokenize #'grog-imap.protocol/tokenize)
(def parse-line #'grog-imap.protocol/parse-line)
(def read-response #'grog-imap.protocol/read-response)
(def collect-until-tagged! #'grog-imap.protocol/collect-until-tagged!)
(def encode-arg #'grog-imap.protocol/encode-arg)
(def build-parts #'grog-imap.protocol/build-parts)
(def send-parts! #'grog-imap.protocol/send-parts!)
(def auth-command! #'grog-imap.protocol/auth-command!)

;;; ---------------------------------------------------------------------------
;;; tokenizer
;;; ---------------------------------------------------------------------------

(deftest tokenize-basics
  (testing "atoms, numbers, NIL"
    (is (= ['* :ok 'hi] (tokenize "* OK hi")))
    (is (= [:ok nil] (tokenize "OK NIL")))
    (is (= [:ok 42] (tokenize "OK 42")))
    (is (= [:ok 0] (tokenize "OK 0"))))
  (testing "flags"
    (is (= [:seen] (tokenize "\\Seen")))
    (is (= [:answered :flagged :deleted :draft]
           (tokenize "\\Answered \\Flagged \\Deleted \\Draft"))))
  (testing "status words"
    (is (= [:ok] (tokenize "OK")))
    (is (= [:no] (tokenize "NO")))
    (is (= [:bad] (tokenize "BAD")))
    (is (= [:bye] (tokenize "BYE")))
    (is (= [:preauth] (tokenize "PREAUTH"))))
  (testing "parenthesized lists"
    (is (= [1 ['* 'x] 2] (tokenize "1 (* x) 2")))
    (is (= [['a ['b ['c]]]] (tokenize "(a (b (c)))"))))
  (testing "quoted strings become strings"
    (is (= ["hello world"] (tokenize "\"hello world\"")))
    (is (= ["a\"b" 'c] (tokenize "\"a\\\"b\" c"))))
  (testing "literals inline"
    (is (= ["abcde"] (tokenize "{5}abcde")))
    (is (= [:ok "abcde"] (tokenize "OK {5}abcde"))))
  (testing "empty"
    (is (= [] (tokenize "")))))

;;; ---------------------------------------------------------------------------
;;; response classification
;;; ---------------------------------------------------------------------------

(deftest parse-line-classification
  (testing "untagged with status"
    (let [r (parse-line "* OK [CAPABILITY IMAP4rev1] ready")]
      (is (= :untagged (:type r)))
      (is (= :ok (:status r)))))
  (testing "untagged without status (data line)"
    (let [r (parse-line "* 5 EXISTS")]
      (is (= :untagged (:type r)))
      (is (nil? (:status r)))
      (is (= [5 'EXISTS] (:data r)))))
  (testing "tagged completion"
    (let [r (parse-line "A1 OK done")]
      (is (= :tagged (:type r)))
      (is (= "A1" (:tag r)))
      (is (= :ok (:status r)))
      (is (= '(done) (:data r)))))
  (testing "tagged NO"
    (is (= :no (:status (parse-line "A1 NO denied")))))
  (testing "continuation"
    (let [r (parse-line "+ idling")]
      (is (= :continuation (:type r)))
      (is (= '(idling) (:data r)))))
  (testing "empty line"
    (is (= :empty (:type (parse-line ""))))))

(deftest read-response-single-read
  (testing "reads exactly one response; the next read gets the following line"
    (let [conn (support/fake-conn "* OK hi\r\nA1 OK done\r\n")
          r1 (read-response conn)
          r2 (read-response conn)]
      (is (= :untagged (:type r1)))
      (is (= :ok (:status r1)))
      (is (= :tagged (:type r2)))
      (is (= "A1" (:tag r2))))))

(deftest read-response-literal
  (testing "literal content is assembled into the same parsed line"
    (let [conn (support/fake-conn "* 1 FETCH (BODY[] {5}hello)\r\nA1 OK done\r\n")
          r (read-response conn)]
      (is (= :untagged (:type r)))
      ;; data: (1 FETCH [BODY[] "hello"])
      (is (= "hello" (-> (:data r) (nth 2) (nth 1)))))))

(deftest collect-until-tagged
  (let [conn (support/fake-conn "* 5 EXISTS\r\nA1 OK done\r\n")
        res (collect-until-tagged! conn "A1")]
    (is (= :ok (:status (:tagged res))))
    (is (= 1 (count (:untagged res))))
    (is (= [5 'EXISTS] (:data (first (:untagged res)))))))

;;; ---------------------------------------------------------------------------
;;; command encoding
;;; ---------------------------------------------------------------------------

(deftest encode-arg-test
  (is (= "NIL" (encode-arg nil)))
  (is (= "TRUE" (encode-arg true)))
  (is (= "FALSE" (encode-arg false)))
  (is (= "INBOX" (encode-arg "INBOX")))
  (is (= "\"\"" (encode-arg "")))
  (is (= {:_lit "has space"} (encode-arg "has space")))
  (is (= {:_lit "with\"quote"} (encode-arg "with\"quote")))
  (is (= "UID" (encode-arg :uid)))
  (is (= "42" (encode-arg 42))))

(deftest seq-set-test
  (is (= "1" (imap/seq-set [1])))
  (is (= "1:3,5" (imap/seq-set [5 1 2 3])))
  (is (= "1:2,4,7:9" (imap/seq-set [1 2 4 7 8 9])))
  (is (= "1" (imap/sequence-set 1)))
  (is (= "1:3" (imap/sequence-set [3 1 2])))
  (is (= "1:4" (imap/sequence-set "1:4"))))

(deftest flag-wire-test
  (is (= "\\Seen" (imap/flag-wire :seen)))
  (is (= "\\Answered" (imap/flag-wire :answered)))
  (is (= "MyFlag" (imap/flag-wire "MyFlag"))))

(deftest parenthesized-test
  (is (= {:paren "(UID FLAGS)"} (imap/parenthesized [:uid :flags])))
  (is (= {:paren "(MESSAGES UNSEEN)"} (imap/parenthesized [:messages :unseen])))
  (testing "pre-wired flag strings pass through verbatim"
    (is (= {:paren "(\\Seen \\Draft)"}
           (imap/parenthesized [(imap/flag-wire :seen) (imap/flag-wire :draft)])))))

(deftest build-parts-literal-portal
  (let [conn (support/fake-conn "")
        msg "long message body\r\nwith spaces"
        parts (build-parts conn "APPEND" ["INBOX" msg])]
    (is (= (str "A1 APPEND INBOX {" (count msg) "}") (first parts)))
    (is (= {:lit msg} (second parts)))))

(deftest send-parts-literal
  (let [conn (support/fake-conn "+ \r\nA3 OK APPEND done\r\n")
        msg "body text"
        items ["A2 APPEND INBOX {9}" {:lit msg}]]
    (send-parts! conn items)
    (is (= (str "A2 APPEND INBOX {9}\r\n" msg "\r\n") (support/written conn)))))

;;; ---------------------------------------------------------------------------
;;; completion helpers
;;; ---------------------------------------------------------------------------

(deftest completion-helpers
  (let [ok-res {:tagged {:type :tagged :tag "A1" :status :ok :data [] :raw "A1 OK done"}}
        no-res {:tagged {:type :tagged :tag "A1" :status :no :data [] :raw "A1 NO denied"}}]
    (is (imap/ok? ok-res))
    (is (not (imap/ok? no-res)))
    (is (= :no (imap/completion-status no-res)))
    (is (= ok-res (imap/throw-unless-ok ok-res)))
    (is (thrown? clojure.lang.ExceptionInfo (imap/throw-unless-ok no-res)))))

(deftest untagged-helpers
  (let [res {:untagged [{:type :untagged :status nil :data [1 'FETCH]}
                        {:type :untagged :status :ok :data ['LIST]}
                        {:type :untagged :status nil :data [2 'FETCH]}]}]
    (is (= 2 (count (imap/untagged-of-type res 'FETCH))))
    (is (= :ok (:status (imap/find-untagged res 'LIST))))
    (is (nil? (imap/find-untagged res 'SEARCH)))
    (is (= [1 'FETCH] (:data (imap/find-untagged res 'FETCH))))))

;;; ---------------------------------------------------------------------------
;;; SASL AUTHENTICATE
;;; ---------------------------------------------------------------------------

(deftest auth-login-wire
  (let [conn (support/fake-conn "+ VXNlcm5hbWU6\r\n+ UGFzc3dvcmQ6\r\nA1 OK done\r\n")
        res (auth-command! conn "LOGIN" ["user" "pass"])]
    (is (= :ok (imap/completion-status res)))
    (is (= (str "A1 AUTHENTICATE LOGIN\r\n"
                (support/b64-of "user") "\r\n"
                (support/b64-of "pass") "\r\n")
           (support/written conn)))))

(deftest auth-rejects-without-continuation
  (let [conn (support/fake-conn "A1 NO auth failed\r\n")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (auth-command! conn "LOGIN" ["u" "p"])))))

(deftest xoauth2-wire
  (let [ir (str "user=" "u@example.com" "\u0001auth=Bearer tok123\u0001\u0001")
        b64 (support/b64-of ir)]
    (testing "success (no continuation)"
      (let [conn (support/fake-conn "A1 OK AUTHENTICATE completed\r\n")
            res (imap/authenticate-xoauth2 conn "u@example.com" "tok123")]
        (is (imap/ok? res))
        (is (= (str "A1 AUTHENTICATE XOAUTH2 " b64 "\r\n")
               (support/written conn)))))
    (testing "failure continuation answered with empty line"
      (let [conn (support/fake-conn "+ eyJlcnJvciI6ImJhZGRfY3JlZGVudGlhbHMifQ\r\nA1 NO auth failed\r\n")
            res (imap/authenticate-xoauth2 conn "u@example.com" "tok123")]
        (is (= :no (imap/completion-status res)))
        (is (= (str "A1 AUTHENTICATE XOAUTH2 " b64 "\r\n\r\n")
               (support/written conn)))))))

(deftest idle-deferred
  (let [conn (support/fake-conn "")]
    (is (thrown? clojure.lang.ExceptionInfo (imap/idle conn)))))

;;; ---------------------------------------------------------------------------
;;; end-to-end over a real socket
;;; ---------------------------------------------------------------------------

(deftest e2e-connect-login-select-list-fetch-search
  (let [{:keys [port]} (support/start-fake-server)
        conn (imap/connect "127.0.0.1" :port port :timeout 5000)]
    (try
      (testing "greeting"
        (is (= :untagged (:type (:greeting conn))))
        (is (= :ok (:status (:greeting conn)))))
      (testing "login"
        (is (imap/ok? (imap/login conn "user" "pass"))))
      (testing "select"
        (let [res (imap/select conn "INBOX")]
          (is (imap/ok? res))
          (is (= 5 (-> (imap/find-untagged res 'EXISTS) :data first)))
          (is (= 3 (-> (imap/find-untagged res 'RECENT) :data first)))))
      (testing "list"
        (let [res (imap/list-folders conn)]
          (is (imap/ok? res))
          (is (= 2 (count (imap/untagged-of-type res 'LIST))))))
      (testing "fetch"
        (let [res (imap/fetch conn 1 :uid :flags)]
          (is (imap/ok? res))
          (let [fetch-resp (first (imap/untagged-of-type res 'FETCH))]
            ;; data: (1 FETCH [UID 42 FLAGS (\Seen) RFC822.SIZE 1234])
            (is (= 42 (-> (:data fetch-resp) (nth 2) (nth 1))))
            (is (= [:seen] (-> (:data fetch-resp) (nth 2) (nth 3)))))))
      (testing "search"
        (let [res (imap/search conn :all)]
          (is (imap/ok? res))
          (is (= [1 2 3]
                 (vec (rest (:data (first (imap/untagged-of-type res 'SEARCH)))))))))
      (finally
        (imap/disconnect conn)))))
