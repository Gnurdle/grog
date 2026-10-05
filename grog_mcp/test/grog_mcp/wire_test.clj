(ns grog-mcp.wire-test
  "Wire-level regression test (Group E3, task 17).

  Asserts that an MCP tool descriptor carries `inputSchema` as a JSON Schema
  OBJECT, on the ACTUAL WIRE OUTPUT of the built bundle jar. A *string* here is
  the bug that cost days in September 2026: strict providers reject the entire
  request (`tools[14].function.parameters must be a JSON Schema object`), the
  failover chain sometimes mislabels it as a context-length error, and because
  the model never sees parameter names it calls tools with empty arguments
  ('Missing required :sql', 'path is required'). It is invisible from the
  Clojure side of the SDK — the String constructor ships the schema verbatim —
  so only a wire assertion can guard it.

  Run:  clojure -T:build uber   (in grog_mcp; the jar must exist first)
        clojure -M:test"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io])
  (:import (java.io BufferedReader InputStreamReader File)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private read-timeout-ms 90000)

(defn- newest-jar
  "The newest target/grog-mcp-*.jar beside this project, or nil when unbuilt."
  ^File []
  (->> (seq (.listFiles (File. "target")))
       (filter (fn [^File f] (re-matches #"grog-mcp-.*\.jar" (.getName f))))
       (sort-by (fn [^File f] (.lastModified f)))
       last))

(defn- pump!
  "Drain a stream into a queue on a daemon thread (pipe reads block; a queue
  lets the test time out instead of hanging forever)."
  [^BufferedReader rdr ^LinkedBlockingQueue q]
  (doto (Thread. (fn []
                   (try
                     (loop []
                       (when-let [l (.readLine rdr)]
                         (.put q l)
                         (recur)))
                     (catch Throwable _ nil))))
    (.setDaemon true)
    (.start)))

(defn- send!
  "Write one JSON-RPC frame to the child's stdin (PrintWriter: the raw process
  pipe only offers write(int)/write(byte[]))."
  [^java.io.PrintWriter w m]
  (.println w ^String (json/generate-string m))
  (.flush w))

(defn- take-line [^LinkedBlockingQueue q ms] (.poll q (long ms) TimeUnit/MILLISECONDS))

(defn- take-matching
  "Read lines until one parses as JSON AND satisfies `pred` (responses are
  interleaved with notifications, so `take-line` alone grabs the wrong frame)."
  [^LinkedBlockingQueue q ^long ms pred]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (if-let [line (take-line q (max 1 (- deadline (System/currentTimeMillis))))]
        (let [m (try (json/parse-string line true) (catch Throwable _ nil))]
          (cond
            (and m (pred m)) line
            (< (System/currentTimeMillis) deadline) (recur)
            :else nil))
        nil))))

(defn- server-request
  "Start the bundle jar for `server`, perform the MCP handshake and return
  {:init <json> :tools <json>} parsed from the wire."
  [^String jar ^String server]
  (let [pb (ProcessBuilder. ^java.util.List
                            ["java"
                             "--add-opens=java.base/java.lang=ALL-UNNAMED"
                             "--enable-native-access=ALL-UNNAMED"
                             "-cp" jar
                             "clojure.main" "-m" "grog_mcp.main" "--server" server])
        p (.start pb)
        out (LinkedBlockingQueue.)
        err (LinkedBlockingQueue.)]
    (pump! (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8")) out)
    (pump! (BufferedReader. (InputStreamReader. (.getErrorStream p) "UTF-8")) err)
    (try
      (let [w (java.io.PrintWriter.
               (java.io.OutputStreamWriter. (.getOutputStream p) "UTF-8"))]
        (send! w {:jsonrpc "2.0" :id 1 :method "initialize"
                  :params {:protocolVersion "2025-03-26"
                           :capabilities {}
                           :clientInfo {:name "grog wire-test" :version "0"}}})
        (let [init-line (take-matching out read-timeout-ms #(= 1 (:id %)))]
          (is (some? init-line)
              (str "no initialize response from the jar; stderr: "
                   (loop [acc [] n 6] (if (pos? n)
                                        (if-let [l (take-line err 200)] (recur (conj acc l) (dec n)) acc)
                                        acc))))
          (send! w {:jsonrpc "2.0" :method "notifications/initialized"})
          ;; NO sleep here. Asking immediately is the point: the bundle compiles
          ;; each server namespace at runtime (non-AOT), so if the stdio transport
          ;; is alive before the tools are registered, an immediate `tools/list`
          ;; returns a PARTIAL list (observed: 0 tools). A 2500ms sleep used to
          ;; hide exactly that bug.
          (send! w {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
          (let [tools-line (take-matching out read-timeout-ms #(= 2 (:id %)))]
            (is (some? tools-line) "no tools/list response from the jar")
            {:init (when init-line (json/parse-string init-line true))
             :tools (when tools-line (json/parse-string tools-line true))})))
      (finally
        (try (.destroy p) (catch Throwable _ nil))))))

(deftest tool-schemas-are-javascript-schema-objects
  (if-let [jar (newest-jar)]
    (let [jar-path (.getAbsolutePath ^File jar)
          {:keys [init tools]} (server-request jar-path "grog-rss")
          tool-list (get-in tools [:result :tools])]
      (testing "the server responds and lists tools"
        (is (= "grog-mcp" (get-in init [:result :serverInfo :name])))
        (is (seq tool-list) "expected at least one tool from grog-rss"))
      (testing "every tool ships inputSchema as an OBJECT (not a JSON string)"
        (doseq [t tool-list]
          (let [schema (:inputSchema t)]
            (is (map? schema)
                (str "tool " (:name t) " shipped inputSchema as "
                     (type schema) " — a string here 400s every strict provider "
                     "and empties tool arguments"))
            (is (= "object" (:type schema))
                (str "tool " (:name t) " schema has no type:object"))))))
    (is false
        "no grog-mcp jar in target/ — run `clojure -T:build uber` in grog_mcp first")))
