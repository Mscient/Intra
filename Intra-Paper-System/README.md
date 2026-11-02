# Intra Paper System

An isolated, offline-first Java 21 futures and single-option premium-bar backtesting simulator. It is separate from the HFT 2 research notes and has no broker or network adapter.

## Built in this increment

- JDK 21 command-line application with `validate`, `run`, run-artifact `verify`, and quote-journal `journal-verify` commands.
- Strict normalized-candle CSV parsing, monotonic and same-session interval-spacing checks, OHLC/tick validation, and explicit timestamp-role handling.
- Required open-session calendar with expected first/last bar labels, effective-dated instrument-master resolution for every candle, and within-session spacing checks. A run is restricted to one dated futures or single-option contract; expiry stitching and index-to-futures substitution are rejected.
- Long-only SMA crossover harness. A signal computed from a completed candle can execute only as a simulated market order at the next eligible candle's open.
- One signal-audit row per candle, with the moving averages and warm-up status recorded.
- Fixed adverse tick slippage, explicit fee inputs, position/notional caps, and a per-session loss halt. These settings are recorded in each run.
- Local simulated order lifecycle, fills, completed trades, marked equity curve, run summary, input hashes, and output hashes.
- Content-addressed run IDs; artifacts are written to a sibling staging directory and published with a same-volume atomic move after all files and hashes are complete. Write failures clean the staging directory. Results default outside the repository under `%LOCALAPPDATA%\Intra-Paper-System\runs`.

The bundled example is synthetic. Reports are always classified as `SYNTHETIC_PLUMBING_ONLY` or `REPLAY_DIAGNOSTIC_ONLY`, and `performance_claim_eligible` is always `false` in this build.

## Build and run

Requires a JDK 21 installation. The build uses `JAVA_HOME` explicitly, embeds the source Git commit in the JAR manifest when built in a checkout, and writes compiler output outside the OneDrive project by default.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\build.ps1
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $java -jar $jar validate .\examples\synthetic-futures-bars.csv .\examples\synthetic-run.properties
& $java -jar $jar run .\examples\synthetic-futures-bars.csv .\examples\synthetic-run.properties
```

Use `INTRA_PAPER_RUNS_DIR` to choose another local output directory. A repeated identical run uses the same content ID and will fail rather than overwrite existing artifacts. The `verify` command recomputes eight output-ledger SHA-256 hashes from the manifest; it does not verify source rights or re-hash input files. `journal-verify` scans a quote journal and validates its framing, canonical encoding, sequence ordering, and SHA-256 hash chain without connecting to a data source.

See [the operating guide](docs/OPERATING-GUIDE.md) for the input contract and run files.

## Scope and limitations

- One dated futures or one listed option premium series per run, long-only SMA harness. Option valuation/Greeks, multi-leg strategies, expiry exercise/settlement, short exposure, portfolio aggregation, passive/queue fills, partial fills, stop orders, latency, and exchange matching are not modeled.
- No live quotes, API credentials, broker connectivity, or live order route. Simulated acknowledgements are local ledger events.
- The fixed-fee/tick-slippage cost model is a configurable diagnostic, not a current exchange/broker cost schedule. The entry gate also accepts a fixed user-supplied margin-per-lot proxy; it is not an exchange margin calculation or contract-sizing advice.
- The simulator does not implement a complete exchange-hours rule engine or forced end-of-day liquidation. `ALLOW_OVERNIGHT` permits cross-session/end-of-data positions; `REQUIRE_FLAT` fails when one remains. The input calendar must be supplied and reviewed.
- Cross-session pending orders are disabled by default; `REQUIRE_FLAT` cannot be combined with them. No forced liquidation fill is invented.
- The sample strategy and all synthetic inputs are plumbing examples, not trading recommendations or evidence of an edge. Current data rights, costs, operating limits, and G0/G1/G2 evidence remain open.
- No automated test suite is included. The synthetic sample replay exercises plumbing only; broader behavior remains unvalidated.

Private data and run artifacts are excluded from Git. Never put broker credentials, account identifiers, private budgets, or licensed market rows in this repository.

## Research references

- [Paper trading and backtesting roadmap](../research/systems/PAPER-TRADING-BACKTESTING-ROADMAP.md)
- [Nifty intraday paper-system design](../research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md)
- [Lab data schema](../research/systems/LAB-DATA-SCHEMA.md)
- [Isolated testing plan](../research/systems/ISOLATED-TESTING-PLAN.md)
- [Instrument and data capability matrix](../research/systems/INSTRUMENT-DATA-CAPABILITY-MATRIX.md)
- [Wave A signal specification](../research/systems/WAVE-A-SIGNAL-SPEC.md)
- [Local research synthesis](docs/research/LOCAL-RESEARCH-SYNTHESIS.md)
- [First-cut data contract](docs/DATA-CONTRACT.md)
- [Implementation tracker](IMPLEMENTATION-TRACKER.md)
