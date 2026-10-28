#!/usr/bin/env bb
;; model-picker-test.clj — what a PICK actually writes.
;;
;;   scripts/model-picker-test.sh    (isolated config home; use the wrapper)
;;
;; Why this exists. Two reports about the SAME panel:
;;
;;   1. "picking one from the history list doesn't set it correctly"
;;   2. "when picking the strata transport, the model is BOGUS — I'd prefer it
;;      blank to being wrong"
;;
;; Both are about what lands in the CONFIG after a pick. The picker sends a
;; PLACE (`local`/`remote`) or a recents row's own `[url model]`, and the server
;; turns that into the `:llm :url` and `:llm :model` a session then runs. Nothing
;; in this repo asserted on that outcome — `recents-test.clj` covers the LRU
;; order, `providers-test.clj` the catalogue — so a wiring bug here (a placeholder
;; id saved as the live model, a recent that switches the id but not the
;; endpoint) was invisible to every existing test and to any screenshot.
;;
;; This drives the REAL `grog.server/handle-request` over a REAL session, so what
;; is measured is the code path the renderer calls, not a re-implementation.
;;
;; DETERMINISM: every endpoint used here is one NOTHING is listening on, so the
;; result never depends on whether the developer happens to have Strata or ollama
;; running. The "server is up" halves of the same rules (read the live id; fall
;; back to the hint only for a remote) are covered in context-window-test.clj.
;;
;; ISOLATION: writes grog.edn, so it REFUSES to run without an isolated
;; GROG_CONFIG_HOME (same guard as recents-test.clj).

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(def fails (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! fails inc))))

(defn- eq! [id expected actual]
  (check! id (= expected actual) (str "expected " (pr-str expected) ", got " (pr-str actual))))

(def tmp-home (System/getenv "GROG_CONFIG_HOME"))

(when (str/blank? tmp-home)
  (println "REFUSING TO RUN: GROG_CONFIG_HOME is unset.")
  (println "  This test writes grog.edn; with no GROG_CONFIG_HOME it would write")
  (println "  the REAL one. Use: scripts/model-picker-test.sh")
  (System/exit 2))

(when (= (str tmp-home) (str (io/file (System/getProperty "user.home") ".config" "grog")))
  (println "REFUSING TO RUN: GROG_CONFIG_HOME points at the real config home:" tmp-home)
  (System/exit 2))

(println (str "(isolated config home: " tmp-home ")"))
(.mkdirs (io/file tmp-home))

(require '[grog.config :as config]
         '[grog.client.loop :as loop]
         '[grog.models :as models]
         '[grog.projects :as projects]
         '[grog.providers :as providers]
         '[grog.server :as server])

(def ^:private home (io/file tmp-home "grog.edn"))
(def ^:private projects-dir (io/file tmp-home "projects"))

(defn- write-config! [m]
  ;; `:projects :dir` is pinned inside the scratch home too: a non-global pick
  ;; writes the PROJECT manifest, so without this the test would write into the
  ;; developer's real ~/grog-projects.
  (spit home (with-out-str (pp/pprint (assoc m :projects {:dir (str projects-dir)})))
        :encoding "UTF-8")
  (config/reload!))

(defn- cfg [] (edn/read-string (slurp home)))
(defn- cfg-llm [] (:llm (cfg)))

;; A port nothing listens on: the endpoint is unreachable by construction, which
;; is exactly the state "I picked a local transport and have not launched it yet".
(def ^:private dead-local "http://127.0.0.1:59321/v1")

(defn- rpc
  "Drive the real server handler (reached through its var — it is private, an
  implementation detail of the server loop). Answers are captured so a pick that
  errors is SEEN rather than silently absent."
  [method params]
  (let [handle @#'server/handle-request
        out (atom [])]
    (handle
     (fn [msg] (swap! out conj msg))
     (fn [_kind _payload] nil)
     (atom {})
     {:jsonrpc "2.0" :id 1 :method method :params params})
    (first @out)))

(defn- result-of
  "The `:result` of a real request, failing the check if it errored."
  [method params]
  (let [r (rpc method params)]
    (when-let [e (:error r)]
      (check! (str method " did not error") false (str "error: " (:message e))))
    (:result r)))

;; --- the session the picks are made through ---------------------------------
;;
;; `set-model` writes through the LOOP adapter, keyed by session — so the test
;; needs a real one. `set-provider` writes the config directly.

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})

(def ^:private sid (:id (result-of "open" {:project "grog"})))
(check! "a session was opened for the picks" (some? sid) (str "id=" (pr-str sid)))

;; --- 1. the recents pick: the WHOLE pair, url included ----------------------
;;
;; The report: "picking one from the history list doesn't set it correctly".
;;
;; A recents row is a `[url model]` PAIR — storing the url beside the id is the
;; entire point. Clicking one must switch BOTH, or "switch back to the local one
;; I used last week" points the restored id at whatever endpoint happens to be
;; configured now.

(println "\n== a recents pick switches the MODEL and the TRANSPORT ==")

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"
                      :recents [{:url dead-local
                                 :model "some-local-model"
                                 :provider "strata"}]}})

(let [row (first (config/recents))]
  ;; what the renderer sends for a click on that row — the row's url, no source
  (result-of "set-model" {:id sid :model (:model row) :url (:url row) :global true})
  (eq! "the recents pick writes the model"
       "some-local-model" (get-in (cfg) [:llm :model]))
  (eq! "…and the transport it was remembered with"
       dead-local (get-in (cfg) [:llm :url]))
  (eq! "…and the config agrees on both after a reload"
       [dead-local "some-local-model"]
       [(config/llm-url) (config/eca-model)]))

;; --- 2. a transport pick must never INVENT a model --------------------------
;;
;; The report: "picking the strata transport, the model is BOGUS". Strata's
;; catalogue entry ships `:sample nil` (no generic Strata id exists — see
;; resources/providers.edn), so there is nothing to name. The old code called
;; `live-model-id`, which fell back to the hint — and other local entries shipped
;; the placeholder "local-model", which no launch answers to. Worse, when the
;; transport changed and no model could be resolved, the PREVIOUS model was left
;; in place, pairing a foreign id with the new endpoint.

(println "\n== a transport pick never invents or inherits a model ==")

(check! "the strata entry ships no :sample (the premise this rests on)"
        (nil? (:sample (providers/by-id "strata")))
        (str "sample=" (pr-str (:sample (providers/by-id "strata")))))

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})

;; An EXPLICIT dead url, so the outcome cannot depend on whether the real Strata
;; happens to be running on this machine (it usually is — and when it is, reading
;; the served id is exactly right, see the direct checks below).
(result-of "set-provider" {:url dead-local})
(let [c (cfg-llm)]
  (eq! "the transport url was written" dead-local (:url c))
  (check! "NO placeholder was invented"
          (not (models/placeholder-model-id? (:model c)))
          (str "model=" (pr-str (:model c))))
  (check! "and the OLD transport's model was NOT left behind"
          (nil? (:model c))
          (str "model=" (pr-str (:model c)))))

;; and the real catalogue entry, whose url is the SHARED local port: whatever the
;; live server answers is what must be recorded — never the shipped placeholder.
(result-of "set-provider" {:id "strata"})
(let [c (cfg-llm)]
  (eq! "picking strata writes its url"
       "http://localhost:8080/v1" (:url c))
  (check! "and NEVER the \"local-model\" placeholder"
          (not (models/placeholder-model-id? (:model c)))
          (str "model=" (pr-str (:model c)))))

;; the rule, stated directly and without a server:
(eq! "a DEAD local endpoint + a placeholder hint yields NO model (not the hint)"
     nil (models/live-model-id dead-local "local-model"))
(eq! "a DEAD local endpoint + a real-looking hint still yields NO model"
     nil (models/live-model-id dead-local "llama-3.3-70b"))
(check! "a dead REMOTE endpoint may keep the catalogue hint (a real catalog id)"
        (= "some-real-catalog-id"
           (models/live-model-id "https://not-local.example/v1" "some-real-catalog-id")))

;; --- 3. a local CATALOGUE pick names the model it picked --------------------
;;
;; Double-clicking a row in the Local tab is a real pick of a specific id, made
;; against the endpoint the tab is listing. It must persist THAT id and THAT
;; endpoint — not repoint it at ollama on the assumption that "local" is ollama.

(println "\n== a local catalogue pick keeps the id and the endpoint ==")

;; NOT global: this writes the PROJECT manifest (that is the documented split —
;; a project's own model beats the global default), so both surfaces are read.
(write-config! {:llm {:url dead-local :model ""}})

(result-of "set-model" {:id sid :model "the-clicked-model" :source "local"})
(eq! "the clicked id is persisted verbatim (project manifest)"
     "the-clicked-model" (projects/project-model "grog"))
(eq! "…and the GLOBAL model is left alone"
     "" (get-in (cfg) [:llm :model]))
(eq! "the endpoint is NOT rewritten to ollama's port"
     dead-local (get-in (cfg) [:llm :url]))
(check! "the endpoint is not ollama's"
        (not= "http://localhost:11434/v1" (get-in (cfg) [:llm :url])))

;; …and the global flavour of the same pick does write grog.edn.
(write-config! {:llm {:url dead-local :model ""}})
(result-of "set-model" {:id sid :model "the-clicked-model" :source "local" :global true})
(eq! "a GLOBAL pick writes grog.edn instead"
     "the-clicked-model" (get-in (cfg) [:llm :model]))

(eq! "a local pick is labelled with the CONFIGURED local provider, not ollama"
     "strata"
     (do (write-config! {:llm {:url "http://localhost:8080/v1" :model "x"}})
         (models/picker-source->provider "local")))

(eq! "…and with a remote transport, \"local\" still means ollama"
     "ollama"
     (do (write-config! {:llm {:url "https://openrouter.ai/api/v1" :model "x"}})
         (models/picker-source->provider "local")))

;; --- 4. a BLANK pick writes nothing ----------------------------------------
;;
;; The dialog's Apply with an empty box and no url. There is no model to name, so
;; the honest outcome is to leave the config alone — never a guess.

(println "\n== a blank pick writes no model ==")

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})

(result-of "set-model" {:id sid :model "" :global true})
(eq! "the configured model was left exactly as it was"
     "aion-labs/aion-2.0" (get-in (cfg) [:llm :model]))

;; --- 5. the panel and the config agree -------------------------------------
;;
;; The dialog's transport label / endpoint line come from `providers`' `:current`
;; and `:info`. If those disagree with the config, the panel shows one thing and
;; writes another — the "not wired together" complaint.

(println "\n== the panel reads the SAME state the picks write ==")

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})

(let [p (result-of "providers" {})]
  (eq! "providers reports the configured url"
       "https://openrouter.ai/api/v1" (get-in p [:current :url]))
  (eq! "…the configured model"
       "aion-labs/aion-2.0" (get-in p [:current :model]))
  (eq! "…and names the provider the panel shows"
       "openrouter" (get-in p [:current :provider])))

(check! "the catalogue is keyed by PLACE (local/remote), matching the tabs"
        (contains? (result-of "models" {}) :local))

(eq! "a remote transport offers BOTH tabs"
     [["local" "Local"] ["remote" "Remote"]]
     (do (write-config! {:llm {:url "https://openrouter.ai/api/v1" :model "x"}})
         (models/model-sources)))

(eq! "a LOCAL transport offers only Local (a Remote tab would list the same server)"
     [["local" "Local"]]
     (do (write-config! {:llm {:url "http://localhost:8080/v1" :model "x"}})
         (models/model-sources)))

;; --- 6. the config CACHE must not outlive the file ---------------------------
;;
;; THE ROOT CAUSE of "picking one from the history list doesn't set it
;; correctly". `save-model-url!` wrote `[:llm :url]` but did not refresh the
;; cached config, so the NEXT step of the same pick — resolving the model
;; (`resolve-llm`, which reads `config/llm-url`) to push the recents row and label
;; the status line — used the transport the user had just replaced. The file said
;; the new endpoint; the session still acted on the old one.
(println)
(println "=== 6. a write is visible to the config immediately ===")

(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})

(models/save-model-url! dead-local)
(eq! "after saving a url, config/llm-url ALREADY reports it"
     dead-local (config/llm-url))
(check! "…so a later resolution is no longer pointed at the REPLACED transport"
        (not= "https://openrouter.ai/api/v1" (config/llm-url))
        (str "url=" (config/llm-url)))

(println "  (a recents row pushed during that pick records the transport just saved)")
(write-config! {:llm {:url "https://openrouter.ai/api/v1" :model "aion-labs/aion-2.0"}})
(models/save-model-url! dead-local)
(models/push-recent! (config/llm-url) "chosen-on-the-new-transport")
(eq! "the recents row carries the url that was JUST saved, not the previous one"
     dead-local (:url (first (config/recents))))

;; --- 7. a model never gets grafted onto a transport that cannot run it -------
;;
;; The "BOGUS model" report has THREE sources, and the third is a merge rather
;; than a pick: `:llm :url` and `:llm :model` are one choice, but the legacy
;; `:big-model` and `:eca :model` fallbacks were merged KEY BY KEY, so a perfectly
;; ordinary config grew a cross-transport pairing on its own — `:url` from `:llm`,
;; `:model` from a leftover elsewhere. The result is a config that names a model
;; on an endpoint that has never heard of it: confident, and wrong. A blank is
;; honest, so that is what a mismatched leftover must produce.
(println)
(println "=== 7. no model is grafted onto a transport that cannot run it ===")

;; (a) the legacy :big-model pair is only taken WHOLE, and only when :llm names
;;     no transport of its own.
(write-config! {:llm {:url "http://localhost:11434/v1"}
                :big-model {:url "https://openrouter.ai/api/v1"
                            :model "aion-labs/aion-2.0"}})
(eq! "an :llm with a url does not inherit :big-model's model"
     nil (config/eca-model))
(eq! "…nor its url (the :llm url stands)"
     "http://localhost:11434/v1" (config/llm-url))

(write-config! {:big-model {:url "https://openrouter.ai/api/v1"
                            :model "aion-labs/aion-2.0"}})
(eq! "but a config with ONLY :big-model keeps its pair (nothing to conflict with)"
     "aion-labs/aion-2.0" (config/eca-model))
(eq! "…url and all"
     "https://openrouter.ai/api/v1" (config/llm-url))

;; (b) the :eca :model fallback is provider-QUALIFIED and belongs to another
;;     transport — the exact leftover in the reported config.
(write-config! {:llm {:url "http://localhost:11434/v1"}
                :eca {:model "openrouter/deepseek/deepseek-v4.1-flash"}})
(eq! "a foreign-qualified :eca model is NOT grafted onto the local transport"
     nil (config/eca-model))

;; …while the same fallback is honoured when it FITS the transport.
(write-config! {:llm {:url "https://openrouter.ai/api/v1"}
                :eca {:model "openrouter/deepseek/deepseek-v4.1-flash"}})
(eq! "the same id IS used when the transport is the one it names"
     "openrouter/deepseek/deepseek-v4.1-flash" (config/eca-model))

;; …and a BARE id stays transport-relative, which is the normal shape of
;; `:llm :model` — it must still be accepted.
(write-config! {:llm {:url "http://localhost:8080/v1"} :eca {:model "deepseek-v4.1-flash"}})
(eq! "an unqualified id is transport-relative and is accepted"
     "deepseek-v4.1-flash" (config/eca-model))

;; and a properly paired :llm is always used as-is.
(write-config! {:llm {:url "http://localhost:8080/v1" :model "the-real-one"}
                :big-model {:url "https://openrouter.ai/api/v1" :model "aion-labs/aion-2.0"}})
(eq! "a complete :llm pair wins outright"
     "the-real-one" (config/eca-model))

;; (c) the same rule reaches the panel, which reads its state from the config.
(write-config! {:llm {:url "http://localhost:11434/v1"}
                :eca {:model "openrouter/deepseek/deepseek-v4.1-flash"}})
(eq! "the picker reports a BLANK model rather than the mismatched one"
     nil (:model (models/slot-info nil)))

;; --- 8. the STATUS LINE and the TURN must name the same endpoint -------------
;;
;; The report: "grog seems to be routing things to models other than what is
;; shown on the status line."
;;
;; That is a DIVERGENCE between two computations of the same choice:
;;
;;   * the status line is built ONCE, when the model is set, from the transport
;;     the PICK resolved to (`model-status`, over `resolve-llm`); and
;;   * the turn used to RE-RESOLVE its endpoint from the live config on every
;;     call, with no source (`resolve-llm @:model nil`).
;;
;; Those agree only by luck. They part wherever the pick's transport is not the
;; one `:llm :url` currently holds — a pick whose SOURCE named another provider
;; (status says `strata`, turn posts to `:llm :url`), or a project pick that
;; rewrote the GLOBAL `:llm :url` underneath an ALREADY-RUNNING session (the
;; session keeps advertising the old endpoint while silently posting to the new
;; one). Either way the user is told one endpoint and billed against another.
;;
;; The fix pins the session's transport: the url the status line shows is the url
;; a turn sends to. What is asserted here is the INVARIANT, not the mechanism —
;; whatever the snapshot advertises, `session-llm` must resolve to.

(println)
(println "=== 8. the status line and the turn name the same endpoint ===")

(defn- session-state
  "The live `grog.chat` state map for a session id (through the loop's private
  registry — this test drives the real adapter)."
  [sid]
  (:state (get @@#'loop/!sessions sid)))

(defn- turn-url
  "The endpoint a TURN would actually post to — `session-llm` is exactly what
  both `send-fn` and the subagent pass to `run-turn!`."
  [sid]
  (:url (#'loop/session-llm (session-state sid))))

(defn- agree!
  "Assert the snapshot's endpoint and the turn's endpoint are the same."
  [id sid]
  (let [snap (result-of "session" {:id sid})]
    (eq! (str id " — status line and turn agree")
         (:url snap) (turn-url sid))))

;; (a) the plain case: a configured remote transport.
(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})
(def ^:private sid2 (:id (result-of "open" {:project "agree"})))
(agree! "at open" sid2)

;; (b) a pick whose SOURCE names a provider OTHER than the configured transport.
;; This is the discriminating case: BEFORE the fix the snapshot said
;; `…:8080/v1` (strata) while the turn posted to `:llm :url`.
(result-of "set-model" {:id sid2 :model "llama-3.3-70b" :source "strata" :global true})
(agree! "after a source=strata pick" sid2)
(eq! "…and that endpoint really is strata's"
     "http://localhost:8080/v1" (turn-url sid2))

;; (c) A PROJECT pick rewrites the GLOBAL `:llm :url` (that is the documented
;;     local-tab behaviour). A session for ANOTHER project that is already open
;;     must not be dragged onto it: it keeps the endpoint its own status line
;;     shows. This is the cross-project form of the same bug.
(write-config! {:llm {:url "https://openrouter.ai/api/v1"
                      :model "aion-labs/aion-2.0"}})
(def ^:private sid-alpha (:id (result-of "open" {:project "alpha"})))
(def ^:private sid-beta  (:id (result-of "open" {:project "beta"})))
(result-of "set-model" {:id sid-alpha :model "alpha-local-model" :source "local"})
(agree! "a running session in another project is not dragged onto the new global url" sid-beta)
(eq! "…it still posts to the endpoint it was opened with"
     "https://openrouter.ai/api/v1" (turn-url sid-beta))
(check! "…even though the GLOBAL transport has moved"
        (= "http://localhost:11434/v1" (get-in (cfg) [:llm :url]))
        (str "global url=" (get-in (cfg) [:llm :url])))

(println)
(if (pos? @fails)
  (do (println (str "model-picker-test: FAILED — " @fails " check(s)"))
      (System/exit 1))
  (do (println "model-picker-test: all checks passed")
      (System/exit 0)))
