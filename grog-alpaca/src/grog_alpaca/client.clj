(ns grog-alpaca.client
  "Thin Alpaca REST client — trading + market data — with keyring credentials.

  Config: ~/.config/grog/alpaca.edn
    {:paper true            ;; paper (default) or live trading endpoints
     :allow-orders false    ;; read-only unless explicitly enabled
     :stock-feed \"iex\"       ;; stocks: iex (free) | sip | delayed_sip
     :options-feed \"indicative\" ;; options: indicative (free) | opra (paid)
     :timeout-ms 20000}

  Credentials come from the OS keyring (service \"grog\", accounts
  ALPACA_API_KEY / ALPACA_SECRET_KEY) — set them from grog chat:
      /secret set ALPACA_API_KEY <key>
      /secret set ALPACA_SECRET_KEY <secret>

  Endpoints:
    trading paper : https://paper-api.alpaca.markets
    trading live  : https://api.alpaca.markets
    market data   : https://data.alpaca.markets
  Auth headers: APCA-API-KEY-ID / APCA-API-SECRET-KEY."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http])
  (:import [com.github.javakeyring BackendNotSupportedException Keyring PasswordAccessException]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Config (file-based; env vars intentionally NOT read)
;; ---------------------------------------------------------------------------

(defn- config-file ^java.io.File []
  (io/file (or (some-> (System/getenv "HOME") str not-empty) "~")
           ".config/grog/alpaca.edn"))

(defn- load-config []
  (let [^java.io.File f (config-file)]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e (binding [*out* *err*] (println "alpaca config load error:" (.getMessage e))) {}))
      {})))

(defn cfg [] (load-config))

(defn paper?
  "True (default) → paper trading endpoints; false → live."
  []
  (not (false? (:paper (cfg)))))

(defn allow-orders?
  "Order placement is off unless `:allow-orders true`."
  []
  (true? (:allow-orders (cfg))))

(defn stock-feed []
  (let [v (:stock-feed (cfg))] (if (and (string? v) (seq v)) v "iex")))

(defn options-feed []
  (let [v (:options-feed (cfg))] (if (and (string? v) (seq v)) v "indicative")))

(defn timeout-ms [] (long (or (:timeout-ms (cfg)) 20000)))

;; ---------------------------------------------------------------------------
;; Keyring credentials (time-bounded so a hung D-Bus can't freeze a tool call)
;; ---------------------------------------------------------------------------

(def ^:private service-id "grog")
(defonce ^:private !keyring-unreachable (atom false))

(defn- fetch-secret-blocking! ^String [^String account]
  (with-open [^Keyring kr (Keyring/create)]
    (try
      (let [^String p (.getPassword kr service-id account)]
        (some-> p str str/trim not-empty))
      (catch PasswordAccessException _ nil))))

(defn secret ^String [^String account]
  (when-not @!keyring-unreachable
    (try
      (let [f (future
                (try
                  (fetch-secret-blocking! account)
                  (catch BackendNotSupportedException _ ::unsupported)
                  (catch Exception _ ::error)))
            v (deref f 4000 ::timeout)]
        (cond
          (= ::timeout v)
          (do (reset! !keyring-unreachable true)
              (binding [*out* *err*]
                (println "grog-alpaca: OS keyring did not respond within 4s; secret reads disabled for this process."))
              nil)
          (= ::unsupported v) nil
          (= ::error v) nil
          :else v))
      (catch Exception _ nil))))

(defn api-key [] (secret "ALPACA_API_KEY"))
(defn secret-key [] (secret "ALPACA_SECRET_KEY"))

(defn configured? []
  (boolean (and (api-key) (secret-key))))

(defn config-hint []
  (str "Alpaca credentials not set. Store them in the OS keyring (service \"grog\"): "
       "/secret set ALPACA_API_KEY <key>  and  /secret set ALPACA_SECRET_KEY <secret>. "
       "Account settings live in ~/.config/grog/alpaca.edn "
       "(defaults: {:paper true :allow-orders false})."))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- base-url ^String [kind]
  (case kind
    :data "https://data.alpaca.markets"
    (if (paper?) "https://paper-api.alpaca.markets" "https://api.alpaca.markets")))

(defn- auth-headers []
  {"APCA-API-KEY-ID" (api-key)
   "APCA-API-SECRET-KEY" (secret-key)
   "Accept" "application/json"})

(defn request
  "Perform an Alpaca request. `kind` is :trading or :data. `method` is :get/:post/:delete.
  `opts` may include :query (map) and :body (map → JSON). Returns parsed JSON
  (map/vector) on 2xx; throws ex-info with the status + body otherwise."
  [kind method ^String path {:keys [query body]}]
  (when-not (configured?)
    (throw (ex-info (config-hint) {})))
  (let [hdrs (cond-> (auth-headers) body (assoc "Content-Type" "application/json"))
        url  (str (base-url kind) path)
        opts (cond-> {:method method :url url :headers hdrs
                      :as :string :throw-exceptions false
                      :socket-timeout (timeout-ms) :conn-timeout 10000}
               query (assoc :query-params query)
               body  (assoc :body (json/generate-string body)))
        resp (http/request opts)
        st   (:status resp)
        ^String raw (str (:body resp))]
    (if (and st (>= (long st) 200) (< (long st) 300))
      (if (str/blank? raw) {} (json/parse-string raw true))
      (throw (ex-info (str "Alpaca HTTP " st " on " (name method) " " path ": "
                           (str/trim (subs raw 0 (min 500 (count raw)))))
                      {:status st :body raw})))))

(defn trading-get [path & [query]] (request :trading :get path {:query query}))
(defn data-get [path & [query]] (request :data :get path {:query query}))
(defn trading-post [path body] (request :trading :post path {:body body}))
(defn trading-delete [path] (request :trading :delete path nil))
