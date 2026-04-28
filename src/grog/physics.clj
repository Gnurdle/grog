(ns grog.physics
  (:import [org.jbox2d.dynamics World BodyDef BodyType FixtureDef Body CircleShape PolygonShape Vec2]))

(defn- vec2->map [^Vec2 v]
  {:x (.x v) :y (.y v)})

(defn- body->map [^Body body]
  {:position (vec2->map (.getPosition body))
   :angle (.getAngle body)
   :linear-velocity (vec2->map (.getLinearVelocity body))
   :angular-velocity (.getAngularVelocity body)
   :type (str (.getType body))})

(defn run-simulate-physics!
  "Simulate 2D physics for given bodies over time steps.
   Args: {:bodies [{:type :dynamic|:static|:kinematic, :shape :circle|:box, :position [x y], :size r|[w h], :density 1.0, ...}], :time-step 1/60, :velocity-iterations 6, :position-iterations 2, :steps 100}
   Returns: list of body states after each step."
  [args]
  (let [{:keys [bodies time-step velocity-iterations position-iterations steps]
         :or {time-step (/ 1.0 60.0) velocity-iterations 6 position-iterations 2 steps 10}} args
        world (World. (Vec2. 0.0 -9.81))]  ; gravity
    (doseq [body-spec bodies]
      (let [body-def (BodyDef.)
            {:keys [type position]} body-spec]
        (set! (.type body-def) (case type
                                 :dynamic BodyType/DYNAMIC
                                 :static BodyType/STATIC
                                 :kinematic BodyType/KINEMATIC
                                 BodyType/DYNAMIC))
        (set! (.position body-def) (Vec2. (first position) (second position)))
        (let [body (.createBody world body-def)
              fixture-def (FixtureDef.)
              {:keys [shape size density]} body-spec]
          (set! (.density fixture-def) (or density 1.0))
          (set! (.shape fixture-def) (case shape
                                       :circle (let [s (CircleShape.)] (set! (.m_radius s) (or size 1.0)) s)
                                       :box (let [s (PolygonShape.)] (.setAsBox s (/ (first size) 2.0) (/ (second size) 2.0)) s)
                                       (CircleShape.)))
          (.createFixture body fixture-def))))
    (loop [step 0 results []]
      (if (>= step steps)
        results
        (do
          (.step world time-step velocity-iterations position-iterations)
          (let [bodies-state (mapv body->map (for [i (range (.getBodyCount world))] (.getBodyList world i)))]
            (recur (inc step) (conj results bodies-state))))))))

(defn tool-spec []
  {:type "function"
   :function {:name "simulate_physics"
              :description "Simulate 2D physics using Box2D. Provide bodies with type, shape, position, etc., and simulation parameters. Returns body states over steps."
              :parameters {:type "object"
                           :properties {:bodies {:type "array"
                                                 :items {:type "object"
                                                         :properties {:type {:type "string" :enum ["dynamic" "static" "kinematic"]}
                                                                      :shape {:type "string" :enum ["circle" "box"]}
                                                                      :position {:type "array" :items {:type "number"} :minItems 2 :maxItems 2}
                                                                      :size {:oneOf [{:type "number"} {:type "array" :items {:type "number"} :minItems 2 :maxItems 2}]}
                                                                      :density {:type "number"}}}}
                                       :time-step {:type "number" :default 0.0167}
                                       :velocity-iterations {:type "integer" :default 6}
                                       :position-iterations {:type "integer" :default 2}
                                       :steps {:type "integer" :default 10}}
                           :required ["bodies"]}}})

(defn tool-log-summary [args]
  (str "Simulating " (count (:bodies args)) " bodies for " (:steps args 10) " steps"))