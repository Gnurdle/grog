(ns grog.e-trade
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as PP])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.net URLEncoder]))

;; Load configuration from resources/e-trade.edn
(def config (edn/read-string (slurp (io/resource "e-trade.edn"))))

;; Configuration: Replace these with your actual E-Trade credentials
(def ^:private consumer-key (:consumer-key config))
(def ^:private consumer-secret (:consumer-secret config))
(def ^:private access-token-key (:access-token-key config))
(def ^:private access-token-secret (:access-token-secret config))

(def base-url (:sandbox-base config))  ;; sandbox

(defn url-encode [s]
  (clojure.string/replace (URLEncoder/encode s "UTF-8") "+" "%20"))

(defn generate-signature [base-string secret]
  (let [mac (Mac/getInstance "HmacSHA1")
        key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA1")]
    (.init mac key)
    (String. (.encode (Base64/getEncoder) (.doFinal mac (.getBytes base-string "UTF-8"))) "UTF-8")))


;; ===
(defn build-oauth-params [consumer-key token & {:keys [verifier callback]}]
  (let [nonce     (str (rand-int 1000000000))
        timestamp (str (quot (System/currentTimeMillis) 1000))]
    (cond-> {"oauth_consumer_key"    consumer-key
             "oauth_nonce"           nonce
             "oauth_signature_method" "HMAC-SHA1"
             "oauth_timestamp"       timestamp
             "oauth_version"         "1.0"}
      token     (assoc "oauth_token" token)
      verifier  (assoc "oauth_verifier" verifier)
      callback  (assoc "oauth_callback" callback))))

;; Updated signed-request (now accepts callback + uses GET properly)
(defn signed-request [method uri & {:keys [query-params token token-secret verifier callback]}]
  (let [token        (if (= token ::none) nil (or token access-token-key))
        token-secret (or token-secret access-token-secret)
        params       (build-oauth-params consumer-key token :verifier verifier :callback callback)
        all-params   (merge params (or query-params {}))
        sorted-params (sort-by first all-params)               ; critical: sort by key
        param-string (str/join "&" (map #(str (url-encode (name (key %)))
                                             "="
                                             (url-encode (str (val %))))
                                        sorted-params))
        base-string  (str method "&" (url-encode uri) "&" (url-encode param-string))
        signing-key  (str consumer-secret "&" token-secret)
        signature    (generate-signature base-string signing-key)
        auth-params  (assoc params "oauth_signature" signature)
        ;; Build Authorization header correctly
        auth-header  (str "OAuth "
                          (str/join ", "
                                    (map #(str (name (key %))
                                               "=\""
                                               (if (= (key %) "oauth_signature")
                                                 (url-encode (val %))   ; signature must be encoded too
                                                 (url-encode (str (val %))))
                                               "\"")
                                         (sort-by first auth-params))))]
    (http/request
     (cond-> {:method (keyword (str/lower-case method))
              :url    uri
              :headers {"Authorization" auth-header}
              :throw-exceptions false}
       (= method "GET") (assoc :query-params query-params)))))

;; Fixed request-token (now uses GET + passes callback correctly)
(defn request-token []
  (let [uri      (str base-url "/oauth/request_token")
        response (signed-request "GET" uri
                                 :token ::none
                                 :token-secret ""
                                 :callback "oob")]
    (if (= (:status response) 200)
      (let [body (:body response)]
        (into {} (for [pair (str/split body #"&")]
                   (let [[k v] (str/split pair #"=")]
                     [(keyword k) v]))))
      (do
        (println "Request failed:" (:status response) (:body response))
        response))))
;;===

;; Helper function to make signed request

;; Backward compatibility
(defn signed-get [uri & {:keys [query-params]}]
  (signed-request "GET" uri :query-params query-params))

;; OAuth functions


(defn authorize-url [oauth-token]
  (str "https://us.etrade.com/e/t/etws/authorize?key=" (url-encode consumer-key) "&token=" (url-encode oauth-token) "&oauth_callback=oob"))

(defn exchange-for-access-token [request-token request-token-secret verifier]
  (let [uri (str base-url "/oauth/access_token")
        response (signed-request "GET" uri   ; ← was "POST" → change this
                                 :token request-token
                                 :token-secret request-token-secret
                                 :verifier verifier)]
    (if (= (:status response) 200)
      (let [body (:body response)]
        (into {} (for [pair (str/split body #"&")]
                   (let [[k v] (str/split pair #"=")]
                     [(keyword k) v]))))
      (do (println "Access token failed:" (:status response) (:body response))
          response))))

(defn set-access-tokens [access-token access-token-secret]
  (alter-var-root #'access-token-key (constantly access-token))
  (alter-var-root #'access-token-secret (constantly access-token-secret)))

;; Function to get stock prices for a list of symbols
(defn get-stock-prices [symbols]
  (let [symbols-str (str/join "," symbols)
        uri (str base-url "/v1/market/quote/" symbols-str)
        response (signed-get uri)
        data (json/parse-string (:body response) true)]
    ;; Parse QuoteResponse into a map of symbol -> quote data
    (into {} (for [quote (:QuoteResponse data)]
               [(:symbol quote) {:price (:lastTrade quote)
                                 :bid (:bid quote)
                                 :ask (:ask quote)
                                 :volume (:volume quote)}]))))

;; Function to get option chains for a list of symbols
;; Options are per underlying symbol, so we query each one
(defn ^:private get-option-prices [symbols & {:keys [expiry-year expiry-month expiry-day strike-price-near no-of-strikes]}]
  (into {} (for [symbol symbols]
             (let [params (cond-> {:symbol symbol}
                            expiry-year (assoc :expiryYear expiry-year)
                            expiry-month (assoc :expiryMonth expiry-month)
                            expiry-day (assoc :expiryDay expiry-day)
                            strike-price-near (assoc :strikePriceNear strike-price-near)
                            no-of-strikes (assoc :noOfStrikes no-of-strikes))
                   uri (str base-url "/v1/market/optionchains")
                   response (signed-get uri :query-params params)
                   data (json/parse-string (:body response) true)]
               [symbol (:OptionChainResponse data)]))))

(comment
  (def request-tokens (request-token))
  (PP/pprint {:request-tokens request-tokens})

  (def au (authorize-url (:oauth_token request-tokens)))
  (PP/pprint {:authorized au})
  (def access-tokens (exchange-for-access-token
                      (:oauth_token request-tokens)
                      (:oauth_token_secret request-tokens)
                      "XUWHQ"))
  (PP/pprint {:access-tokens access-tokens})
  )