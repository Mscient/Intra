# Candidate-by-candidate evidence and decision

Review date 2026-11-08. This is a blocked evidence report. The two rows below are
the task's candidate slots, not newly selected strategy IDs or an instrument
universe. S05/S07/S09 are documentary archetypes only. **Zero candidates are
testable and zero market trials were run in this session.**

| Requested candidate | Testability | Decision | Exact candidate-specific blockers |
| --- | --- | --- | --- |
| Queue-aware market making in simulation/paper | NOT TESTABLE | INCOMPLETE | No owner-approved frozen MM card or fair-value/quote/size/inventory/cancel/queue rules, instruments or benchmark. No authorized event/depth/trade archive with dated identity/clock/session evidence. Available documented best-five snapshots do not reveal exact priority, additions/cancels or L3 queue behavior. No reviewed calibrated queue/fill/latency/impact model, economic inputs or risk limits. |
| One slower relative-value candidate | NOT TESTABLE | INCOMPLETE | No owner-selected frozen RV card. Pair/basis mechanism, legs, hedge/carry, thresholds, exits, relationship-break/legging policies and universe remain unspecified. S05 versus S07 is not chosen here. No authorized synchronized executable two-leg history, contract periods, dated costs/settlement/margin, fill/latency/impact/capacity evidence or benchmark. |

Common blockers are G0/G1/G2 approvals, missing independent source/right review,
unknown search/exposure history, exact chronological split and custody, approved
cost hurdle/net interval/adequacy and sensitivity acceptance. Neither candidate
can receive `REJECTED` without a valid frozen experiment. `INCOMPLETE` does not
mean failure to earn money. No eventual live universe is selected.

## Results by evidence layer

Every cell below is an unavailable measurement, not a numerical estimate. No
synthetic software result fills a market-evidence cell.

| Required output | MM result | RV result | Missing evidence / measurement rule |
| --- | --- | --- | --- |
| Signal count, gross edge/markouts | INCOMPLETE | INCOMPLETE | Approved information set, horizon/label, hypothesis, dated authorized inputs and benchmark. |
| Simulated fills and fill markouts | INCOMPLETE | INCOMPLETE | Frozen fill/cancel/partial/miss rules, opposing quotes/events and reviewed assumptions. Index markouts are not fills. |
| Realized spread, adverse selection, impact | INCOMPLETE | INCOMPLETE | Approved side/time/mid benchmark, horizons, execution events and incremental-impact attribution. Avoid double counting price effects already in P&L. |
| Brokerage, exchange/clearing, IPFT, SEBI, statutory taxes/STT/stamp/GST | INCOMPLETE | INCOMPLETE | Effective account/contract tariff, applicable bases/side/rounding/aggregation and reviewed source amendments. No absent charge is zero. |
| Spread crossing, slippage, impact, leg/hedge cost | INCOMPLETE | INCOMPLETE | Frozen executable prices and attribution; reviewed latency/size/participation assumptions and data. |
| Expiry/settlement/exercise costs | INCOMPLETE | INCOMPLETE | Exact dated contract events, authorized settlement/turnover basis and applicable charge schedules. Missing costs cannot support covered net P&L. |
| Funding, fixed/time allocation, capital charge | INCOMPLETE | INCOMPLETE | Private approved rates, allocation/aggregation period and denominator; distinguish financing from capital opportunity hurdle. |
| Gross and all-in net P&L/net markouts | INCOMPLETE | INCOMPLETE | All prior layers plus exact accounting, capitalization and aggregation. Existing modeled replay net subtracts its configured charges, not every economic component automatically. |
| 3× trading-cost hurdle | PENDING_METHOD_APPROVAL | PENDING_METHOD_APPROVAL | Candidate-specific frozen numerator, round-trip cost denominator, applicable components, units, aggregation and uncertainty treatment. Plan examples are not an approved application. |
| Capacity/participation, clip, turnover, holding time | INCOMPLETE | INCOMPLETE | Frozen limits/denominator, dated traded volume/depth/events, size path and joint-leg bottleneck. Snapshot displayed size is not total executable capacity. |
| Fill/reject/partial/missed/cancel counts and failure exits | INCOMPLETE | INCOMPLETE | All intent/lifecycle outcomes, including incomplete legs, residual orders, failed exits and latency. No survivor-only fill scoring. |
| Exposure, margin, drawdown and stress/session loss | INCOMPLETE | INCOMPLETE | Approved capital/loss/position/Greek limits, dated marks/model inputs and complete risk ledger. Margin proxy is not SPAN/ELM; capital is not acceptable loss. |
| Cost/latency/fill/market-regime sensitivities | INCOMPLETE | INCOMPLETE | Predeclared scenarios, magnitudes, model input availability and acceptance. No arbitrary realistic/calibrated scenario or favorable scenario selection. |
| Primary metric/benchmark, Sharpe/DSR/PBO/HAC | INCOMPLETE | INCOMPLETE | Approved candidate protocol, complete actual family and valid session returns/assumptions. HAC is SE-only and cannot supply a profitability bound. |
| Positive net holdout lower confidence bound | PENDING_METHOD_APPROVAL | PENDING_METHOD_APPROVAL | Method, estimand, level, sidedness, sample/assumption adequacy, acceptance rule, one-shot selection/report and authorized untouched data. |

Uncalibrated execution sensitivities are permissible only when an owner-approved
card specifies them and authorized data support their inputs. There is no such
card here. Toy FIFO books, next-bar fills and full/partial L1 IOC examples remain
explicit conditional software models. No exact passive fill, guaranteed flatten,
exchange margin or market capacity conclusion follows.

## Other existing proposals, kept separate

| Card / version | Present evidence | Decision consequence |
| --- | --- | --- |
| G1-S01 / 1 | PROPOSED_PENDING_OWNER_SELECTION; 15-minute opening-range directional index markout; freeze and all five approvals absent. | NOT TESTABLE / INCOMPLETE; no MM/RV substitution. |
| G1-S02 / 1 | DEFERRED; fixed 30-minute momentum, 15-minute cadence/label; same pending approvals. | NOT TESTABLE / INCOMPLETE. |
| G1-S03 / 1 | DEFERRED; first-15-minute gap reversal, verified prior close required; same pending approvals. | NOT TESTABLE / INCOMPLETE. |

The copied cards and companion register disclose three Wave A baselines and six
confirmatory signal comparisons. Their universe list is proposed, not approved.
Their exact split dates, sample adequacy/seed, data rights and source clocks remain
pending. The 2026-11-02 partial smoke and 2026-10-30 gap review are known historic
exposure disclosures; raw results were not accessed by this session.

## Decision record

Both required slots and overall G3: **INCOMPLETE**. No
`QUALIFIES_FOR_NEXT_PAPER_STAGE` or market-performance `REJECTED` result is supported.
A completed negative G3 review would be valid if every prerequisite and frozen
evaluation were satisfied and all candidates failed; this is an insufficient-
evidence review, not that completed negative outcome. G3 remains open, G5 closed,
and this record grants no forward paper or live authorization.
