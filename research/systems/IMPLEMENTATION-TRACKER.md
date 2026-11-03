# Jane Street-Class System — Implementation Tracker

**Plan source:** [Final Implementation Plan](FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.md), prepared 2026-11-03. The plan is treated as design guidance; it does not authorize account access, data capture, broker orders, or live trading.

**Updated:** 2026-11-03 (Asia/Kolkata)
**Implementation:** [`Intra-Paper-System/`](../../Intra-Paper-System/README.md), Java 21, offline only.
**Current gate:** S0 / G0 remains open. The compiled system is an offline single-contract derivatives replay MVP; option inputs are premium bars only. It is not completion of the full staged plan.

## Current position

- The Java 21 CLI compiles and supports normalized-bar validation and backtest replay for one dated futures contract or one option premium series at a time, including supplied session-boundary completeness checks and strict same-session interval spacing unless an explicit diagnostic override is set.
- The replay uses a long-only SMA harness, next-eligible-bar-open market fills, explicit adverse tick slippage and fee inputs, an independent position/notional/session-loss risk gate, and local OMS/fill/trade/equity/session-reconciliation artifacts, published atomically after output hashes are written. Position handling supports `ALLOW_OVERNIGHT`, fail-closed `REQUIRE_FLAT`, and an optional session-end exit at the final bar's open; that fill model still requires review.
- Run-folder verification recomputes the eight recorded output hashes and checks the manifest ID; it does not re-open inputs or validate data rights.
- Effective-dated contract resolution, explicit session calendars, and hashed run manifests are required. Expiry stitching, index-to-futures substitution, passive-touch fills, and live order egress are not supported.
- A clearly synthetic fixture was validated and replayed through the CLI: run `e9c5e0585cbf2dba6c24d632`, 30 bars, one complete supplied session, `REQUIRE_FLAT`, final position 0, `SYNTHETIC_PLUMBING_ONLY`, diagnostic net P&L `-1.36 INR`. No behavior tests or real-data strategy evaluation were performed; no real market data, credentials, account, or broker API were accessed.
- A separate Python offline backtest engine is described in earlier session records but its implementation was not present in the checked-out HFT 2 workspace; the Java simulator is not represented as a port of it.

## Gate tracker

| Stage / gate | Status | Evidence needed to close |
|---|---|---|
| S0 / G0 — charter (WP0) | **Open; work started** | Confirm operator/stop authority, route and current rules, data rights/retention, current source set, private risk budgets, and unresolved A1–A15 assumptions. Keep order egress disabled. |
| S1 / G1 — foundations (WP1) | Implementation started; gate open | Review market-model assumptions and freeze a research card with mechanism, horizon, benchmark, costs, data, variant count, universe, and rejection rules. The SMA is only a harness. |
| S2 / G2 — data and simulator (WP2–WP5) | Offline single-contract replay slice built; incomplete | Raw capture/import, full quality/completeness checks, session-hours policy, reproducible recovery, exchange margin, verified fees, option valuation/settlement, and broader simulator evidence remain open. |
| S3 / G3 — strategy evidence (WP6) | Blocked on G1/G2 | Preregister a candidate; use authorized dated data, verified execution/cost assumptions, chronological development/validation/holdout, and independent review. No performance claim is currently eligible. |
| S4 / G4 — forward paper (WP8) | Not started | 60 complete sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful drills, and no open severe incident. |
| S5 / G5 — optional live pilot (WP9) | Not authorized | Requires a separate human decision and independent review after all G4 evidence. No live route exists in the project. |
| S6 / G6 — tier review (WP10) | Not started | Requires at least six months of live evidence if an optional G5 pilot occurs, plus capacity and cost-benefit evidence. |

## Reusable backtesting research

- The current Wave A runner and S01–S03 specification concern NIFTY 50 index-candle signal markouts. Those markouts are not futures/options fills or executable P&L.
- Keep session-level chronological splits, provenance hashes, eligibility rules, and a sealed holdout for future strategy research. This MVP does not implement that evaluation workflow.
- A completed-bar signal may fill no earlier than the next eligible bar. A candle touching a passive limit does not establish a fill.
- Preserve dated futures/options contracts and master snapshots. Do not stitch expiries or map an index candle to a contract fill.
- Historical F&O coverage, local retention terms, exact dated costs/margins, and complete session-calendar evidence remain open. Synthetic runs are plumbing-only; other runs are diagnostic-only in this implementation.

## Next work

1. Keep G0 unresolved items out of public Git where they contain private budgets or account-specific information.
2. Add raw source capture/import, stronger stale/gap/completeness rules, and recovery/reconciliation artifacts.
3. Review the new cross-session policy and define a realistic end-of-session liquidation model before intraday deployment.
4. Extend the simulator only with verified data and cost assumptions; add option valuation/settlement and portfolio accounting after the futures and premium-bar paths are reviewed.
5. Update this tracker when implementation evidence changes; do not mark gates closed based only on compilation or synthetic examples.
