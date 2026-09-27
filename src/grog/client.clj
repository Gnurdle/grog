(ns grog.client
  "The client boundary (doc/server-and-client.md §3).

  The Swing UI is written against this namespace and never calls ECA/MCP
  directly again — that is the whole point of the seam. Adapters implement the
  `Client` protocol:

    `grog.client.local`  — in-process, exactly today's behavior (Phase 2)
    `grog.client.remote` — JSON-RPC to `grog-server` (Phase 3; not built yet)

  The UI calls only the delegating fns below, so swapping local for remote is
  an install call (`set-impl!`) instead of a rewrite. With no adapter
  installed, the first call lazily installs the local one — zero-config today,
  explicit choice tomorrow.

  Interface notes vs doc/server-and-client.md §3:
    * `subscribe!`/`unsubscribe!` are SESSION-SCOPED (`id` first): events are
      per-session and the GUI paints into a per-tab stream; a global
      fan-in can be layered on top later without changing this shape.
    * `answer!` takes `{:approval-id … :decision …}` — matching
      `grog.chat/answer-approval!`'s registry. Question events carry their own
      `:answer` callback today (in-process); Phase 3 must route that as a
      request/response — noted, not yet.
    * `open!` returns `{:id :project :state}` where `:state` is the local
      `grog.chat` state map (atoms the status bar renders). The remote adapter
      will return no `:state` — the UI must then read `sessions`/`snapshot` +
      events. That is the one local-only convenience in this interface.

  Seam rule: **zero `:import` forms in this file** — it is pure delegation.
  Adapters may import transport/process classes; this boundary may not pull in
  a windowing toolkit (or anything else), or the seam is a lie."
  )

(defprotocol Client
  "One adapter's implementation of the grog client API."
  (open [this opts])
  ;; opts — local adapter:
  ;;   :project  (required) project name this session attaches to
  ;;   :console  (fn [] -> java.io.Writer) per-turn *out*/*err* sink; nil = process streams
  ;;   :quit!    (fn []) /quit handler; nil = System/exit
  ;;   :trace-fn (fn [direction frame]) ECA stdio tracer (view-owned debug)
  ;;   :on-state (fn [state]) called once with the live grog.chat state map
  ;;                (local-only escape hatch — the server uses it to wire its
  ;;                console publisher; never crosses the wire)
  ;; returns {:id .. :project .. :state <grog.chat state, local only>}
  (close [this id])
  (sessions [this])   ; -> vector of (snapshot) maps, each with :id
  (session [this id]) ; -> snapshot map or nil
  (connect! [this id])     ; eager ECA connect (tab open does this today)
  (disconnect! [this id])  ; alias of close, kept for the UI's :disconnect! slot
  (prompt! [this id text]) ; queue a normal prompt
  (steer! [this id text])  ; steer a running turn
  (stop! [this id])
  (answer! [this id ans])  ; ans = {:approval-id .. :decision ..}
  (set-model! [this id model])
  (set-trust! [this id on?])
  (subscribe! [this id f])   ; f receives stamped events, per session
  (unsubscribe! [this id f]))

(defonce ^:private !impl (atom nil))

(defn set-impl!
  "Install the active adapter (local, remote, …). Returns it. Installing nil
  resets to lazy-local."
  [c]
  (reset! !impl c)
  c)

(defn impl
  "The active adapter, or nil when none has been installed explicitly."
  []
  @!impl)

(defn- need
  "The active adapter, lazily installing the local one on first use so the
  single-process path needs no ceremony (today's behavior, unchanged)."
  []
  (or @!impl
      (do ((requiring-resolve 'grog.client.local/install!))
          @!impl)
      (throw (ex-info "grog.client: no adapter installed" {}))))

;; --- delegating fns (the API the UI calls) --------------------------------

(defn open! [opts] (open (need) opts))
(defn close! [id] (close (need) id))
(defn sessions [] (sessions (need)))
(defn session [id] (session (need) id))
(defn connect! [id] (connect! (need) id))
(defn disconnect! [id] (disconnect! (need) id))
(defn prompt! [id text] (prompt! (need) id text))
(defn steer! [id text] (steer! (need) id text))
(defn stop! [id] (stop! (need) id))
(defn answer! [id ans] (answer! (need) id ans))
(defn set-model! [id model] (set-model! (need) id model))
(defn set-trust! [id on?] (set-trust! (need) id on?))
(defn subscribe! [id f] (subscribe! (need) id f))
(defn unsubscribe! [id f] (unsubscribe! (need) id f))