# cloud-itonami-isco-1213

Open Business Blueprint for **ISCO-08 1213**: Policy and Planning Managers — an ISCO
**Wave 1 (design & governance)** occupation per ADR-2607121000. This
is the FIRST wave-1 blueprint batch: management work is cognitive
(no robotics gate), sequenced after the wave-0 cognitive substrate in
rollout priority.

**Maturity: `:implemented`** — PolicyPlanningManagementAdvisor ⊣
PolicyPlanningManagementGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 27 assertions green.

The policy-planning HARD invariants — both registered sets, checked
deterministically:

1. **Mandate scope containment** — a proposal's scope must be a member
   of the organization's registered mandate set. Jurisdiction is a
   set, not a mood.
2. **Control-point conflict** — an adopted directive that differs from
   an active registered policy on the same control point is a
   contradiction, detected by comparison, not judgement.

Also HARD: invented/foreign control points, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:repeal-policy` (withdrawing standing policy), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
