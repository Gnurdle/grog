(ns grog.chat
  "Headless chat core: one chat's mutable state plus an event stream.

  This is the bottom of the client/server split. The UI transcript subscribes
  and paints; `grog-server` publishes these same events over the wire and a
  thin client subscribes instead. Local and remote are the same code with a
  different transport, which is the whole point of putting the stream down
  here.

  **Seam rule: this namespace declares no `:import` at all.** Verify with
  `grep -cE '^\\s*\\(:import' src/grog/chat.clj` → must be 0. The moment a
  windowing toolkit is pulled in, the boundary has leaked and no headless
  server can be built on top of this file.

  (The check targets the *import form* rather than naming the forbidden
  packages — writing those names in this docstring would otherwise trip the
  test, so the rule would fail on its own documentation.)

  Named `grog.chat`, not `grog.session` (`grog.session` is the project-lock ns).
  This core manages *chats* (`chatId`-scoped; one ECA per project, many chats
  per ECA) while the *server* owns projects.

  Event envelope — the wire format:

      {:session <uuid>   ; this chat's id
       :chatId  <str>    ; ECA chat id, nil until a prompt mints one
       :user    <str>    ; \"local\" until multiuser lands
       :type    <kw>     ; :content :status :trust :approval :question
                          ; :user :clear :line
                          ; (normalized into the doc's
                          ;  :text|:thinking|:tool|… vocabulary)
       :at      <epoch-ms>
       ;; type-specific
       :value   ...}"
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.core :as core]
            [grog.eca :as eca]
            [grog.eca-config :as ecacfg]
            [grog.models :as models]
            [grog.project-dialog :as project-dialog]
            [grog.secrets :as secrets]
            [grog.cancel :as cancel]))

;; ---------------------------------------------------------------------------
;; Session state
;; ---------------------------------------------------------------------------

(declare watch!)

(defn make-state
  "All mutable state for one chat, as a plain map of atoms.

  Every atom here is something BOTH the local GUI and a remote client must
  observe — which is why it belongs in the core.

  Keys (initial values come from the caller, so `build-session!` keeps its
  existing expressions for `:chat-id` / `:model`):

    :project :chat-id :status :model :trust :usage :connected :running?
    :history :last-sent :pending-steer     — chat-loop state
    :subs                                  — event-stream subscribers

  The core calls it `:trust`; `build-session!` keeps the local name `yolo-ref`
  so its downstream references don't churn."
  [{:keys [project chat-id model]}]
  (let [st {:project   (some-> project str)
            :chat-id   (atom chat-id)
            :status    (atom "idle")
            :model     (atom model)
            :trust     (atom false)
            :usage     (atom {:turn-tokens 0 :turn-cost 0.0
                              :session-tokens 0 :session-cost nil})
            :connected (atom false)
            :running?  (atom false)
            :history   (atom [])
            :last-sent (atom nil)
            :pending-steer (atom nil)
            ;; Pending tool-approval promises, keyed by ECA tool-call id. See
            ;; `answer-approval!` — the core blocks here, a client answers.
            :approvals (atom {})
            ;; subscribers to this chat's event stream
            :subs      (atom #{})}]
    ;; `running?` drives every client's busy indicator (the tab dot and the
    ;; status bar). The server owns the turn lifecycle — it is set when a prompt
    ;; is queued and cleared when the turn finishes — so bridge it into the
    ;; event stream. Without this a client that optimistically marks itself busy
    ;; on send never learns the turn ended: the dot pulsed forever, the status
    ;; bar sat on "streaming", and the toolbar's Stop button stayed enabled.
    (watch! st (:running? st) :running)
    st))

(defn snapshot
  "Dereference the state to plain data — the read-only view a status bar or a
  remote client needs. No atoms cross the boundary."
  [state]
  {:project   (:project state)
   :chat-id   @(:chat-id state)
   :status    @(:status state)
   :model     @(:model state)
   :trust     @(:trust state)
   :usage     @(:usage state)
   :connected @(:connected state)
   :running?  @(:running? state)})

;; ---------------------------------------------------------------------------
;; Event stream
;; ---------------------------------------------------------------------------

(defn subscribe!
  "Register `f` to receive this chat's events. Returns `f` so a caller can
  pass it straight to `unsubscribe!`."
  [state f]
  (swap! (:subs state) (fnil conj #{}) f)
  f)

(defn unsubscribe!
  "Remove a subscriber registered with `subscribe!`. Never throws if absent."
  [state f]
  (swap! (:subs state) disj f)
  nil)

(defn- stamp
  "Stamp `event` with this chat's wire envelope: session/chat ids, acting user,
  timestamp. Pure data — no fan-out."
  [state event]
  (assoc event
         :session (:project state)
         :chatId  @(:chat-id state)
         :user    "local"
         :at      (System/currentTimeMillis)))

(def ^:private !tally
  "Measurement of what actually flows through the stream (the Step-3
  instrumentation): counts by event key. `:content` events are sub-keyed by
  ECA's content type (`[:content \"text\"]`, `[:content \"toolCallRun\"]`, …)
  because normalizing raw content into the doc's `:text|:thinking|:tool|…`
  vocabulary is exactly the decision this tally is gathering evidence for."
  (atom {}))

(defn- tally-key
  "Counting key for one event: type, with `:content` split by ECA content type."
  [ev]
  (if (= :content (:type ev))
    [:content (str (or (:type (:content ev)) "?"))]
    (:type ev)))

(defn event-tally
  "Snapshot of the per-key event counts (plain data — deref, don't mutate)."
  []
  @!tally)

(defn reset-tally!
  "Zero the event counters (before a measurement run)."
  []
  (reset! !tally {})
  nil)

(defn publish!
  "Stamp `event` with this chat's envelope and fan it out to subscribers.

  A subscriber that throws cannot break the chat or its siblings — the error is
  swallowed on purpose, because a painting bug must not kill a prompt turn.
  Returns the stamped event so the caller can log it too.

  Every event is also counted in `!tally` (see `event-tally`) — one `swap!` on
  a private atom, off every subscriber's critical path.

  This is the one function the server will re-implement as a network write."
  [state event]
  (let [ev (stamp state event)]
    (swap! !tally update (tally-key ev) (fnil inc 0))
    (doseq [f @(:subs state)]
      (try (f ev) (catch Throwable _ nil)))
    ev))

(defn watch!
  "Bridge an existing state atom into the event stream: when `ref` changes to
  `v`, publish `{:type t :value v}`.

  This is how `:status`/`:model`/`:trust`/`:usage` become observable without
  threading callbacks through every call site — the atoms stay where they are
  and the stream does the broadcasting. Returns the watch key so the caller can
  `unwatch!` it when the chat closes.

  Only real changes are published: `reset!` notifies watches even when the value
  is unchanged, and re-announcing the same value would put pointless traffic on
  the wire (an ECA `idle` status, say, arrives more than once a turn)."
  [state ref type-kw]
  (let [k (keyword (str "grog.chat." (name type-kw)))]
    (add-watch ref k
               (fn [_ _ old new]
                 (when (not= old new)
                   (publish! state {:type type-kw :value new}))))
    k))

(defn unwatch!
  "Remove a watch installed by `watch!`."
  [state ref type-kw]
  (remove-watch ref (keyword (str "grog.chat." (name type-kw))))
  nil)

;; ---------------------------------------------------------------------------
;; Tool-approval round trip — a blocking core
;; ---------------------------------------------------------------------------

;; NOTE: this is a REQUEST/RESPONSE, not an event — clients `answer!` it.
;;
;; The ECA reader thread does NOT block on approval: ECA holds the tool call
;; open until grog answers, so nothing here waits. A client must therefore not
;; show the decision dialog on the reader thread.
;;
;; Therefore the registry holds a RESPOND CLOSURE, not a promise: answering
;; later runs the right ECA action (approve / approve-and-allow / reject /
;; yolo), with `sid`/`chat-id` captured in the closure. That keeps the reader
;; free — so other events, and other chats sharing one ECA connection, keep
;; flowing while a human reads a dialog.
;;
;; A fully async turn loop with no per-call closures would be cleaner under
;; real concurrency, but it rewrites `handle-turn!` / `chat-worker!`. Not needed
;; while same-project concurrency is unresolved, so the current design takes the
;; smallest correct cut.

(defn answer-approval!
  "Deliver a client's decision for a pending tool approval.

  `decision` is one of `:approve` `:approve-tool` `:reject` `:yolo`. The entry
  is consumed on first answer, so a duplicate, a second client racing, or an
  answer for an unknown/already-settled id is a harmless no-op — it must never
  blow up a turn."
  [state id decision]
  (when-let [{:keys [respond]} (get @(:approvals state) id)]
    (swap! (:approvals state) dissoc id)
    (respond decision))
  nil)

;; ---------------------------------------------------------------------------
;; ECA event handling
;; ---------------------------------------------------------------------------

(defn make-event-handler
  "Headless ECA event handler (runs on the ECA reader thread).

  Domain work lives here — echo suppression, assistant-text accumulation,
  finish detection, model-catalog capture, tool-approval bookkeeping.
  Everything that needs pixels is *published*; a client subscribes and paints.

  Events emitted through `publish!`:

    :content   every non-echoed `chat/contentReceived` (including `usage` — the
               subscriber decides whether to render it or fold it into the
               footer). Carried raw; normalizing it into the doc's
               `:status|:assistant|:thinking|:tool|…` vocabulary happens
               downstream, once we've measured what actually flows.
    :status    published AFTER finish-on-idle
    :trust     YOLO switch flipped by an approval answer
    :approval  a question needing a client answer — see `answer-approval!`

  `hooks` — plain fns injected by the caller so this namespace stays free of any UI toolkit:

    :resend-steer (fn [s])  re-issue a steer ECA never consumed"
  [state sid {:keys [resend-steer]}]
  (let [assistant-acc (atom "")
        ;; a steer is consumed when ECA echoes it back; resend undelivered steers
        ;; on any idle/finished transition (protocol fallback).
        finish! (fn []
                  (when-let [s @(:pending-steer state)]
                    (reset! (:pending-steer state) nil)
                    (when resend-steer (resend-steer s)))
                  ;; the assistant reply is done — persist it to the project
                  ;; dialog (best effort; ignore if no active project).
                  (let [reply (str/trim (str @assistant-acc))]
                    (reset! assistant-acc nil)
                    (when (seq reply)
                      (try
                        (project-dialog/append-turn! :assistant reply)
                        (catch Throwable e
                          (binding [*out* *err*]
                            (println "[grog] dialog append assistant error:"
                                     (.getMessage e)))))))
                  (reset! (:running? state) false))]
    (fn [method params]
      (case method
        "chat/contentReceived"
        (let [content (:content params)
              echo? (and (= "text" (:type content))
                         @(:last-sent state)
                         (= (str/trim (str (:text content)))
                            (str/trim (str @(:last-sent state)))))]
          ;; the steer echo (from ECA consuming the steer) confirms it was
          ;; accepted — mark it consumed so the finish-path doesn't resend it.
          (when (and echo? @(:pending-steer state)
                     (= (str/trim (str (:text content)))
                        (str/trim (str @(:pending-steer state)))))
            (reset! (:pending-steer state) nil))
          (when-not echo?
            ;; accumulate the assistant reply text for the project dialog
            (when (and (= "text" (:type content))
                       (seq (str (:text content))))
              (swap! assistant-acc str (str (:text content))))
            (publish! state {:type :content :content content})
            (when (= "finished" (:state content))
              (finish!))
            ;; manual-approval tool call -> in YOLO mode auto-approve (everything
            ;; goes, no dialog); otherwise ask. We register a respond closure and
            ;; return at once, so the reader keeps dispatching while a human
            ;; reads the dialog — the caller's modal dialog stays on the EDT.
            (when (and (= "toolCallRun" (:type content))
                       (true? (:manualApproval content)))
              (if @(:trust state)
                (eca/approve! sid @(:chat-id state) (:id content))
                (let [id (:id content)]
                  (swap! (:approvals state) assoc id
                         {:content content
                          :respond
                          (fn [decision]
                            (case decision
                              :approve      (eca/approve! sid @(:chat-id state) id)
                              :approve-tool (do (ecacfg/approve-tool! (str (:name content)))
                                                (eca/approve! sid @(:chat-id state) id))
                              :yolo         (do (reset! (:trust state) true)
                                                (publish! state {:type :trust :value true})
                                                (eca/set-trust! sid @(:chat-id state) true)
                                                (eca/approve! sid @(:chat-id state) id))
                              ;; :reject, a dismissed dialog, or anything
                              ;; unknown -> safe reject rather than run the tool
                              (eca/reject! sid @(:chat-id state) id)))})
                  (publish! state {:type :approval
                                   :id id
                                   :name (str (:name content))
                                   :args (:arguments content)
                                   :summary (:summary content)}))))))

        "chat/statusChanged"
        (let [st (str (:status params))]
          (when (= "idle" st)
            (finish!))
          (publish! state {:type :status :value st}))

        ;; ECA re-syncs its model catalog on startup/login: remember the full set
        ;; of provider-qualified ids so model qualification is exact.
        "config/updated"
        (when-let [ms (get-in params [:chat :models])]
          (models/register-eca-catalog! ms))

        nil))))

(defn parse-cost
  "Parse an ECA cost string (e.g. \"0.03\") to a double, or nil when absent or
  unparseable — the model has no price table, so ECA omits the cost fields."
  [s]
  (when s
    (try (Double/parseDouble (str s)) (catch Throwable _ nil))))

(def ^:private provider-env-accounts
  "Provider API keys read from the OS keyring (/secret) and injected into the
  ECA child process env at launch — ECA config references them only via
  `${env:...}` so no literal key ever lands in a config file."
  ["OPENROUTER_API_KEY" "MOONSHOT_API_KEY" "XAI_API_KEY"])

(defn provider-env
  "Per-process env vars for the ECA server.

  `GROG_LLM_API_KEY` is ALWAYS set, from grog's own resolved LLM key (`:llm
  :api-key`, else the `LLM_API_KEY` secret). The provider entry grog generates
  references `${env:GROG_LLM_API_KEY}`, so the base case is: enter ONE secret
  (`/secret set LLM_API_KEY …`) and chat — no eca/config.json, no provider block,
  and no key ever written to a file.

  The provider-specific accounts are still injected when present, for people who
  keep a separate key per provider."
  []
  (let [keys (into {} (keep (fn [acct]
                              (when-let [v (secrets/get-secret acct)]
                                [acct v])))
                   provider-env-accounts)]
    (if-let [k (config/llm-api-key)]
      (assoc keys "GROG_LLM_API_KEY" k)
      keys)))

;; ---------------------------------------------------------------------------
;; ECA server→client requests
;; ---------------------------------------------------------------------------

(defn make-request-handler
  "Handle ECA server→client requests. The one that matters is
  `chat/askQuestion` — the LLM asking a question — re-emitted as a `:question`
  event carrying an `:answer` callback:

      {:type :question :prompt … :options […] :allowFreeform? bool
       :answer (fn [{:cancelled bool :answer str|nil}])}

  A subscriber (the GUI's modal dialog) replies through `:answer`; this fn
  BLOCKS on the ECA reader thread until then — which is what we want: ECA is
  waiting for the response, so the answer has to arrive while the reader still
  holds the turn.

  Wedge-safety, because a blocked reader thread wedges the whole chat: a
  question is only waited on if it was answered DURING the fan-out — i.e. the
  answering subscriber answers before it returns (the GUI's modal dialog
  does). A subscriber that ignores the
  event (a status-bar-only client), one that throws, or no subscribers at all
  all fall through to `{:cancelled true :answer nil}` immediately; the reader
  can never hang. An answer arriving ASYNC (after the subscriber returns) is
  too late for this request — see the approvals note above for the async
  turn-loop alternative.

  Everything else gets safe defaults (empty diagnostics / empty result)."
  [state]
  (fn [method params]
    (case method
      "chat/askQuestion"
      (let [prompt (or (some-> (:prompt params) str str/trim not-empty)
                       (some-> (:message params) str str/trim not-empty)
                       (some-> (:question params) str str/trim not-empty)
                       (pr-str params))
            options (when (sequential? (:options params)) (vec (:options params)))
            allow-freeform? (not (false? (:allowFreeform params)))
            cancelled {:cancelled true :answer nil}
            p (promise)
            ev (stamp state {:type :question
                             :prompt prompt
                             :options options
                             :allowFreeform? allow-freeform?
                             ;; first delivery wins; answered before returning
                             :answer (fn [res] (deliver p res))})]
        (doseq [f @(:subs state)]
          (when-not (realized? p)
            (try (f ev) (catch Throwable _ nil))))
        {:result (if (realized? p) (deref p) cancelled)})

      "editor/getDiagnostics"
      {:result {:diagnostics []}}

      {:result {}})))

;; ---------------------------------------------------------------------------
;; Turn pipeline: queue → worker → handle-turn!
;; ---------------------------------------------------------------------------

(defn handle-turn!
  "Echo user input, route slash commands, or hand an LLM turn to `:send`.
  Returns the updated history; the worker stores it back into `:history`.

  The domain half of a turn lives here: the `/eca-model` / `/yolo` patterns,
  `grog.core`'s slash-command dispatch, history bookkeeping. Everything with
  pixels is either PUBLISHED (`:user` echo, `:clear` transcript wipe) or
  DELEGATED through `hooks`:

    :send       (fn [history text] -> history)  hand an LLM turn over
    :set-model! (fn [name])  /eca-model — qualify, push to ECA, footer, status
    :set-yolo!  (fn [on?])   /yolo (nil toggles)
    :console    (fn [] -> Writer)  called once per turn; `*out*`/`*err*` bind
                 to it so slash-command output lands in the transcript. The
                 GUI supplies a fresh `transcript/console-writer` (it buffers
                 until flush, then appends a status line); nil keeps the
                 process streams — what a headless server wants.
    :quit!      (fn [])  /quit; defaults to System/exit

  The console binding wraps the whole route (the `:user` echo publishes
  before it, as before), because `route-slash-command!` prints its handlers'
  output to `*out*`."
  [state {:keys [send set-model! set-yolo! console quit!]} text]
  (cancel/clear!)
  ;; echo the user's input (prompt or command) as a bubble. `/secret KEY VALUE`
  ;; carries a credential: publish a MASKED form so the value never reaches the
  ;; transcript, the persisted chat DB, or (via the server) another client.
  ;; See grog.secrets/redact-secret-command.
  (when (seq (str/trim text))
    (publish! state {:type :user :text (secrets/redact-secret-command (str text))}))
  (let [route (fn []
                (cond
                  (re-matches #"(?i)^/eca-model\s+(.+)$" (str/trim text))
                  (let [m (re-matches #"(?i)^/eca-model\s+(.+)$" (str/trim text))]
                    (when set-model! (set-model! (str/trim (second m))))
                    @(:history state))

                  (re-matches #"(?i)^/yolo(?:\s+(on|off))?$" (str/trim text))
                  (let [m (re-matches #"(?i)^/yolo(?:\s+(on|off))?$" (str/trim text))
                        on? (when (second m) (= "on" (str/lower-case (second m))))]
                    (when set-yolo! (set-yolo! on?))
                    @(:history state))

                  :else
                  (case (core/route-slash-command! text)
                    :grog.core/quit
                    (do (if quit! (quit!) (System/exit 0))
                        @(:history state))

                    :grog.core/clear
                    (do
                      ;; wipe the transcript, and drop any trust (yolo)
                      ;; auto-approve so a fresh transcript starts clean
                      (publish! state {:type :clear})
                      (when set-yolo! (set-yolo! false))
                      (println "History cleared.")
                      [])

                    :grog.core/handled
                    @(:history state)

                    :grog.core/llm
                    (send @(:history state) text))))]
    (if-let [w (when console (console))]
      (binding [*out* w *err* w] (route))
      (route))))

(defn chat-worker!
  "Process the input queue on a background thread, returning the Thread
  (the caller starts it). Each line goes through `handle-turn!` with `hooks`;
  the result lands in `:history`. `running?` inside the state is managed by
  the ECA event lifecycle.

  A throw inside one turn must never kill the worker — that would silently
  swallow every message queued after it. The error is logged to stderr (the
  per-instance log) AND published as a `:line` so the transcript says so,
  loudly, instead of going mute."
  [state ^java.util.concurrent.LinkedBlockingQueue queue hooks]
  (Thread.
   (fn []
     (loop []
       (when-let [text (.take queue)]
         (binding [*out* *err*]
           ;; never log a credential: mask `/secret KEY VALUE` before printing
           (println "worker take:" (pr-str (secrets/redact-secret-command (str text)))))
         (try
           (reset! (:history state) (handle-turn! state hooks text))
           (catch Throwable e
             (binding [*out* *err*]
               (println "worker turn error:" (.getMessage e))
               (println (with-out-str (.printStackTrace e))))
             (publish! state {:type :line
                              :text (str "[grog] internal error handling that message: "
                                         (.getMessage e))})))
         (recur))))))
