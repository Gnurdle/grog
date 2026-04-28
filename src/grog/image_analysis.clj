(ns grog.image-analysis
  "Image analysis tool using Ollama vision models."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.fs :as fs])
  (:import [java.io File FileInputStream]
           [java.util Base64]))

(defn- valid-image-extension? [path]
  (let [lower (str/lower-case path)]
    (some #(str/ends-with? lower %) [".png" ".jpg" ".jpeg"])))

(defn- parse-args [arguments]
  (let [m (cond (map? arguments) arguments
                (string? arguments) (try (json/parse-string arguments true)
                                         (catch Exception _ {}))
                :else {})
        path (or (some-> (:path m) str str/trim not-empty)
                 (some-> (get m "path") str str/trim not-empty))
        prompt (or (some-> (:prompt m) str str/trim not-empty)
                   (some-> (get m "prompt") str str/trim not-empty))
        model (or (some-> (:model m) str str/trim not-empty)
                  (some-> (get m "model") str str/trim not-empty)
                  (config/ollama-vision-model)
                  (config/model)
                  "llava")]
    {:path path :prompt prompt :model model}))

(defn- encode-image-base64 [^File f]
  (with-open [in (FileInputStream. f)]
    (let [bytes (byte-array (.length f))]
      (.read in bytes)
      (.encodeToString (Base64/getEncoder) bytes))))

(defn analyze-workspace-image-tool-spec []
  {:type "function"
   :function
   {:name "analyze_workspace_image"
    :description (str "Analyze an image using an Ollama vision model. "
                      "Supports PNG, JPG, JPEG files under the workspace. "
                      "Returns AI-generated description/analysis based on the prompt.")
    :parameters {:type "object"
                 :required ["path" "prompt"]
                 :properties {:path {:type "string"
                                     :description "Path to image file under workspace (PNG/JPG/JPEG)"}
                              :prompt {:type "string"
                                       :description "Analysis prompt/question about the image"}
                              :model {:type "string"
                                      :description "Vision model name (default: configured Ollama model or llava)"}}}}})

(defn run-analyze-workspace-image! [arguments]
  (let [{:keys [path prompt model]} (parse-args arguments)]
    (cond
      (str/blank? path)
      (json/generate-string {:error "path is required"})

      (str/blank? prompt)
      (json/generate-string {:error "prompt is required"})

      :else
      (try
        (let [f (fs/resolve-workspace-path! path)]
          (cond
            (not (.exists f))
            (json/generate-string {:error "image file not found" :path path})

            (not (.isFile f))
            (json/generate-string {:error "not a regular file" :path path})

            (not (valid-image-extension? (.getName f)))
            (json/generate-string {:error "file must be PNG, JPG, or JPEG" :path path})

            (> (.length f) (config/ollama-vision-max-image-bytes))
            (let [max-b (config/ollama-vision-max-image-bytes)]
              (json/generate-string {:error (str "image too large (max "
                                                 (quot max-b 1048576)
                                                 " MiB; set :ollama :vision :max-image-mb or :max-image-bytes)")
                                     :path path
                                     :size_bytes (.length f)
                                     :max_bytes max-b}))

            :else
            (let [b64 (encode-image-base64 f)
                  url (str (str/trim (config/ollama-url)) "/api/chat")
                  body-map {:model model
                            :messages [{:role "user" :content prompt :images [b64]}]
                            :stream false}
                  resp (http/post url {:content-type "application/json"
                                       :body (json/generate-string body-map)
                                       :as :json
                                       :throw-exceptions false})
                  status (:status resp)
                  parsed (:body resp)]
              (if (= 200 status)
                (let [text (some-> parsed :message :content str str/trim not-empty)]
                  (if text
                    (json/generate-string {:format "image_analysis"
                                           :path path
                                           :model model
                                           :prompt prompt
                                           :analysis text})
                    (json/generate-string {:error "model returned empty analysis"})))
                (json/generate-string {:error (str "Ollama API error: HTTP " status " - " (pr-str parsed))})))))

        (catch Exception e
          (json/generate-string {:error (str "analysis failed: " (.getMessage e))}))))))