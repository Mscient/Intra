# Nifty Intraday Research Project

This repository tracks research for a cost-aware Nifty intraday paper-trading system in India. The initial brief sets the constraints: own account, Nifty first, Python/research-first, no paid tick feed at the outset, and a 90-day paper-system target. The user has selected Angel One SmartAPI; documented capabilities and limits are in the [instrument/data matrix](research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md). The isolated `Intra-Paper-System/` subproject now builds with Java 21 and contains an offline dated-derivative CSV replay simulator for one futures contract or one option premium series at a time, with a local simulated OMS, explicit risk/cost inputs, and hashed run artifacts. It has no broker adapter or live order route; the bundled data is synthetic and no account credentials or licensed market data were used. The simulator offers a configurable session-end exit using a final-bar-open assumption and explicit source-attributed long-only expiry settlement inputs. Componentized option-exercise STT and SEBI turnover fees and dated source-attributed per-contract margin inputs are supported; the latter are not a SPAN/ELM calculator. Other settlement charges, option valuation, and Greeks remain unmodeled. Earlier session records describe a separate Python engine, which is not a port of this Java implementation. See the [paper-system roadmap](research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md) and [implementation tracker](research/systems/IMPLEMENTATION-TRACKER.md) for current scope and gates.

The reports translate book material and lawful public-source evidence into pre-build requirements. They distinguish publisher descriptions and previews from complete-book reading, and they do not claim that any strategy is profitable or ready for live trading.

## Research status

Research notes are prepared for all 15 listed books. Full-text reading remains in progress wherever authorized complete text was not available. See the tracker for exact per-book access status.

- B01: [Trading and Exchanges — Larry Harris](research/books/B01-Trading-and-Exchanges-Harris.docx)
- B02: [Option Volatility & Pricing — Sheldon Natenberg](research/books/B02-Option-Volatility-and-Pricing-Natenberg.md)
- B03: [Algorithmic and High-Frequency Trading — Cartea, Jaimungal, and Penalva](research/books/B03-Algorithmic-and-High-Frequency-Trading-Cartea-Jaimungal-Penalva.md)
- B04: [Algorithmic Trading — Ernest P. Chan](research/books/B04-Algorithmic-Trading-Chan.md)
- B05: [Volatility Trading — Euan Sinclair](research/books/B05-Volatility-Trading-Sinclair.md)
- B06: [Market Microstructure in Practice — Lehalle and Laruelle](research/books/B06-Market-Microstructure-in-Practice-Lehalle-Laruelle.md)
- B07: [Algorithmic Trading & DMA — Barry Johnson](research/books/B07-Algorithmic-Trading-and-DMA-Johnson.md)
- B08: [The Volatility Surface — Jim Gatheral](research/books/B08-The-Volatility-Surface-Gatheral.md)
- B09: [Options, Futures, and Other Derivatives — John Hull](research/books/B09-Options-Futures-and-Other-Derivatives-Hull.md)
- B10: [Advances in Financial Machine Learning — Marcos López de Prado](research/books/B10-Advances-in-Financial-Machine-Learning-Lopez-de-Prado.md)
- B11: [Designing Data-Intensive Applications, 2nd ed. — Kleppmann and Riccomini](research/books/B11-Designing-Data-Intensive-Applications-Kleppmann-Riccomini.md)
- B12: [Systems Performance, 2nd ed. — Brendan Gregg](research/books/B12-Systems-Performance-Gregg.md)
- B13: [Stochastic Volatility Modeling — Lorenzo Bergomi](research/books/B13-Stochastic-Volatility-Modeling-Bergomi.md)
- B14: [C++ Concurrency in Action, 2nd ed. — Anthony Williams](research/books/B14-CPP-Concurrency-in-Action-Williams.md)
- B15: [Building Low Latency Applications with C++ — Sourav Ghosh](research/books/B15-Building-Low-Latency-Applications-with-CPP-Ghosh.md)

- Ten-system research overview: [research/systems/TOP-10-TRADING-SYSTEMS.md](research/systems/TOP-10-TRADING-SYSTEMS.md)
- System research tracker: [research/systems/SYSTEM_TRACKER.md](research/systems/SYSTEM_TRACKER.md)
- Final integrated system design: [research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md](research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md). It covers the platform architecture and design/data/evaluation gates for S01–S10.
- Jane Street-class trading system study, Phase 4 continued (Sections 13–15): [research/design-study/PHASE-4-CONTINUED-13-15.md](research/design-study/PHASE-4-CONTINUED-13-15.md). It completes the failure register, roadmap, decision log, and learning path. Educational design study; no claim is made about Jane Street’s private systems.
- Final implementation plan for the four-phase Jane Street-class study: [editable Markdown](research/systems/FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.md) and [rendered PDF](research/systems/FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.pdf). It consolidates the architecture, work packages, gates, risk controls, assumptions, decisions, sources, learning path, and §1–§15 traceability.
- Implementation progress tracker: [research/systems/IMPLEMENTATION-TRACKER.md](research/systems/IMPLEMENTATION-TRACKER.md). It records current gate status, the first offline implementation increment, research reuse, and the evidence required to continue.
- Instrument/data capability matrix: [research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md](research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md)
- Isolated testing plan: [research/systems/ISOLATED-TESTING-PLAN.md](research/systems/ISOLATED-TESTING-PLAN.md)
- Same-day Wave A MVP build plan: [research/systems/SAME-DAY-MVP-BUILD-PLAN.md](research/systems/SAME-DAY-MVP-BUILD-PLAN.md)
- Wave A signal specification: [research/systems/WAVE-A-SIGNAL-SPEC.md](research/systems/WAVE-A-SIGNAL-SPEC.md)
- Lab data schema: [research/systems/LAB-DATA-SCHEMA.md](research/systems/LAB-DATA-SCHEMA.md)
- Nifty futures/options paper-system roadmap: [research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md](research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md). Angel One SmartAPI read-only login and a spot/future/option FULL-quote check succeeded; the local engine has a dated instrument-master importer and 18 engine tests. Authorized multi-expiry replay data, local retention review, and exact margin/fee verification remain open.
- Wave A status: the isolated Python runner is built and nine unit tests pass. It smoke-tested 104 consecutive 2 Nov candles through 10:58 IST, generated S01/S02 signals, and skipped S03 because the prior close was absent. This is a partial-session pipeline check, not a strategy backtest; full-session candles are still pending. Runs save a private report, signal ledger, and input-hash manifest locally.
- Book backlog and access status: [research/BOOK_TRACKER.md](research/BOOK_TRACKER.md)
- Session history: [research/sessions/](research/sessions/)
- Per-chat logging and commit procedure: [SESSION_PROTOCOL.md](SESSION_PROTOCOL.md)

## Source and text policy

Use publisher pages, authorized previews, library access, and public-domain or openly licensed works. Do not download or commit pirated scans or reproduce complete copyrighted books. Prefer original summaries with citations. If complete text requires a subscription or library login, record that access status and continue with lawful public material until authorized access is available.

## Repository structure

- research/books/ — individual book research reports.
- research/systems/ — comparative research notes and status for trading-system archetypes.
- research/sessions/ — one uniquely named record for each chat that changes project state.
- research/BOOK_TRACKER.md — status and reading order for the supplied book list.
- research/SESSION_INDEX.csv — session IDs mapped to commits containing their work.
