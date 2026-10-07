(ns grog.mcp-http
  "Owns the shared grog-mcp Streamable-HTTP endpoint process.

  grog-server starts ONE grog-mcp JVM at boot (`clojure -M:http --base-port N`
  in the grog_mcp project): it serves the entire toolset over localhost HTTP,
  one listener per server key. `grog.eca-config` emits `:url` entries for every
  key instead of 13 stdio commands, so each ECA — and every tab — dials the one
  process instead of spawning its own pile of MCP JVMs.

  Why a child process rather than in-process: the tools pull native/PDF/OCR/
  office/postgres deps; keeping them out of grog.server's JVM preserves crash
  isolation (a native tool crash kills the endpoint, not the session server).

  Readiness: the endpoint prints one stderr line per listener —
    grog-mcp http READY <server-id> 127.0.0.1:<port>/mcp
  — and we collect them into a {server-id url} map, which makes the endpoint's
  state observable (`urls` non-empty => ECA configs use `:url`).

  Failure is soft: if the endpoint never comes up, `urls` stays empty and
  eca-config falls back to spawning stdio MCPs."
  (:require [clojure.string :as str]
            [grog.platform :as platform])
  (:import (java.io BufferedReader File InputStreamReader)
           (java.lang ProcessHandle)))

(defonce ^:private !ep
  ;; {:process Process :urls {id url}} while running
  (atom nil))

(def ^:private expected-servers
  "How many listeners to wait for (the grog_mcp bundle's :servers count is 13;
  we also accept a settle-timeout so a changed bundle can't hang boot)."
  13)

(defn urls
  "{server-id url} for the running endpoint, or nil when not running."
  []
  (:urls @!ep))

(defn running? [] (boolean (:urls @!ep)))

(defn- drain!
  "Read the endpoint's stderr forever: READY lines fill `urls`, everything else
  is echoed to our stderr (per-instance log)."
  [^BufferedReader r urls]
  (future
    (try
      (loop []
        (when-let [line (.readLine r)]
          (if-let [[_ id url] (re-matches #".*READY\s+(\S+)\s+(\S+)" line)]
            (swap! urls assoc id url)
            (binding [*out* *err*]
              (println "[grog-mcp]" line)))
          (recur)))
      (catch Throwable _ nil))))

(defn start!
  "Start the endpoint (idempotent). Options:
     :cmd        launch vector  (default [\"clojure\" \"-M:http\" \"--base-port\" <base>])
     :dir        working dir    (default \"grog_mcp\" under the cwd)
     :base-port  first port     (default 9700)
     :timeout-ms how long to wait for listeners (default 180000)
   Returns the {server-id url} map (possibly empty on failure)."
  [{:keys [cmd dir base-port timeout-ms]
    :or {base-port 9700 timeout-ms 180000}}]
  (if (running?)
    (urls)
    (let [cmd (or cmd ["clojure" "-M:http" "--base-port" (str base-port)])
          dir (or dir (str (System/getProperty "user.dir") File/separator "grog_mcp"))
          urls (atom {})
          deadline (+ (System/currentTimeMillis) timeout-ms)]
      (try
        (let [pb (doto (ProcessBuilder. ^java.util.List (vec cmd))
                   (.directory (File. (str dir))))
              ;; Tell the endpoint which grog-server owns it, so it can exit when
              ;; we die (even a hard kill that skips OUR shutdown hook) — see the
              ;; watchdog in grog_mcp.http.
              _ (try (.put (.environment pb) "GROG_SERVER_PID"
                           (str (.pid (ProcessHandle/current))))
                     (catch Throwable _ nil))
              p (.start pb)]
          (drain! (BufferedReader. (InputStreamReader. (.getErrorStream p) "UTF-8")) urls)
          (loop [last-count 0 last-change (System/currentTimeMillis)]
            (let [n (count @urls)
                  now (System/currentTimeMillis)]
              (cond
                (>= n expected-servers) nil
                ;; endpoint child died (e.g. port already held by another grog):
                ;; stop waiting — otherwise boot blocks the full timeout
                (not (.isAlive p)) nil
                (> now deadline) nil
                (and (pos? n) (> (- now last-change) 5000)) nil  ; settled
                :else (do (Thread/sleep 250)
                          (recur n (if (= n last-count) last-change now))))))
          (reset! !ep {:process p :urls @urls})
          (binding [*out* *err*]
            (println (str "[grog] grog-mcp http endpoint: " (count @urls)
                          " listener(s) from " (pr-str cmd))))
          @urls)
        (catch Throwable e
          (binding [*out* *err*]
            (println "[grog] grog-mcp http endpoint failed to start:"
                     (.getMessage e)))
          {})))))

(defn stop!
  "Stop the endpoint process (if running) AND its children. The endpoint is a
  `clojure -M:http` WRAPPER whose real work is a `java` child, so a plain
  `.destroy` orphaned the JVM on Windows — see grog.platform/kill-tree!."
  []
  (when-let [{:keys [^Process process]} @!ep]
    (platform/kill-tree! process))
  (reset! !ep nil)
  nil)