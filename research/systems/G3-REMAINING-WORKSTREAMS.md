# G3 remaining workstreams

**As of 2026-11-11: G3 OPEN / INCOMPLETE. No candidate or eligible trading universe is promoted.** This is the execution and review checklist for the user's three remaining workstreams. It does not freeze a strategy, supply missing data, or report an evaluation result.

G3 closes only when reviewed evidence supports promoting a candidate **and** identifies its eligible trading universe. A completed evaluation in which all candidates fail is a valid research outcome; **it does not close G3**. A deadline, software PASS, synthetic replay, or completed evidence inventory cannot close the gate. Continue research/offline paper work within existing authority when no candidate qualifies.

This closure rule clarifies the historical packet's phrase “completed negative G3.” The [2026-11-08 packet](g3-strategy-evidence-2026-11-08/README.md) and its hashed snapshots remain historical records; their bytes are preserved. The [Phase 4 continuation](../design-study/PHASE-4-CONTINUED-13-15.md), especially F7, D39 and D41, supplies the economic and universe requirements.

## Current evidence and intake

| Item | Established by inspected records | Remaining requirement |
| --- | --- | --- |
| G0/G1 | G0 open; three authored G1 signal cards with no freeze/approval | Actual prerequisite decisions, executable candidate selection, completed card and independent review |
| G2 | Offline permission, quality, replay, cost and accounting tooling exists | Authorized dated corpus, source validation, calibrated execution assumptions and qualified economic inputs |
| G3 candidates | Historical MM and slower RV slots are NOT TESTABLE / INCOMPLETE | Complete candidate-specific inputs and protocol before evaluation |
| Holdout | No verified custody/release supplied by the inspected packet | Untouched periods, independently controlled access, frozen selection and one-shot evaluation/report |
| Newer package | User indicated an existing package on 2026-11-11; its path/reference has not yet been supplied | Inspect package identity and review/authorization metadata before opening any market outcomes |
| Independent review | Review handoffs exist; no completed candidate/data/result review identified | Qualified reviewer, declared independence, reproduction and recorded disposition |

The current shared checkout contains unrelated work. This update is isolated from fetched `origin/main` at `6c398f1e8d090a527f413d713dc7d294e411c548`. Supplemental G1 preparation at local commit `a385277`, file `research/systems/g1-closure-prep-2026-11-09/OWNER-DECISIONS.md`, was inspected; its owner decisions remain pending. That local branch is not claimed as published or integrated. No market dataset or holdout financial artifact was opened for this update.

## 1. Freeze the strategy and test plan — G1 prerequisite

**Deliverable:** a reviewed, versioned strategy card plus an immutable test protocol and reconciled variant/exposure history. Use the [strategy-card template](../../Intra-Paper-System/docs/STRATEGY-CARD-TEMPLATE.md), [G1 foundations](G1-FOUNDATIONS-2026-11-08.md), existing card registry and trial/outcome ledgers.

- [ ] Select the executable candidate scope. Resolve the plan's MM/slower-RV requirements explicitly; the three index-signal cards do not replace a traded-contract strategy.
- [ ] Specify hypothesis and competing explanations; exact instruments and dated contract/roll selection; timezone, trading hours, entry cutoff and flat deadline; available information and warm-up; signal, entry, exit, failed-exit and no-trade rules; sizing, working-order exposure, margin, risk/loss limits and reasons to stop. Reference private limits without publishing account values.
- [ ] Fix the benchmark/no-trade baseline, observation unit, denominators, primary metric, economic and statistical acceptance rules, sample adequacy, planned sensitivity grid and search budget before evaluating outcomes.
- [ ] Predeclare the test universe and objective eligibility/exclusion rules. Fix exact chronological development, validation and sealed holdout sessions before their use. Use permitted coverage metadata to establish feasibility; preserve reasons for missing/excluded sessions. Purge or embargo actual feature/label/position overlap as specified in the protocol.
- [ ] Record every attempted candidate, parameter, universe, feature, filter, cost/fill assumption, seed and dataset/split revision, including failed, abandoned and partially viewed attempts. Link code/card/config/data/split identities and outcome events. Reconcile private/off-repository history; a missing history is UNKNOWN, never zero.
- [ ] Record actual owner/reviewer dispositions and externally retained chronology. Freeze exact final card/protocol/companion bytes and register them before outcome access. A local timestamp or hash alone is not proof of preregistration.

Development can inform only the changes and selection allowed by the registered plan, with every change logged. Freeze the final candidate/universe selection before holdout access. Never use holdout results to choose a strategy, universe, parameter or favorable subgroup. A changed strategy needs a new version and fresh untouched confirmatory evidence.

**Exit evidence:** reviewed card and protocol identities, complete or explicitly unresolved history, selected test universe/split, actual freeze/custody record and prerequisite decisions. Until these exist, this workstream remains incomplete.

### Known candidate and variant history to reconcile

This table indexes existing declarations; it does not certify all historical trials. The detailed [variant ledger](g1-foundations-2026-11-08/variant-ledger-v1.md) remains authoritative for its exact recorded proposals.

| Recorded item | Existing disposition / exposure |
| --- | --- |
| S01-OR15-H15 / G1-S01 v1 | Opening-range signal; proposed pending owner selection; no executable card approval |
| S02-M30-C15-H15 / G1-S02 v1 | Momentum signal; authored DEFERRED state, not an owner decision |
| S03-GAP15-H15 / G1-S03 v1 | Gap-reversal signal; authored DEFERRED state, prior-close dependency |
| Wave A horizons and inference | Three baselines; six declared confirmatory comparisons, six descriptive 5/30-minute horizon outputs; proposed bootstrap 2/5/10 sensitivities. These are declarations, not counts of completed experiments |
| S04 VWAP; S06 schedules; S08 OFI; S10 delta-hedged options | Documented unresolved/deferred archetypes; S04 index-volume rejection retained; no newly selected variants |
| S05 spot/futures RV; S07 pairs/RV; S09 queue-aware MM | Candidate mechanisms/rules unresolved; no eligible traded universe selected |
| Instrument alternatives and K0–K3 cost scenarios | Recorded alternatives/scenarios, no approved economic measurements or new magnitudes |
| SMA/pricer/toy/portfolio fixtures | Engineering configurations; not market evidence. Historic configuration totals are not asserted |
| Prior market exposure | Records disclose a partial 2026-11-02 smoke and a 2026-10-30 gap review. Raw outcomes were not reopened here; affected periods cannot be assumed untouched |
| Earlier unlogged/private search and viewing | UNKNOWN; owner/reviewer reconciliation remains required |

## 2. Qualify data and executable costs — G2 prerequisite

**Deliverable:** an authorized dataset manifest, quality/reproducibility report and effective-dated economic model with documented source reconciliation. Start from the [G2 inventory](../../Intra-Paper-System/docs/G2-READINESS-INVENTORY.md), [permission preflight](../../Intra-Paper-System/docs/DATASET-INTENDED-USE-PREFLIGHT.md), [feed audit](../../Intra-Paper-System/docs/FEED-COMPLETENESS-GAP-AUDIT.md) and [G1 cost register](g1-foundations-2026-11-08/cost-model-v1.csv).

- [ ] Identify the supplied package without opening sealed results. Verify permission for local storage, research/replay, retention, derived outputs and intended sharing over the exact instruments/dates. Obtain missing data only within established source authorization.
- [ ] Bind raw/normalized input identities, dated contract master, venue/calendar and session scope. Verify timestamp meaning, timezone, ordering, receipt/availability timing, clock limitations and cross-leg synchronization.
- [ ] Check first/last coverage, expected sessions, missing/duplicate/out-of-order records, gaps and quarantine. Preserve all exceptions against predeclared rules; no interpolation or convenient universe filtering to improve results.
- [ ] Reproduce allowed replays with the exact build, config and input identities; reconcile fills, fees, positions, cash and equity. Hash equality establishes byte reproducibility, not market correctness or data rights.
- [ ] Source and validate brokerage, exchange/clearing/IPFT/SEBI charges, GST, stamp duty, STT and other applicable taxes by effective interval, instrument, side, turnover basis, aggregation and rounding. Reconcile representative boundary-date and expiry cases to documented sources; missing charges remain unknown.
- [ ] Validate spread, slippage/impact, order delay, partial/missed fills, cancellations and failed exits at the intended clip. Passive queue claims require appropriate data/calibration; displayed size alone does not prove fill probability or capacity. Include joint-leg failure for RV/hedges.
- [ ] Validate dated margin/headroom, funding, expiry/exercise/settlement cash flows and charges. Caller margin rows are not a verified exchange margin calculator. Account for fixed costs and capital charge on a predeclared time/capital basis.
- [ ] Keep signal/index markouts, executable-price effects, contract P&L, explicit charges, funding/fixed costs and capital charge in separate reconciled layers. Never translate an index markout into derivative P&L; never deduct spread/slippage twice when already embedded in executable prices.

**Exit evidence:** exact authorized scope; quality exceptions and their disposition; reproducible contract accounting; source/effective-date cost coverage; calibrated assumptions and independently reviewed limitations. Existing synthetic examples do not satisfy these items.

## 3. Run the preregistered evaluation and independent review

**Deliverable:** reproducible complete-family development/validation results, one authorized holdout evaluation, and an independent decision tied to the frozen candidate and eligible universe. Begin only when the preceding requirements and actual prerequisite gates are satisfied.

- [ ] Bind source/build, approved card, dataset, split, parameter configuration, cost/margin/settlement model, protocol and full trial/outcome history. Preserve all starts, output production/viewing, mechanical failures and abandonment.
- [ ] Evaluate chronologically on development and validation under the frozen selection rule. Report all attempted variants, including failed or unavailable outcomes with reasons; do not keep only surviving fills or profitable sessions. Include valid no-trade sessions under the declared observation rule.
- [ ] Report gross and all-in net results, drawdown, exposure, turnover, margin/capital usage, capacity at intended size, regime and parameter sensitivity, delay, missed/partial fills, failed exits and cost uncertainty. Specify scenario magnitudes and acceptance before outcomes; report the full grid.
- [ ] Review multiplicity, dependence, sample adequacy and missingness under the predeclared statistical method. Existing DSR/PBO diagnostics and HAC standard errors do not by themselves provide the required selection-adjusted positive net holdout evidence. Do not adopt Wave A's signal-only inference as a contract-P&L decision method.
- [ ] Before release, lock candidate/universe selection and establish independent holdout custody/access history. If prior exposure is uncertain, do not call the data sealed. Release once through the reviewed process; failure/interruption is recorded, not a reason to search or tune against the same holdout. Independent reproduction must confirm the same fixed computation under controlled access.
- [ ] Apply both economic requirements below and every predeclared statistical/risk/capacity threshold. Record failures without lowering thresholds or switching universe after holdout results.
- [ ] Have a qualified independent reviewer examine data authorization/quality, sources/cost semantics, complete search history, chronology/custody, method, assumptions and all results. Record independence/conflicts, exact artifact identities, reproduction differences, unresolved reservations and final decision. The preparer's documentation check or an AI review cannot substitute for that review.

### Economic decision contract to freeze before results

Use common units, horizon and exposure/turnover basis for gross economic result `G`, all-in trading costs `C`, non-trading fixed/funding costs `F`, and capital charge `K`. Define each component and avoid overlaps. Reconcile these to executable contract accounting; `G` is not an index signal markout.

The point-estimate economic conditions are **`G >= 3 * C`** and **`N = G - C - F - K > 0`**. The full decision additionally requires the predeclared method to establish a **positive lower confidence bound for net holdout evidence**, with the frozen estimand, baseline, level, sidedness, dependence/multiplicity adjustment, adequacy rule and sensitivity treatment. These requirements are conjunctive. The 3× multiplier applies to trading costs; it does not replace fixed/funding costs or capital charge, and net P&L is not reduced by three copies of trading costs.

The rate, committed capital, time allocation, exact cost denominator and statistical method remain to be supplied/reviewed. Phase 4's illustrative rupee amounts, 4/12 bp examples and 7% capital hurdle are not adopted as actual inputs. A point estimate, Sharpe/DSR/PBO score or software PASS cannot establish the required confidence bound.

## Decision and next handoff

| Evaluation disposition | G3 disposition | Consequence |
| --- | --- | --- |
| Missing/unqualified inputs, unknown custody, inadequate sample or absent review | OPEN / INCOMPLETE | Record specific missing evidence; no promotion |
| Complete valid evaluation, no candidate passes | OPEN / NO_QUALIFYING_CANDIDATE | Record the negative outcome and all variants; continue research/offline paper within existing authority |
| Candidate passes every frozen requirement and independent review supports its preselected eligible universe | Eligible for a recorded G3 closure decision | Preserve candidate/universe/artifact identities and review; later paper/live gates and their authorizations still apply |

**Immediate next input:** the path or non-sensitive reference for the user's existing package. First inspect its card/authorization/review/custody metadata, then determine which checklist items it actually satisfies. No strategy selection, human approval, data authorization, qualifying result or completed independent review is inferred from the package's existence.
