#!/usr/bin/env bb
;; The shell tool's timeout must actually END the call. No model, no network.
;;
;;   clojure -M scripts/shell-hang-test.clj
;;
;; Why this test exists. On 2026-10-26 a `shell` call hung a whole turn. The log
;; showed the tool's timeout having already fired — `timeout? true`, a
;; `[killed after Nms]` marker — and then nothing, forever. The command was the
;; ordinary shape of "start a server and probe it":
;;
;;     python3 /tmp/fixture.py 32768 8099 & sleep 1.5; curl … http://…:8099/…
;;
;; and it left a leaked `bash`/`python3` pair on the machine for an hour. The
;; cause was NOT the timeout: `run-capture` read the child's stdout through a
;; PIPE in a `future`, waited on `.waitFor` with the timeout, and then deref'd
;; that future with NO bound. `destroyForcibly` does not close a pipe whose write
;; end a backgrounded GRANDCHILD still holds, so the reader blocked on a read
;; that could never return, and the turn never came back.
;;
;; The fix reads output from a temp FILE (no pipe, no reader thread, no dangling
;; write end) and kills the child as a TREE. This file proves the properties that
;; matter, each one measured against a real subprocess:
;;
;;   1. THE REPORTED HANG: a command that backgrounds a long-lived grandchild and
;;      exits returns PROMPTLY, with its output — not after the timeout, not
;;      never.
;;   2. THE TIMEOUT STILL WORKS: a genuine foreground hang is killed at the
;;      timeout and reports `timeout? true` with the marker.
;;   3. A LEAKED GRANDCHILD CANNOT OUTLIVE THE TIMEOUT: background a grandchild
;;      AND hang the foreground; the call must return at the timeout, not block
;;      on the pipe the grandchild holds.
;;   4. ORDINARY COMMANDS ARE FAST AND COMPLETE: no backgrounding, no cost —
;;      output intact, exit code intact.
;;   5. BIG OUTPUT IS NOT LOST: a large burst still lands in full (the file is
;;      not a fixed-size pipe buffer).
;;
;; Every check is wall-clock bounded: a regression shows up as a FAIL, never as
;; a test that hangs the suite.
(require '[clojure.string :as str]
         '[grog.agent.tools :as tools])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(def ^:private run-capture #'tools/run-capture)

;; A backgrounded grandchild (`sleep N &`) is reparented to init the moment the
;; shell exits, so it cannot be reaped through the child's process handle — that
;; is what `&` means, not a bug. The tests still must not leave a pile of them
;; lying around, so every sleep uses this distinctive duration and the script
;; reaps leftovers on the way out (including after a failure).
(def ^:private sleep-secs 47)

(defn- cleanup!
  []
  (try
    (let [p (.start (ProcessBuilder. (into-array String
                                                  ["pkill" "-f" (str "sleep " sleep-secs)])))]
      (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS))
    (catch Throwable _)))

(defn- run-bounded
  "Run `run-capture` on a worker thread and give up after `bound-ms`. Returns
  {:ms :res} or {:ms :blocked? true} — so a hang is a FAIL, not a hung suite."
  [cmd timeout-ms bound-ms]
  (let [t0 (System/currentTimeMillis)
        f (future (run-capture ["bash" "-lc" cmd] "/tmp" timeout-ms))
        r (deref f bound-ms ::blocked)]
    (if (= ::blocked r)
      {:ms (- (System/currentTimeMillis) t0) :blocked? true}
      {:ms (- (System/currentTimeMillis) t0) :res r})))

;; --- 1. the reported hang -----------------------------------------------------
(println "=== 1. the reported hang: background a grandchild, then exit ===")

(let [{:keys [ms res blocked?]} (run-bounded (str "sleep " sleep-secs " & echo grandchild-up") 3000 20000)]
  (check! "a backgrounded grandchild does not hang the call"
          (not blocked?)
          (if blocked? "BLOCKED (the reported bug)" (str ms "ms")))
  (check! "…and the foreground output is returned"
          (and res (str/includes? (str (:out res)) "grandchild-up"))
          (pr-str (some-> res :out)))
  (check! "…and it returns well inside the timeout, not at it"
          (and ms (< ms 2500))
          (str ms "ms")))

;; --- 2. the timeout still works ----------------------------------------------
(println "=== 2. a genuine foreground hang is still killed ===")

(let [{:keys [ms res blocked?]} (run-bounded (str "sleep " sleep-secs) 2500 20000)]
  (check! "a real hang is killed and reported as a timeout"
          (and (not blocked?) (:timeout? res))
          (if blocked? "BLOCKED" (pr-str res)))
  (check! "…the marker says so, so the model is not lied to"
          (and res (str/includes? (str (:out res)) "[killed after 2500ms]"))
          (pr-str (some-> res :out)))
  (check! "…and it returns near the timeout, not far past it"
          (and ms (< ms 6000))
          (str ms "ms")))

;; --- 3. a leaked grandchild cannot outlive the timeout ------------------------
(println "=== 3. backgrounded grandchild + a foreground hang ===")

(let [{:keys [ms res blocked?]} (run-bounded (str "sleep " sleep-secs " & echo up; sleep " sleep-secs) 2500 20000)]
  (check! "the call still ends at the timeout, pipe-holder or not"
          (and (not blocked?) (:timeout? res))
          (if blocked? "BLOCKED (grandchild held the pipe)" (pr-str res)))
  (check! "…and the pre-hang output survived"
          (and res (str/includes? (str (:out res)) "up"))
          (pr-str (some-> res :out))))

;; --- 4. ordinary commands are unaffected --------------------------------------
(println "=== 4. ordinary commands: fast, complete, exit code intact ===")

(let [{:keys [ms res blocked?]} (run-bounded "echo one; echo two; exit 3" 5000 20000)]
  (check! "stdout is captured in order" (= "one\ntwo\n" (:out res))
          (pr-str (some-> res :out)))
  (check! "the exit code is preserved" (= 3 (:exit res)) (pr-str (some-> res :exit)))
  (check! "and it is not slow" (and ms (< ms 1500)) (str ms "ms")))

;; --- 5. a large burst is not truncated by a pipe buffer -----------------------
(println "=== 5. a large output burst still lands in full ===")

(let [{:keys [res blocked?]} (run-bounded "seq 1 20000" 10000 25000)
      lines (count (str/split-lines (str (:out res))))]
  (check! "20k lines come back whole" (and (not blocked?) (= 20000 lines))
          (str lines " lines")))

(cleanup!)
(println)
(if (zero? @failures)
  (println "all checks passed")
  (do (println (str @failures " check(s) FAILED"))
      (System/exit 1)))
(shutdown-agents)
