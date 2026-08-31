(ns grog-imap.core
  "grog-imap.core — the PUBLIC library layer for grog-imap.

  This is what application code AND the MCP server both call. It has NO
  dependency on the MCP SDK; it imports only grog-imap.protocol plus the
  stdlib and clojure.data.json/clojure.edn.

  Application code:

    (require '[grog-imap.core :as imap])

    (def config (imap/load-config))                    ; GROG_IMAP_CONFIG
    (imap/connect-account! config \"gmail\" {:password \"...\"})
    (let [conn (imap/connection \"gmail\")]
      (imap/list-mailboxes conn)
      (imap/search conn :unseen))

  Credentials are never part of account metadata. The caller hands a living
  credential to connect-account!/ensure-connected!: either a map
  ({:password \"...\"} for LOGIN/PLAIN, {:access-token \"...\"} or
  {:oauth {...} :refresh-token \"...\"} for XOAUTH2) or a fn
  [account] -> credential map. Secrets never transit this namespace's config
  or results."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [grog-imap.protocol :as protocol]
            [grog-imap.oauth :as oauth]))

;;; ---------------------------------------------------------------------------
;;; config / account model
;;; ---------------------------------------------------------------------------

(defn- keywordize-keys [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(defn- normalize-account [m]
  (let [m (keywordize-keys m)]
    (-> m
        (update :name str)
        (update :host str)
        (update :port #(when % (int (Long/parseLong (str %)))))
        (update :tls #(boolean %))
        (update :starttls #(boolean %))
        (update :read-only #(if (nil? %) true (boolean %)))
        (update :timeout #(when % (int (Long/parseLong (str %))))))))

(defn load-config
  "Load account metadata. With no argument, reads the GROG_IMAP_CONFIG env var
  (a file path or inline config). With an argument, accepts a config map, a file
  path, or an inline config string. Reads EDN first (the grog writer format),
  falling back to legacy JSON for compatibility. Returns
  {:accounts [{:name :host :port :tls :starttls :user :read-only :timeout ...}]}.
  Metadata NEVER contains passwords."
  ([] (load-config (System/getenv "GROG_IMAP_CONFIG")))
  ([source]
   (let [source (or source "")]
     (cond
       (map? source) source
       (str/blank? source)
       (throw (ex-info "GROG_IMAP_CONFIG is not set (set it to a config file path or inline config (EDN or JSON))"
                       {}))
       :else
       (let [text (if (or (str/starts-with? (str/trim source) "{")
                          (str/starts-with? (str/trim source) ":"))
                    source
                    (slurp source))
             data (try
                    (edn/read-string text)
                    (catch Exception _
                      (json/read-str text)))
             ;; EDN yields keyword keys; data.json returns string keys; accept both
             accounts (or (:accounts data) (get data "accounts"))]
         {:accounts (mapv normalize-account accounts)})))))

(defn accounts
  "All configured accounts (metadata only)."
  [config]
  (vec (:accounts config)))

(defn get-account
  "Return the account map for `name`, or throw. Strict allowlist: only names in
  the config can ever be connected to."
  [config name]
  (let [name (str name)
        acc (first (filter #(= name (str (:name %))) (:accounts config)))]
    (or acc
        (throw (ex-info (str "Unknown account: " name)
                        {:account name
                         :known-accounts (mapv :name (:accounts config))})))))

(defn read-only?
  "True when the account is configured read-only. Defaults to true when
  unspecified (safe by default); only an explicit `:read-only false` opts in to
  mutation."
  [account]
  (not (false? (:read-only account))))

;;; ---------------------------------------------------------------------------
;;; connection lifecycle / session registry
;;; ---------------------------------------------------------------------------

(defonce ^:private sessions (atom {}))

(defn connection
  "The registered live connection for `account-name`, or nil."
  [account-name]
  (get @sessions (str account-name)))

(defn- resolve-credential
  "Resolve the credential for `account` from `credential-source`: a map used
  as-is, a fn called with the account, or nil."
  [account credential-source]
  (cond
    (nil? credential-source) nil
    (fn? credential-source) (credential-source account)
    :else credential-source))

(defn- xoauth2-token
  "Resolve a live access token for XOAUTH2 from a credential:
  `:access-token` (caller-provided) or `:oauth` + `:refresh-token` (library
  refreshes via grog-imap.oauth)."
  [account credential]
  (cond
    (:access-token credential)
    (:access-token credential)

    (or (:refresh-token credential) (get-in credential [:oauth :refresh-token]))
    (let [oauth-conf (merge (or (:oauth account) {})
                            (or (:oauth credential) {}))
          refresh (or (:refresh-token credential)
                      (get-in credential [:oauth :refresh-token]))]
      (oauth/access-token! (assoc oauth-conf :refresh-token refresh)))

    :else
    (throw (ex-info (str "No OAuth token for account " (:name account))
                    {:account (:name account)}))))

(defn- open-connection-for
  "Open and authenticate a fresh protocol connection for `account` using
  `credential`. Mechanism comes from `(:sasl account)` (default :login):
    :login/:plain -> {:password ...}
    :xoauth2      -> {:access-token ...} or {:oauth {...} :refresh-token ...}"
  [account credential]
  (let [conn (protocol/connect (:host account)
                               :port (or (:port account) 143)
                               :ssl (boolean (:tls account))
                               :timeout (or (:timeout account) 30000))]
    (try
      (when (and (:starttls account) (not (:tls account)))
        (protocol/starttls conn))
      (when-let [user (or (:user account) (:user credential))]
        (let [mechanism (or (:sasl account) :login)]
          (case mechanism
            :plain (let [pass (:password credential)]
                     (when-not pass
                       (throw (ex-info (str "No password supplied for account " (:name account))
                                       {:account (:name account)})))
                     (protocol/authenticate-plain conn user pass))
            :xoauth2 (protocol/authenticate-xoauth2 conn user (xoauth2-token account credential))
            :login (let [pass (:password credential)]
                     (when-not pass
                       (throw (ex-info (str "No password supplied for account " (:name account))
                                       {:account (:name account)})))
                     (protocol/login conn user pass))
            (throw (ex-info (str "Unsupported SASL mechanism: " mechanism)
                            {:mechanism mechanism})))))
      conn
      (catch Exception e
        (protocol/disconnect conn)
        (throw e)))))

(defn connect-account!
  "Establish (or refresh) a live connection for `account-name` and register it
  in the session registry. `credential-source` is a credential map
  ({:password \"...\"}) or a fn [account] -> credential map. Returns the
  connection. The secret lives only in the caller-provided credential."
  [config account-name credential-source]
  (let [account (get-account config account-name)
        credential (resolve-credential account credential-source)]
    (when-not credential
      (throw (ex-info (str "No credential supplied for account " (:name account))
                      {:account (:name account)})))
    (when-let [old (get @sessions (:name account))]
      (try (protocol/disconnect old) (catch Exception _)))
    (let [conn (open-connection-for account credential)]
      (swap! sessions assoc (:name account) conn)
      conn)))

(defn connected?
  "True if `conn` still answers NOOP (the socket is alive)."
  [conn]
  (try
    (protocol/noop conn)
    true
    (catch Exception _ false)))

(defn ensure-connected!
  "Return a live connection for `account-name`, reconnecting if the registered
  one is missing or dead. Same `credential-source` contract as
  `connect-account!`."
  [config account-name credential-source]
  (let [existing (connection account-name)]
    (if (and existing (connected? existing))
      existing
      (connect-account! config account-name credential-source))))

(defn disconnect-account!
  "Close and unregister the connection for `account-name`."
  [account-name]
  (let [name (str account-name)]
    (when-let [conn (get @sessions name)]
      (try (protocol/disconnect conn) (catch Exception _))
      (swap! sessions dissoc name))
    :disconnected))

(defn disconnect-all!
  "Close and unregister every session."
  []
  (doseq [name (keys @sessions)]
    (disconnect-account! name))
  :disconnected)

;;; ---------------------------------------------------------------------------
;;; response normalization helpers
;;; ---------------------------------------------------------------------------

(defn- first-token [res token]
  (some-> (protocol/find-untagged res token) :data first))

(defn- all-tokens [res]
  (concat (-> res :tagged :tokens)
          (mapcat :tokens (:untagged res))))

(defn- response-code
  "Extract a response-code value from the response's tokens, e.g.
  [UIDNEXT 4387] -> 4387."
  [res code]
  (some (fn [[k v]] (when (and (keyword? k) (= k code) (number? v)) v))
        (partition 2 1 (all-tokens res))))

(defn- has-code?
  "True if the response carries the given response-code keyword (e.g. :read-write)."
  [res code]
  (boolean (some #(= code %) (all-tokens res))))

;;; ---------------------------------------------------------------------------
;;; mailbox operations
;;; ---------------------------------------------------------------------------

(defn- select-state
  "Normalize a SELECT/EXAMINE result into a mailbox-state map."
  [res]
  {:messages (first-token res 'EXISTS)
   :recent (first-token res 'RECENT)
   :unseen (response-code res :unseen)
   :uidnext (response-code res :uidnext)
   :uidvalidity (response-code res :uidvalidity)
   :read-write (has-code? res :read-write)})

(defn select
  "SELECT `mailbox`; returns the mailbox-state map
  {:messages :recent :unseen :uidnext :uidvalidity :read-write}."
  [conn mailbox]
  (select-state (protocol/throw-unless-ok (protocol/select conn mailbox))))

(defn examine
  "EXAMINE `mailbox` (read-only select); returns the mailbox-state map."
  [conn mailbox]
  (select-state (protocol/throw-unless-ok (protocol/examine conn mailbox))))

(defn unselect
  "UNSELECT — return to authenticated state."
  [conn]
  (protocol/throw-unless-ok (protocol/unselect conn))
  :done)

(defn- mailbox-info [resp]
  (let [d (:data resp)]
    {:name (some-> (nth d 3 nil) str)
     :delimiter (nth d 2 nil)
     :attributes (set (nth d 1 nil))}))

(defn list-mailboxes
  "LIST all folders. Returns decorated maps
  {:name :delimiter :attributes}."
  [conn]
  (let [res (protocol/throw-unless-ok (protocol/list-folders conn))]
    (mapv mailbox-info (protocol/untagged-of-type res 'LIST))))

(defn mailbox-status
  "STATUS of `mailbox` for `items` (keywords; default [:messages :unseen]).
  Returns a map of item keyword -> value."
  [conn mailbox & [items]]
  (let [items (or items [:messages :unseen])
        res (protocol/throw-unless-ok (protocol/status conn mailbox items))
        status-resp (first (protocol/untagged-of-type res 'STATUS))]
    (if-let [pairs (and status-resp (nth (:data status-resp) 2 nil))]
      (into {} (map (fn [[k v]] [(keyword (str/lower-case (name k))) v])
                    (partition 2 pairs)))
      {})))

(defn close-mailbox
  "CLOSE the selected mailbox (expunges \\Deleted messages)."
  [conn]
  (protocol/throw-unless-ok (protocol/close-mailbox conn))
  :done)

(defn expunge
  "EXPUNGE the selected mailbox (deletes \\Deleted messages)."
  [conn]
  (protocol/throw-unless-ok (protocol/expunge conn))
  :done)

(defn create-mailbox [conn mailbox]
  (protocol/throw-unless-ok (protocol/create conn mailbox))
  :done)

(defn rename-mailbox [conn old new]
  (protocol/throw-unless-ok (protocol/rename conn old new))
  :done)

(defn delete-mailbox [conn mailbox]
  (protocol/throw-unless-ok (protocol/delete* conn mailbox))
  :done)

(defn subscribe-mailbox [conn mailbox]
  (protocol/throw-unless-ok (protocol/subscribe conn mailbox))
  :done)

(defn unsubscribe-mailbox [conn mailbox]
  (protocol/throw-unless-ok (protocol/unsubscribe conn mailbox))
  :done)

;;; ---------------------------------------------------------------------------
;;; message operations
;;; ---------------------------------------------------------------------------

(defn search
  "SEARCH. `criteria` accepts keywords (:all, :unseen, :new, ...), raw strings
  (\"FROM \\\"a@b.c\\\"\"), or a vector of criteria to join. Returns a vector of
  message numbers."
  [conn criteria & [charset]]
  (let [res (protocol/throw-unless-ok (protocol/search conn criteria charset))
        hit (first (protocol/untagged-of-type res 'SEARCH))]
    (vec (rest (:data hit)))))

(defn uid-search
  "UID SEARCH — like `search`, but results carry UIDs."
  [conn criteria & [charset]]
  (let [res (protocol/throw-unless-ok (protocol/uid-search conn criteria charset))
        hit (first (protocol/untagged-of-type res 'SEARCH))]
    (vec (rest (:data hit)))))

(defn uid-search-from
  "UID SEARCH FROM <sender>. `sender` is sent as a bare IMAP atom (no spaces),
  which Gmail's IMAP parses correctly (quoted/spaced criteria become literals
  that Gmail rejects). Returns UIDs for messages sent by `sender` in the
  currently selected mailbox."
  [conn sender]
  (let [res (protocol/throw-unless-ok (protocol/uid conn "SEARCH" "FROM" sender))
        hit (first (protocol/untagged-of-type res 'SEARCH))]
    (vec (rest (:data hit)))))

(defn uid-move-chunked
  "Move `uids` to `mailbox` in chunks (default 500, Gmail flaky on big batches).
  Chunks that fail are retried up to `retries` times. Returns
  {:moved n :failed [uid ...]}."
  [conn mailbox uids & {:keys [chunk-size retries] :or {chunk-size 500 retries 3}}]
  (loop [remaining (vec uids)
         moved 0
         attempts retries]
    (if (or (empty? remaining) (zero? attempts))
      {:moved moved :failed remaining}
      (let [move-chunk? (fn [ids]
                          (try
                            (protocol/throw-unless-ok
                             (protocol/uid-move conn (str/join "," (map str ids))
                                                mailbox))
                            true
                            (catch Throwable _ false)))
            chunks (partition-all chunk-size remaining)
            results (map (fn [ch] (if (move-chunk? ch)
                                    {:ok (count ch) :bad []}
                                    {:ok 0 :bad ch}))
                         chunks)]
        (recur (vec (mapcat :bad results))
               (+ moved (reduce + (map :ok results)))
               (dec attempts))))))

(defn- items->map
  "Turn a FETCH parenthesized items vector like
  [UID 42 FLAGS [:seen] RFC822.SIZE 1234] into a keyword-keyed map.
  Bare-atom item names arrive as symbols (UID, FLAGS, BODY[]...)."
  [items]
  (loop [xs (seq items) m {}]
    (if-not xs
      m
      (let [x (first xs)]
        (if (symbol? x)
          (let [name (str/lower-case (name x))
                v (second xs)]
            (recur (nnext xs)
                   (case name
                     "uid" (assoc m :uid v)
                     "flags" (assoc m :flags (vec v))
                     "rfc822.size" (assoc m :size v)
                     "internaldate" (assoc m :internal-date (some-> v str))
                     "envelope" (assoc m :envelope v)
                     "bodystructure" (assoc m :body-structure v)
                     "body" (assoc m :body v)
                     "body[]" (assoc m :body v)
                     "body.peek[]" (assoc m :body v)
                     "body[header]" (assoc m :header v)
                     "body.peek[header]" (assoc m :header v)
                     "body[text]" (assoc m :text v)
                     "body.peek[text]" (assoc m :text v)
                     "rfc822" (assoc m :rfc822 (some-> v str))
                     "rfc822.header" (assoc m :header (some-> v str))
                     "rfc822.text" (assoc m :text (some-> v str))
                     (assoc m (keyword name) v))))
          (recur (next xs) (assoc m :seq x)))))))

(defn- fetch-msg-map
  "Normalize one `* N FETCH (...)` response into a message map."
  [resp]
  (let [d (:data resp)
        items (nth d 2 nil)]
    (items->map (if items
                  (cons (first d) items)
                  [(first d)]))))

(defn fetch
  "FETCH `seq` with `items` (keywords like :uid :flags :rfc822.size :envelope
  :body). Returns a vector of normalized message maps
  {:seq :uid :flags :size :internal-date :envelope :body :header ...}."
  [conn seq & items]
  (let [res (apply protocol/fetch conn seq items)
        _ (protocol/throw-unless-ok res)]
    (mapv fetch-msg-map (protocol/untagged-of-type res 'FETCH))))

(defn uid-fetch
  "UID FETCH — like `fetch`, but `seq` is a set of UIDs."
  [conn seq & items]
  (let [res (apply protocol/uid-fetch conn seq items)
        _ (protocol/throw-unless-ok res)]
    (mapv fetch-msg-map (protocol/untagged-of-type res 'FETCH))))

(defn set-flags
  "STORE `flags` on `seq`. `op` is :+flags (default), :-flags, or :flags."
  ([conn seq flags] (set-flags conn seq :+flags flags))
  ([conn seq op flags]
   (protocol/throw-unless-ok
    (protocol/store conn seq op (if (coll? flags) (vec flags) [flags])))
   :done))

(defn mark-read [conn seq]
  (set-flags conn seq :+flags [:seen]))

(defn mark-unread [conn seq]
  (set-flags conn seq :-flags [:seen]))

(defn delete-messages
  "Flag `seq` \\Deleted and EXPUNGE the selected mailbox."
  [conn seq]
  (set-flags conn seq :+flags [:deleted])
  (expunge conn))

(defn move
  "MOVE `seq` to `mailbox`."
  [conn seq mailbox]
  (protocol/throw-unless-ok (protocol/move conn seq mailbox))
  :done)

(defn copy
  "COPY `seq` to `mailbox`."
  [conn seq mailbox]
  (protocol/throw-unless-ok (protocol/copy conn seq mailbox))
  :done)

(defn uid-move
  "UID MOVE."
  [conn seq mailbox]
  (protocol/throw-unless-ok (protocol/uid-move conn seq mailbox))
  :done)

(defn uid-copy
  "UID COPY."
  [conn seq mailbox]
  (protocol/throw-unless-ok (protocol/uid-copy conn seq mailbox))
  :done)

(defn append
  "APPEND `content` into `mailbox`; optional `:flags` (keywords)."
  [conn mailbox content & {:keys [flags]}]
  (protocol/throw-unless-ok
   (protocol/append conn mailbox content (when (seq flags) {:flags flags})))
  :done)
