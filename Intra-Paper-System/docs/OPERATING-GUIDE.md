# Offline Backtest Operating Guide

## Commands

Build with JDK 21 from the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\Intra-Paper-System\build.ps1
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
```

Validate inputs before a run:

```powershell
java -jar $jar validate .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties
```

Run the local simulator:

```powershell
java -jar $jar run .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties
```

Runs are stored under `%LOCALAPPDATA%\Intra-Paper-System\runs\<content-id>`. Set `INTRA_PAPER_RUNS_DIR` before starting Java to select a different directory. Existing run folders are never overwritten.

## Normalized bars CSV

The header must include these fields in any order:

`timestamp,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,lot_size,tick_size,interval,timestamp_role,open,high,low,close,volume,open_interest,source,raw_record_hash`

- `timestamp` must include an explicit UTC offset. `timestamp_role` must be `BAR_START` or `BAR_END` and must agree with the run configuration.
- The supported interval labels are `ONE_MINUTE`, `FIVE_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, and `ONE_DAY`.
- OHLC prices must be positive, internally consistent, and exact multiples of `tick_size`. Volume and optional open interest must be non-negative.
- `raw_record_hash` must be lowercase SHA-256 hex. The run manifest separately hashes the complete normalized bars CSV, reference CSV, calendar CSV, and configuration file.
- Bar dates are derived in the configured timezone and must exist in the supplied open-session calendar.

## Dated instrument master

The UTF-8 CSV has columns:

`instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,effective_from,effective_through`

Every bar resolves by exchange, segment, source token, and session date. Its contract identity, expiry, lot size, tick size, and symbol must match the resolved row. This build accepts only `FUTURE`, and each run must contain exactly one dated contract.

## Open-session calendar

The UTF-8 CSV has columns `session_date,session_status`, where status is `OPEN` or `CLOSED`. Duplicate dates and bar dates not marked `OPEN` fail input validation. This file is user-supplied provenance; it does not claim to be a current exchange calendar.

## Run properties

The complete working example is `examples/synthetic-run.properties`. Important required values:

- `dataset.rights_status`: `UNVERIFIED` or `AUTHORIZED_LOCAL_REPLAY`. The latter requires a logical `dataset.rights_reference` identifier.
- `dataset.synthetic_fixture`: must remain `true` for synthetic bars; synthetic runs must keep data rights and costs unverified.
- `reference.file`, `calendar.file`: paths relative to the properties file unless absolute.
- `strategy.fast_bars`, `strategy.slow_bars`, `strategy.lots`: long-only SMA crossover harness settings.
- `risk.max_position_lots`, `risk.max_notional_inr`, `risk.max_daily_loss_inr`: mandatory configured limits. The sample values are arbitrary and must never be carried into a real deployment.
- `cost.status`: `UNVERIFIED` or `VERIFIED`. `VERIFIED` requires `cost.evidence_reference`. Fee-per-order, fee-per-unit, and adverse slippage ticks are always explicit.
- `run.commit_id`: the source commit associated with the run; the sample uses a fixture label.

## Fill, risk, and accounting behavior

The strategy reads a completed bar's close and may create a local market order. It can fill only at the next bar's open. BUY fills add the configured slippage ticks; SELL fills subtract them. Fees are charged per order and per unit. A candle's high/low touching a price never creates a passive fill because passive orders are not supported.

The simulator is long-only and uses full fills. It checks maximum lots and notional before a new entry. Session P&L is marked at each close; when configured maximum daily loss is reached, the session is halted and an exit is scheduled for the next eligible bar in that same session. No partial fills, latency, exchange priority, margin, or broker state is modeled. A position may remain open after the last bar or across a session boundary.

## Run artifacts

- `run-manifest.json`: dataset and reference identifiers, input hashes, model/configuration, limits, output hashes, and the always-false performance-claim flag.
- `summary.json`: diagnostic counts and P&L accounting only.
- `orders.csv`, `order-events.csv`: local simulated lifecycle; not broker observations.
- `fills.csv`: reference open, modeled fill, slippage, fees, and resulting position.
- `trades.csv`: completed long trades. Open positions are represented in the equity file and final position summary.
- `equity.csv`: per-bar marked realized/unrealized/net P&L and risk state.

Synthetic results are plumbing-only. Non-synthetic results remain diagnostic-only in this build. Use the tracker to see the gates that still need evidence before any performance assessment or forward-paper stage.
