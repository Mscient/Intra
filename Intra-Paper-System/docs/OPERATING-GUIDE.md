# Offline Backtest Operating Guide

## Commands

Build with JDK 21 from the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\Intra-Paper-System\build.ps1
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
```

Validate inputs before a run:

```powershell
& $java -jar $jar validate .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties
```

Run the local simulator:

```powershell
& $java -jar $jar run .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties
```

## Verify a run

```powershell
$runFolder = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\runs\<content-id>'
& $java -jar $jar verify $runFolder
```

`verify` recomputes the SHA-256 hashes for the eight output ledgers in `run-manifest.json` and checks the folder ID. It does not re-open or re-authorize source inputs.

## Verify a quote journal

```powershell
& $java -jar $jar journal-verify .\path\to\quotes.qjr
```

`journal-verify` reads the complete journal and checks its header, record framing, canonical quote encoding, strictly increasing sequence numbers, and SHA-256 hash chain. It emits a final event count, sequence number, byte count, and chain hash. It does not contact or authenticate to a market-data source.

## Reproduce and verify a saved run

```powershell
& $java -jar $jar replay-verify .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties $runFolder
```

`replay-verify` validates the saved output hashes, reloads the supplied bars and run properties, recomputes the content ID from input hashes, configured build commit, and strategy identity/parameters, runs the simulator in a new temporary directory, and compares all eight output ledgers plus `run-manifest.json` byte-for-byte. The run folder ID must match the supplied inputs and configured build commit. The temporary replay directory is removed after success or failure. This verifies deterministic reproduction; it does not verify data rights or the truth of the source data.

Runs are stored under `%LOCALAPPDATA%\Intra-Paper-System\runs\<content-id>`. Set `INTRA_PAPER_RUNS_DIR` before starting Java to select a different directory. Existing run folders are never overwritten.

## Normalized bars CSV

The header must include these fields in any order:

`timestamp,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,interval,timestamp_role,open,high,low,close,volume,open_interest,source,raw_record_hash`

- `timestamp` must include an explicit UTC offset. `timestamp_role` must be `BAR_START` or `BAR_END` and must agree with the run configuration.
- The supported interval labels are `ONE_MINUTE`, `FIVE_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, and `ONE_DAY`.
- OHLC prices must be positive, internally consistent, and exact multiples of `tick_size`. Volume and optional open interest must be non-negative.
- `raw_record_hash` must be lowercase SHA-256 hex. The run manifest separately hashes the complete normalized bars CSV, reference CSV, calendar CSV, and configuration file.
- The loader checks the row-hash format but cannot independently verify its source relationship without the original raw packet/file; no raw capture or importer is included.
- Bar dates are derived in the configured timezone and must exist in the supplied open-session calendar.
- Adjacent bars within a session must be exactly one configured interval apart by default. `quality.allow_intrasession_gaps=true` permits spacing anomalies only as a reviewed diagnostic and records the anomaly and estimated missing-interval counts.

## Dated instrument master

The UTF-8 CSV has columns:

`instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,effective_from,effective_through`

Every bar resolves by exchange, segment, source token, and session date. Its contract identity, expiry, strike/right where applicable, lot size, tick size, and symbol must match the resolved row. Each run must contain exactly one dated `FUTURE` or `OPTION` contract. Bars on or after expiry are rejected; option exercise and settlement are not modeled.

## Open-session calendar

The UTF-8 CSV has columns `session_date,session_status,first_bar_timestamp,last_bar_timestamp`. Status is `OPEN` or `CLOSED`; closed dates must leave both timestamp fields blank. Open dates require explicit offset-bearing timestamps for the first and last expected bar labels. Duplicate dates fail.

The calendar must include every local calendar date from the first through last bar, including dates marked closed. Every open date in that range must have bars, and its first/last bar timestamps must match the declared boundaries. Within each session, bars must match the configured interval by default. The optional gap override records incomplete sessions for diagnosis. These checks validate the supplied calendar windows; they cannot establish that the calendar itself is accurate or current.

## Run properties

The complete working example is `examples/synthetic-run.properties`. Important required values:

- `dataset.rights_status`: `UNVERIFIED` or `AUTHORIZED_LOCAL_REPLAY`. The latter requires a logical `dataset.rights_reference` identifier. Non-synthetic replay is rejected unless `AUTHORIZED_LOCAL_REPLAY` is selected; this is an operator assertion, not an automated entitlement check.
- `dataset.synthetic_fixture`: must remain `true` for synthetic bars; synthetic runs must keep data rights and costs unverified.
- `reference.file`, `calendar.file`: paths relative to the properties file unless absolute.
- `strategy.fast_bars`, `strategy.slow_bars`, `strategy.lots`: built-in long-only SMA harness settings. It receives immutable history through the decision bar; strategy ID and parameters are written into the run manifest and content ID.
- The CLI selects the built-in SMA harness. Java callers can inject another implementation of `Strategy`; the engine supplies only bars through the current decision point and records that implementation's ID and stable parameters.
- `risk.max_position_lots`, `risk.max_notional_inr`, `risk.margin_per_lot_inr`, `risk.max_margin_inr`, and `risk.max_daily_loss_inr`: mandatory configured limits. Margin is a fixed user-supplied proxy used only to cap simulated entries; it is not an exchange SPAN/ELM calculation. Sample values are arbitrary and must never be carried into a real deployment.
- `orders.allow_next_session`: whether an order created at a session close may fill at the next session's open. It is `false` in the sample. Set it explicitly for daily bars or any workflow that permits cross-session pending orders.
- `position.cross_session_policy`: `ALLOW_OVERNIGHT` permits positions to cross session boundaries and be marked at end of data. `REQUIRE_FLAT` rejects a run if a position crosses an open-session boundary or remains open at the end; it does not invent a liquidation fill and cannot be combined with `orders.allow_next_session=true`.
- `cost.status`: `UNVERIFIED` or `VERIFIED`. `VERIFIED` requires `cost.evidence_reference`. Fee-per-order, fee-per-unit, and adverse slippage ticks are always explicit.
- `run.commit_id`: the source commit associated with the run. It defaults to `BUILD_COMMIT`, read from the JAR manifest. The build records a commit only from a clean Git checkout; dirty builds are marked `unknown-dirty`. A non-synthetic replay requires a full 40-character commit ID, so it fails closed with an unidentified/dirty build. The sample uses a synthetic fixture label.

## Fill, risk, and accounting behavior

The strategy reads a completed bar's close and may create a local market order. It can fill only at the next bar's open. BUY fills add the configured slippage ticks; SELL fills subtract them. Fees are charged per order and per unit. A candle's high/low touching a price never creates a passive fill because passive orders are not supported.

The simulator is long-only and uses full fills. For options it treats the supplied premium bars as marks and fills; it does not calculate theoretical option values or Greeks. It checks maximum lots, notional, and the fixed user-supplied margin proxy before a new entry. Session P&L is marked at each close; when configured maximum daily loss is reached, the session is halted and an exit is scheduled for the next eligible bar. Cross-session pending fills are disabled unless `orders.allow_next_session=true`. No partial fills, latency, exchange priority, exchange margin formula, exercise, settlement, or broker state is modeled. A position may remain open after the last bar or across a session boundary.

## Run artifacts

- `run-manifest.json`: dataset and reference identifiers, input hashes, model/configuration, risk and cross-session position policy, output hashes, and the always-false performance-claim flag.
- `summary.json`: diagnostic counts and P&L accounting only.
- `signals.csv`: one row per completed-bar strategy decision, including the indicator values or warm-up state.
- `sessions.csv`: per-session bar coverage, starting/ending position, realized/unrealized P&L change, fees, modeled slippage, order/fill counts, and risk halts. Net P&L change reconciles to realized plus unrealized change less fees; slippage is reflected in simulated fill prices and also reported as a diagnostic.
- `orders.csv`, `order-events.csv`: local simulated lifecycle; not broker observations.
- `fills.csv`: reference open, modeled fill, slippage, fees, and resulting position.
- `trades.csv`: completed long trades. Open positions are represented in the equity file and final position summary.
- `equity.csv`: per-bar marked realized/unrealized/net P&L and risk state.

Synthetic results are plumbing-only. Non-synthetic results remain diagnostic-only in this build. Use the tracker to see the gates that still need evidence before any performance assessment or forward-paper stage.
