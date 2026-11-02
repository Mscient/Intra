# Wave A Signal Specification

**Status:** Draft baseline rules; exercised only in an offline smoke run on partial 2 Nov 2026 candles. No strategy validation has been performed. The 30 Oct closing-period timestamp gap is handled by truncating at the first missing minute; timestamp convention and complete-session coverage still need verification.
**Reviewed:** 2026-11-01 (Asia/Kolkata).
**Scope:** S01-S03 as signal-only research on NIFTY 50 index bars. These rules do not define executable orders or claim a profitable strategy.

## Why this specification exists

The project has no verified sample data yet. This document fixes a small set of transparent signal definitions before any strategy-performance results are inspected. The opening-range, momentum, and gap-reversal ideas come from the ten-system review; cited evidence there uses other markets and does not establish a Nifty effect. See [TOP-10-TRADING-SYSTEMS.md](TOP-10-TRADING-SYSTEMS.md) and the [isolated testing plan](ISOLATED-TESTING-PLAN.md).

The selected 15-minute opening range and 30-minute momentum lookback are simple starting baselines, not values estimated from Nifty data. Do not run a grid search around them.

## Shared definitions and scoring

- **Signal input:** NIFTY 50 one-minute OHLC bars from SmartAPI, after a private sample confirms timestamp convention, completeness, and session alignment. Index volume is zero, so this dataset cannot test VWAP.
- **Session boundaries:** use the official session calendar included with the dataset. Exclude non-regular/special sessions unless they are separately identified and predeclared.
- **Signal timing:** use only complete bars available at the decision time. A signal becomes known at the signal bar's close. Do not use the signal close as a simulated fill.
- **Primary label:** the index log return from the signal bar's close to the close 15 trading minutes later. Report 5- and 30-minute forward returns as fixed secondary diagnostics, not as a way to choose whichever horizon looks best.
- **Meaning of results:** these are index-price markouts for directional-signal research. They are not futures/options returns, P&L, or fill estimates.
- **Report for every rule:** eligible sessions, signal count, no-signal rate, hit rate (with zero-return labels shown separately), mean and median signal-aligned forward return, distribution tails, and results by time of day. Do not net simulated brokerage or infer executable performance from index returns.
- **Missing data:** exclude a signal if required bars or prior-session references are missing. Report exclusions and missingness; do not forward-fill prices or fabricate volume.
- **Chronology:** divide complete sessions into 60% development, 20% validation, and 20% final holdout, in date order. Set exact boundaries after the data-quality review but before computing signal outcomes. Keep the holdout closed until the rules and advancement criterion are recorded. Remove signals whose 30-minute secondary label would cross the session close.
- **Variants:** one fixed rule per system below. No parameter optimization or cross-system winner selection. Report all three whether results are positive or negative.

## S01 — Opening-range breakout

**Hypothesis:** a close-confirmed break of the initial range may carry directional information over the following 15 minutes.

1. Build the range from the first 15 complete one-minute bars of a regular session: `range_high = max(high)` and `range_low = min(low)`.
2. Beginning with the next complete bar, emit `+1` on the first close strictly above `range_high`, or `-1` on the first close strictly below `range_low`.
3. Allow at most one signal per session; after the first signal, do not re-enter that day. If no close breaks either boundary, emit no signal.
4. Score the fixed 15-minute forward index return from the signal close. The 5- and 30-minute outcomes are secondary diagnostics.

This does not model an order, stop, or fill. A later executable test needs a dated futures contract, quotes, and a separately frozen risk/exit policy.

## S02 — Intraday time-series momentum

**Hypothesis:** the sign of a recent NIFTY index move may predict the next 15-minute index move.

1. At each 15-minute decision point after the first 30 complete session minutes, calculate `m = log(close_now / close_30_minutes_ago)` from completed one-minute bars.
2. Emit `+1` if `m > 0`, `-1` if `m < 0`, and no signal if `m = 0` or required bars are missing.
3. Keep the 15-minute decision cadence and 30-minute lookback fixed. Do not add a volatility threshold or select other lookbacks after seeing results.
4. Score the next 15-minute index return; use 5- and 30-minute forward returns only as predeclared secondary diagnostics.

This rule tests directional persistence only. It is not a tradable futures P&L series.

## S03 — Opening gap reversal

**Hypothesis:** when the first 15-minute move runs against the overnight index gap, that reversal direction may persist for the next 15 minutes.

1. For each regular session, calculate `gap = log(session_open / prior_regular_session_close)`.
2. Calculate `opening_move = log(close_after_first_15_minutes / session_open)` from completed bars.
3. If `gap` and `opening_move` are both nonzero and have opposite signs, emit one signal in the direction of `opening_move` at the close of the first 15-minute block. Otherwise emit no signal.
4. Compare the signal-aligned forward return with the gap-continuation direction and the no-signal baseline. Do not add a gap-size threshold after inspecting outcomes.

This is a deliberately simple confirmation rule; it may generate noisy signals. It does not imply that a reversal is executable after spread, slippage, or delays.

## S04 — VWAP deviation: data gate only

Do not run this rule on NIFTY index bars: the index has no traded volume. Revisit only if the selected traded instrument has a suitable dated volume series. SmartAPI's reported lack of expired F&O history still limits a multi-expiry futures backtest; see the [instrument/data matrix](INSTRUMENT-DATA-CAPABILITY-MATRIX.md).

## Before any performance run

The formulas and statistical decision rules below are fixed before any outcomes are computed. The exact split dates and a development-only precision/power review still depend on the data-quality review. Do not inspect validation or holdout outcomes until those are recorded. No economic pass/fail threshold is set because these index markouts are not executable returns and dated futures costs are not available.

## Statistical decision and advancement gates

### Primary estimate and inference unit

- For each fully eligible regular session, calculate the direction-aligned 15-minute log return for every signal emitted by that system. The session score is the arithmetic mean of its signal returns; set it to zero when the complete session has no signal. A session with missing required bars or labels is ineligible, not a zero. Report eligible and excluded session counts, signal counts, and the conditional per-signal mean separately.
- The primary estimate is the equal-weight mean of complete-session scores. This makes the session, rather than each intraday S02 signal, the inference unit and includes no-signal sessions in the fixed rule's overall signal score.
- Estimate uncertainty with a stationary bootstrap of the chronological session-score sequence, using a mean block length of five sessions and 10,000 resamples. Record the random seed. Report two-session and ten-session mean-block-length sensitivities; they are diagnostics and cannot replace the primary result. This time-series bootstrap relies on weak-dependence/stability assumptions; if data breaks make those assumptions implausible or results too unstable to interpret, classify the result as inconclusive. [1]
- There are six confirmatory comparisons: three fixed systems in validation and the same three in the final holdout. Use two-sided 99.1667% bootstrap intervals for each comparison (Bonferroni familywise error rate 5% across all six). The 5- and 30-minute horizons, hit rates, time-of-day splits, and other breakdowns remain descriptive; they cannot rescue a failed primary result. Predeclare any additional confirmatory outcomes as new tests before viewing them.

### Decision states

- **Pass — signal evidence only:** a system's primary mean is positive and its adjusted interval is entirely above zero in both validation and the final holdout. The primary five-session bootstrap is used for this decision. A pass allows only a separately frozen, isolated study using dated NIFTY futures, executable quotes, applicable costs, and a declared fill/risk model.
- **Reject — negative signal evidence:** the adjusted final-holdout interval is entirely below zero. Record the validation estimate and interval as context; do not retune this baseline against the holdout.
- **Inconclusive:** the adjusted interval includes zero, validation and holdout disagree, available session counts/precision do not support a stable estimate, or the inference assumptions are not credible. Do not change the rule and reuse the same holdout; a revised rule requires a new untouched evaluation period.
- **Not testable with current data:** the timestamp, completeness, session-calendar, label, or provenance gates fail. Stop before computing performance results.

Before opening validation, use development data only to report session counts and precision/power over a range of detectable mean effects. No universal row-count cutoff is asserted: the number of intraday bars is not the number of independent observations. A statistically positive index markout does not establish a cost-covering edge. Until contract history, quotes, and dated costs are available, no result can pass an economic or paper-trading gate.

A later futures test must use a distinct dated contract series, actual applicable costs, and conservative fills. SmartAPI index signals must not be converted directly into futures/options P&L. All experiment code remains in the separate `Intra-Systems-Lab`, not in HFT 2.

## Statistical references

1. [Politis & Romano (1994), “The Stationary Bootstrap,” *Journal of the American Statistical Association*](https://doi.org/10.1080/01621459.1994.10476870) — block resampling for inference with weakly dependent stationary observations.
2. [Romano & Wolf (2005), “Stepwise Multiple Testing as Formalized Data Snooping,” *Econometrica*](https://doi.org/10.1111/j.1468-0262.2005.00615.x) — multiple comparisons and familywise error control. This specification uses the simpler Bonferroni adjustment across its fixed six comparisons.
