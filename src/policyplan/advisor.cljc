(ns policyplan.advisor
  "PolicyPlanningManagementAdvisor — proposes a policy/planning
  operation (adopt a policy, draft a plan, repeal a policy) for a
  registered organization. Swappable mock/llm; the advisor ONLY
  proposes — `policyplan.governor` checks the mandate set and the
  control-point conflict table independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :adopt-policy|:draft-plan|:repeal-policy
               :effect :propose :control-point str :directive kw
               :scope kw :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake control-point directive scope] :as request}]
  {:op op
   :effect :propose
   :control-point control-point
   :directive directive
   :scope scope
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a policy and planning advisor. Given a request, propose an
   :op, the :control-point, :directive and :scope, an honest
   :confidence and a :stake. Never step outside the registered mandate
   — the governor checks the mandate set and the conflict table.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
