#!/usr/bin/env bb
;; `/clear` must reset the CONTEXT, not just the screen. No model, no network.
;;
;;
;;   GROG_MCP_ENABLED=0 clojure -M scripts/clear-context-test.clj
;;
;; (`GROG_MCP_ENABLED=0` is an env var, so it has to be set by the caller; unset,
;; the run still passes but the first turn pays for an MCP child JVM handshake.)
;; The LLM call is stubbed (`transport/stream-chat!`) so nothing leaves the box,
;; and the config home is a throwaway under /tmp so the run cannot read or write
;; the real ~/.config/grog. `GROG_MCP_ENABLED=0` keeps it from spawning an MCP
;; child JVM: the local toolbelt is enough to drive a turn.
;;
;; Why this test exists. On 2026-10-27 the report was, in one line: "`/clear`
;; should reset the context of the session — currently it's not". Two independent
;; defects made that true, and neither was visible from the other:
;;
;;   1. THE COMMAND OFTEN NEVER ARRIVED. The GUI sends what its input box holds,
;;      and Enter leaves a trailing newline, so the spine got `"/clear\n"`. The
;;      exact-set match in `route-slash-command!` missed it, the line fell through
;;      to `::llm`, and the model was asked to ANSWER the word `/clear`. The log
;;      of the session says it out loud:
;;          worker take: "/clear\n"        ← the newline
;;          loop turn: :done steps= 1      ← a real LLM turn, not a command
;;      A later tab in that same log sent a clean `/clear` and produced NO
;;      `loop turn:` line — the command path runs no turn, which is how the two
;;      are told apart here.
;;   2. EVEN A MATCHING `/clear` DID NOT RESET WHAT THE MODEL SEES.
;;      `grog.chat/handle-turn!` dropped its own `:history` and published the
;;      transcript wipe, but the desktop loop replays `grog.client.loop`'s
;;      `!messages` atom — the actual conversation, every tool result in it —
;;      plus the `tool_search`-loaded tool set and the session's todo list. All
;;      three survived, so the transcript went blank while the next turn still
;;      carried everything. A wipe that fails silently is the worst kind: the UI
;;      says cleared, and the token bill proves otherwise.
;;
;; The end-to-end check is the one that matters: after `/clear`, the NEXT request
;; the loop sends must contain no earlier turn.
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[grog.agent.transport :as transport]
         '[grog.chat :as chat]
         '[grog.client :as client]
         '[grog.client.loop :as loopc]
         '[grog.core :as core])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

;; --- 1. the command arrives: `/clear\n` is `/clear` ----------------------------
(println "=== 1. a padded command is still the command (the log's \"/clear\\n\") ===")

(check! "/clear routes to ::clear"
        (= :grog.core/clear (core/route-slash-command! "/clear"))
        (pr-str (core/route-slash-command! "/clear")))
(check! "\"/clear\\n\" (what the GUI actually sends) routes to ::clear"
        (= :grog.core/clear (core/route-slash-command! "/clear\n"))
        (pr-str (core/route-slash-command! "/clear\n")))
(check! "\"  /CLEAR  \" too — case and padding are not part of a command"
        (= :grog.core/clear (core/route-slash-command! "  /CLEAR  "))
        (pr-str (core/route-slash-command! "  /CLEAR  ")))
(check! "/fresh behaves the same"
        (= :grog.core/clear (core/route-slash-command! "/fresh\n"))
        (pr-str (core/route-slash-command! "/fresh\n")))
;; THE REGRESSION THIS FIXES: untrimmed, the word fell through to the model.
(check! "REGRESSION GUARD: a padded /clear is NOT sent to the LLM"
        (not= :grog.core/llm (core/route-slash-command! "/clear\n"))
        "a steps=1 turn answering the word /clear is what the user saw")
(check! "…while a sentence that merely CONTAINS /clear stays prose"
        (= :grog.core/llm (core/route-slash-command! "please /clear the cache"))
        (pr-str (core/route-slash-command! "please /clear the cache")))
(check! "quit/exit tolerate the newline as well"
        (= :grog.core/quit (core/route-slash-command! "quit\n"))
        (pr-str (core/route-slash-command! "quit\n")))
(check! "and an ordinary sentence is still prose"
        (= :grog.core/llm (core/route-slash-command! "hello\n"))
        (pr-str (core/route-slash-command! "hello\n")))

;; --- 2. the core asks the adapter to wipe the engine, not just the UI ---------
(println "=== 2. handle-turn! runs the adapter's :clear! hook ===")

(let [st (chat/make-state {:project "p" :chat-id "c" :session-id "p#1" :model "m"})
      !cleared (atom 0)
      !events (atom [])]
  (chat/subscribe! st (fn [e] (swap! !events conj (:type e))))
  (reset! (:history st) [{:user "earlier" :assistant "answer"}])
  (let [h (chat/handle-turn! st
                             {:send (fn [h _ _] h) :clear! (fn [] (swap! !cleared inc))}
                             "/clear\n" nil)]
    (check! "the core's :history is dropped" (empty? h) (pr-str h))
    (check! "the adapter's :clear! hook ran exactly once" (= 1 @!cleared) (pr-str @!cleared))
    (check! "the transcript wipe is still published for every client"
            (some #{:clear} @!events) (pr-str @!events))))

;; An adapter that supplies no :clear! must still work — no NPE, and the core
;; still drops what it owns.
(let [st (chat/make-state {:project "p" :chat-id "c" :model "m"})]
  (reset! (:history st) [{:user "earlier" :assistant "answer"}])
  (check! "a missing :clear! hook is not an error"
          (empty? (chat/handle-turn! st {:send (fn [h _ _] h)} "/clear" nil))))

;; yolo is dropped by a clear, as before — a fresh transcript starts untrusting.
(let [st (chat/make-state {:project "p" :chat-id "c" :model "m"})
      !trust (atom :untouched)]
  (reset! (:trust st) true)
  (chat/handle-turn! st {:send (fn [h _ _] h) :set-yolo! (fn [on?] (reset! !trust on?))}
                     "/clear" nil)
  (check! "/clear still leaves trust (yolo) off" (false? @!trust) (pr-str @!trust)))

;; --- 3. END TO END: the next request carries no earlier turn ------------------
(println "=== 3. the real loop: after /clear the wire carries no prior turn ===")

(def !requests (atom []))
(def !n (atom 0))

(defn- stub-transport!
  "Replace the HTTP streaming call with a canned text answer, RECORDING the
  messages it was handed. Those messages ARE the session's context — which is
  exactly what `/clear` has to reset, so this is the quantity under test."
  []
  (alter-var-root #'transport/stream-chat!
                  (fn [_]
                    (fn [{:keys [messages] :as _opt}]
                      (swap! !requests conj (vec messages))
                      (let [i (swap! !n inc)]
                        {:text (str "reply " i) :reasoning nil :tool-calls nil
                         :malformed nil :usage nil :finish-reason "stop"})))))

(defn- contents-of [msgs] (mapv :content msgs))

(defn line-events [events]
  "The `:line` events — the transcript's status lines, where a command's own
  confirmation lands. Written as a named fn, not `#(...)`, because the check that
  uses it needs another anonymous fn inside it and the reader rejects `#(#…)`."
  [events]
  (filterv #(= :line (:type %)) events))

(defn- wait-until!
  "Poll `pred` until true, up to `timeout-ms`. Returns the final truth.

  WHY POLLING AND NOT `running?`: `prompt!` is only a queue write — the turn runs
  on the session's worker thread — so a check that reads state before the worker
  is idle races it. And `running?` is useless as a start signal: it is still
  false the instant after `prompt!` returns, and the FIRST turn additionally
  pays a lazy MCP connect (seconds). Waiting on THE REQUEST COUNT MOVING (or on
  the event a command publishes) is the honest signal, because it is the thing
  the check is about."
  [pred timeout-ms]
  (loop [n 0]
    (if (pred)
      true
      (when (< n (/ timeout-ms 50))
        (Thread/sleep 50)
        (recur (inc n))))))

(let [tmp (io/file (System/getProperty "java.io.tmpdir") "grog-clear-test")
      _ (.mkdirs (io/file tmp "projects" "clear-context-test"))]
  (println (str "config home in use: "
                (try (let [f (requiring-resolve 'grog.config/config-home-dir)]
                       (.getPath ^java.io.File (f)))
                     (catch Throwable _ "?"))
                "  — the checks below stub the transport, so no key, no network,"))
  (println "     and no config value is read for the model call itself.")
  (stub-transport!)
  (loopc/install!)
  (let [opened (client/open! {:project "clear-context-test"})
        id (:id opened)
        !events (atom [])]
    (client/subscribe! id (fn [e] (swap! !events conj e)))

    ;; turn 1 — a real turn, so there IS a conversation to lose
    (client/prompt! id "first question")
    (check! "turn 1 reached the transport"
            (wait-until! #(pos? (count @!requests)) 120000)
            (str "requests=" (count @!requests)))
    (check! "turn 1 sent its own message"
            (some #(= "first question" %) (contents-of (first @!requests)))
            (pr-str (contents-of (first @!requests))))

    ;; turn 2 — the loop replays turn 1: this is the context under test
    (client/prompt! id "second question")
    (wait-until! #(>= (count @!requests) 2) 120000)
    (check! "turn 2 replays turn 1 (context accumulates, as it should)"
            (some #(= "first question" %) (contents-of (last @!requests)))
            (pr-str (mapv :role (last @!requests))))
    ;; THE TWO ADJACENT DEFECTS, pinned while proving what /clear resets. Both
    ;; were visible in the roles of turn 2's request, and neither is visible in
    ;; the transcript — only in the bill and in a model that cannot quote its own
    ;; last answer:
    ;;   · `run-turn!` returned `:messages` WITHOUT the final assistant text, and
    ;;     the session replays exactly that list — so the model saw its own
    ;;     questions and tool traffic but never its own previous answers.
    ;;   · the session passed that list back as `:history` AND `:system` again, so
    ;;     every turn appended a SECOND copy of the ~1.6k-token system prompt.
    (check! "turn 2 carries turn 1's ASSISTANT reply (the model sees its own answers)"
            (some #(= "reply 1" %) (contents-of (last @!requests)))
            (pr-str (mapv :role (last @!requests))))
    (check! "exactly ONE system message on the wire — not one per turn"
            (= 1 (long (count (filter #(= "system" (:role %)) (last @!requests)))))
            (pr-str (frequencies (mapv :role (last @!requests)))))

    ;; now the command, spelled exactly as the GUI sends it
    (client/prompt! id "/clear\n")
    (check! "the :clear event reached the client (transcript wipe)"
            ;; `some` over the type SET rather than a nested `#()`: the reader
            ;; rejects `#(#…)` and the first attempt at this line was a read error.
            (wait-until! #(boolean (some #{:clear} (mapv :type @!events))) 20000)
            (pr-str (mapv :type @!events)))
    (check! "the spine says WHAT it cleared — a checkable claim, not a hope"
            (wait-until! #(boolean (some (fn [e] (str/includes? (str (:text e)) "context cleared"))
                                         (line-events @!events)))
                         20000)
            (pr-str (mapv :text (line-events @!events))))
    ;; No new request may appear: a command runs no turn. Give the worker time it
    ;; would have taken, then count.
    (Thread/sleep 1500)
    (check! "/clear ran NO LLM turn" (= 2 (count @!requests)) (str "requests=" (count @!requests)))

    ;; turn 3 — nothing from before may be on the wire
    (client/prompt! id "third question")
    (check! "turn 3 reached the transport"
            (wait-until! #(>= (count @!requests) 3) 120000)
            (str "requests=" (count @!requests)))
    (let [cs (contents-of (last @!requests))]
      (check! "turn 3 carries NO earlier user turn"
              (not-any? #(or (= "first question" %) (= "second question" %)) cs)
              (pr-str cs))
      (check! "turn 3 carries NO earlier assistant reply"
              (not-any? #(and (string? %) (str/starts-with? % "reply ")) cs)
              (pr-str cs))
      (check! "turn 3 still asks its own question"
              (some #(= "third question" %) cs) (pr-str cs)))

    (client/close! id)))

(println)
(if (zero? @failures)
  (println "all checks passed")
  (do (println (str @failures " check(s) FAILED"))
      (System/exit 1)))
(shutdown-agents)
