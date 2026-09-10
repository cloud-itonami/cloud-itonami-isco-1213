(ns policyplan.store
  "SSoT for the ISCO-08 1213 community policy & planning actor (itonami
  actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client        — a registered organization {:client-id :name
                    :mandate-scopes #{kw}}. The mandate is a REGISTERED
                    set — a jurisdiction table, not a mood.
    control-point — a registered locus of policy {:cp-id :client-id
                    :name}. Policies may only attach to registered
                    control points (no invented loci).
    policy        — an adopted directive {:policy-id :client-id
                    :control-point :directive :status}. Two ACTIVE
                    policies on one control point must agree — a
                    contradiction is detectable by set comparison,
                    not judgement.
    record        — a committed operating record (adopted policy,
                    drafted plan, repeal) — written ONLY via
                    commit-record!.
    ledger        — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (control-point [s cp-id])
  (policies-at [s client-id cp-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-control-point! [s cp])
  (register-policy! [s policy])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (control-point [_ cp-id] (get-in @a [:control-points cp-id]))
  (policies-at [_ client-id cp-id]
    (filter #(and (= client-id (:client-id %))
                  (= cp-id (:control-point %))
                  (= :active (:status %)))
            (vals (:policies @a))))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-control-point! [s cp]
    (swap! a assoc-in [:control-points (:cp-id cp)] cp) s)
  (register-policy! [s policy]
    (swap! a assoc-in [:policies (:policy-id policy)]
           (merge {:status :active} policy)) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :control-points {}
                                    :policies {} :records [] :ledger []}
                                   seed)))))
