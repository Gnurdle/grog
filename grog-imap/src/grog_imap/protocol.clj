(ns grog-imap.protocol
  "grog-imap.protocol — a self-contained IMAP4rev1 / IMAP4rev2 protocol client
  (RFC 3501 / RFC 9051). No JavaMail / Jakarta Mail dependency: it speaks the
  IMAP wire protocol over java.net.Socket + javax.net.ssl.SSLSocketFactory,
  standard-library only, so it runs under babashka and the JVM.

  Scope: the *inbox / manage* surface — list and select folders, search, fetch,
  set flags, copy/move/expunge, and APPEND a message into a folder. It does NOT
  send mail; outbound sending is SMTP (RFC 5321), a separate protocol, out of
  scope here.

  --- Result shape -----------------------------------------------------------
  Every command returns a normalized result:

    {:tagged   {:type :tagged :tag \"A5\" :status :ok|:no|:bad
                :data [...] :raw \"..\"}
     :untagged [{:type :untagged :status nil|:ok|:no|:bad :data [...] :raw \"..\"}
                ...]}

  `:data` is the tokenized tail of each response line. Raw `* ...` data tokens are
  Clojure values: numbers, strings, nil (NIL), keywords (\\flags, status).
  Use ok?, completion-status, find-untagged, untagged-of-type, result-data to read it."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; Forward declarations for fns defined later in this file but referenced by
;; earlier ones (connect reads the greeting; auth-command! base64-encodes).
(declare read-response* b64 utf8)

;;; ---------------------------------------------------------------------------
;;; connection
;;; ---------------------------------------------------------------------------

(defn connect
  "Open a socket to `host` and read the server greeting. Options:
  :port (default 143), :ssl (default false; uses an implicit-TLS socket),
  :timeout ms (default 60000). Returns a connection map."
  [host & {:keys [port ssl timeout] :or {port 143 ssl false timeout 60000}}]
  (let [^java.net.Socket sock (if ssl
                                (let [^javax.net.ssl.SSLSocket s
                                      (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault)
                                                     ^String host (int port))]
                                  (.startHandshake s)
                                  s)
                                (java.net.Socket. ^String host (int port)))
        _ (.setSoTimeout sock (int timeout))
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream sock) "UTF-8"))
        conn {:host host :port port :ssl ssl :timeout timeout
              :socket sock :reader reader :writer writer
              :tag (atom 0)
              :capabilities (atom [])}]
    (assoc conn :greeting (read-response* conn))))

(def open-connection connect)

(defn- read-crlf-line ^String [conn]
  (let [^java.io.Reader r (:reader conn)
        sb (StringBuilder.)
        raw (loop []
              (let [c (.read r)]
                (cond
                  (neg? c) (str sb)
                  (= 10 c) (str sb)   ; LF ends the line
                  :else (do (.append sb (char c)) (recur)))))]
    ;; strip the trailing CR of a CRLF-terminated line
    (if (str/ends-with? raw "\r")
      (subs raw 0 (dec (count raw)))
      raw)))

(defn- read-literal ^String [conn ^long n]
  (let [^java.io.Reader r (:reader conn)
        buf (char-array n)]
    (.read r buf 0 n)
    (String. buf)))

(defn- read-response-line ^String [conn]
  (let [sb (StringBuilder.)]
    (loop []
      (let [line (read-crlf-line conn)]
        (.append sb line)
        (if-let [[_ n] (re-find #"\{(\d+)\}$" line)]
          (do (.append sb (read-literal conn (Long/parseLong n)))
              (recur))
          (str sb))))))

(declare parse-line)

(defn- read-response* [conn]
  (parse-line (read-response-line conn)))

(defn- read-response [conn]
  (read-response* conn))

;;; ---------------------------------------------------------------------------
;;; response parsing / classification
;;; ---------------------------------------------------------------------------

(defn- finish-atom [s]
  (cond
    (= s "NIL") nil
    (re-matches #"\d+" s)  (Long/parseLong s)
    (= s "*")   (symbol "*")
    (= s "+")   (symbol "+")
    (str/starts-with? s "\\") (-> s (subs 1) str/lower-case keyword)
    (contains? #{"OK" "NO" "BAD" "BYE" "PREAUTH"} s)
    (keyword (str/lower-case s))
    (str/starts-with? s "[") (keyword (str/lower-case (str/replace s #"[\[\]]" "")))
    :else (symbol s)))

(defn- close-frame
  "Pop the current frame off `frames` and make it a single token in its parent
  frame (IMAP ')' handling)."
  [frames]
  (let [sub (peek frames)
        parents (pop frames)]
    (conj (pop parents) (conj (peek parents) sub))))

(defn- finish-tokens
  "Finalize the in-flight `cur` token into the top frame of `frames`.
  Quoted tokens become plain strings; bare tokens become finish-atom values."
  [frames cur]
  (if cur
    (conj (pop frames)
          (conj (peek frames)
                (if (:quoted cur)
                  (str (:sb cur))
                  (finish-atom (str (:sb cur))))))
    frames))

(defn- tokenize
  "Split a raw IMAP response line into Clojure tokens: `(a b)` -> [a b],
  `\\Seen` -> :seen, `123` -> 123, NIL -> nil, \"x\" -> \"x\",
  `{n}..content..` literal -> content string, status words -> keywords.
  Returns a vector; parenthesized lists become nested vectors."
  [^String s]
  (let [n (count s)]
    (loop [i 0 frames [[]] cur nil]
      (if (>= i n)
        (peek (finish-tokens frames cur))
        (let [c (.charAt s i)]
          (cond
            ;; literal portal {n} followed inline by its content
            (and (nil? cur) (= c \{) (re-matches #"\{\d+\}.*" (subs s i)))
            (let [close (long (str/index-of s "}" i))
                  len (long (Long/parseLong (subs s (inc i) close)))
                  start (inc close)
                  content (subs s start (min n (+ start len)))]
              (recur (+ start len)
                     (conj (pop frames) (conj (peek frames) content))
                     nil))
            ;; quoted string starts
            (and (nil? cur) (= c \"))
            (recur (inc i) frames {:sb (StringBuilder.) :quoted true})
            ;; inside a quoted string
            (and cur (:quoted cur))
            (cond
              (= c \\) (do (.append ^StringBuilder (:sb cur) (.charAt s (inc i)))
                           (recur (+ i 2) frames cur))
              (= c \") (recur (inc i) (finish-tokens frames cur) nil)
              :else (do (.append ^StringBuilder (:sb cur) c) (recur (inc i) frames cur)))
            ;; inside a bare token
            (and cur (not (:quoted cur)))
            (cond
              (Character/isWhitespace c) (recur (inc i) (finish-tokens frames cur) nil)
              (= c \() (recur (inc i) (conj (finish-tokens frames cur) []) nil)
              (= c \)) (recur (inc i) (close-frame (finish-tokens frames cur)) nil)
              :else (do (.append ^StringBuilder (:sb cur) c) (recur (inc i) frames cur)))
            ;; start a new bare token / handle delimiters at boundaries
            (Character/isWhitespace c) (recur (inc i) frames cur)
            (= c \() (recur (inc i) (conj frames []) cur)
            (= c \)) (recur (inc i) (close-frame frames) cur)
            :else (let [sb (StringBuilder.)]
                    (.append sb c)
                    (recur (inc i) frames {:sb sb :quoted false}))))))))

(defn- parse-line
  "Classify one raw response line into a response map (see ns docstring)."
  [raw]
  (let [toks (tokenize raw)]
    (if-not (seq toks)
      {:type :empty :raw raw :tokens []}
      (let [f (first toks)]
        (cond
          (= f (symbol "*"))
          (let [r (rest toks)
                status (when (contains? #{:ok :no :bad :bye :preauth} (first r))
                         (first r))
                data (if status (rest r) r)]
            {:type :untagged :status status :data data :raw raw :tokens toks})
          (= f (symbol "+"))
          {:type :continuation :data (rest toks) :raw raw :tokens toks}
          :else
          {:type :tagged :tag (str f)
           :status (when (keyword? (second toks)) (second toks))
           :data (nnext toks) :raw raw :tokens toks})))))

;;; ---------------------------------------------------------------------------
;;; command plumbing
;;; ---------------------------------------------------------------------------

(defn- next-tag [conn] (str "A" (swap! (:tag conn) inc)))

(defn- collect-until-tagged! [conn tag]
  (loop [untagged []]
    (let [resp (read-response conn)]
      (case (:type resp)
        :continuation (recur untagged)
        :untagged      (recur (conj untagged resp))
        :empty         (throw (ex-info "IMAP connection closed (EOF) while awaiting completion"
                                       {:expected tag}))
        :tagged        (if (= tag (:tag resp))
                         {:tagged resp :untagged untagged}
                         (throw (ex-info "Unexpected completion tag"
                                         {:expected tag :got (:tag resp)})))))))

(defn- safe-astring? [^String s]
  ;; conservative safe-atom set: anything not in it falls back to a literal.
  ;; excludes SP, controls, \" \\ ( ) { } % * [ ] which would need escaping.
  (and (pos? (count s))
       (re-matches #"[A-Za-z0-9._+\-@/=$]+" s)))

(defn- encode-arg [arg]
  (cond
    (nil? arg) "NIL"
    (string? arg) (cond
                    (zero? (count arg)) "\"\""
                    (safe-astring? arg) arg
                    :else {:_lit arg})
    (boolean? arg) (if arg "TRUE" "FALSE")
    (keyword? arg) (str/upper-case (name arg))
    (number? arg) (str arg)
    (symbol? arg) (str arg)
    :else (str arg)))

(defn- send-parts! [conn items]
  (let [^java.io.Writer w (:writer conn)]
    (loop [items (seq items)]
      (when items
        (let [it (first items)]
          (if (map? it) ; literal portal -> wait for '+', then send bytes+CRLF
            (do (let [resp (read-response conn)]
                  (when-not (= :continuation (:type resp))
                    (throw (ex-info "Expected '+ ' continuation before literal"
                                    {:resp (:raw resp)}))))
                (.write w (str (:lit it) "\r\n"))
                (.flush w))
            (do (.write w (str it "\r\n"))
                (.flush w)))
          (recur (next items)))))))

(defn- build-parts [conn command args]
  (let [tag (next-tag conn)
        parts (atom [])
        cur (atom (str tag " " command))]
    (doseq [arg args]
      (let [tok (encode-arg arg)]
        (if (and (map? tok) (:_lit tok))
          (let [portal (str " {" (count (:_lit tok)) "}")]
            (swap! parts conj (str @cur portal))
            (swap! parts conj {:lit (:_lit tok)})
            (reset! cur ""))
          (swap! cur str (if (= "" @cur) "" " ") tok))))
    (if (not= "" @cur) (conj @parts @cur) @parts)))

(defn- imap-command! [conn command & args]
  (let [items (build-parts conn command args)
        tag (-> (first items) (str/split #"\s") first)]
    (send-parts! conn items)
    (collect-until-tagged! conn tag)))

;;; ---------------------------------------------------------------------------
;;; result helpers
;;; ---------------------------------------------------------------------------

(defn result-data [res] res)

(defn completion-status
  "Completion status keyword of a command result: :ok, :no, or :bad."
  [res]
  (:status (:tagged res)))

(defn ok? [res] (= :ok (completion-status res)))

(defn throw-unless-ok [res]
  (when-not (ok? res)
    (throw (ex-info (str "IMAP command failed: " (:raw (:tagged res)))
                    {:result res})))
  res)

(defn untagged-of-type
  "All untagged responses of `token`. Matches the token as the leading data
  token (`* LIST ...`, `* SEARCH ...`) or as the second data token for
  FETCH-style responses (`* 1 FETCH ...`)."
  [res token]
  (filter (fn [r]
            (and (= :untagged (:type r))
                 (let [d (:data r)]
                   (or (= token (first d))
                       (= token (second d))))))
          (:untagged res)))

(defn find-untagged
  "First untagged response of `token`, or nil."
  [res token]
  (first (untagged-of-type res token)))

(defn untagged-response
  "All untagged responses of a command result (`(:untagged res)`)."
  [res]
  (:untagged res))

;;; ---------------------------------------------------------------------------
;;; connection / lifecycle
;;; ---------------------------------------------------------------------------

(defn logout [conn]
  (imap-command! conn "LOGOUT")
  :logged-out)

(defn disconnect [conn]
  (try (logout conn) (catch Exception _))
  (try (.close ^java.io.Reader (:reader conn)) (catch Exception _))
  (try (.close ^java.net.Socket (:socket conn)) (catch Exception _))
  :logged-out)

(defn login [conn user pass]
  (throw-unless-ok (imap-command! conn "LOGIN" (str user) (str pass))))

(defn- read-continuation!
  "Read one response and assert it is a server `+ ` continuation; return the
  parsed response (or throw if the server completed/rejected instead)."
  [conn]
  (let [resp (read-response conn)]
    (when-not (= :continuation (:type resp))
      (throw (ex-info "Expected '+ ' continuation from server"
                      {:got (:type resp) :raw (:raw resp)})))
    resp))

(defn- auth-command!
  "AUTHENTICATE with a SASL mechanism: send `pieces` (clear-text payloads) as
  base64 after each `+ ` continuation, then collect the tagged completion."
  [conn mechanism pieces]
  (let [tag (next-tag conn)
        ^java.io.Writer w (:writer conn)]
    (.write w (str tag " AUTHENTICATE " mechanism "\r\n"))
    (.flush w)
    (doseq [piece pieces]
      (read-continuation! conn)
      (.write w (str (b64 (utf8 (str piece))) "\r\n"))
      (.flush w))
    (collect-until-tagged! conn tag)))

(defn authenticate-login [conn user pass]
  (auth-command! conn "LOGIN" [(str user) (str pass)]))

(defn authenticate-plain [conn user pass & [authzid]]
  (auth-command! conn "PLAIN" [(str (or authzid "") \u0000 user \u0000 pass)]))

(defn starttls
  "Upgrade the current (plaintext) connection to TLS via STARTTLS. The server
  must advertise the STARTTLS capability. Returns an updated connection map with
  socket/reader/writer swapped for the TLS layer (hostname verification on)."
  [conn]
  (throw-unless-ok (imap-command! conn "STARTTLS"))
  (let [host (:host conn)
        port (int (:port conn))
        ^javax.net.ssl.SSLSocketFactory factory
        (javax.net.ssl.SSLSocketFactory/getDefault)
        ^javax.net.ssl.SSLSocket tls-sock
        (.createSocket factory
                       ^java.net.Socket (:socket conn)
                       ^String host port true)
        params (.getSSLParameters tls-sock)]
    (try
      ;; verify the peer certificate hostname, like an HTTPS client
      (.setEndpointIdentificationAlgorithm params "HTTPS")
      (.setSSLParameters tls-sock params)
      (catch Exception _))
    (.setSoTimeout tls-sock (int (:timeout conn 60000)))
    (.startHandshake tls-sock)
    (let [reader (java.io.BufferedReader.
                  (java.io.InputStreamReader. (.getInputStream tls-sock) "UTF-8"))
          writer (java.io.BufferedWriter.
                  (java.io.OutputStreamWriter. (.getOutputStream tls-sock) "UTF-8"))]
      (assoc conn :socket tls-sock :reader reader :writer writer :ssl true))))

(defn capability [conn]
  (throw-unless-ok (imap-command! conn "CAPABILITY")))

(defn noop [conn]
  (throw-unless-ok (imap-command! conn "NOOP")))

;;; ---------------------------------------------------------------------------
;;; command encoding
;;; ---------------------------------------------------------------------------

(defn parenthesized
  "Encode a list of items as an IMAP parenthesized list. Keywords are
  uppercased (data items like :uid -> UID); strings pass through verbatim
  (pre-wired flag strings like \"\\\\Seen\"); everything else is str'd."
  [items]
  (str "(" (str/join " "
                     (map (fn [x]
                            (cond
                              (keyword? x) (str/upper-case (name x))
                              (string? x) x
                              :else (str x)))
                          items))
       ")"))

(defn seq-set
  "Collapse a collection of message numbers into an IMAP seq-set string:
  runs of consecutive numbers become `a:b` (e.g. [1 2 3 5] -> \"1:3,5\")."
  [nums]
  (->> (sort (distinct nums))
       (reduce (fn [runs n]
                 (if-let [[s e] (peek runs)]
                   (if (= n (inc e)) (conj (pop runs) [s n]) (conj runs [n n]))
                   [[n n]]))
               [])
       (map (fn [[a b]] (if (= a b) (str a) (str a ":" b))))
       (str/join ",")))

(defn sequence-set
  "Normalize a message set (single number, collection, or raw string) into the
  wire form used by FETCH/STORE/COPY/MOVE."
  [s]
  (cond
    (int? s) (str s)
    (coll? s) (seq-set s)
    :else (str s)))

(defn flag-wire
  "Encode a flag for the wire: :seen -> \\Seen, strings pass through."
  [flag]
  (cond
    (keyword? flag) (str "\\" (str/capitalize (name flag)))
    (string? flag)  flag
    :else (str flag)))

;;; ---------------------------------------------------------------------------
;;; mailbox commands
;;; ---------------------------------------------------------------------------

(defn select [conn mailbox]
  (throw-unless-ok (imap-command! conn "SELECT" mailbox)))

(defn examine [conn mailbox]
  (throw-unless-ok (imap-command! conn "EXAMINE" mailbox)))

(defn unselect [conn]
  (throw-unless-ok (imap-command! conn "UNSELECT")))

(defn create [conn mailbox]
  (throw-unless-ok (imap-command! conn "CREATE" mailbox)))

(defn delete* [conn mailbox]
  (throw-unless-ok (imap-command! conn "DELETE" mailbox)))

(defn rename [conn old new]
  (throw-unless-ok (imap-command! conn "RENAME" old new)))

(defn subscribe [conn mailbox]
  (throw-unless-ok (imap-command! conn "SUBSCRIBE" mailbox)))

(defn unsubscribe [conn mailbox]
  (throw-unless-ok (imap-command! conn "UNSUBSCRIBE" mailbox)))

(defn list-folders [conn & [ref pattern]]
  (throw-unless-ok (imap-command! conn "LIST" (or ref "") (or pattern "*"))))

(defn lsub [conn & [ref pattern]]
  (throw-unless-ok (imap-command! conn "LSUB" (or ref "") (or pattern "*"))))

(defn list-namespaces [conn]
  (throw-unless-ok (imap-command! conn "NAMESPACE")))

(defn status [conn mailbox items]
  (throw-unless-ok (imap-command! conn "STATUS" mailbox (parenthesized items))))

(defn check [conn]
  (throw-unless-ok (imap-command! conn "CHECK")))

(defn close-mailbox [conn]
  (throw-unless-ok (imap-command! conn "CLOSE")))

(defn expunge [conn]
  (throw-unless-ok (imap-command! conn "EXPUNGE")))

;;; ---------------------------------------------------------------------------
;;; message commands
;;; ---------------------------------------------------------------------------

(defn fetch [conn seq & items]
  (throw-unless-ok (imap-command! conn "FETCH" (sequence-set seq) (parenthesized items))))

(defn store [conn seq flag-op flags]
  (throw-unless-ok
   (imap-command! conn "STORE" (sequence-set seq)
                  (str/upper-case (name flag-op))
                  (parenthesized (map flag-wire (if (coll? flags) flags [flags]))))))

(defn search-criteria [criteria]
  (cond
    (keyword? criteria) (str/upper-case (name criteria))
    (string? criteria)  criteria
    (vector? criteria)  (str/join " " (map search-criteria criteria))
    :else (str criteria)))

(defn search [conn criteria & [charset]]
  (throw-unless-ok
   (if charset
     (imap-command! conn "SEARCH" "CHARSET" (str charset) (search-criteria criteria))
     (imap-command! conn "SEARCH" (search-criteria criteria)))))

(defn uid [conn command & args]
  (throw-unless-ok (apply imap-command! conn "UID" command args)))

(defn uid-fetch [conn seq & items]
  (apply uid conn "FETCH" (sequence-set seq) (parenthesized items)))

(defn uid-store [conn seq flag-op flags]
  (uid conn "STORE" (sequence-set seq) (str/upper-case (name flag-op))
       (parenthesized (map flag-wire (if (coll? flags) flags [flags])))))

(defn uid-search
  "UID SEARCH: same as `search` but the result carries UIDs."
  [conn criteria & [charset]]
  (let [args (concat ["SEARCH"]
                     (when charset ["CHARSET" (str charset)])
                     [(search-criteria criteria)])]
    (apply uid conn args)))

(defn copy [conn seq mailbox]
  (throw-unless-ok (imap-command! conn "COPY" (sequence-set seq) mailbox)))

(defn move [conn seq mailbox]
  (throw-unless-ok (imap-command! conn "MOVE" (sequence-set seq) mailbox)))

(defn uid-copy [conn seq mailbox]
  (uid conn "COPY" (sequence-set seq) mailbox))

(defn uid-move [conn seq mailbox]
  (uid conn "MOVE" (sequence-set seq) mailbox))

(defn append [conn mailbox content & [{:keys [flags date-time]}]]
  (let [args (vec (concat [mailbox]
                          (when (seq flags) [(parenthesized (map flag-wire flags))])
                          (when date-time [date-time])
                          [content]))]
    (throw-unless-ok (apply imap-command! conn "APPEND" args))))

(defn delete-messages [conn seq]
  (store conn seq :+flags [:deleted])
  (expunge conn))

(defn idle
  "IMAP IDLE is deferred for v1. `imap-command!` blocks waiting for a tagged
  completion, but IDLE stays in continuation/streaming mode until the client
  sends DONE — so the naive version would hang the connection. Fail loudly
  instead of deadlocking."
  [conn]
  (throw (ex-info "IMAP IDLE is not implemented in v1 (see status.md Phase 1)"
                  {:conn conn})))

(defn enable [conn & caps]
  (throw-unless-ok (apply imap-command! conn "ENABLE"
                          (map #(str/upper-case (name %)) caps))))

;;; ---------------------------------------------------------------------------
;;; private transport helpers used above (b64 / utf8)
;;; ---------------------------------------------------------------------------

(defn- b64 ^String [^bytes b]
  (.encodeToString (java.util.Base64/getEncoder) b))

(defn- utf8 ^bytes [s]
  (.getBytes ^String s "UTF-8"))

(def system-flags [:seen :answered :flagged :deleted :draft :recent])

(def status-items [:messages :recent :uidnext :uidvalidity :unseen])