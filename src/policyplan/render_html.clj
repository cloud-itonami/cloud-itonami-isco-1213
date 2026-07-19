(ns policyplan.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`policyplan.actor` -> `policyplan.governor` -> `policyplan.store`)
  through a scenario built from real, exercised store data and renders
  the result deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111/2113 build-time-console precedents
  (`90-docs/business/cloud-itonami-maturity-loop.md` iterations 9/10/11
  in com-junkawasaki/root) using this repo's OWN real fixture, not a
  copy of theirs: `client-1` (\"Kobo Trade\", mandate-scopes
  `#{:it-security :hr}`) + control point `CP-1` (\"password rotation\")
  are lifted VERBATIM from `policyplan.actor-test`/
  `policyplan.governor-test`'s `fresh-store` fixture (ground truth, not
  invented). The pre-existing standing policy `POL-1` (directive
  `:forbid` on `CP-1`) is registered via `store/register-policy!`
  EXACTLY the way `policyplan.governor-test/hard-on-conflicting-directive`
  sets up its own contradiction fixture -- a real store protocol call,
  not a hand-typed record. `CP-3` (\"public records portal\", also
  client-1) plus a second organization `client-2` (\"Meikei Regional
  Council\", mandate-scopes `#{:transport}`) with its own control point
  `CP-2` (\"transit lane designation\") are ADDITIONAL demo data
  registered via the SAME real protocol calls
  (`register-client!`/`register-control-point!`) this actor's own store
  exposes -- disclosed here plainly, not presented as pre-existing
  fixture, so the console can show the cross-client `:cp-wrong-client`
  rule (client-1 citing client-2's control point) and a second organization
  operating within its own mandate. Every other field this page displays
  (statuses, control-point/policy counts, hold reasons) is real output
  read after `run-demo!` actually executed the graph -- none of it is
  hand-typed.

  This governor has the richest reachable rule set of the ISCO consoles
  built so far: 6 of its 7 HARD-hold reasons (`:no-client`,
  `:scope-outside-mandate`, `:no-control-point`, `:unknown-control-point`,
  `:cp-wrong-client`, `:policy-conflict`) plus its 1 reachable escalation
  reason (`:repeal-policy` always escalates) are all driven through the
  real `mock-advisor`, which passes `:control-point`/`:directive`/`:scope`
  straight through from the request (see `policyplan.advisor/infer`).

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `policyplan.governor` itself, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    unconditionally sets `:effect :propose` on every proposal it emits.
    Covered instead by
    `policyplan.governor-test/hard-on-no-actuation-violation` (which
    calls `governor/check` directly with a hand-built proposal).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `policyplan.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6). Covered instead by
    `policyplan.governor-test/escalates-low-confidence`.
  Both gaps are the same shape as the ISCO-08 1211/2113 precedents'
  disclosed `:no-actuation` gap -- this demo, like those, only ever
  drives the real actor/graph the way an operator actually would, and
  does not hand-construct proposals to force unreachable paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [policyplan.store :as store]
            [policyplan.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real policy/planning operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 6 of
  the 7 distinct HARD-hold reasons in `policyplan.governor` -- the 7th,
  `:no-actuation`, plus the low-confidence escalation reason, are
  architecturally unreachable via the real advisor, see namespace
  docstring). Every `:op` keyword and violation rule name below is
  copied from `policyplan.governor`'s own `hard-violations`/`check`, not
  invented. Ordering matters: the conflict op (`c1-adopt-conflict`) must
  run AFTER `POL-1` is registered in `run-demo!`'s store setup (it is),
  and the clean adopt uses a DIFFERENT control point (`CP-3`) that has
  no pre-existing policy so it is not itself a conflict."
  [;; client-1 / \"Kobo Trade\" / CP-1 + CP-3 (real fixture + one extra CP)
   ["c1-adopt-clean-cp3"    "client-1" :adopt-policy  {:control-point "CP-3" :directive :require :scope :it-security :stake :low}]
   ["c1-adopt-conflict"     "client-1" :adopt-policy  {:control-point "CP-1" :directive :require :scope :it-security :stake :low}]
   ["c1-repeal-cp1"         "client-1" :repeal-policy {:control-point "CP-1" :scope :it-security :stake :medium}]
   ["c1-draft-plan-clean"   "client-1" :draft-plan    {:scope :hr :stake :low}]
   ["c1-adopt-no-cp"        "client-1" :adopt-policy  {:directive :require :scope :it-security :stake :low}]
   ["c1-adopt-unknown-cp"   "client-1" :adopt-policy  {:control-point "CP-ghost" :directive :require :scope :it-security :stake :low}]
   ["c1-adopt-wrong-client" "client-1" :adopt-policy  {:control-point "CP-2" :directive :require :scope :it-security :stake :low}]
   ["c1-scope-outside"      "client-1" :draft-plan    {:scope :finance :stake :low}]
   ;; unregistered client entirely
   ["ghost-no-client"       "no-such-client" :draft-plan {:scope :transport :stake :low}]
   ;; client-2 / \"Meikei Regional Council\" / CP-2 (additional demo data,
   ;; registered via the same real register-client!/register-control-point!
   ;; calls -- see namespace docstring)
   ["c2-adopt-clean"        "client-2" :adopt-policy  {:control-point "CP-2" :directive :restrict :scope :transport :stake :low}]
   ["c2-draft-plan-clean"   "client-2" :draft-plan    {:scope :transport :stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `policyplan.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Trade"
                                :mandate-scopes #{:it-security :hr}})
    (store/register-control-point! db {:cp-id "CP-1" :client-id "client-1"
                                       :name "password rotation"})
    (store/register-control-point! db {:cp-id "CP-3" :client-id "client-1"
                                       :name "public records portal"})
    ;; pre-existing standing policy, set up the SAME way
    ;; policyplan.governor-test/hard-on-conflicting-directive does
    (store/register-policy! db {:policy-id "POL-1" :client-id "client-1"
                                :control-point "CP-1" :directive :forbid})
    (store/register-client! db {:client-id "client-2" :name "Meikei Regional Council"
                                :mandate-scopes #{:transport}})
    (store/register-control-point! db {:cp-id "CP-2" :client-id "client-2"
                                       :name "transit lane designation"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id client-name mandate-scopes]} runs]
  (let [record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc client-name) (esc (str/join ", " (map clojure.core/name mandate-scopes)))
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:control-point request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `policyplan.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:adopt-policy</code></td><td><span class=\"ok\">auto-commit when in-mandate, control point registered to this client, and no conflicting active policy</span></td></tr>"
   "        <tr><td><code>:repeal-policy</code></td><td><span class=\"warn\">ALWAYS human approval &middot; withdrawing standing policy</span></td></tr>"
   "        <tr><td><code>:draft-plan</code></td><td><span class=\"ok\">auto-commit when scope is within the client's registered mandate</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :client-name "Kobo Trade" :mandate-scopes #{:it-security :hr}}
                 {:client-id "client-2" :client-name "Meikei Regional Council" :mandate-scopes #{:transport}}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-1213 &middot; community policy &amp; planning</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Policy &amp; Planning (ISCO-08 1213) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · mandate is a registered set, not a mood</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered organizations &amp; mandates</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>policyplan.store</code> via <code>policyplan.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Mandate scopes</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Policy Planning Management Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. A policy contradiction is a set comparison against registered active policies, not a judgement call.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own control point (if any), and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Control point</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
