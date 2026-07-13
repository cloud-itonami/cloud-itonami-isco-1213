(ns policyplan.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [policyplan.store :as store]
            [policyplan.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"
                                :mandate-scopes #{:it-security :hr}})
    (store/register-control-point! st {:cp-id "CP-1" :client-id "client-1"
                                       :name "password rotation"})
    st))

(defn- adopt [directive]
  {:op :adopt-policy :effect :propose :control-point "CP-1"
   :directive directive :scope :it-security :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-mandate-no-conflict
  (let [st (fresh-store)
        v (governor/check req {} (adopt :require) st)]
    (is (:ok? v))))

(deftest ok-when-same-directive-already-active
  (testing "re-affirmation is not a contradiction"
    (let [st (fresh-store)]
      (store/register-policy! st {:policy-id "POL-1" :client-id "client-1"
                                  :control-point "CP-1" :directive :require})
      (let [v (governor/check req {} (adopt :require) st)]
        (is (:ok? v))))))

(deftest hard-on-conflicting-directive
  (testing "a contradiction is a set comparison, not a judgement call"
    (let [st (fresh-store)]
      (store/register-policy! st {:policy-id "POL-1" :client-id "client-1"
                                  :control-point "CP-1" :directive :forbid})
      (let [v (governor/check req {} (assoc (adopt :require)
                                            :confidence 0.99) st)]
        (is (:hard? v))
        (is (some #(= :policy-conflict (:rule %)) (:violations v)))))))

(deftest hard-on-scope-outside-mandate
  (testing "the mandate is a registered set, not a mood"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (adopt :require)
                                          :scope :corporate-finance
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :scope-outside-mandate (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (adopt :require) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (adopt :require)
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-invented-control-point
  (let [st (fresh-store)
        v (governor/check req {} (assoc (adopt :require)
                                        :control-point "CP-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-control-point (:rule %)) (:violations v)))))

(deftest hard-on-foreign-control-point
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"
                                :mandate-scopes #{:it-security}})
    (let [v (governor/check {:client-id "client-2"} {} (adopt :require) st)]
      (is (:hard? v))
      (is (some #(= :cp-wrong-client (:rule %)) (:violations v))))))

(deftest escalates-policy-repeal
  (let [st (fresh-store)
        v (governor/check req {} {:op :repeal-policy :effect :propose
                                  :control-point "CP-1" :scope :it-security
                                  :confidence 0.9 :stake :medium} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :draft-plan :effect :propose
                                  :scope :hr :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
