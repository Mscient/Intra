# Same-Day Wave A Paper/Research MVP Build Plan

**Target date:** 2026-11-02 (Asia/Kolkata)  
**Status:** Build plan started; no runner code or today's data has been added yet.  
**Goal:** Create an isolated, offline Python runner for S01-S03 and smoke-test it on the user's completed NIFTY candles for 2 Nov 2026.

## What “built today” means

Deliver a repeatable local command that reads a private candle file, validates it, computes the fixed signal rules and index markouts, and writes a compact report plus run manifest. This is a one-session smoke test of the research pipeline. It is not a statistically valid strategy backtest, executable P&L, a broker-connected simulator, or a live trading system.

## Scope and boundaries

- Keep code in the separate `Intra-Systems-Lab`, outside HFT 2. Use Python's standard library for the first version to minimize setup.
- No Angel One SDK, API credentials, login/TOTP code, network calls, live quotes, or order routing in the lab. The user's existing local SmartAPI script supplies the candle file separately.
- Input: completed NIFTY 50 one-minute candles for 2026-11-02 with timestamp, open, high, low, close, and volume. Keep the file in a local ignored `data/private/` folder; do not commit raw candles.
- Exclude the still-forming candle if the market is open. Preserve source timestamps; do not assume whether they label bar start or end until verified.
- On any timestamp discontinuity, stop the usable session at the first missing minute, report the gap and ignore later candles for that session. Never forward-fill, interpolate, or score a signal whose forward-label bars are absent. This makes the accepted closing-gap treatment explicit and reproducible.
- S01-S03 use the frozen definitions in [WAVE-A-SIGNAL-SPEC.md](WAVE-A-SIGNAL-SPEC.md). S03 runs only if the prior regular-session close is present and validated; otherwise report it as unavailable, not zero.
- Metrics are direction-aligned NIFTY index markouts at 5, 15, and 30 minutes. They are not trades or P&L. No parameter search or strategy selection.

## Build sequence for today

1. **Scaffold:** separate lab README, CLI entry point, private-data ignore rules, and a run-manifest format. Pin the Python version; avoid adding dependencies unless necessary.
2. **Input and quality checks:** parse the SmartAPI candle output/CSV; validate schema, numeric OHLC bounds, unique monotonic timestamps, one-minute cadence, session date, volume, and missing intervals. Print only counts/ranges in the report.
3. **Signal engine:** implement S01 opening-range breakout, S02 fixed 30-minute momentum at 15-minute cadence, and S03 first-15-minute gap reversal using only completed bars.
4. **Markouts and report:** calculate only horizons with complete future bars; include eligible rows, exclusions, cutoff, signal counts, no-signal state, and per-signal marks. State clearly that one day cannot pass the predeclared validation/holdout criteria.
5. **Tests:** run unit tests for parsing, OHLC validation, duplicate/gap handling, cutoff behavior, each signal rule, and prevention of labels crossing missing data or session boundaries.
6. **Today's data smoke run:** after today's session is complete, run the CLI once on the user's private 2 Nov file. Keep the input and outputs local; copy only a redacted summary and manifest hash to the research record.

## Acceptance criteria

- A clean local install/run instruction and one CLI command.
- Unit tests pass on deterministic synthetic fixtures.
- The 2 Nov input has an explicit quality summary: row count, first/last timestamp, duplicate count, OHLC violation count, missing-minute count, cutoff, and whether the current partial candle was removed.
- Signals and markouts are reproducible from a recorded rule hash and input hash; no result uses a candle after the first gap or an incomplete forward horizon.
- If no signal fires or today's data fail validation, the runner still emits a truthful report and does not invent a positive/negative strategy conclusion.

## Still outside today's deliverable

- Multi-session chronological development/validation/holdout analysis, confidence intervals, adequate-power assessment, and claims of strategy performance.
- Futures/options conversion, transaction costs, fills, risk limits, broker paper environment, and all live order routing.
- Resolving the SmartAPI 30 Oct closing gap. The MVP will cut off at the first gap and report it; a separate-source check is still needed before using that date for broader research.

## Required local data handoff

The user runs the existing SmartAPI script locally with `exchange=NSE`, symbol token `99926000`, interval `ONE_MINUTE`, and a 2 Nov 2026 range through the last completed candle (or the completed session after 15:30 IST). Save the response as a private CSV/JSON. Do not include API keys, PIN/TOTP, authentication/session tokens, HTTP auth headers, or account data in the file shared with the lab. A full session is required for today's smoke run; partial-session data may be used only to test parsing and early signal generation.
