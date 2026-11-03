# Intra Paper System

An isolated, offline-first Java 21 futures and single-option premium-bar backtesting simulator. It is separate from the HFT 2 research notes and has no broker or network adapter.

## Built in this increment

- JDK 21 command-line application with `validate`, `run`, run-artifact `verify` and `oms-recover`, quote-journal `journal-verify`, `quote-quality-report`, `quote-feature-export`, `quote-order-replay` and its artifact verifier, raw-capture `raw-capture-verify`, `feed-lifecycle-report`, `feed-capture-reconcile`, and deterministic `replay-verify` commands.
- Java API to import verified raw captures into normalized quote journals through caller-supplied offline decoders; the importer publishes only a fully verified journal and does not include a vendor adapter.
- Separate hash-chained feed-lifecycle journal records sanitized connection, authentication, subscription, heartbeat, disconnect, and reconnect facts. Its analyzer reconstructs state and counts invalid transitions. It does not connect to a feed or establish market-data completeness.
- `feed-capture-reconcile` verifies a feed-lifecycle journal and raw-capture journal, then matches captured message receive times against acknowledged subscription intervals by source, connection, and subscription ID. Its wall-clock tolerance is explicit; unmatched messages, empty intervals, and timeline anomalies are diagnostics, not a completeness certificate or vendor adapter.
- Quote execution eligibility helper requires a matching fresh quote, clean top-of-book, full displayed size, and explicit adverse tick slippage; it remains an offline building block without partial fills, queue priority, or a feed adapter.
- Offline `QuoteOrderReplay` evaluates IOC market or marketable-limit intents at caller-supplied arrival latency against verified L1 quote history. Per intent it caps fills at displayed top-level size, emits full/partial/missed outcomes and OMS state transitions, and cancels IOC residuals. It does not deplete displayed size across intents, infer passive queue fills, calculate fees, submit broker orders, or provide a conservative fill lower bound; it is not wired into the bar strategy runner.
- `quote-order-replay` accepts a strict intent CSV, verifies its quote journal, indexes snapshots by source/instrument/receive time, processes intents in arrival order, and atomically publishes a content-addressed run folder. Its manifest records build identity, input hashes, quality thresholds, outcome counts, and result-file hash. `quote-order-replay-verify` checks the manifest identity and result hash. The batch consumes displayed quantity once per selected quote and side until another snapshot is selected. It still loads the complete quote history into memory; use only with journals that fit available memory.
- L1 feature API and streaming CSV exporter derive spread, midpoint, size-weighted microprice, and displayed-size imbalance from normalized quotes at explicit decision times. The snapshot-based L1 order-flow estimate requires consecutive local journal sequences, the same source, and eligible snapshots; no depth or queue claims are made.
- Standalone `EuropeanOptionPricer` and `Black76OptionPricer` compute European Black–Scholes–Merton spot-option and Black-76 futures-option values and Greeks from caller-supplied inputs. Outputs are per underlying unit; vega/rho are per percentage point and theta is per 365-day calendar day. Black-76 treats the supplied futures price as independent of the discount rate. Neither pricer is connected to replay/accounting or independently validated against an external benchmark, and neither is calibrated to a market implied-volatility surface.
- Strict normalized-candle CSV parsing, monotonic and same-session interval-spacing checks, OHLC/tick validation, and explicit timestamp-role handling.
- Required open-session calendar and effective-dated market-rule registry. Each open date must resolve by regime ID, effective date, exchange/segment/contract kind, source reference, timezone, session window, lot size, and tick size. Session/run artifacts retain the applied rules and registry hash. Source verification labels remain caller assertions. A run is restricted to one dated futures or single-option contract; expiry stitching and index-to-futures substitution are rejected.
- A reusable strategy API receives immutable history ending at the decision candle, so strategy code cannot read future bars through the replay interface. The built-in long-only SMA crossover is a plumbing harness; signals can execute only as simulated market orders at the next eligible candle's open.
- One strategy-audit row per candle, with strategy diagnostics and warm-up status recorded.
- Fixed adverse tick slippage, explicit fee inputs, position/notional caps, and a per-session loss halt. These settings are recorded in each run.
- A configurable minimum time between new BUY intents; exits remain allowed, scale-in targets are rejected, and the order/position ledgers are independently reconciled.
- Venue-neutral OMS state transitions, pending/unknown residual-unit reservation rules, and execution-ID idempotency primitives; the bar simulator currently exercises immediate local full-fill paths only.
- Optional effective-dated BUY/SELL execution cost schedule, contract-kind-aware itemized fee schedule, and source-attributed expiry settlement cost schedule. Executed charges are auditable in `fee-breakdowns.csv`, exercise charges in `settlement-charges.csv`, and additional caller-supplied settlement lines in `settlement-cost-lines.csv`.
- Local simulated order lifecycle, fills, completed trades, marked equity curve, run summary, input hashes, and output hashes.
- The built-in deterministic SMA replay checkpoints every 250 bars by default to `.checkpoints/<run-id>` under the run root. Re-running the exact same bars/config/build resumes from the last checkpoint in the verified chain; set `recovery.checkpoint_interval_bars=0` to disable it. A damaged chain fails closed. Checkpoints are cleared only after the complete run folder is published. Custom Java strategies must explicitly declare deterministic checkpoint support before using this recovery path.
- Input CSV hashes are computed from the exact byte streams consumed by their parsers; the run-properties hash is computed from the same byte snapshot used to parse its settings.
- Content-addressed run IDs include input, configuration, build, and strategy ID/parameter hashes; artifacts are written to a sibling staging directory and published with a same-volume atomic move after all files and hashes are complete. Sidecar OS locks protect active staging writes, and a later write removes only recognized abandoned staging directories whose lock is available. Results default outside the repository under `%LOCALAPPDATA%\Intra-Paper-System\runs`.

The bundled example is synthetic. Reports are always classified as `SYNTHETIC_PLUMBING_ONLY` or `REPLAY_DIAGNOSTIC_ONLY`, and `performance_claim_eligible` is always `false` in this build.

## Build and run

Requires a JDK 21 installation. The build uses `JAVA_HOME` explicitly, embeds the Git commit only when the checkout is clean, marks dirty builds `unknown-dirty`, and writes compiler output outside the OneDrive project by default. Non-synthetic runs require a full commit ID, so a dirty-build JAR cannot run non-synthetic data.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\build.ps1
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $java -jar $jar validate .\examples\synthetic-futures-bars.csv .\examples\synthetic-run.properties
& $java -jar $jar run .\examples\synthetic-futures-bars.csv .\examples\synthetic-run.properties
```

Use `INTRA_PAPER_RUNS_DIR` to choose another local output directory. A repeated identical run uses the same content ID and will fail rather than overwrite existing artifacts. The `run` command resumes a deterministic built-in SMA replay from its latest checkpoint in the verified chain when the same inputs, configuration, and build are used. The `verify` command recomputes twelve output-ledger SHA-256 hashes from current manifests; it also accepts prior nine-, ten-, and eleven-output manifests. It does not verify source rights or re-hash input files. `oms-recover` verifies the full manifest before reconstructing completed-run OMS order states, deduplicated executions, reservations, and final position from saved ledgers. It does not restore broker state. `journal-verify` scans a quote journal and validates its framing, canonical encoding, sequence ordering, and SHA-256 hash chain without connecting to a data source. `quote-quality-report` performs the same full integrity scan while reporting event-time and structural quote flags using explicit caller-supplied freshness thresholds. `quote-feature-export` writes sequence-aware L1 metrics from a fully verified journal with atomic no-overwrite publication. `feed-lifecycle-report` verifies a `.flj` journal and reports event counts, reconstructed connection states, unresolved subscriptions, and invalid transitions. `feed-capture-reconcile` separately verifies both journals and compares each raw message source/connection/subscription and receive time with acknowledged lifecycle windows using caller-supplied wall-clock tolerance. It reports unmatched messages, empty intervals, anomalies, and hashes, but does not map decoded instruments, prove completeness, establish data rights, or correct clock errors. Flag counts can overlap, and quote reports/feature exports do not determine feed completeness, source semantics, data rights, or whether a threshold is appropriate. `replay-verify` rebuilds all run artifacts from supplied bars and configuration in a temporary folder and requires a byte-for-byte match with the saved ledgers and manifest.

See [the operating guide](docs/OPERATING-GUIDE.md) for the input contract and run files.

## Scope and limitations

- One dated futures or one listed option premium series per bar-run, long-only SMA harness. Long-only expiry settlement is supported only from an explicit source-attributed input row. A component cost schedule can model option exercise STT and SEBI turnover using the applicable rate row and sourced turnover basis. An optional settlement schedule adds caller-sourced effective-dated fixed, per-unit, or basis-point charge lines with explicit GST-on-charge selection; these inputs do not establish a complete or verified account tariff. The separate L1 quote-order replay models IOC top-size partial/missed outcomes with supplied latency, but not passive queue fills, fee calculation, or broker orders. Option valuation/Greeks, multi-leg strategies, short exposure, portfolio aggregation, stop orders, and exchange matching remain unmodeled.
- No live quotes, API credentials, broker connectivity, or live order route. Simulated acknowledgements are local ledger events.
- The configurable per-order, per-unit, notional-basis-point, and tick-slippage cost model is diagnostic, not a current exchange/broker cost schedule. The entry gate accepts either a fixed margin proxy or a source-attributed effective-dated per-contract margin input; neither is an exchange SPAN/ELM calculation or contract-sizing advice.
- The simulator validates supplied regular-session boundaries against the dated caller rule registry, but does not implement exchange matching, pre-open auction execution, market-on-close behavior, or automatic rule sourcing. Pre-open, expiry, ELM, and calendar-spread fields are recorded as dated metadata and do not drive order or margin mechanics. `ALLOW_OVERNIGHT` permits cross-session/end-of-data positions; `REQUIRE_FLAT` fails when one remains; `AUTO_FLATTEN_SESSION_END` suppresses new entries at the penultimate bar close and schedules an open-position exit for the final bar open. The supplied calendar must be reviewed. This is a bar-model assumption, not a guaranteed real-market close.
- Cross-session pending orders are disabled by default and cannot be combined with either flat-at-session-end policy. Automatic flattening fails closed if the final bar is reached with a position still open.
- The sample strategy and all synthetic inputs are plumbing examples, not trading recommendations or evidence of an edge. Current data rights, costs, operating limits, and G0/G1/G2 evidence remain open.
- No automated test suite is included. The synthetic sample replay exercises plumbing only; broader behavior remains unvalidated.

Private data, raw-capture journals (`.rawcap`, `.rawcapture`, `.raw`), feed-lifecycle journals (`.flj`), quote journals (`.qj`, `.qjr`), standard run artifacts, and `quote-replay-runs/` are excluded from Git. Never put broker credentials, account identifiers, private budgets, raw server messages, or licensed market rows in this repository. The ignore patterns are convenience safeguards, not access controls; confirm `git status` before committing data files.

## Research references

- [Paper trading and backtesting roadmap](../research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md)
- [Nifty intraday paper-system design](../research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md)
- [Lab data schema](../research/systems/LAB-DATA-SCHEMA.md)
- [Isolated testing plan](../research/systems/ISOLATED-TESTING-PLAN.md)
- [Instrument and data capability matrix](../research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md)
- [NSE index-derivatives settlement mechanics](../research/systems/2026-11-03-nse-index-derivatives-settlement.md)
- [Wave A signal specification](../research/systems/WAVE-A-SIGNAL-SPEC.md)
- [Local research synthesis](docs/research/LOCAL-RESEARCH-SYNTHESIS.md)
- [Strategy research-card template](docs/STRATEGY-CARD-TEMPLATE.md)
- [First-cut data contract](docs/DATA-CONTRACT.md)
- [Implementation tracker](IMPLEMENTATION-TRACKER.md)
