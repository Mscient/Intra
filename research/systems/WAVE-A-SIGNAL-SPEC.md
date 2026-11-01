# Wave A Signal Specification

**Status:** Draft baseline rules; not run. SmartAPI sample-quality review is pending.
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

This document freezes the signal formulas, but not an economic pass/fail threshold. Because the signal series is non-tradable and execution costs are not yet available, do not promote a positive signal markout as a strategy candidate. Before opening the development results, record the research question, minimum sample requirements, any statistical decision rule, and the exact next-stage gate. The final holdout remains sealed until that record exists.

A later futures test must use a distinct dated contract series, actual applicable costs, and conservative fills. SmartAPI index signals must not be converted directly into futures/options P&L. All experiment code remains in the separate `Intra-Systems-Lab`, not in HFT 2.
