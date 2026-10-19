#!/usr/bin/env bb
;; Agent-loop smoke test — the parts that do not need a live model:
;;   * grog.agent.tools  (read/write/edit/list/grep/shell, and the SELF-VERIFYING edit)
;;   * grog.agent.mcp    (spawn grog's own tool server, handshake, list, call)
;;
;;   clojure -M scripts/agent-loop-test.clj
;;
;; The live-loop check is a separate thing: see src/grog/agent/README.md for the
;; two turns that were run against real models, with their numbers.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[grog.agent.tools :as t]
         '[grog.agent.mcp :as mcp])

(def dir (str (System/getProperty "java.io.tmpdir") "/agent-loop-test"))
(io/make-parents (io/file dir "x.txt"))
(spit (str dir "/x.txt") "alpha\nbeta\ngamma\n")
(spit (str dir "/dup.txt") "same\nsame\n")

(defn- run1 [name args]
  (let [r (t/call (t/handlers dir) name args)]
    (println (str "--- " name " " (pr-str args)))
    (println (str "    error?=" (:error? r)))
    (println (str "    " (str/replace (str (:text r)) "\n" "\n    ")))
    r))

(println "=== the self-verifying edit ===")
(run1 "read_file" {"path" "x.txt"})
(run1 "edit_file" {"path" "x.txt" "original_content" "beta" "new_content" "BETA"})
(run1 "edit_file" {"path" "x.txt" "original_content" "not-in-the-file" "new_content" "q"})
(run1 "edit_file" {"path" "dup.txt" "original_content" "same" "new_content" "SAME"})
(run1 "edit_file" {"path" "dup.txt" "original_content" "same" "new_content" "SAME" "all_occurrences" true})

(println)
(println "=== a write that does NOT land (/dev/null accepts and keeps nothing) ===")
(run1 "write_file" {"path" "/dev/null" "content" "this cannot be read back"})

(println)
(println "=== shell + grep + list_dir ===")
(run1 "shell" {"command" "echo hi from the loop; pwd"})
(run1 "grep" {"pattern" "BETA" "path" dir})
(run1 "list_dir" {"path" dir})

(println)
(println "=== MCP client against grog's own consolidated tool server ===")
(try
  (let [project (or (mcp/active-project) "grog")
        _ (println (str "project resolved by grog: " (pr-str (mcp/active-project))
                        " — using " (pr-str project)))
        spec (mcp/server-spec project)]
    (println (str "spec: command=" (:command spec) " env-keys=" (pr-str (keys (:env spec)))))
    (let [conn (mcp/start! spec)]
      (try
        (let [init (mcp/handshake! conn)]
          (println (str "initialize ok: serverInfo=" (pr-str (:serverInfo init)))))
        (let [tools (mcp/list-tools! conn)]
          (println (str "tools listed: " (count tools)))
          (println (str "sample: " (pr-str (take 8 (mapv :name tools)))))
          (println (str "schema-types: " (pr-str (frequencies (map #(type (:inputSchema %)) tools))))))
        (let [r (mcp/call-tool! conn "assoc_store" {"key" "smoke.loop-spike" "value" "written by grog's own MCP client"})
              g (mcp/call-tool! conn "assoc_get" {"key" "smoke.loop-spike"})]
          (println (str "assoc_store -> " (pr-str (select-keys r [:error? :text]))))
          (println (str "assoc_get   -> " (pr-str (select-keys g [:error? :text])))))
        (finally (mcp/stop! conn)))))
  (catch Throwable t
    (println (str "MCP ERROR: " (.getMessage t) " " (pr-str (ex-data t))))))
