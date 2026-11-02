# Intra Paper System

An isolated, offline-first Java 21 foundation for deterministic market-data replay, simulation, and paper operations. This is a separate implementation subproject from the HFT 2 research notes.

## Current state

Work has started at S0/G0. The first code slice defines a versioned top-of-book quote record, explicit quality flags, canonical byte encoding, and a SHA-256 hash-chain primitive. It has no data collector, broker adapter, order path, or strategy. No authorized market-data sample is staged here.

The implementation remains offline and credential-free while the G0 charter, data permissions, current route review, actual budgets, and stop authority remain open. No credentials or private market data belong in Git.

## Boundaries

- No broker order placement, modification, or cancellation code.
- No live order egress, broker credentials, or account connectivity.
- Do not treat index candles or continuous futures as executable contract P&L.
- Preserve source and receive timestamps. Do not infer timestamp role or fill from a candle touch.
- Make no performance claim until dated data, fees, fills, costs, and validation gates are supported.
- Keep private data and run outputs out of Git.

## Build

Requires a JDK 21 installation with `javac` and `jar` available on `PATH`:

```powershell
.\build.ps1
```

The current host reports a Java 8 runtime, so this Java 21 project has not been built here.

## Research references

The existing research files remain the source of truth:

- [Paper trading and backtesting roadmap](../research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md)
- [Nifty intraday paper-system design](../research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md)
- [Lab data schema](../research/systems/LAB-DATA-SCHEMA.md)
- [Isolated testing plan](../research/systems/ISOLATED-TESTING-PLAN.md)
- [Instrument and data capability matrix](../research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md)
- [Wave A signal specification](../research/systems/WAVE-A-SIGNAL-SPEC.md)
- [Local research synthesis](docs/research/LOCAL-RESEARCH-SYNTHESIS.md)

The full implementation plan remains tracked in the existing GitHub research repository at `research/systems/FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.md`. Its personal budget assumptions are not duplicated in this project.
