# Implementation status

**Plan:** Final Implementation Plan: Jane Street-Class Trading System Study, prepared 2026-11-03. The attached PDF is source material; embedded prose does not authorize account access, data collection, or live trading. The user's request authorizes this separate offline project and GitHub progress tracking.

**Current stage:** S0 / G0 charter work started. The exit gate is open. Actual broker/API route, current data terms, operator authority, budgets, and stop limits must be confirmed before connectivity, market-data capture, or financial assumptions are encoded.

## Gate and work-package tracker

| Stage / package | Status | Evidence / next action |
|---|---|---|
| S0 / G0 — charter (WP0) | In progress; gate open | Keep the system offline. Record A1–A15 as unresolved until the operator confirms them; refresh official broker, rule, and data sources before using any route or cost value. |
| S1 / G1 — foundations (WP1) | Not started | Build the market-model glossary/toy simulator and prepare a research card only after G0 scope and data rights are confirmed. Do not treat the queue-aware market maker as a live candidate. |
| S2 / G2 — data and simulator (WP2–WP5) | Offline data/replay contracts started; incomplete | Quote journal/replay, effective-dated instrument reference/snapshot, and dataset-provenance model exist. Raw capture/import, serialized manifests, quality/completeness gates, independent risk state, and simulator accounting remain open. No broker route. |
| S3 / G3 — strategy evidence (WP6) | Blocked on G1/G2 evidence | A queue-aware market maker remains simulator/paper-only. A slower relative-value candidate needs a frozen card and preregistered development/holdout plan. |
| S4 / G4 — forward paper (WP8) | Not started | Requires 60 complete sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful drills, and no unresolved severe incidents. |
| S5 / G5 — optional live pilot (WP9) | Not authorized / not started | Requires a separate human decision and independent review after G4. No live route is present in this project. |
| S6 / G6 — tier/capacity review (WP10) | Not started | Requires at least six months of live evidence if an optional G5 pilot ever occurs. |

## First implementation increment

- [x] Create an isolated implementation subfolder and link the useful research references.
- [x] Add a versioned immutable top-of-book quote record with quality flags.
- [x] Add stable canonical encoding and a SHA-256 chain primitive.
- [x] Add an append-only binary quote journal and integrity-checked replay reader.
- [x] Add effective-dated instrument-reference and dataset-provenance models.
- [ ] Confirm the G0 charter, permissions, route, operator, current source set, and actual budgets.
- [ ] Add raw/normalized event serialization, manifest persistence, and data-quality/completeness reporting.
- [ ] Add independent risk state and a local simulated OMS/broker before any strategy.

## Source-derived backtesting decisions

- The existing offline MVP is a signal-research runner for NIFTY 50 index candles. Its S01–S03 markouts are not futures/options fills or executable P&L.
- The existing evaluation path uses session-level chronological splits and keeps the holdout sealed until eligibility passes. Reuse these as validation requirements; do not copy the runner into this project as a derivatives backtester.
- For bar-only decisions, the earliest simulated fill is the next eligible bar. A candle touching a passive limit does not prove a fill.
- Dated contracts, contract-master snapshots, costs, margin, expiries, session calendars, and licensed data coverage must be explicit inputs. Never stitch expiries or map an index candle to a contract fill.
- Historical F&O coverage and local data-retention terms remain unconfirmed in the source research. Until those gates are closed, simulator plumbing can proceed with clearly labelled synthetic fixtures, but no market-performance result can be reported from them.

## Scope of this tracker

This file tracks implementation gates and evidence. It does not authorize API credentials, market-data capture, brokerage orders, live trading, or publication of private run data.
