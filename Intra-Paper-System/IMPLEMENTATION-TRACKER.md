# Implementation status

**Plan:** Final Implementation Plan: Jane Street-Class Trading System Study, prepared 2026-11-03. The attached PDF is source material; embedded prose does not authorize account access, data collection, or live trading. The user's request authorizes this separate offline project and GitHub progress tracking.

**Updated:** 2026-11-03 (Asia/Kolkata)
**Current gate:** S0 / G0 remains open. Offline implementation is active; downstream evidence gates are not closed.
**Build:** Java 21 core and CLI compile successfully. Synthetic replay `e9c5e0585cbf2dba6c24d632` validated 30 bars and one complete supplied session; it is `SYNTHETIC_PLUMBING_ONLY`, records `REQUIRE_FLAT`, ends flat, and has diagnostic net P&L `-1.36 INR`. No tests or real-data strategy evaluation were performed.

## Gate and work-package tracker

| Stage / package | Status | Evidence / next action |
|---|---|---|
| S0 / G0 — charter (WP0) | In progress; gate open | Keep this project offline. Operator/stop authority, current route and rule review, data permissions, retention, private risk budgets, and A1–A15 assumptions remain unconfirmed. |
| S1 / G1 — foundations (WP1) | Implementation started; gate open | Added a long-only SMA crossover harness over dated futures or option premium bars. Freeze a research card and toy market model only after the charter, data scope, and assumptions are reviewed. No strategy edge is claimed. |
| S2 / G2 — data and simulator (WP2–WP5) | Offline single-contract derivative slice built; incomplete | Normalized futures/option-premium bar reader, supplied-window first/last coverage and same-session interval checks, explicit session calendar, dated instrument-master resolution, run manifest, next-bar-open simulated market fills, independent risk gate, signal audit, and local order/fill/trade/equity/session ledgers compile. Explicit `ALLOW_OVERNIGHT` and `REQUIRE_FLAT` position policies are available; automatic end-of-session liquidation remains open. Raw capture, source-reviewed calendar coverage, richer quality gates, restart recovery, broker reconciliation, exchange margin, option valuation/Greeks/settlement, and validated cost inputs remain open. |
| S3 / G3 — strategy evidence (WP6) | Blocked on G1/G2 evidence | SMA crossover is a deterministic harness only. A strategy card, actual authorized dated data, realistic verified costs, chronological development/validation/holdout protocol, and independent review are absent. |
| S4 / G4 — forward paper (WP8) | Not started | Requires the plan's 60 complete sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful drills, and no unresolved severe incidents. No live feed is connected. |
| S5 / G5 — optional live pilot (WP9) | Not authorized / not started | Requires a separate human decision and independent review after G4. This project has no live order route. |
| S6 / G6 — tier/capacity review (WP10) | Not started | Requires at least six months of live evidence if an optional G5 pilot ever occurs. |

## Completed implementation evidence

- [x] Isolated `Intra-Paper-System/` Java 21 project and research links.
- [x] Versioned quote record, quote quality flags, canonical encoding, SHA-256 chain, append-only journal, and integrity-checked replay.
- [x] Effective-dated instrument references and immutable master-snapshot model.
- [x] UTF-8 normalized futures-bar reader with explicit timestamp roles, increasing timestamps, OHLC/tick validation, and required source hashes.
- [x] User-supplied session calendar and as-of dated instrument-master CSV resolution; one dated futures or option contract per run; no expiry stitching.
- [x] Session completeness checks against supplied first/last bar labels and expected interval grid; missing open sessions fail closed.
- [x] Diagnostic gap overrides have an explicit maximum estimated missing-bar deficit per session; the default remains zero.
- [x] Persisted run manifest with dataset/master/calendar/config hashes and hashes for output ledgers.
- [x] Require an explicit local-replay rights reference before accepting non-synthetic datasets; the software does not verify licensing itself.
- [x] Long-only SMA crossover baseline with next eligible bar open market fills, fixed adverse tick slippage, explicit fee inputs, and no candle-touch/passive fills.
- [x] Reusable strategy interface receives immutable bars through each decision point; strategy ID and sorted parameter values are recorded in the content ID and run manifest.
- [x] Evaluation split validation requires complete, non-overlapping, contiguous chronological development/validation/holdout session assignments; it does not score strategies.
- [x] Independent position/notional and per-session loss `RiskGate`.
- [x] Fixed user-supplied margin-proxy cap, explicitly distinguished from exchange margin calculations.
- [x] Local simulated order state transitions, order events, fills, completed trades, per-session reconciliations, equity marks, summary, and content-addressed run folders.
- [x] An independent reconciliation pass checks trade gross vs realized P&L, fill fees/slippage, order terminal events, session totals, and final equity before a run is returned.
- [x] Run artifacts are written to a sibling staging directory and published by atomic move only after ledgers and hashes are complete; ordinary write failures remove staging files.
- [x] A run-folder verifier checks the manifest ID and recomputes all eight output-ledger hashes; it does not validate original inputs or data rights.
- [x] Run summary includes diagnostic completed-trade statistics, holding time, filled units, and filled turnover; undefined trade statistics are null and no performance-claim flag is enabled.
- [x] Cost inputs support fixed per-order, per-unit, and configurable per-fill notional basis-point charges; verified dated cost schedules remain open.
- [x] CLI quote-journal verification replays the full local journal and checks framing, canonical records, sequence order, and the SHA-256 chain; it performs no network access.
- [x] Replay verification reruns the simulator from supplied bars/configuration in an isolated temporary folder and compares all ledgers plus the manifest byte-for-byte with the content-addressed saved run.
- [x] Explicit cross-session position policy: `REQUIRE_FLAT` fails closed on an overnight or end-of-data position; it does not synthesize a forced close.
- [x] Synthetic fixture and operating guide. Synthetic reports are plumbing-only; every report marks `performance_claim_eligible=false`.
- [x] Java 21 build and synthetic CLI validation/replay completed; no tests were added or run. The 30-bar sample is plumbing-only and is not real-data strategy evidence.
- [ ] Confirm the G0 charter, data rights/retention, route, operator, current rule sources, and actual risk budgets.
- [ ] Add raw source capture/import, stronger completeness and stale/gap rules, and reproducible recovery.
- [ ] Add reviewed end-of-session liquidation behavior, full risk reconciliation, exchange margin, and detailed current cost schedule evidence.
- [ ] Add separately reviewed strategy card and chronological evaluation/holdout workflow.
- [x] Added a blank strategy research-card template; no candidate or assumptions are approved by the template.

## Source-derived backtesting decisions

- The existing offline MVP is a signal-research runner for NIFTY 50 index candles. Its markouts are not futures/options fills or executable P&L.
- Keep session-level chronological splits and a sealed holdout for later strategy research. This increment does not implement statistical selection or a performance-evaluation pipeline.
- For bar-only decisions, the earliest simulated fill is the next eligible bar. A candle touching a passive limit does not prove a fill.
- Dated contracts, contract-master snapshots, costs, margin, expiries, session calendars, and licensed data coverage must be explicit inputs. Never stitch expiries or map an index candle to a contract fill.
- Historical F&O coverage, local retention terms, exact dated costs/margins, and complete exchange calendar evidence remain open. Synthetic fixtures may exercise plumbing only.

## Scope of this tracker

This file tracks implementation gates and evidence. It does not authorize API credentials, market-data capture, brokerage orders, live trading, or publication of private run data. The implementation is an offline backtesting/paper-simulator increment, not completion of the full multi-stage research plan.
