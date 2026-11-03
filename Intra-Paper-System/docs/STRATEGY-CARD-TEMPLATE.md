# Strategy research card template

**Status:** Draft template only. This file does not select or approve a strategy.

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

## Execution and cost assumptions

- Fill model and earliest eligible fill:
- Queue / partial-fill evidence, if any:
- Slippage model and version:
- Dated fee schedule source and version:
- Margin model and source:
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
- Multiple-testing controls:
- Metrics and uncertainty intervals:
- Independent reproduction procedure:

## Decision rules

- Minimum evidence required to continue:
- Predeclared rejection criteria:
- Kill-switch / stop conditions:
- Required reviewer sign-off:
- Decision and date:

## Approval state

Do not mark approved until G0 and G1 evidence gates are reviewed. Approval of this research card does not authorize data acquisition, broker connectivity, forward paper operation, or order routing.
