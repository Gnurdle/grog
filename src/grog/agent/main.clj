(ns grog.agent.main
  "Run grog's own loop from the shell — the spike's way in.

    clojure -M -m grog.agent.main --prompt \"list the .clj files under src/grog\" --cwd /d/gni/grog

  Defaults come from grog's own config (`grog.models/llm-config` + the key from
  `grog.config/llm-api-key`), so the loop drives the same model grog is
  configured for. For a no-key, no-cost local run:

    clojure -M -m grog.agent.main --url http://localhost:11434/v1 --model qwen3:8b --prompt \"…\"

  Flags: --prompt (required) · --url --model --key · --cwd · --max-steps
         --no-mcp (local tools only) · --quiet (no streaming echo) · --system FILE"
  (:require [clojure.string :as str]
            [grog.agent.loop :as loop]
            [grog.agent.mcp :as mcp]))

(defn- parse-args [args]
  (loop [as args acc {}]
    (if (empty? as)
      acc
      (let [[a & more] as]
        (case a
          "--prompt" (recur (rest more) (assoc acc :prompt (first more)))
          "--url" (recur (rest more) (assoc acc :url (first more)))
          "--model" (recur (rest more) (assoc acc :model (first more)))
          "--key" (recur (rest more) (assoc acc :key (first more)))
          "--cwd" (recur (rest more) (assoc acc :cwd (first more)))
          "--system" (recur (rest more) (assoc acc :system-file (first more)))
          "--max-steps" (recur (rest more) (assoc acc :max-steps (Long/parseLong (first more))))
          "--no-mcp" (recur more (assoc acc :no-mcp true))
          "--quiet" (recur more (assoc acc :quiet true))
          (recur more acc))))))

(defn- resolve-llm [opts]
  (let [cfg (try (requiring-resolve 'grog.models/llm-config)
                 (catch Throwable _ nil))
        conf (when cfg (try (cfg) (catch Throwable _ nil)))
        key (try ((requiring-resolve 'grog.config/llm-api-key)) (catch Throwable _ nil))]
    {:url (or (:url opts) (:url conf))
     :model (or (:model opts) (:model conf))
     :key (or (:key opts) key)}))

(defn -main [& args]
  (let [opts (parse-args args)]
    (when-not (:prompt opts)
      (println "usage: clojure -M -m grog.agent.main --prompt \"…\" [--url U --model M] [--cwd DIR] [--no-mcp]")
      (System/exit 2))
    (let [llm (resolve-llm opts)]
      (when-not (:url llm)
        (println "no :llm :url — pass --url (or configure grog.edn)"))
      (println (str "== grog agent loop (spike): model=" (:model llm) " url=" (:url llm)
                    " key=" (if (:key llm) "present" "none")))
      (let [mcp-conn (when-not (:no-mcp opts)
                       (try
                         (let [spec (mcp/server-spec nil)
                               conn (mcp/start! spec)]
                           (mcp/handshake! conn)
                           (println (str "== MCP: " (count (mcp/list-tools! conn)) " tools from grog-mcp"))
                           conn)
                         (catch Throwable t
                           (println (str "== MCP unavailable: " (.getMessage t) " (local tools only)"))
                           nil)))
            !tools (atom [])
            on-event (fn [seg]
                       (case (:kind seg)
                         :thinking (when-not (:quiet opts) (print (:text seg)))
                         :answer (when-not (:quiet opts) (print (:text seg)))
                         :tool (do (swap! !tools conj (:text seg))
                                   (println (str "\n  [tool] " (:text seg))))
                         :usage nil
                         nil)
                       (flush))]
        (try
          (let [t0 (System/currentTimeMillis)
                res (loop/run-turn! {:llm llm
                                     :message (:prompt opts)
                                     :cwd (or (:cwd opts) (System/getProperty "user.dir"))
                                     :mcp mcp-conn
                                     ;; uncapped unless --max-steps was passed
                                     :max-steps (:max-steps opts)
                                     :system (if-let [f (:system-file opts)]
                                               (slurp f)
                                               loop/default-system-prompt)
                                     :on-event on-event})
                ms (- (System/currentTimeMillis) t0)]
            (println "\n\n== summary")
            (println (str "status=" (:status res) " steps=" (:steps res) " ms=" ms))
            (println (str "usage=" (pr-str (:usage res))))
            (println (str "messages=" (count (:messages res))
                          " tool-events=" (count @!tools))))
          (catch Throwable t
            (println (str "\n\n== FAILED: " (.getMessage t)))
            (when-let [d (ex-data t)] (println (pr-str d)))
            (System/exit 1))
          (finally
            (when mcp-conn (mcp/stop! mcp-conn))))))))
