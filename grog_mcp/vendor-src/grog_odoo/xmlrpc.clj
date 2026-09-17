(ns grog-odoo.xmlrpc
  "A minimal, self-contained XML-RPC client for Odoo's native API.

  Odoo exposes two XML-RPC endpoints:
    - {url}/xmlrpc/2/common  -> authenticate
    - {url}/xmlrpc/2/object  -> execute_kw (search_read/create/write/unlink/call)

  We build the XML-RPC `<methodCall>` body by hand and parse the
  `<methodResponse>` with the stdlib `clojure.xml` — no extra libraries beyond
  clj-http for the HTTP POST."
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.xml :as xml])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; --- encode -----------------------------------------------------------------

(defn- esc [s]
  (-> (str s)
      (str/replace #"&" "&amp;")
      (str/replace #"<" "&lt;")
      (str/replace #">" "&gt;")))

(defn- to-enc
  "Recursively normalize a value for XML-RPC encoding, converting Java
  collection types (which the MCP SDK delivers: java.util.List / java.util.Map
  / java.util.Set) into plain Clojure data.

  Without this, `sequential?` and `map?` are false for java.util collections,
  so they fall through to the string fallback and get sent as e.g.
  \"[[id, =, 2]]\" instead of a real array -> Odoo rejects the request with an
  XML-RPC fault."
  [v]
  (cond
    (instance? java.util.Map v)
    (into {}
          (map (fn [^java.util.Map$Entry e]
                 [(keyword (name (.getKey e))) (to-enc (.getValue e))])
               (.entrySet ^java.util.Map v)))

    (sequential? v)          (mapv to-enc v)
    (instance? java.util.List v) (mapv to-enc v)
    (instance? java.util.Set  v) (set (map to-enc v))
    :else v))

(defn encode-value
  "Encode a Clojure value as an XML-RPC `<value>` string."
  [v]
  (let [v (to-enc v)]
    (cond
      (nil? v)                "<value/>"
      (string? v)             (str "<value><string>" (esc v) "</string></value>")
      (keyword? v)            (encode-value (name v))
      (int? v)                (str "<value><int>" v "</int></value>")
      (integer? v)            (str "<value><int>" v "</int></value>")
    (float? v)              (str "<value><double>" v "</double></value>")
    (double? v)             (str "<value><double>" v "</double></value>")
    (boolean? v)            (str "<value><boolean>" (if v 1 0) "</boolean></value>")
    (map? v)                (str "<value><struct>"
                                 (apply str
                                        (for [[k val] v]
                                          (str "<member><name>"
                                               (esc (if (keyword? k) (name k) (str k)))
                                               "</name>"
                                               (encode-value val) "</member>")))
                                 "</struct></value>")
    (sequential? v)         (str "<value><array><data>"
                                 (apply str (map encode-value v))
                                 "</data></array></value>")
    :else                   (str "<value><string>" (esc (str v)) "</string></value>"))))

(defn- request-xml [method-name params]
  (str "<?xml version=\"1.0\"?>"
       "<methodCall><methodName>" (esc method-name) "</methodName><params>"
       (apply str (for [p params] (str "<param>" (encode-value p) "</param>")))
       "</params></methodCall>"))

;; --- decode -----------------------------------------------------------------

(declare decode-value)

(defn- kids-of [node] (:content node))

(defn text-of [node] (apply str (kids-of node)))

(defn- decode-array [node]
  (let [data (first (filter #(= :data (:tag %)) (kids-of node)))]
    (mapv decode-value (filter #(= :value (:tag %)) (kids-of data)))))

(defn- decode-struct [node]
  (into {}
        (for [m (filter #(= :member (:tag %)) (kids-of node))
              :let [name (->> (kids-of m)
                              (filter #(= :name (:tag %)))
                              first text-of)
                    val-node (->> (kids-of m)
                                  (filter #(= :value (:tag %)))
                                  first)]]
          [(keyword name) (decode-value val-node)])))

(defn- decode-value [node]
  (let [kids (kids-of node)]
    (cond
      (nil? kids) nil
      (string? (first kids)) (text-of node)
      :else
      (let [child (first kids)
            tag (:tag child)]
        (case tag
          :string  (text-of child)
          :int     (Integer/parseInt (str/trim (text-of child)))
          :i4      (Integer/parseInt (str/trim (text-of child)))
          :double  (Double/parseDouble (str/trim (text-of child)))
          :boolean (= "1" (str/trim (text-of child)))
          :nil     nil
          :array   (decode-array child)
          :struct  (decode-struct child)
          (text-of child))))))

(defn parse-response
  "Parse an XML-RPC `<methodResponse>` string into Clojure data. Throws on a
  `<fault>` (which is how Odoo reports model errors over HTTP 200)."
  [xml-str]
  (let [tree    (xml/parse (ByteArrayInputStream. (.getBytes xml-str StandardCharsets/UTF_8)))
        content (kids-of tree)
        fault   (first (filter #(= :fault (:tag %)) content))
        params  (first (filter #(= :params (:tag %)) content))]
    (cond
      fault
      (let [val-node (first (kids-of fault))]
        (throw (ex-info "Odoo XML-RPC fault"
                        (decode-value val-node))))

      params
      (let [val-node (->> (kids-of params)
                          (filter #(= :param (:tag %)))
                          first kids-of first)]
        (decode-value val-node))

      :else (throw (ex-info "Malformed XML-RPC response" {:body xml-str})))))

;; --- transport --------------------------------------------------------------

(defn- post [url xml-body]
  (let [resp (http/post url
                        {:body xml-body
                         :content-type "text/xml; charset=utf-8"
                         :accept "text/xml"
                         :throw-exceptions false
                         :socket-timeout 30000
                         :conn-timeout 10000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "Odoo XML-RPC HTTP error " (:status resp))
                      {:status (:status resp) :body (:body resp)})))
    (:body resp)))

(defn xmlrpc-call!
  "Call `endpoint` (e.g. \"common\" or \"object\") at `base-url` with
  `method-name` and `params`, returning the decoded result."
  [base-url endpoint method-name params]
  (let [url (str base-url "/xmlrpc/2/" endpoint)]
    (->> (request-xml method-name params)
         (post url)
         parse-response)))
