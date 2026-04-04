(ns grog.brave
  "Brave Web Search API (https://api.search.brave.com/res/v1/web/search)."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [grog.secrets :as secrets]))

(def ^:private api-url "https://api.search.brave.com/res/v1/web/search")

(defn tool-spec
  "Ollama `tools[]` entry for `brave_web_search`."
  []
  {:type "function"
   :function {:name "brave_web_search"
              :description (str "Search the public web via Brave Search. "
                                "Use for current events, facts you are unsure about, or anything "
                                "that needs up-to-date sources. Pass a concise search query.")
              :parameters {:type "object"
                           :required ["query"]
                           :properties {:query {:type "string"
                                                :description "Search query (keywords or question)."}
                                        :count {:type "integer"
                                                :description "Max results to return (1–10, default 5)."}}}}})

(defn parse-web-search-args
  "Parse Ollama `function.arguments` for brave_web_search (map or JSON string)."
  [arguments]
  (let [m (cond
            (map? arguments) arguments
            (string? arguments) (try (json/parse-string arguments true)
                                     (catch Exception _ {}))
            :else {})
        q (or (some-> (:query m) str str/trim not-empty)
              (some-> (get m "query") str str/trim not-empty))
        c (or (:count m) (get m "count"))]
    {:query q
     :count (if (number? c)
              (max 1 (min 10 (long c)))
              5)}))

(defn- format-hit [i {:keys [title url description]}]
  (str (inc i) ". **" (or title "(no title)") "**\n   "
       (or url "") "\n   "
       (str/trim (or description ""))))

(defn brave-api-key
  "Subscription token from OS keyring (service `grog`, account `BRAVE_SEARCH_API`)."
  []
  (secrets/get-secret secrets/brave-search-api-account))

(defn brave-search-configured?
  "True when an API key is present in the keyring."
  []
  (boolean (some-> (brave-api-key) not-empty)))

(defn- format-results-body [body]
  (let [results (vec (get-in body [:web :results]))]
    (if (empty? results)
      "No web results returned."
      (->> results
           (map-indexed format-hit)
           (str/join "\n\n")))))

(defn run-web-search!
  "Execute Brave web search. Returns a string for the model (or an error explanation)."
  [arguments]
  (if-let [api-key (brave-api-key)]
    (let [{:keys [query count]} (parse-web-search-args arguments)]
      (if (str/blank? query)
        "brave_web_search error: missing or empty `query` parameter."
        (try
          (let [resp (http/get api-url
                               {:headers {"X-Subscription-Token" api-key
                                           "Accept" "application/json"}
                                :query-params {:q query :count count}
                                :as :string
                                :throw-exceptions false})
                st (:status resp)
                ^String raw (or (:body resp) "")
                kbytes (/ (alength (.getBytes raw "UTF-8")) 1024.0)]
            (binding [*out* *err*]
              (printf "grog: brave_web_search retrieved %.1f kB\n" kbytes)
              (flush))
            (cond
              (= 200 st)
              (try
                (let [body (json/parse-string raw true)]
                  (str "Brave web search results for query: " query "\n\n" (format-results-body body)))
                (catch Exception e
                  (str "brave_web_search: invalid JSON in response: " (.getMessage e))))

              (= 422 st)
              (try
                (let [m (json/parse-string raw true)]
                  (str "Brave API 422: " (pr-str (or (:message m) m))))
                (catch Exception _
                  (str "Brave API HTTP 422: " (pr-str raw))))

              :else
              (str "Brave API HTTP " st ": " (pr-str raw))))
          (catch Exception e
            (str "brave_web_search failed: " (.getMessage e))))))
    (str "brave_web_search is not configured: store the token in the OS secret store (service \"grog\", account \""
         secrets/brave-search-api-account
         "\"), e.g. chat command /secret.")))
