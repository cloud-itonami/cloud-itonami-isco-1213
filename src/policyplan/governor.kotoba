(ns policyplan.governor
  "PolicyPlanningManagementGovernor — the independent safety/
  traceability layer for the ISCO-08 1213 community policy & planning
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section). Modeled on cloud-itonami-isco-4311's bookkeeping.governor.
  Policy-planning twist: the mandate is a REGISTERED set and a policy
  contradiction is a set comparison — jurisdiction is not a mood, and
  two active directives on one control point either agree or they
  don't; no judgement call is involved.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. mandate scope     — the proposal's :scope must be a member of
                           the client's registered :mandate-scopes
                           (no policy outside jurisdiction).
    4. control-point basis — an adopt/repeal must cite a REGISTERED
                           control point belonging to this client
                           (no invented loci).
    5. policy conflict   — an :adopt-policy whose :directive differs
                           from an ACTIVE registered policy on the
                           same control point is a contradiction
                           (detected by comparison, not judgement).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :repeal-policy (withdrawing standing policy).
    7. low confidence (< `confidence-floor`)."
  (:require [policyplan.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record cp conflicts]
  (let [{:keys [op control-point scope directive]} proposal
        cp-op? (contains? #{:adopt-policy :repeal-policy} op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and client-record scope
           (not (contains? (:mandate-scopes client-record) scope)))
      (conj {:rule :scope-outside-mandate
             :detail (str "scope " scope " は登録済み管轄 "
                          (:mandate-scopes client-record)
                          " の外（管轄は集合であって気分ではない）")})

      (and cp-op? (nil? control-point))
      (conj {:rule :no-control-point :detail "policy 操作は統制点の引用が必須（統制点の捏造禁止）"})

      (and cp-op? control-point (nil? cp))
      (conj {:rule :unknown-control-point :detail (str "未登録統制点: " control-point)})

      (and cp-op? cp (not= (:client-id cp) (:client-id request)))
      (conj {:rule :cp-wrong-client :detail "統制点が別 client のもの"})

      (and (= :adopt-policy op) cp directive
           (some #(not= directive (:directive %)) conflicts))
      (conj {:rule :policy-conflict
             :detail (str "統制点 " control-point " に矛盾する現行 policy — "
                          "directive " directive " vs "
                          (mapv :directive conflicts)
                          "（矛盾は集合照合で機械的に検出できる）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `policyplan.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        cp (some->> (:control-point proposal) (store/control-point store))
        conflicts (when cp
                    (store/policies-at store (:client-id request) (:cp-id cp)))
        hard (hard-violations {:request request :proposal proposal}
                              client-record cp conflicts)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :repeal-policy (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
