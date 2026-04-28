(ns grog.render
  (:require [clojure.string :as str]))

(defn run-render_scene!
  "Render a simple 2D scene as ASCII art.
   Args: {:width 20, :height 10, :objects [{:type :circle|:rect, :x 5, :y 5, :w 2, :h 2, :char \"O\"}]}
   Returns: ASCII string."
  [args]
  (let [{:keys [width height objects] :or {width 20 height 10}} args
        grid (vec (repeat height (vec (repeat width " "))))]
    (let [updated-grid (reduce
                         (fn [g obj]
                           (let [{:keys [type x y w h char] :or {char "#"}} obj]
                             (case type
                               :circle (let [r (/ w 2)]
                                         (reduce (fn [gg [dx dy]]
                                                   (let [gx (+ x dx) gy (+ y dy)]
                                                     (if (and (>= gx 0) (< gx width) (>= gy 0) (< gy height))
                                                       (assoc-in gg [gy gx] char)
                                                       gg)))
                                                 g
                                                 (for [dx (range (- r) (inc r)) dy (range (- r) (inc r))
                                                       :when (<= (+ (* dx dx) (* dy dy)) (* r r))] [dx dy])))
                               :rect (reduce (fn [gg [dx dy]]
                                               (let [gx (+ x dx) gy (+ y dy)]
                                                 (if (and (>= gx 0) (< gx width) (>= gy 0) (< gy height))
                                                   (assoc-in gg [gy gx] char)
                                                   gg)))
                                             g
                                             (for [dx (range w) dy (range h)] [dx dy]))
                               g)))
                         grid objects)]
      (str/join "\n" (map #(str/join "" %) updated-grid)))))

(defn tool-spec []
  {:type "function"
   :function {:name "render_scene"
              :description "Render a simple 2D scene as ASCII art."
              :parameters {:type "object"
                           :properties {:width {:type "integer" :default 20}
                                       :height {:type "integer" :default 10}
                                       :objects {:type "array"
                                                 :items {:type "object"
                                                         :properties {:type {:type "string" :enum ["circle" "rect"]}
                                                                      :x {:type "integer"}
                                                                      :y {:type "integer"}
                                                                      :w {:type "integer"}
                                                                      :h {:type "integer"}
                                                                      :char {:type "string"}}}}}
                           :required ["objects"]}}})

(defn tool-log-summary [args]
  (str "Rendering scene with " (count (:objects args)) " objects"))