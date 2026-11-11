# Strategy research card template

**Status:** Draft template only. This file does not select or approve a strategy.

Use the [three G3 workstreams](../../research/systems/G3-REMAINING-WORKSTREAMS.md) for prerequisite evidence and closure rules. Complete fields with reviewed decisions or an explicit unresolved state; a filled template alone is not a freeze or approval.

Complete and review this card before using any real or authorized market data. Keep private account limits and licensed data out of Git; refer to a private record by logical ID only.

## Identity and ownership

- Strategy ID and version:
- Owner:
- Independent reviewer:
- Date frozen:
- Plan revision / source commit:
- Decision authority and stop authority:

## Hypothesis

- Market behavior being tested:
- Why the behavior could persist after costs:
- Expected holding period and event frequency:
- Conditions where the hypothesis should fail:
- Known alternative explanations:

## Instrument and information set

- Instrument class: dated NIFTY future / one dated option / other approved contract
- Contract-selection rule, fixed before each decision:
- Predeclared test universe, objective eligibility/exclusions, and dated contract/roll rules:
- Trading timezone and hours, entry cutoff, flat deadline, holidays and exceptional sessions:
- Permitted signal inputs and timestamp roles:
- Feature calculation and warm-up behavior:
- Maximum information age:
- Explicitly excluded information:

## Entry, exit, and sizing

- Entry signal:
- Exit signal:
- Position target and maximum holding time:
- Session-end behavior:
- Expiry / settlement handling:
- Position sizing rule:
- No-trade conditions:

## Risk and stop controls

- Private approved risk/capital record reference, units and effective period:
- Per-order and aggregate position/notional/Greek limits, including working orders:
- Daily loss, drawdown and stressed-liquidation limits:
- Margin/headroom, participation and intended-capacity limits:
- Stale/missing-data, missed-fill, failed-exit and multi-leg failure responses:
- Who can stop/resume, evidence required to resume, and post-freeze change rules:

## Execution and cost assumptions

- Fill model and earliest eligible fill:
- Queue / partial-fill evidence, if any:
- Slippage model and version:
- Dated fee schedule source and version:
- Effective intervals, tax/fee side and basis, rounding/aggregation and source-review record:
- Margin model and source:
- Funding, fixed-cost allocation and capital charge, with private input references:
- Reconciliation of signal markouts, executable contract P&L and cost attribution without double counting:
- Sensitivity cases to evaluate:

## Data and provenance

- Dataset IDs and content hashes:
- Data-rights review reference:
- Contract-master snapshot IDs:
- Session-calendar source and review:
- Known gaps, timestamp issues, and exclusions:
- Retention and deletion conditions:

## Evaluation design

- Benchmark and no-trade baseline:
- Development sessions:
- Validation sessions:
- Sealed holdout sessions:
- Split-plan file hash:
- Variants / parameter search budget:
- Full candidate/universe/parameter/data/cost/fill search history and prior outcome exposure, including unknowns:
- Trial/outcome ledger references, including failures, abandonment and partial views:
- Multiple-testing controls:
- Metrics and uncertainty intervals:
- Primary estimand, units/denominator, sample-adequacy rule and dependence treatment:
- Interval method, level, sidedness and selection/multiplicity adjustment:
- Purge/embargo justified by feature, label and position overlap:
- Development/validation selection rule; final candidate/universe locked before holdout:
- Independently controlled holdout custody, access history and one-shot release/report procedure:
- Frozen cost/delay/missed-fill/size/regime/parameter sensitivity grid and acceptance rules:
- Independent reproduction procedure:

## Decision rules

- Minimum evidence required to continue:
- Predeclared rejection criteria:
- Exact gross >= 3 x trading-cost hurdle and positive all-in net holdout lower-bound rule, including capital charge:
- Required drawdown, exposure, capacity and sensitivity acceptance:
- Kill-switch / stop conditions:
- Required reviewer sign-off:
- Reviewer independence/conflicts, exact reviewed artifacts, chronology and unresolved reservations:
- No-qualifier disposition: record the result and keep G3 open; no holdout-driven retuning:
- Decision and date:

## Approval state

Do not mark approved until G0 and G1 evidence gates are reviewed. Approval of this research card does not authorize data acquisition, broker connectivity, forward paper operation, or order routing.
