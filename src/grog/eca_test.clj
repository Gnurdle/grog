(ns grog.eca-test
  "Headless smoke test for the ECA stdio client (`grog.eca`).

  Connects to the real `eca server`, completes the handshake, records the
  `config/updated` / `tool/serverUpdated` notifications, sends one `chat/prompt`,
  and dumps every `chat/contentReceived` event that comes back. Used to prove the
  client works before rewiring the Swing GUI. Not part of the app."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.eca :as eca]))

(defn- file-uri [^String path]
  (str (.toURI (-> (java.io.File. path)
                   .toPath .toAbsolutePath .normalize .toFile))))

(defn- event-summary [method params]
  (let [content (:content params)
        ctype (when content (:type content))
        text (case ctype
               "text" (let [t (str (:text content))]
                        (str "text: " (if (> (count t) 120) (str (subs t 0 120) "…") t)))
               "reasonStarted" (str "reasonStarted id=" (:id content))
               "reasonText" "reasonText…"
               "reasonFinished" (str "reasonFinished ms=" (:totalTimeMs content))
               "toolCallPrepare" (str "toolPrepare " (:name content))
               "toolCallRun" (str "toolRun " (:name content) " manual=" (:manualApproval content))
               "toolCallRunning" (str "toolRunning " (:name content))
               "toolCalled" (str "toolCalled " (:name content) " ms=" (:totalTimeMs content) " err=" (:error content))
               "toolCallRejected" (str "toolRejected " (:name content) " reason=" (:reason content))
               "usage" (str "usage sessionTokens=" (:sessionTokens content))
               "progress" (str "progress " (:state content))
               "metadata" (str "metadata title=" (:title content)))
        text (or text (str "content-type=" ctype))]
    (str method " :: " text)))

(defn -main
  "Args: [repo-root] [message] [model]. Defaults: /d/gni/grog, \"Reply with
  exactly the word OK.\", and openrouter/moonshotai/kimi-k2.6 (must be an
  actually-configured provider/model for the running eca)."
  [& args]
  (let [root (or (first args) "/d/gni/grog")
        message (or (second args) "Reply with exactly the word OK.")
        model (or (nth args 2 nil) "openrouter/moonshotai/kimi-k2.6")
        seen (atom [])
        handler (fn [method params]
                  (let [line (event-summary method params)]
                    (swap! seen conj line)
                    (binding [*out* *err*]
                      (println "  [event] " line))))
        logfn (fn [line] (binding [*out* *err*] (println "  [eca-stderr] " line)))
        init (eca/connect! [{:uri (file-uri root) :name (str "grog-" (System/currentTimeMillis))}]
                           :event-handler handler
                           :log-fn logfn
                           ;; quiet eca's own logging so stdout stays clean
                           :args ["--log-level" "warn"])]
    (println "== initialize ok:" init)
    (println "== sending chat/prompt: " message "  model=" model)
    (let [resp (eca/prompt! message {:model model})]
      (println "== chat/prompt response:" resp)
      (when-let [chat-id (get-in resp [:ok :chatId])]
        (println "== chatId:" chat-id)
        ;; collect for up to ~40s or until we've seen a plausible end to a reply
        (let [stop-at (+ (System/currentTimeMillis) 40000)]
          (loop []
            (when (and (< (System/currentTimeMillis) stop-at)
                       (not (some #(or (str/includes? % "reasonFinished")
                                       (str/includes? % "toolCalled")
                                       (str/includes? % "usage"))
                                  @seen)))
              (Thread/sleep 500)
              (recur))))
        (println "== total events:")
        (doseq [e @seen] (println "   " e))))
    (eca/disconnect!)
    (println "== disconnected")))
