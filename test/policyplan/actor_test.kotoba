(ns policyplan.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [policyplan.actor :as actor]
            [policyplan.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"
                                :mandate-scopes #{:it-security :hr}})
    (store/register-control-point! st {:cp-id "CP-1" :client-id "client-1"
                                       :name "password rotation"})
    st))

(deftest commits-an-in-mandate-policy-adoption
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :adopt-policy :stake :low
                 :control-point "CP-1" :directive :require :scope :it-security}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-contradicting-policy-adoption
  (let [st (fresh-store)]
    (store/register-policy! st {:policy-id "POL-1" :client-id "client-1"
                                :control-point "CP-1" :directive :forbid})
    (let [graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :adopt-policy :stake :low
                   :control-point "CP-1" :directive :require :scope :it-security}
          result (actor/run-request! graph request {} "thread-2")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest interrupts-then-repeals-on-human-approval
  (let [st (fresh-store)]
    (store/register-policy! st {:policy-id "POL-1" :client-id "client-1"
                                :control-point "CP-1" :directive :require})
    (let [graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :repeal-policy :stake :medium
                   :control-point "CP-1" :scope :it-security}
          interrupted (actor/run-request! graph request {} "thread-3")]
      (is (= :interrupted (:status interrupted)))
      (is (empty? (store/records-of st "client-1")))
      (let [resumed (actor/approve! graph "thread-3")]
        (is (= :done (:status resumed)))
        (is (= 1 (count (store/records-of st "client-1"))))))))
