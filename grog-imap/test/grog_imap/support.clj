(ns grog-imap.support
  "Shared helpers for grog-imap tests: string-backed fake connections and an
  in-process fake IMAP server (real sockets) with a canned response script."
  (:require [clojure.string :as str]))

(defn fake-conn
  "A connection map backed by string streams — enough for the read/write
  plumbing unit tests exercise."
  [^String input]
  {:reader (java.io.BufferedReader. (java.io.StringReader. input))
   :writer (java.io.StringWriter.)
   :tag (atom 0)})

(defn written [conn]
  (str (.toString ^java.io.StringWriter (:writer conn))))

(defn b64-of [^String s]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- reply!
  [^java.io.Writer out ^String s]
  (.write out s)
  (.flush out))

(defn- reply-to-line!
  "Emit the canned response for one client command line."
  [^java.io.BufferedWriter out ^String line]
  (let [parts (str/split line #"\s+")
        tag (first parts)
        cmd (second parts)
        cmd2 (nth parts 2 nil)]
    (case cmd
      "LOGOUT" (reply! out (str "* BYE fake closing\r\n" tag " OK LOGOUT done\r\n"))
      "LOGIN" (reply! out (str tag " OK LOGIN completed\r\n"))
      "AUTHENTICATE" (reply! out (str tag " OK AUTHENTICATE completed\r\n"))
      "SELECT" (if (= "Nope" cmd2)
                 (reply! out (str tag " NO Mailbox doesn't exist\r\n"))
                 (reply! out (str "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n"
                                   "* 5 EXISTS\r\n"
                                   "* 3 RECENT\r\n"
                                   tag " OK [READ-WRITE] SELECT completed\r\n")))
      "EXAMINE" (reply! out (str "* 5 EXISTS\r\n"
                                 "* 3 RECENT\r\n"
                                 tag " OK [READ-ONLY] EXAMINE completed\r\n"))
      "LIST" (reply! out (str "* LIST (\\HasNoChildren) \"/\" INBOX\r\n"
                              "* LIST (\\HasNoChildren \\Trash) \"/\" Trash\r\n"
                              tag " OK LIST completed\r\n"))
      "STATUS" (reply! out (str "* STATUS \"INBOX\" (MESSAGES 5 UNSEEN 2 UIDNEXT 22)\r\n"
                                tag " OK STATUS completed\r\n"))
      "FETCH" (cond
                (re-find #"ENVELOPE" line)
                (reply! out (str "* 1 FETCH (UID 42 ENVELOPE (\"Thu, 01 Jan 2024 00:00:00 +0000\" \"Test Subject\" ((\"A\" NIL \"a\" \"x.com\")) NIL NIL NIL NIL NIL NIL))\r\n"
                                  tag " OK FETCH completed\r\n"))
                (re-find #"BODY" line)
                (let [body "Subject: hi\r\n\r\nbody"]
                  (reply! out (str "* 1 FETCH (UID 42 BODY[] {" (count body) "}\r\n"
                                    body ")\r\n"
                                    tag " OK FETCH completed\r\n")))
                :else
                (reply! out (str "* 1 FETCH (UID 42 FLAGS (\\Seen) RFC822.SIZE 1234)\r\n"
                                  tag " OK FETCH completed\r\n")))
      "UID" (cond
              (= "FETCH" cmd2)
              (reply! out (str "* 1 FETCH (UID 42 FLAGS (\\Seen))\r\n"
                               tag " OK UID FETCH completed\r\n"))
              (= "SEARCH" cmd2)
              (reply! out (str "* SEARCH 42 43\r\n" tag " OK UID SEARCH completed\r\n"))
              (or (= "MOVE" cmd2) (= "COPY" cmd2))
              (reply! out (str tag " OK UID " cmd2 " completed\r\n"))
              :else
              (reply! out (str tag " OK done\r\n")))
      "SEARCH" (reply! out (str "* SEARCH 1 2 3\r\n" tag " OK SEARCH completed\r\n"))
      "STORE" (reply! out (str "* 1 FETCH (FLAGS (\\Seen))\r\n"
                               tag " OK STORE completed\r\n"))
      "EXPUNGE" (reply! out (str "* 1 EXPUNGE\r\n" tag " OK EXPUNGE completed\r\n"))
      "MOVE" (reply! out (str tag " OK MOVE completed\r\n"))
      "COPY" (reply! out (str tag " OK COPY completed\r\n"))
      "APPEND" (reply! out (str tag " OK [APPENDUID 1 43] APPEND completed\r\n"))
      "CREATE" (reply! out (str tag " OK CREATE completed\r\n"))
      "RENAME" (reply! out (str tag " OK RENAME completed\r\n"))
      "DELETE" (reply! out (str tag " OK DELETE completed\r\n"))
      "SUBSCRIBE" (reply! out (str tag " OK SUBSCRIBE completed\r\n"))
      "UNSUBSCRIBE" (reply! out (str tag " OK UNSUBSCRIBE completed\r\n"))
      "NOOP" (reply! out (str tag " OK done\r\n"))
      "UNSELECT" (reply! out (str tag " OK done\r\n"))
      "CLOSE" (reply! out (str tag " OK CLOSE completed\r\n"))
      (reply! out (str tag " BAD unknown command\r\n")))))

(defn- read-fully!
  "Read until `buf` is full (or EOF). BufferedReader.read is not guaranteed to
  fill the buffer in one call."
  [^java.io.Reader r ^chars buf]
  (loop [off 0]
    (when (< off (alength buf))
      (let [n (.read r buf off (- (alength buf) off))]
        (when (pos? n)
          (recur (+ off n)))))))

(defn- read-line-with-timeout
  "Read the next line, returning nil if nothing arrives within 300ms (used to
  tell 'more command text follows this literal' from 'the client is waiting for
  our reply'). Restores the normal read timeout afterwards."
  [^java.net.Socket sock ^java.io.BufferedReader in]
  (.setSoTimeout sock 300)
  (try
    (.readLine in)
    (catch java.net.SocketTimeoutException _ nil)
    (finally (.setSoTimeout sock 5000))))

(defn- serve-connection!
  "Serve one client connection against the canned script."
  [^java.net.Socket sock ^java.io.BufferedReader in ^java.io.BufferedWriter out]
  (reply! out "* OK [CAPABILITY IMAP4rev1 IDLE] fake ready\r\n")
  (loop [full nil pending nil]
    (let [line (or pending (.readLine in))]
      (when line
        (if-let [[_ n] (re-find #"\{(\d+)\}$" line)]
          ;; literal portal -> continuation + bytes + trailing CRLF
          (let [buf (char-array (int (Long/parseLong n)))]
            (reply! out "+ \r\n")
            (when (pos? (alength buf))
              (read-fully! in buf))
            (.readLine in)
            ;; rebuild the command line including the literal's content so
            ;; reply-to-line! can route on it
            (let [rebuilt (str (or full line) " " (String. buf 0 (alength buf)))]
              (if-let [next-line (read-line-with-timeout sock in)]
                (recur rebuilt next-line)
                (do (reply-to-line! out rebuilt)
                    (recur nil nil)))))
          (do (reply-to-line! out (or full line))
              (recur nil nil)))))))

(defn- handle-accept
  "Accept one connection and serve it with the canned script."
  [^java.net.ServerSocket ss]
  (try
    (let [sock (.accept ss)]
      (.setSoTimeout sock 5000)
      (with-open [sock sock
                  in (java.io.BufferedReader.
                      (java.io.InputStreamReader.
                       (.getInputStream sock) "UTF-8"))
                  out (java.io.BufferedWriter.
                       (java.io.OutputStreamWriter.
                        (.getOutputStream sock) "UTF-8"))]
        (serve-connection! sock in out)))
    (catch Exception _)))

(defn- accept-loop
  "Accept connections until the server socket is closed; each is served on its
  own thread by the canned script."
  [^java.net.ServerSocket ss]
  (loop []
    (when-not (.isClosed ss)
      (try (handle-accept ss) (catch Exception _))
      (recur))))

(defn start-fake-server
  "In-process IMAP server on an ephemeral localhost port with a canned script
  (greeting + LOGIN/SELECT/EXAMINE/LIST/STATUS/FETCH/UID FETCH/SEARCH/STORE/
  EXPUNGE/MOVE/COPY/APPEND/NOOP/UNSELECT/CLOSE/LOGOUT). Accepts repeated
  connections and handles `{n}` literal portals. Returns {:port port}."
  []
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        t (Thread. (fn [] (accept-loop ss)))]
    (.setDaemon t true)
    (.start t)
    {:port port}))