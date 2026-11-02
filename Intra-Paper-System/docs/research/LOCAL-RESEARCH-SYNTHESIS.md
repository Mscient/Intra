# Research synthesis for the first backtesting increment

Reviewed the existing project research on 2026-11-03. The source documents linked below remain authoritative; this synthesis records which findings are reusable and which gates remain open.

## Reusable backtesting work

- The [paper/backtesting roadmap](../../../research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md) calls for a deterministic replay/simulation core shared with paper mode, with futures first and options after contract plumbing. It separates the research repository, strategy lab, and paper system.
- The [Nifty paper-system design](../../../research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md) records the offline runner's limits, next-bar fill rule, stale/unknown-contract handling, and prohibition on inferring passive fills from candle touches.
- The [lab data schema](../../../research/systems/LAB-DATA-SCHEMA.md) defines provenance, timestamp roles, dated instrument masters, run manifests, per-session scores, split integrity, and a strict gap policy. Reuse the contract, not unverified input assumptions.
- The [isolated testing plan](../../../research/systems/ISOLATED-TESTING-PLAN.md) requires staged read-only inputs, no credentials in the experiment environment, and network isolation for offline evaluation.
- The [Wave A signal specification](../../../research/systems/WAVE-A-SIGNAL-SPEC.md) freezes S01–S03 signal rules and validation/holdout semantics. These are index-signal diagnostics; they do not supply derivatives fills.
- The [instrument/data matrix](../../../research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md) documents the distinction between index candles, dated futures, and dated options.

## Findings that constrain this project

1. The existing lab research is not a general futures/options backtester. Its NIFTY 50 candle results are markouts only, with index volume zero and no contract-level fill accounting.
2. The 2026-10-30 close-period timestamp gap and incomplete 2026-11-02 sample are recorded data-quality gates; do not repair them by forward filling or extrapolation.
3. SmartAPI expired-contract coverage, lawful retention, and multi-expiry futures/options history were not established by the cited research. Confirm source terms and coverage before any scored historical run.
4. Fees, margin, taxes, expiry settlement, and execution assumptions in older notes are planning placeholders until replaced by dated authoritative values.
5. The final implementation plan selects Java 21 for shared core behavior and Python only for research orchestration/statistics. This project follows that core boundary.

## Initial implementation choice

Start with immutable normalized event data and deterministic serialization. Keep the project offline and credential-free. Add persistence, replay, OMS, risk, costs, and strategy layers in dependency order. Do not present queue-model results as factual fills without suitable event-level data.
