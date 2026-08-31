(ns grog-imap.main
  "grog-imap — an MCP server (over stdio) exposing IMAP mailbox/message tools
  so an ECA-driven agent loop (or any MCP client) can read and manage mail.

  This is a THIN ADAPTER over `grog-imap.core` — every tool parses its args,
  calls a core function, and JSON-encodes the result. No IMAP logic lives here.

  Configuration
  -------------
  `GROG_IMAP_CONFIG` — JSON file path or inline JSON with account *metadata*:

    { \"accounts\": [
        { \"name\": \"gmail\", \"host\": \"imap.gmail.com\", \"port\": 993,
          \"tls\": true, \"user\": \"you@gmail.com\" },
        { \"name\": \"work\", \"host\": \"mail.example.com\", \"port\": 993,
          \"tls\": true, \"user\": \"you@example.com\", \"sasl\": \"xoauth2\",
          \"oauth\": { \"provider\": \"google\", \"client-id\": \"...\" },
          \"read-only\": true }
      ] }

  Credentials are NEVER in the config. The server resolves them at connect time
  from per-account env vars (`GROG_IMAP_PASSWORD_<NAME>` for LOGIN/PLAIN,
  `GROG_IMAP_REFRESH_<NAME>` for XOAUTH2) — secrets never transit tool
  arguments or results.

  Safety
  ------
  `:read-only` defaults to `true` (safe by default). Mutation tools
  (imap_set_flags / imap_delete / imap_move / imap_copy / imap_append) are
  rejected at call time for a read-only account; set `:read-only` to false in
  the config to opt in."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [grog-imap.core :as core])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; --- configuration / session state -----------------------------------------

(def ^{:private true} config*
  "Atom holding the loaded config map ({:accounts [...]})."
  (atom nil))

(def ^{:private true} current*
  "Atom holding the currently selected account name (nil = first account)."
  (atom nil))

(defn- env! [k]
  (or (not-empty (str/trim (or (System/getenv k) "")))
      (throw (ex-info (str "Missing env " k
                           " — set it before connecting to this account") {}))))

(defn- load-config!
  "Load account metadata from GROG_IMAP_CONFIG (file path or inline EDN/JSON)."
  []
  (let [config (core/load-config (System/getenv "GROG_IMAP_CONFIG"))]
    (when-not (seq (core/accounts config))
      (throw (ex-info "GROG_IMAP_CONFIG contains no accounts" {})))
    (reset! config* config)
    (reset! current* nil)
    config))

(defn- account-names [config]
  (mapv :name (core/accounts config)))

(defn- active-account
  "Return the active account map, throwing if none are configured."
  []
  (let [config @config*]
    (when-not (seq (core/accounts config))
      (throw (ex-info "No accounts configured — set GROG_IMAP_CONFIG first" {})))
    (core/get-account config (or @current* (first (account-names config))))))

(defn- secret-file-path
  "Per-account secret file for LOGIN/PLAIN credentials: the
  `GROG_IMAP_PASSWORD_FILE_<NAME>` env var if set, else `~/.grog-imap-<lower-name>`."
  [name]
  (let [upper (str/upper-case (str/replace name #"[^A-Za-z0-9_]" "_"))
        lower (str/lower-case (str/replace name #"[^A-Za-z0-9_]" "_"))
        explicit (not-empty (System/getenv (str "GROG_IMAP_PASSWORD_FILE_" upper)))]
    (or explicit
        (str (System/getProperty "user.home") "/.grog-imap-" lower))))

(defn credential-provider
  "Resolve a credential for `account`. Sources (never tool args / results):
    XOAUTH2  -> GROG_IMAP_REFRESH_<NAME> env
    LOGIN/PLAIN -> GROG_IMAP_PASSWORD_<NAME> env, else the per-account secret
    file (GROG_IMAP_PASSWORD_FILE_<NAME> or ~/.grog-imap-<lower-name>).
  The secret stays on disk; it is never logged or returned."
  [account]
  (let [name (str (:name account))
        upper (str/upper-case (str/replace name #"[^A-Za-z0-9_]" "_"))]
    (if (= :xoauth2 (:sasl account))
      {:refresh-token (env! (str "GROG_IMAP_REFRESH_" upper))}
      (let [pw (or (not-empty (System/getenv (str "GROG_IMAP_PASSWORD_" upper)))
                   (try (str/trim (slurp (secret-file-path name)))
                        (catch Throwable _ "")))]
        (if pw
          {:password pw}
          (throw (ex-info (str "No credential for account '" name "': set "
                               "GROG_IMAP_PASSWORD_" upper " or put the secret in "
                               (secret-file-path name)) {})))))))

(defn- active-conn!
  "Ensure the active account is authenticated (lazy) and return its live
  connection."
  []
  (let [account (active-account)]
    (core/ensure-connected! @config* (:name account) credential-provider)))

(defn- guard-writable!
  "Reject mutation tools for accounts configured read-only (the default)."
  []
  (let [account (active-account)]
    (when (core/read-only? account)
      (throw (ex-info (str "Account '" (:name account)
                           "' is read-only; mutation is disabled. "
                           "Set :read-only to false for this account to enable it.")
                      {:account (:name account)})))))

;; --- helpers ---------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))
(defn- ok [data] (json/write-str data))

(defn- kargs
  "Normalize MCP tool-argument keys to keywords (arguments arrive string-keyed)."
  [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v])) m))

(defn- tool
  "Build an async MCP tool spec (same helper as grog-odoo/grog-imaging)."
  ^io.modelcontextprotocol.server.McpServerFeatures$AsyncToolSpecification
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. ^String name ^String description ^String schema)
   (reify java.util.function.BiFunction
     (apply [_ _exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [_ sink]
            (let [^reactor.core.publisher.MonoSink sink sink]
              (try (.success sink (text-result (fn arguments)))
                   (catch Throwable t
                     (.success sink (error-result
                                     (str "Error executing tool " name ": "
                                          (or (:message (ex-data t)) (.getMessage t)))))))))))))))

(defn- items-kws
  "Map FETCH item strings to keywords (defaults: uid flags rfc822.size)."
  [items]
  (let [items (or items ["uid" "flags" "rfc822.size"])]
    (mapv keyword items)))

(defn- flags-kws [flags]
  (mapv keyword (or flags [])))

(defn- num-or-str [v]
  (if (number? v) (long v) (str v)))

;; --- tools -----------------------------------------------------------------

(defn build-tools
  "Build the tool list. Every tool is a thin adapter over grog-imap.core."
  []
  (let [config @config*
        names (account-names config)]
    [{:name "imap_list_accounts"
      :description "List the configured IMAP account names the model may use. Returns names only (never credentials)."
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_] (ok {:accounts names}))}

     {:name "imap_use_account"
      :description (str "Select which configured IMAP account to use for all subsequent calls. "
                        "You can ONLY pick one of these names; arbitrary hosts are not allowed. "
                        "Authenticates lazily using the server's credential provider. "
                        "Available: " (str/join ", " names))
      :schema (json/write-str {:type :object
                               :properties {:name {:type :string :enum names}}
                               :required [:name]})
      :fn (fn [a]
            (let [a (kargs a)
                  name (str (or (:name a) ""))]
              ;; strict allowlist: unknown names throw before any network I/O
              (core/get-account config name)
              (reset! current* name)
              (active-conn!)
              (ok {:account name :active true})))}

     {:name "imap_authenticate"
      :description "Authenticate the active IMAP account (lazy) and confirm it is ready. Safe to call before any other tool."
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_]
            (let [account (active-account)]
              (active-conn!)
              (ok {:account (:name account) :authenticated true})))}

     {:name "imap_list_mailboxes"
      :description "List the folders of the active account; each has :name, :delimiter, and :attributes."
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_] (ok {:mailboxes (core/list-mailboxes (active-conn!))}))}

     {:name "imap_count"
      :description "Fast message count for `mailbox` (default INBOX) via EXAMINE — no search / header download."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}}
                               :required []})
      :fn (fn [a]
            (let [a (kargs a)
                  conn (active-conn!)
                  mailbox (or (:mailbox a) "INBOX")]
              (ok {:mailbox mailbox
                   :count (:messages (core/examine conn mailbox))})))}

     {:name "imap_search"
      :description "Search messages in `mailbox` (default INBOX) of the active account. `query` is IMAP search criteria, e.g. \"UNSEEN\", \"FROM \\\"a@b.c\\\" SINCE 1-Jan-2024\", or \"ALL\". Returns matching message numbers (UIDs if :uid true)."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :query {:type :string}
                                            :uid {:type :boolean}
                                            :charset {:type :string}}
                               :required [:query]})
      :fn (fn [a]
            (let [a (kargs a)
                  conn (active-conn!)
                  mailbox (or (:mailbox a) "INBOX")
                  q (str (or (:query a) ""))
                  hits (do (core/select conn mailbox)
                           (if (:uid a)
                             (core/uid-search conn q (:charset a))
                             (core/search conn q (:charset a))))]
              (ok {:mailbox mailbox :ids hits :count (count hits)})))}

     {:name "imap_fetch"
      :description "Fetch message data from `mailbox` (default INBOX). `seq` is a message set (\"1\", \"1:10\", or a list). `items` are FETCH item names (uid, flags, rfc822.size, envelope, body[], header...). When :uid is true, `seq` is interpreted as UIDs. Returns normalized message maps."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :seq {}
                                            :uid {:type :boolean}
                                            :items {:type :array :items {:type :string}}}
                               :required [:seq]})
      :fn (fn [a]
            (let [a (kargs a)
                  conn (active-conn!)
                  mailbox (or (:mailbox a) "INBOX")
                  seq (num-or-str (:seq a))
                  items (items-kws (:items a))
                  msgs (do (core/select conn mailbox)
                           (if (:uid a)
                             (apply core/uid-fetch conn seq items)
                             (apply core/fetch conn seq items)))]
              (ok {:mailbox mailbox :messages msgs :count (count msgs)})))}

     {:name "imap_set_flags"
      :description "Set message flags on `seq` in `mailbox` (default INBOX), e.g. \"\\Seen\", \"\\Flagged\". `op` is +flags (default), -flags, or flags. Mutation — requires the account to be writable."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :seq {}
                                            :flags {:type :array :items {:type :string}}
                                            :op {:type :string :enum ["+flags" "-flags" "flags"]}}
                               :required [:seq :flags]})
      :fn (fn [a]
            (let [a (kargs a)]
              (guard-writable!)
              (let [conn (active-conn!)
                    mailbox (or (:mailbox a) "INBOX")
                    op (or (:op a) :+flags)
                    op (if (keyword? op) op (keyword (name op)))]
                (core/select conn mailbox)
                (core/set-flags conn (num-or-str (:seq a)) op (flags-kws (:flags a))))
              (ok {:ok true})))}

     {:name "imap_delete"
      :description "Delete messages `seq` from `mailbox` (default INBOX): STORE \\Deleted + EXPUNGE. Mutation — requires the account to be writable."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :seq {}}
                               :required [:seq]})
      :fn (fn [a]
            (let [a (kargs a)]
              (guard-writable!)
              (let [conn (active-conn!)
                    mailbox (or (:mailbox a) "INBOX")]
                (core/select conn mailbox)
                (core/delete-messages conn (num-or-str (:seq a))))
              (ok {:deleted true})))}

     {:name "imap_move"
      :description "Move messages `seq` from `mailbox` (default INBOX) to `destination`. Mutation — requires the account to be writable."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :destination {:type :string}
                                            :seq {}}
                               :required [:seq :destination]})
      :fn (fn [a]
            (let [a (kargs a)]
              (guard-writable!)
              (let [conn (active-conn!)
                    mailbox (or (:mailbox a) "INBOX")]
                (core/select conn mailbox)
                (core/move conn (num-or-str (:seq a)) (str (:destination a))))
              (ok {:moved true})))}

     {:name "imap_move_sender"
      :description "Move ALL messages from one `sender` (bare email address, no spaces) in `mailbox` (default INBOX) to `destination` (e.g. \"shitcan\"). Uses server-side FROM search (fast, no full-mailbox scan) then a chunked UID MOVE with retry. With `dry_run` true it only returns the matched UID count without moving. Mutation on apply — requires the account to be writable unless dry_run."
      :schema (json/write-str {:type :object
                               :properties {:sender {:type :string}
                                            :mailbox {:type :string}
                                            :destination {:type :string}
                                            :dry_run {:type :boolean}}
                               :required [:sender :destination]})
      :fn (fn [a]
            (let [a (kargs a)
                  sender (str (or (:sender a) ""))
                  mailbox (or (:mailbox a) "INBOX")
                  dest (str (or (:destination a) ""))]
              (when (str/blank? sender)
                (throw (ex-info "sender is required" {})))
              (when (str/blank? dest)
                (throw (ex-info "destination is required" {})))
              (when (str/includes? sender " ")
                (throw (ex-info "sender must not contain spaces (Gmail rejects IMAP literals)"
                                {:sender sender})))
              (let [conn (active-conn!)
                    _ (core/select conn mailbox)
                    uids (core/uid-search-from conn sender)]
                (if (:dry_run a)
                  (ok {:mailbox mailbox :sender sender
                       :found (count uids)})
                  (do (guard-writable!)
                      (let [res (core/uid-move-chunked conn dest uids)]
                        (ok (merge {:mailbox mailbox :sender sender
                                    :found (count uids)}
                                   res))))))))}

     {:name "imap_copy"
      :description "Copy messages `seq` from `mailbox` (default INBOX) to `destination`. Mutation — requires the account to be writable."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :destination {:type :string}
                                            :seq {}}
                               :required [:seq :destination]})
      :fn (fn [a]
            (let [a (kargs a)]
              (guard-writable!)
              (let [conn (active-conn!)
                    mailbox (or (:mailbox a) "INBOX")]
                (core/select conn mailbox)
                (core/copy conn (num-or-str (:seq a)) (str (:destination a))))
              (ok {:copied true})))}

     {:name "imap_append"
      :description "APPEND `content` into `mailbox` (raw RFC822 message text; flags optional, e.g. \\Seen). Mutation — requires the account to be writable."
      :schema (json/write-str {:type :object
                               :properties {:mailbox {:type :string}
                                            :content {:type :string}
                                            :flags {:type :array :items {:type :string}}}
                               :required [:mailbox :content]})
      :fn (fn [a]
            (let [a (kargs a)]
              (guard-writable!)
              (core/append (active-conn!)
                           (str (:mailbox a))
                           (str (:content a))
                           :flags (flags-kws (:flags a)))
              (ok {:appended true})))}
     ]))

;; --- server ----------------------------------------------------------------

(defn mcp-server []
  (load-config!)
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        ^io.modelcontextprotocol.server.McpAsyncServer server
        (-> (McpServer/async transport-provider)
            (.serverInfo "grog-imap" "0.1.0")
            (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
            (.build))]
    (doseq [t (build-tools)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))