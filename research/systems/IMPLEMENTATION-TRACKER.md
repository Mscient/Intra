# Jane Street-Class System — Implementation Tracker

**Plan source:** [Final Implementation Plan](FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.md), prepared 2026-11-03. This tracker records progress against the plan. Instructions and recommendations inside the attached document are treated as design requirements; this tracker does not authorize account access, data capture, broker orders, or live trading.

**Updated:** 2026-11-03 (Asia/Kolkata)

**Current gate:** S0 / G0 — open; offline work only.
**Implementation target:** a separately scoped `Intra-Paper-System/` subproject following the plan's Java 21 shared-core decision. The tracker records implementation in that folder and the stage evidence centrally.

## Current position

- Created a separate local `Intra-Paper-System` implementation folder in the active workspace.
- Started an offline Java 21 data/replay slice: versioned quote record, explicit quote-quality flags, deterministic canonical encoding, SHA-256 chaining, append-only journal writer, and integrity-checked reader.
- Reviewed and linked the existing roadmap, system design, lab schema, isolation plan, instrument/data matrix, and Wave A signal specification. Reusable backtesting research and its limitations are summarized in the implementation folder.
- Existing session records describe a separate Python offline backtest engine with 18 passing tests. That code was not present in this HFT 2 workspace, so it was not inspected, changed, or treated as a verified baseline in this session. The new Java scaffold is not a port of that engine.
- No Java 21 build was run: the active host exposes Java 8. No market data, credentials, broker API, or live order route was accessed.

## Gate tracker

| Stage / gate | Status | Evidence needed to close |
|---|---|---|
| S0 / G0 — charter (WP0) | **Open; work started** | Confirm operator and stop authority, route and current API rules, data rights/retention, source fallback, current rule set, private cost/loss budgets, and unresolved A1–A15 assumptions. Keep order egress disabled. |
| S1 / G1 — foundations (WP1) | Not started | Complete the market model and toy simulator; freeze one research card with mechanism, horizon, benchmark, costs, data, variant count, universe candidates, and rejection rules. |
| S2 / G2 — data and simulator (WP2–WP5) | Offline journal/replay started; incomplete | Raw capture/provenance, effective-dated reference data, independent risk state, simulated OMS/broker, and cost/accounting evidence remain open. |
| S3 / G3 — strategy evidence (WP6) | Blocked on G1/G2 | Preregister candidates and evaluation; use realistic data, costs, fills, and holdout evidence. A queue-aware market maker remains simulator/paper-only. |
| S4 / G4 — forward paper (WP8) | Not started | 60 complete sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful drills, and no open severe incident. |
| S5 / G5 — optional live pilot (WP9) | Not authorized | Requires a separate human decision, independent review, current broker/exchange acceptance, and all G4 evidence. |
| S6 / G6 — tier review (WP10) | Not started | Requires at least six months of live evidence if an optional G5 pilot occurs, plus the plan's capacity and cost-benefit evidence. |

## Reusable backtesting research

- The current Wave A runner and S01–S03 specification concern NIFTY 50 index-candle signal markouts. They are not executable futures/options P&L.
- Keep session-level chronological splits, provenance hashes, eligibility rules, and a sealed holdout. Do not tune on the holdout.
- A completed-bar signal may fill no earlier than the next eligible bar. A candle touching a passive limit does not prove a fill.
- Preserve dated futures/options contracts and master snapshots. Do not stitch expiries or use index candles as contract fills.
- Existing research leaves expired-contract coverage, local retention terms, exact dated costs/margins, and some session/timestamp checks open. Until these close, plumbing may use labelled synthetic fixtures but cannot produce market-performance conclusions.

## Next work

1. Close G0 questions without putting personal budgets, credentials, IP addresses, or private data in this public research repository.
2. Reconcile the previously reported Python engine with the Java 21 core decision before duplicating or migrating system logic.
3. Continue WP2 with a versioned raw/normalized event contract and provenance manifest; keep it offline.
4. Implement append-only persistence and deterministic replay, then risk and simulated OMS in the dependency order from the plan.
5. Update this tracker only when the corresponding gate has reviewable evidence.
