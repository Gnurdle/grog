(ns grog.client
  "The client boundary.

  Clients are written against this namespace and never call ECA/MCP directly —
  that is the whole point of the seam. Adapters implement the `Client` protocol:

    `grog.client.local`  — in-process (the desktop app's path)
    `grog.client.remote` — JSON-RPC to a server over a socket

  The UI calls only the delegating fns below, so swapping local for remote is
  an install call (`set-impl!`) instead of a rewrite. With no adapter
  installed, the first call lazily installs the local one — zero-config local
  by default; installing another adapter is an explicit choice.

  Interface notes:
    * `subscribe!`/`unsubscribe!` are SESSION-SCOPED (`id` first): events are
      per-session and the GUI paints into a per-tab stream; a global
      fan-in can be layered on top without changing this shape.
    * `answer!` takes `{:approval-id … :decision …}` — matching
      `grog.chat/answer-approval!`'s registry. Question events carry their own
      `:answer` callback (in-process); the remote adapter routes that as a
      request/response.
    * `open!` returns `{:id :project :state}` where `:state` is the local
      `grog.chat` state map (atoms the status bar renders). The remote adapter
      returns no `:state` — the UI reads `sessions`/`snapshot` + events
      instead. That is the one local-only convenience in this interface.

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
  (connect! [this id])     ; eager ECA connect (the tab-open path does this)
  (disconnect! [this id])  ; alias of close, kept for the UI's :disconnect! slot
  (prompt! [this id text]) ; queue a normal prompt
  (steer! [this id text])  ; steer a running turn
  (stop! [this id])
  (answer! [this id ans])  ; ans = {:approval-id .. :decision ..}
  (set-model! [this id model] [this id model source])
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
  single-process path needs no ceremony."
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
(defn set-model!
  ([id model] (set-model! (need) id model))
  ([id model source] (set-model! (need) id model source)))
(defn set-trust! [id on?] (set-trust! (need) id on?))
(defn subscribe! [id f] (subscribe! (need) id f))
(defn unsubscribe! [id f] (unsubscribe! (need) id f))