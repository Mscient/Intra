# Portfolio Sharpe HAC diagnostic implementation

**Branch:** `codex/portfolio-sharpe-hac-20261107`\
**Base:** `07e5483b8bd5afe744fed9712198aabea91b02b3` (`codex/local-integration-reviewed-20261106`)\
**State:** implementation completed and locally validated before consolidation; no push or merge had occurred at report time.\
**Original checkout:** left unchanged.

## Delivered

- Added the frozen content-addressed protocol in `Intra-Paper-System/docs/PORTFOLIO-SHARPE-HAC-PROTOCOL.md`. Protocol SHA-256: `dc4d71687a830ceba16d4849771dc1a497e14e45c847d1585d4d57aeed36b73c`.
- Added a separate fixed-candidate, original-frequency Sharpe HAC standard error using population moment influence values, global centering, denominator `T`, Bartlett weights, `c_T=sqrt((T-1)/T)`, no prewhitening, and the protocol's sorted bandwidth grid. Matrix projection is checked against the scalar influence-series calculation.
- Added return, squared-centered-return, and influence ACFs with lag pair counts. They are descriptive and do not screen the HAC calculation.
- Added the `T >= 60` operational floor. Below the floor, the V3 JSON reports HAC `INCOMPLETE` with null HAC estimates while preserving legacy outputs. Invalid/incomplete verified session series fail closed with `INCOMPLETE: VERIFIED_SESSION_SERIES_UNAVAILABLE` and do not publish family scores.
- Added SHA-256 fingerprints for the hashed validated calendar/session axis and ordered included date/partition/return series. Existing artifact and outcome provenance remains in the report. Independent preregistration is explicitly `UNVERIFIED`; prior exposure is `UNKNOWN`.
- Advanced the portfolio trial-evaluation JSON schema to V3. Existing Sharpe, DSR, trial-count sensitivity, and PBO calculations were not changed. `performance_claim_eligible` remains false.

## Frozen synthetic results

The protocol was frozen before running its new synthetic qualification harness. With 2,000 replications of 240 retained sessions per process, the largest-grid-bandwidth RMS HAC SE divided by empirical population SD of the Sharpe point estimate was:

| Process | Ratio | Frozen range | Result |
| --- | ---: | ---: | --- |
| IID Gaussian | 0.965083 | 0.80–1.20 | Pass |
| Gaussian AR(1), rho 0.25 | 0.941566 | 0.80–1.20 | Pass |
| GARCH-style volatility clustering | 0.971323 | 0.80–1.20 | Pass |

These results qualify only the three fixed-seed processes and settings in the protocol. They do not establish general coverage or real-return suitability.

## Validation

- The updated portfolio trial-evaluation runner passed **165 evaluator checks**, **30,019 HAC checks**, and its portfolio replay-engine, execution-cost-schedule, and artifact-verifier regressions.
- Checks cover the exact four-observation equation fixture, matrix/scalar equality, `T=60` floor handling, grid construction, zero/near-zero variance and nonfinite rejection, locale/timezone-independent output, sealed HOLDOUT mutation invariance, unchanged DSR/PBO results, and the synthetic qualification suite.
- Full Java 21 production build passed. The JAR was written to `C:\Users\prash\AppData\Local\Temp\codex-portfolio-sharpe-hac-20261107-build\intra-paper-system.jar`. Its manifest reports `Build-Commit: unknown-dirty`, as expected before committing this implementation.
- No market data or real trial results were read. No GitHub push or merge occurred.

## Limits

The 60-session floor is an operational choice, not a theorem. HAC SE is not an interval, p-value, profitability probability, selection correction, or evidence of stationarity. The ACF does not establish dependence assumptions. Pooling DEVELOPMENT/VALIDATION follows the registered continuous hashed axis but does not establish regime stability. The input calendar and session declarations are validated relative to supplied hashes and cannot reveal omitted or falsely declared exchange sessions. Trial dependence, search completeness, independent preregistration, prior outcome exposure, and all broader strategy-evidence gates remain unresolved.
