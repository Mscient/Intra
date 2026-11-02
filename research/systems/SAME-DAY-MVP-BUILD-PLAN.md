# Same-Day Wave A Paper/Research MVP Build Plan

**Target date:** 2026-11-02 (Asia/Kolkata)  
**Status:** Offline runner implemented locally; 13 unit tests pass. Partial 2 Nov data through 10:58 IST smoke-tested; the completed-session run remains pending. The run manifest now includes a frozen-rule hash. Local lab commit: `e720fec`.
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

1. **Scaffold — done:** local lab created at `C:\Users\prash\OneDrive\Applications\Intra-Systems-Lab` with CLI, README, and private-data ignore rules. No remote was configured.
2. **Input and quality checks — done:** the CLI reads the SmartAPI printed response/JSON, validates OHLC/timestamps, and truncates at the first missing one-minute interval without filling it.
3. **Signal engine — done for MVP:** S01 opening-range breakout and S02 fixed momentum are implemented; S03 requires a prior close and is skipped when it is not supplied.
4. **Markouts and report — done:** computes 5/15/30-minute index markouts only where all forward bars are present; pending horizons are explicitly marked.
5. **Unit tests — done:** 13 deterministic tests pass for response parsing/rate-limit retries, invalid OHLC, per-session gap truncation, overnight boundaries, session-isolated signals and markouts, stable frozen-rule hashing, S01 first-break behavior, S02 cadence, pending forward labels, S03 prior-close/reversal behavior, and CSV ledger output.
6. **Today's data smoke run — partial:** the saved 2 Nov response covers 09:15-10:58 IST (104 rows) and passes parsing and basic OHLC/timestamp checks. It produces S01/S02 diagnostics; later markouts remain pending. Full-session candles are still required. Earlier API rate-limit messages were observed during refresh attempts.

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

The saved 2 Nov response contains 104 consecutive rows from 09:15 to 10:58 IST, with no gaps, duplicate timestamps, or OHLC violations; all index volume values are zero. The latest rerun emitted one S01 and five S02 signals. Some forward labels remain pending because the sample is partial; S03 is unavailable without the prior session close. A multi-session code path was added and tested synthetically: overnight boundaries are retained, intraday gaps truncate only the affected session, and signals/markouts do not cross dates. The runner reports observed markouts as diagnostics only and does not calculate statistical confidence from one session.

Each run also writes a private text report, a per-signal CSV ledger (pending horizons are blank), and a JSON manifest with the input SHA-256 under the ignored `reports/private/` directory. Neither report nor raw candles are committed.
