(ns grog.with-api-key
  "HTTP tool that injects a keyring secret the LLM never sees.

  Configure `:with-api-key {:allowed-secrets [\"BRAVE_SEARCH_API\" …]}` (or legacy `:allowed-accounts`).
  Optional `:allowed-url-prefixes`, https-only by default, non-public hosts rejected."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [grog.config :as cfg]
            [grog.secrets :as secrets])
  (:import [java.net InetAddress URI URLDecoder]))

(def ^:private known-secret-methods
  "How the secret is attached; more values can be added over time."
  #{"header" "query" "bearer" "x_subscription_token"})

(defn- parse-json-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
        :else {}))

(defn- str-trim [x]
  (str/trim (str (or x ""))))

(defn- form-decode-map [^String q]
  (when-not (str/blank? q)
    (into {}
          (for [seg (.split q "&")
                :let [pair (.split seg "=" 2)
                      k (first pair)
                      v (second pair)]
                :when (not (str/blank? k))]
            [(URLDecoder/decode k "UTF-8")
             (URLDecoder/decode (str (or v "")) "UTF-8")]))))

(defn- split-url-base-and-query [^String url]
  (let [s (str/trim url)
        no-frag (if-let [i (str/index-of s "#")] (subs s 0 i) s)
        [base q] (if-let [i (str/index-of no-frag "?")]
                   [(subs no-frag 0 i) (subs no-frag (inc i))]
                   [no-frag nil])]
    {:base base :query q}))

(defn- private-or-local-host? [^String host]
  (try
    (let [ia (InetAddress/getByName host)]
      (or (.isLoopbackAddress ia) (.isLinkLocalAddress ia) (.isSiteLocalAddress ia)
          (.isMulticastAddress ia)))
    (catch Exception _ true)))

(defn- valid-param-name? [^String s]
  (boolean (and (not (str/blank? s))
                (re-matches #"(?i)[a-z0-9][a-z0-9._-]*" s)
                (< (count s) 128))))

(defn- normalize-secret-method [s]
  (when s
    (let [x (str/lower-case (str/trim (str s)))]
      (case x
        "" nil
        ("header" "http_header") "header"
        ("query" "http_query") "query"
        ("bearer" "http_authorization_bearer" "authorization_bearer" "http_bearer") "bearer"
        ("x_subscription_token" "x-subscription-token" "brave_subscription" "brave") "x_subscription_token"
        (when (known-secret-methods x) x)))))

(defn- parse-with-api-key-args [arguments]
  (let [m (parse-json-args arguments)
        url (str-trim (or (:url m) (get m "url")))
        ;; secret_name (preferred) or legacy secret_account
        secret-name (str-trim (or (:secret_name m) (get m "secret_name")
                                  (:secret_account m) (get m "secret_account")))
        header-name (str-trim (or (:header_name m) (get m "header_name")
                                  (:headerName m) (get m "headerName")
                                  (:auth_name m) (get m "auth_name")))
        query-param-name (str-trim (or (:query_param_name m) (get m "query_param_name")
                                         (:queryParamName m) (get m "queryParamName")
                                         (:query_param m) (get m "query_param")))
        header-prefix (str (or (:header_prefix m) (get m "header_prefix")
                               (:headerPrefix m) (get m "headerPrefix")
                               (:auth_prefix m) (get m "auth_prefix") ""))
        secret-method-raw (or (:secret_method m) (get m "secret_method")
                              (:secretMethod m) (get m "secretMethod")
                              (:auth_method m) (get m "auth_method"))
        legacy-placement (str/lower-case (str-trim (or (:auth_placement m) (get m "auth_placement") "")))
        secret-method (or (normalize-secret-method secret-method-raw)
                          (when (= "header" legacy-placement) "header")
                          (when (= "query" legacy-placement) "query"))
        ;; Legacy query used auth_name as param name
        query-param-name' (if (and (= "query" secret-method) (str/blank? query-param-name) (not (str/blank? header-name)))
                            header-name
                            query-param-name)
        http-method (str/upper-case (str-trim (or (:http_method m) (get m "http_method")
                                                  (:method m) (get m "method") "GET")))
        body (or (some-> (:body m) str) (some-> (get m "body") str))
        content-type (str-trim (or (:content_type m) (get m "content_type") (get m "contentType") ""))]
    {:url url
     :secret-name secret-name
     :secret-method secret-method
     :header-name header-name
     :query-param-name query-param-name'
     :header-prefix header-prefix
     :http-method http-method
     :body body
     :content-type content-type}))

(defn tool-spec
  []
  {:type "function"
   :function
   {:name "with_api_key"
    :description
    (str "HTTP request with a **secret from the OS keyring** applied for you — you never see the secret value. "
         "Pass **`secret_name`**: the keyring account name (same labels as `/secret` in chat). It must be listed "
         "under grog.edn `:with-api-key :allowed-secrets` (or `:allowed-accounts`). Pass **`secret_method`** to say "
         "how Grog attaches the secret (new schemes can be added over time). Prefer **https**. "
         "**Do not** put tokens or passwords in tool arguments — only `secret_name`.")
    :parameters
    {:type "object"
     :required ["url" "secret_name" "secret_method"]
     :properties
     {:url {:type "string"
            :description "Full URL (https). Existing query string is preserved; query-method merges the secret param."}
      :secret_name {:type "string"
                    :description "Keyring secret name (e.g. BRAVE_SEARCH_API) — must be in :with-api-key :allowed-secrets."}
      :secret_method {:type "string"
                      :enum ["header" "query" "bearer" "x_subscription_token"]
                      :description (str "header = custom HTTP header (set header_name; optional header_prefix e.g. \"Bearer \"). "
                                      "query = URL query parameter (set query_param_name). "
                                      "bearer = Authorization: Bearer <secret>. "
                                      "x_subscription_token = X-Subscription-Token: <secret> (e.g. Brave Search). "
                                      "Aliases accepted: http_header, http_query, authorization_bearer, brave_subscription.")}
      :header_name {:type "string"
                    :description "Required when secret_method is header — e.g. X-Api-Key, Authorization."}
      :header_prefix {:type "string"
                      :description "Optional; prepended before the secret when secret_method is header (e.g. \"Bearer \")."}
      :query_param_name {:type "string"
                         :description "Required when secret_method is query — query parameter name for the secret."}
      :method {:type "string"
               :description "HTTP verb: GET (default), POST, PUT, PATCH, DELETE, HEAD, OPTIONS. (Alias: http_method.)"}
      :http_method {:type "string"
                    :description "Same as method."}
      :body {:type "string"
             :description "Optional request body (POST/PUT/PATCH)."}
      :content_type {:type "string"
                     :description "Optional Content-Type for body (e.g. application/json)."}}}}})

(defn- tool-log-line [args]
  (let [{:keys [url secret-name secret-method http-method]} (parse-with-api-key-args args)
        u (if (> (count url) 100) (str (subs url 0 100) "…") url)]
    (str (pr-str http-method) " " (pr-str u) " secret_name=" (pr-str secret-name)
         " secret_method=" (pr-str secret-method))))

(defn tool-log-summary
  "For stderr logging (no secrets)."
  [args]
  (try (tool-log-line args) (catch Exception _ "(with_api_key args)")))

(defn run-with-api-key!
  [arguments]
  (if-not (cfg/with-api-key-configured?)
    (json/generate-string
     {:error "with_api_key is not configured"
      :hint "Set :with-api-key {:allowed-secrets [\"KEY_NAME\" …]} in grog.edn (legacy :allowed-accounts also works). Each name must be a /secret key. Optional: :allowed-url-prefixes, :max-response-chars, :allow-insecure-http."})
    (let [{:keys [url secret-name secret-method header-name query-param-name header-prefix http-method body content-type]}
          (parse-with-api-key-args arguments)
          allowed (set (cfg/with-api-key-allowed-accounts))
          prefixes (cfg/with-api-key-url-prefixes)
          max-ch (cfg/with-api-key-max-response-chars)]
      (cond
        (str/blank? url)
        (json/generate-string {:error "url is required"})

        (str/blank? secret-name)
        (json/generate-string {:error "secret_name is required"
                               :hint "Legacy secret_account is still accepted."})

        (not (contains? allowed secret-name))
        (json/generate-string {:error "secret_name not allowed for with_api_key"
                               :allowed (vec (sort allowed))})

        (not (secrets/known-account? secret-name))
        (json/generate-string {:error "unknown secret_name" :hint "Use /secret; name must be in known-secret-defs."})

        (str/blank? secret-method)
        (json/generate-string {:error "secret_method is required or use legacy auth_placement"
                               :allowed_methods (vec (sort known-secret-methods))})

        (not (known-secret-methods secret-method))
        (json/generate-string {:error "unknown secret_method"
                               :allowed_methods (vec (sort known-secret-methods))})

        (and (= "header" secret-method) (not (valid-param-name? header-name)))
        (json/generate-string {:error "header_name is required for secret_method header and must be a valid name"})

        (and (= "query" secret-method) (not (valid-param-name? query-param-name)))
        (json/generate-string {:error "query_param_name is required for secret_method query and must be a valid name"})

        (not (#{"GET" "POST" "PUT" "PATCH" "DELETE" "HEAD" "OPTIONS"} http-method))
        (json/generate-string {:error "unsupported HTTP method" :method http-method})

        :else
        (let [secret (secrets/get-secret secret-name)]
          (if (str/blank? secret)
            (json/generate-string {:error "secret not set in keyring" :secret_name secret-name})
            (try
              (let [^URI uri (URI/create url)
                    scheme (.getScheme uri)
                    host (.getHost uri)
                    sch (some-> scheme str/lower-case)]
                (cond
                  (str/blank? host)
                  (json/generate-string {:error "URL must include a host"})

                  (not (#{"http" "https"} sch))
                  (json/generate-string {:error "only http and https URLs are supported"})

                  (and (not (cfg/with-api-key-allow-http?))
                       (not= sch "https"))
                  (json/generate-string {:error "only https URLs are allowed (set :with-api-key :allow-insecure-http true to allow http)"})

                  (private-or-local-host? host)
                  (json/generate-string {:error "host is local or private; refused"})

                  (and (seq prefixes) (not (some #(str/starts-with? url %) prefixes)))
                  (json/generate-string {:error "url does not match any :allowed-url-prefixes"
                                         :prefixes prefixes})

                  :else
                  (let [{:keys [base query]} (split-url-base-and-query url)
                        qmap (merge (or (form-decode-map query) {})
                                    (when (= "query" secret-method)
                                      {query-param-name secret}))
                        headers (cond-> {"Accept" "*/*"}
                                  (= "header" secret-method)
                                  (assoc header-name (str header-prefix secret))

                                  (= "bearer" secret-method)
                                  (assoc "Authorization" (str "Bearer " secret))

                                  (= "x_subscription_token" secret-method)
                                  (assoc "X-Subscription-Token" secret)

                                  (not (str/blank? content-type))
                                  (assoc "Content-Type" content-type))
                        req (cond-> {:method (keyword (str/lower-case http-method))
                                     :url base
                                     :headers headers
                                     :query-params qmap
                                     :as :string
                                     :throw-exceptions false}
                              (and body (not (str/blank? body)))
                              (assoc :body body))
                        resp (http/request req)
                        st (:status resp)
                        ^String raw-body (or (:body resp) "")
                        ct (let [raw (or (get-in resp [:headers "content-type"])
                                         (get-in resp [:headers "Content-Type"]))]
                             (str (if (sequential? raw) (first raw) raw)))
                        n (count raw-body)
                        truncated? (> n max-ch)
                        out-body (if truncated? (subs raw-body 0 max-ch) raw-body)]
                    (json/generate-string
                     {:status st
                      :content_type ct
                      :body_char_count n
                      :body_truncated (boolean truncated?)
                      :max_response_chars max-ch
                      :body out-body}))))
              (catch IllegalArgumentException _
                (json/generate-string {:error "invalid URL"}))
              (catch Exception e
                (json/generate-string {:error (.getMessage e)})))))))))
