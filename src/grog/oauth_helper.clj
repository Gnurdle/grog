(ns grog.oauth-helper
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.net URLEncoder]))

;; Load config
(def config (edn/read-string (slurp (io/resource "e-trade.edn"))))

(defn url-encode [s]
  (URLEncoder/encode s "UTF-8"))

(defn generate-signature [base-string secret]
  (let [mac (Mac/getInstance "HmacSHA1")
        key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA1")]
    (.init mac key)
    (String. (.encode (Base64/getEncoder) (.doFinal mac (.getBytes base-string "UTF-8"))) "UTF-8")))

(defn build-oauth-params [consumer-key token]
  (let [nonce (str (rand-int 1000000))
        timestamp (str (quot (System/currentTimeMillis) 1000))]
    (cond-> {"oauth_consumer_key" consumer-key
             "oauth_nonce" nonce
             "oauth_signature_method" "HMAC-SHA1"
             "oauth_timestamp" timestamp
             "oauth_version" "1.0"}
      token (assoc "oauth_token" token)
      (not token) (assoc "oauth_callback" "oob"))))

(defn get-request-token []
  (let [consumer-key (:consumer-key config)
        consumer-secret (:consumer-secret config)
        url "https://apisb.etrade.com/v1/oauth/request_token"
        params (build-oauth-params consumer-key nil)
        sorted-params (sort params)
        param-string (str/join "&" (map #(str (url-encode (key %)) "=" (url-encode (val %))) sorted-params))
        base-string (str "POST&" (url-encode url) "&" (url-encode param-string))
        signature (generate-signature base-string (str consumer-secret "&"))
        auth-params (assoc params "oauth_signature" signature)
        auth-header (str "OAuth " (str/join ", " (map #(if (= (key %) "oauth_signature") (str (key %) "=\"" (val %) "\"") (str (key %) "=\"" (url-encode (val %)) "\"")) auth-params)))
        response (http/post url {:headers {"Authorization" auth-header "Content-Type" "application/x-www-form-urlencoded"} :throw-exceptions false})
        body (:body response)]
    (println "Response status:" (:status response))
    (println "Response body:" body)
    (if (= 200 (:status response))
      (let [token-map (into {} (map #(let [[k v] (str/split % #"=" 2)] [k v]) (str/split body #"&")))
            request-token (:oauth_token token-map)]
        (println "Request token:" request-token)
        (println "Authorize URL:" (str "https://us.etrade.com/e/t/etws/authorize?key=" consumer-key "&token=" request-token))
        request-token)
      (do (println "Failed to get request token")
          nil))))

(defn get-access-token [request-token verifier]
  (let [consumer-key (:consumer-key config)
        consumer-secret (:consumer-secret config)
        token-secret "" 
        url "https://apisb.etrade.com/v1/oauth/access_token"
        params (assoc (build-oauth-params consumer-key request-token) "oauth_verifier" verifier)
        sorted-params (sort params)
        param-string (str/join "&" (map #(str (url-encode (key %)) "=" (url-encode (val %))) sorted-params))
        base-string (str "POST&" (url-encode url) "&" (url-encode param-string))
        signature (generate-signature base-string (str consumer-secret "&" token-secret))
        auth-params (assoc params "oauth_signature" signature)
        auth-header (str "OAuth " (str/join ", " (map #(if (= (key %) "oauth_signature") (str (key %) "=\"" (val %) "\"") (str (key %) "=\"" (url-encode (val %)) "\"")) auth-params)))
        response (http/post url {:headers {"Authorization" auth-header "Content-Type" "application/x-www-form-urlencoded"} :throw-exceptions false})
        body (:body response)
        token-map (into {} (map #(let [[k v] (str/split % #"=" 2)] [k v]) (str/split body #"&")))]
    (println "Access token:" (:oauth_token token-map))
    (println "Access secret:" (:oauth_token_secret token-map))
    {:oauth_token (:oauth_token token-map) :oauth_token_secret (:oauth_token_secret token-map)}))

;; Usage: Run with args
;; For request: clojure -M -m grog.oauth-helper get-request
;; For access: clojure -M -m grog.oauth-helper get-access REQUEST_TOKEN VERIFIER

(let [[action & args] *command-line-args*]
  (case action
    "get-request" (get-request-token)
    "get-access" (apply get-access-token args)
    (println "Usage: clojure -M -m grog.oauth-helper {get-request|get-access REQUEST_TOKEN VERIFIER}")))