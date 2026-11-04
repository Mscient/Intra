# Offline Statistical Evaluation Implementation Report

**Date:** 2026-11-04  
**Project:** Intra Paper System  
**Branch:** `codex/offline-statistical-evaluation`  
**Implementation commit:** `dd08688001a07091df761f10fe60a2f1eb62a323`

## Objective

Add an offline evaluator for preregistered backtest runs that reports per-trial returns and Sharpe ratios, Deflated Sharpe probability, and Probability of Backtest Overfitting (PBO). The evaluator must bind every result to the existing trial ledger, strategy and dataset identities, and frozen split plan; reject inconsistent or incomplete artifacts; exclude sealed holdout P&L; and keep all performance claims ineligible.

## Delivered

- Added `OfflineEvaluationAnalyzer` and the `statistical-evaluate` CLI command.
- Added verified read access to entries in the append-only `ResearchTrialLedger` and a split-plan loader for artifact-side session-date checks.
- Defined a strict `trial_id,run_id,analysis_capital_inr` run-index format in the operating guide.
- Verified run artifact hashes and matched run metadata, registration sequence, strategy ID/version, variant ID, registration hash, strategy-card hash, dataset hash, split-plan hash, and parameter-configuration hash.
- Required all preregistered trials sharing the selected dataset and split-plan hashes to appear in the comparison input.
- Rejected missing or duplicate session rows, session dates that differ from the frozen split file, incomplete partitions, and partitions with boundary positions.
- Computed simple cumulative return using fixed analysis capital, daily and 252-session annualized Sharpe, DSR, and chronological-block CSCV PBO using DEVELOPMENT and VALIDATION only.
- Kept HOLDOUT P&L out of all metrics and output. Reports set `performance_claim_eligible=false`.
- Updated the operating guide and implementation tracker.

## Statistical definitions

Session return is `net_pnl_change_inr / analysis_capital_inr`. Cumulative return is the simple sum of session returns without compounding. Sharpe uses sample standard deviation, a zero risk-free rate, and an annualization factor of `sqrt(252)`.

DSR uses the return series' skewness and raw kurtosis and the number of registrations sharing the evaluated dataset and split-plan hashes. It compares observed Sharpe with an approximation to the expected maximum of independent normal Sharpe estimates.

PBO uses combinatorially symmetric cross-validation over near-equal chronological blocks drawn from the concatenated DEVELOPMENT and VALIDATION sessions. For each half-block combination, it selects the in-sample Sharpe leader and records whether its out-of-sample midrank is below the cross-sectional median.

## Build and verification

- JDK 21.0.13 compilation succeeded from the clean implementation commit.
- The built JAR manifest records `Build-Commit: dd08688001a07091df761f10fe60a2f1eb62a323`.
- No automated tests or runtime fixtures were run for this increment.
- The implementation build was run from a clean commit; the task report was added afterward.

## Limits

- The evaluator does not enforce one-shot holdout secrecy. Existing run artifacts contain holdout results; operators must keep holdout access controlled outside this command. The analyzer checks holdout session coverage/completeness but does not use or emit its P&L.
- Sharpe annualization assumes independent sessions. Return serial dependence is not adjusted.
- DSR assumes independent trial Sharpe estimates for its expected-maximum approximation; correlation among trials is not modeled.
- Analysis capital is a caller-supplied fixed denominator and results are simple, non-compounded returns.
- Zero-variance return series or PBO folds have undefined Sharpe and fail closed.
- The output is diagnostic and does not establish profitability, authorize paper/live trading, or set `performance_claim_eligible=true`.

## Files

- `src/main/java/com/mscient/intrapaper/evaluation/OfflineEvaluationAnalyzer.java`
- `src/main/java/com/mscient/intrapaper/evaluation/EvaluationSplitPlan.java`
- `src/main/java/com/mscient/intrapaper/evaluation/ResearchTrialLedger.java`
- `src/main/java/com/mscient/intrapaper/cli/IntraPaperCli.java`
- `docs/OPERATING-GUIDE.md`
- `IMPLEMENTATION-TRACKER.md`
