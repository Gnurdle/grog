(ns grog.ai)

(defn run-simulate-ai!
  "Simulate a simple AI state machine.
   Args: {:states {:state1 {:transitions [{:condition fn, :to :state2}] :action fn}}, :initial-state :state1, :inputs [...], :steps 10}
   Returns: sequence of states and actions."
  [args]
  (let [{:keys [states initial-state inputs steps]
         :or {steps 10}} args]
    (loop [current-state initial-state
           input-idx 0
           step 0
           results []]
      (if (>= step steps)
        results
        (let [state-def (get states current-state)
              input (get inputs input-idx {})
              transitions (:transitions state-def [])
              next-state (or (some #(when ((:condition %) input) (:to %)) transitions) current-state)
              action ((:action state-def) input)]
          (recur next-state (mod (inc input-idx) (count inputs)) (inc step)
                 (conj results {:state current-state :action action :input input})))))))

(defn tool-spec []
  {:type "function"
   :function {:name "simulate_ai"
              :description "Simulate a simple AI state machine with states, transitions, and actions."
              :parameters {:type "object"
                           :properties {:states {:type "object"
                                                 :additionalProperties {:type "object"
                                                                        :properties {:transitions {:type "array"
                                                                                                   :items {:type "object"
                                                                                                           :properties {:condition {:type "string"} :to {:type "string"}}}}
                                                                                     :action {:type "string"}}}}
                                       :initial-state {:type "string"}
                                       :inputs {:type "array" :items {:type "object"}}
                                       :steps {:type "integer" :default 10}}
                           :required ["states" "initial-state"]}}})

(defn tool-log-summary [args]
  (str "Simulating AI with " (count (:states args)) " states for " (:steps args 10) " steps"))