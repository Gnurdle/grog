(ns grog-alpaca.main
  "grog-alpaca — a standalone MCP server (over stdio) exposing **Alpaca** market
  data and trading to an ECA-driven agent loop.

  Read-only tools (always available):
    alpaca_clock          market open/closed + next open/close
    alpaca_account        account status, buying power, cash
    alpaca_positions      open positions (optionally one symbol)
    alpaca_orders         recent orders
    alpaca_order          one order by id
    alpaca_quote          latest quote/trade snapshot(s) for stocks
    alpaca_bars           historical bars (stocks)
    alpaca_option_chain   option chain (latest quote + greeks) for an underlying
    alpaca_assets         asset lookup / list
    alpaca_calendar       market calendar (trading days + hours)

  Order tools (DISABLED unless `:allow-orders true` in ~/.config/grog/alpaca.edn):
    alpaca_place_order    place a stock/option order
    alpaca_cancel_order   cancel an order by id

  Credentials: OS keyring, service `grog`, accounts ALPACA_API_KEY /
  ALPACA_SECRET_KEY (`/secret set …`). Endpoint (paper vs live) and feeds are in
  ~/.config/grog/alpaca.edn. See grog-alpaca/client.

  Wire into ECA:
    :mcpServers
      {\"grog-alpaca\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-alpaca' && clojure -M:mcp -m grog-alpaca.main\"]}}"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog-alpaca.client :as c])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- kargs
  "MCP passes `arguments` as a java.util.Map (not a Clojure map)."
  [arguments]
  (cond
    (map? arguments) arguments
    (instance? java.util.Map arguments)
    (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
    (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
    :else {}))

(defn- s [v] (some-> v str str/trim not-empty))

(defn- j
  "Pretty JSON for the model."
  ^String [x]
  (json/generate-string x {:pretty true}))

(defn- syms
  "Normalize a symbols argument (comma string or vector) to a comma string."
  [v]
  (cond
    (sequential? v) (str/join "," (map str v))
    (string? v) (let [t (str/replace v #"\s+" "")]
                  (when (seq t) t))
    :else nil))

(defn- orders-disabled-msg []
  (str "Order placement is DISABLED. Set `:allow-orders true` in ~/.config/grog/alpaca.edn to enable "
       "(with :paper false this places REAL orders)."))

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(defn build-tools []
  [{:name "alpaca_clock"
    :description "Alpaca market clock: whether the US market is open right now, plus the next open and close times. Use this before claiming any quote/stream is 'live'."
    :schema (json/generate-string {:type :object :properties {}})
    :fn (fn [_] (j (c/trading-get "/v2/clock")))}

   {:name "alpaca_account"
    :description "Alpaca trading account: status, buying power, cash, equity, and options approval level."
    :schema (json/generate-string {:type :object :properties {}})
    :fn (fn [_] (j (c/trading-get "/v2/account")))}

   {:name "alpaca_positions"
    :description "Open positions (or a single position when `symbol` is given): qty, avg entry price, market value, unrealized P/L."
    :schema (json/generate-string {:type :object
                                   :properties {:symbol {:type :string :description "Optional single symbol."}}})
    :fn (fn [a]
          (let [a (kargs a)
                sym (s (:symbol a))]
            (j (if sym (c/trading-get (str "/v2/positions/" sym))
                   (c/trading-get "/v2/positions")))))}

   {:name "alpaca_orders"
    :description "Recent orders. `status` is open|closed|all (default open)."
    :schema (json/generate-string {:type :object
                                   :properties {:status {:type :string :description "open (default) | closed | all"}
                                                :limit {:type :integer :description "Max orders (default 50)."}
                                                :symbols {:type :string :description "Optional comma-separated symbols."}
                                                :side {:type :string :description "Optional buy|sell."}}})
    :fn (fn [a]
          (let [a (kargs a)
                status (or (s (:status a)) "open")
                limit (if (number? (:limit a)) (long (:limit a)) 50)
                q (cond-> {"status" status "limit" (str limit) "direction" "desc"}
                    (syms (:symbols a)) (assoc "symbols" (syms (:symbols a)))
                    (s (:side a)) (assoc "side" (s (:side a))))]
            (j (c/trading-get "/v2/orders" q))))}

   {:name "alpaca_order"
    :description "Fetch one order by its id."
    :schema (json/generate-string {:type :object
                                   :properties {:order_id {:type :string}}
                                   :required ["order_id"]})
    :fn (fn [a]
          (let [a (kargs a)
                id (s (:order_id a))]
            (when-not id (throw (ex-info "Missing required :order_id" {})))
            (j (c/trading-get (str "/v2/orders/" id)))))}

   {:name "alpaca_quote"
    :description "Latest quote/trade snapshot(s) for one or more STOCK symbols (bid/ask, last trade, today's bar). Pass `symbols` as a comma string (e.g. \"AAPL,MSFT\")."
    :schema (json/generate-string {:type :object
                                   :properties {:symbols {:type :string :description "Comma-separated stock symbols."}
                                                :feed {:type :string :description "Optional: iex (default) | sip | delayed_sip."}}
                                   :required ["symbols"]})
    :fn (fn [a]
          (let [a (kargs a)
                ss (syms (:symbols a))]
            (when-not ss (throw (ex-info "Missing required :symbols" {})))
            (j (c/data-get "/v2/stocks/snapshots"
                           (cond-> {"symbols" ss}
                             (s (:feed a)) (assoc "feed" (s (:feed a))))))))}

   {:name "alpaca_bars"
    :description "Historical STOCK bars. `timeframe` is e.g. 1Min|5Min|15Min|1Hour|1Day (default 1Day). `start`/`end` are RFC3339 (e.g. 2024-01-01T00:00:00Z) or a date. Returns a map of symbol → bars."
    :schema (json/generate-string {:type :object
                                   :properties {:symbols {:type :string :description "Comma-separated stock symbols."}
                                                :timeframe {:type :string :description "1Min|5Min|15Min|1Hour|1Day (default 1Day)."}
                                                :start {:type :string :description "RFC3339 or YYYY-MM-DD."}
                                                :end {:type :string :description "RFC3339 or YYYY-MM-DD."}
                                                :limit {:type :integer :description "Max bars (default 1000)."}
                                                :adjustment {:type :string :description "raw|split|dividend|all (default raw)."}
                                                :feed {:type :string :description "iex (default) | sip | delayed_sip."}}
                                   :required ["symbols"]})
    :fn (fn [a]
          (let [a (kargs a)
                ss (syms (:symbols a))]
            (when-not ss (throw (ex-info "Missing required :symbols" {})))
            (j (c/data-get "/v2/stocks/bars"
                           (cond-> {"symbols" ss
                                    "timeframe" (or (s (:timeframe a)) "1Day")
                                    "limit" (str (if (number? (:limit a)) (long (:limit a)) 1000))}
                             (s (:start a)) (assoc "start" (s (:start a)))
                             (s (:end a)) (assoc "end" (s (:end a)))
                             (s (:adjustment a)) (assoc "adjustment" (s (:adjustment a)))
                             (s (:feed a)) (assoc "feed" (s (:feed a))))))))}

   {:name "alpaca_option_chain"
    :description "Option chain for an underlying: latest quote + trade + greeks per contract. `feed` defaults to indicative (free); set opra if subscribed. Filter by type/expiration/strike to keep the response small. NOTE: real-time OPRA options require the paid data plan; the free 'indicative' feed is real-time but randomized, and historical options only go back to Feb 2024."
    :schema (json/generate-string {:type :object
                                   :properties {:underlying {:type :string :description "Underlying symbol, e.g. AAPL."}
                                                :type {:type :string :description "Optional: call | put."}
                                                :expiration_date {:type :string :description "YYYY-MM-DD."}
                                                :expiration_date_gte {:type :string}
                                                :expiration_date_lte {:type :string}
                                                :strike_price_gte {:type :number}
                                                :strike_price_lte {:type :number}
                                                :limit {:type :integer :description "Max contracts (default 100)."}
                                                :feed {:type :string :description "indicative (default) | opra."}}
                                   :required ["underlying"]})
    :fn (fn [a]
          (let [a (kargs a)
                u (s (:underlying a))]
            (when-not u (throw (ex-info "Missing required :underlying" {})))
            (j (c/data-get (str "/v1beta1/options/snapshots/" u)
                           (cond-> {"limit" (str (if (number? (:limit a)) (long (:limit a)) 100))
                                    "feed" (or (s (:feed a)) (c/options-feed))}
                             (s (:type a)) (assoc "type" (s (:type a)))
                             (s (:expiration_date a)) (assoc "expiration_date" (s (:expiration_date a)))
                             (s (:expiration_date_gte a)) (assoc "expiration_date_gte" (s (:expiration_date_gte a)))
                             (s (:expiration_date_lte a)) (assoc "expiration_date_lte" (s (:expiration_date_lte a)))
                             (:strike_price_gte a) (assoc "strike_price_gte" (str (:strike_price_gte a)))
                             (:strike_price_lte a) (assoc "strike_price_lte" (str (:strike_price_lte a))))))))}

   {:name "alpaca_assets"
    :description "Look up one asset by symbol, or list assets (status/asset_class filters). asset_class: us_equity | us_option | crypto."
    :schema (json/generate-string {:type :object
                                   :properties {:symbol {:type :string :description "Optional single symbol to look up."}
                                                :status {:type :string :description "active | inactive (default active)."}
                                                :asset_class {:type :string :description "us_equity (default) | us_option | crypto."}}})
    :fn (fn [a]
          (let [a (kargs a)
                sym (s (:symbol a))]
            (j (if sym
                 (c/trading-get (str "/v2/assets/" sym))
                 (c/trading-get "/v2/assets"
                                (cond-> {"status" (or (s (:status a)) "active")
                                         "asset_class" (or (s (:asset_class a)) "us_equity")}))))))}

   {:name "alpaca_calendar"
    :description "US market calendar: trading days and session open/close times between `start` and `end` (YYYY-MM-DD)."
    :schema (json/generate-string {:type :object
                                   :properties {:start {:type :string :description "YYYY-MM-DD (default today)."}
                                                :end {:type :string :description "YYYY-MM-DD (default today+7)."}}})
    :fn (fn [a]
          (let [a (kargs a)
                today (str (java.time.LocalDate/now))
                q (cond-> {"start" (or (s (:start a)) today)}
                    (s (:end a)) (assoc "end" (s (:end a)))
                    (not (s (:end a))) (assoc "end" (str (.plusDays (java.time.LocalDate/now) 7))))]
            (j (c/trading-get "/v2/calendar" q))))}

   ;; --- order placement (gated) -------------------------------------------
   {:name "alpaca_place_order"
    :description (str "PLACE AN ORDER (stocks or options). Disabled unless `:allow-orders true` in alpaca.edn; "
                      "with :paper false this sends a REAL order. Provide :symbol, :qty (or :notional), :side (buy|sell), "
                      ":type (market|limit|stop|stop_limit), :time_in_force (day|gtc|opg|cls|ioc|fok); add :limit_price/:stop_price "
                      "for those types. Options multi-leg: set :order_class \"mleg\" and pass :legs.")
    :schema (json/generate-string {:type :object
                                   :properties {:symbol {:type :string}
                                                :qty {:type :string :description "Whole number of shares/contracts."}
                                                :notional {:type :string :description "Dollar amount (fractional); mutually exclusive with qty."}
                                                :side {:type :string :description "buy | sell"}
                                                :type {:type :string :description "market | limit | stop | stop_limit"}
                                                :time_in_force {:type :string :description "day | gtc | opg | cls | ioc | fok"}
                                                :limit_price {:type :string}
                                                :stop_price {:type :string}
                                                :extended_hours {:type :boolean :description "Stocks only; requires a limit/type that supports it."}
                                                :order_class {:type :string :description "simple | bracket | oco | mleg"}
                                                :take_profit {:type :object :description "Bracket take-profit leg."}
                                                :stop_loss {:type :object :description "Bracket stop-loss leg."}
                                                :legs {:type :array :description "Multi-leg option legs (mleg)."}
                                                :client_order_id {:type :string}}
                                   :required ["symbol" "side" "type" "time_in_force"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (if-not (c/allow-orders?)
              (orders-disabled-msg)
              (let [sym (s (:symbol a)) side (s (:side a))
                    typ (s (:type a)) tif (s (:time_in_force a))
                    missing (->> [["symbol" sym] ["side" side] ["type" typ] ["time_in_force" tif]]
                                 (remove (comp seq second)) (map first) seq)]
                (when missing
                  (throw (ex-info (str "Missing required: " (str/join ", " missing)) {})))
                (let [body (cond-> {:symbol sym :side side :type typ :time_in_force tif}
                             (:qty a) (assoc :qty (str (:qty a)))
                             (:notional a) (assoc :notional (str (:notional a)))
                             (:limit_price a) (assoc :limit_price (str (:limit_price a)))
                             (:stop_price a) (assoc :stop_price (str (:stop_price a)))
                             (contains? a :extended_hours) (assoc :extended_hours (boolean (:extended_hours a)))
                             (s (:order_class a)) (assoc :order_class (s (:order_class a)))
                             (:take_profit a) (assoc :take_profit (:take_profit a))
                             (:stop_loss a) (assoc :stop_loss (:stop_loss a))
                             (:legs a) (assoc :legs (:legs a))
                             (s (:client_order_id a)) (assoc :client_order_id (s (:client_order_id a))))]
                  (j (c/trading-post "/v2/orders" body)))))))}

   {:name "alpaca_cancel_order"
    :description "Cancel an order by id. Disabled unless `:allow-orders true` in alpaca.edn."
    :schema (json/generate-string {:type :object
                                   :properties {:order_id {:type :string}}
                                   :required ["order_id"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (if-not (c/allow-orders?)
              (orders-disabled-msg)
              (let [id (s (:order_id a))]
                (when-not id (throw (ex-info "Missing required :order_id" {})))
                (c/trading-delete (str "/v2/orders/" id))
                (str "Cancelled " id)))))}])

;; ---------------------------------------------------------------------------
;; MCP wiring
;; ---------------------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try (.success sink (text-result (str (fn arguments))))
                   (catch Throwable t
                     (.success sink (error-result
                                     (str "Error executing tool " name ": "
                                          (or (:message (ex-data t)) (.getMessage t))))))))))))))

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-alpaca" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (doseq [t (build-tools)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))
