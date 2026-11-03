# Offline Backtest Operating Guide

## Commands

Build with JDK 21 from the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\Intra-Paper-System\build.ps1
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
```

The build compiles into a new temporary classes directory under the external build folder, stages the JAR, and atomically replaces the published artifact after packaging succeeds. It then removes temporary output. This avoids stale class files carrying over and preserves the previous JAR if a build fails. Set `INTRA_PAPER_BUILD_DIR` to override the default output folder.

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

`verify` recomputes the SHA-256 hashes for the nine output ledgers in `run-manifest.json` and checks the folder ID. It does not re-open or re-authorize source inputs.

## Verify a quote journal

```powershell
& $java -jar $jar journal-verify .\path\to\quotes.qjr
```

`journal-verify` reads the complete journal and checks its header, record framing, canonical quote encoding, strictly increasing sequence numbers, and SHA-256 hash chain. It emits a final event count, sequence number, byte count, and chain hash, plus the number of sequence jumps and total missing sequence integers between recorded events. A jump is diagnostic only: whether it represents loss depends on the upstream sequence domain and subscription coverage. The first recorded sequence is not compared to an assumed origin. The verifier does not contact or authenticate to a market-data source.

Java callers can create a local raw-payload journal with `RawCaptureJournalWriter.create(path)` and append each received payload with its source, connection/subscription identifiers, optional upstream sequence, and receive wall/monotonic timestamps. `RawCaptureJournalWriter.resume(path)` locks and verifies the entire journal, restores the final chain hash, then appends at the verified end; corrupt or truncated journals are rejected without repair. Payload bytes are stored unchanged inside a versioned length-delimited frame and SHA-256 chain. `raw-capture-verify <journal>` verifies framing and the complete chain without making a network connection. It also reports upstream sequence jumps and non-increasing transitions within each source/connection/subscription stream. These counts are diagnostic: sequence jumps do not prove packet loss, source sequences may reset or use other semantics, and no starting sequence is assumed. Capture adapters, connection-state events, retention authorization, and privacy controls are not implemented here; this API does not establish the source, entitlement, or completeness of captured bytes.

Java callers can use `RawCaptureQuoteImporter.importJournal(rawPath, quotePath, decoder)` to replay a verified raw journal through a caller-supplied `RawQuoteDecoder`. The decoder may skip non-quote messages with `Optional.empty()` and must return normalized quote values whose source and receive clocks match the capture metadata. The importer assigns a new contiguous local quote-journal sequence, verifies the complete output journal, then atomically publishes it at a new path through same-filesystem hard-link creation; it fails closed if the filesystem lacks hard-link support. It does not create bars, establish contract mapping or source semantics, include a vendor decoder, or connect to a feed.

Java callers may use `QuoteJournalWriter.resume(path)` to continue an existing journal. Resume takes an exclusive file lock, scans and validates the complete journal before returning a writer, restores the last sequence number and chain hash, and appends at the verified end. Corrupt, truncated, or concurrently changed journals are rejected; no automatic truncation or repair is attempted. All writers using this API honor the exclusive lock. This restores the quote journal only; it does not restore simulator, OMS, or risk state.

Java callers may apply `QuoteQualityPolicy(maximumAge, maximumFutureSkew)` to get stale/future exchange-timestamp flags in addition to the quote's structural quality flags. Both durations are explicit inputs; the assessor retains the snapshot and does not choose an exchange or broker tolerance. Missing exchange time remains flagged by the base quote assessment.

`QuoteExecutionGate.assess(...)` evaluates a normalized quote at the caller's decision time. It rejects a contract mismatch, a receive timestamp later than the decision, any structural/freshness flag, insufficient displayed size, integer price overflow, or a non-positive adverse-slippage price. BUY uses the ask and SELL the bid, then applies caller-supplied adverse ticks. It accepts only full displayed-size top-of-book snapshots; it does not model queue priority, partial fills, latency, or a broker/exchange execution guarantee, and the current CLI/backtest engine does not call this helper.

Journal creation uses create-new semantics. If initialization fails, the writer closes the handle and removes the file it just created so an incomplete header cannot masquerade as a valid empty journal.

## Validate a chronological evaluation split

Provide a UTF-8 CSV with exactly `session_date,partition` columns. Assign every open session in the validated dataset date range exactly once, using only `DEVELOPMENT`, `VALIDATION`, or `HOLDOUT`. Each partition must have at least one session and the partition order must be contiguous and chronological.

```powershell
& $java -jar $jar split-validate .\bars.csv .\run.properties .\evaluation-splits.csv
```

The command checks coverage and ordering, then prints the split-file SHA-256. It does not score strategies or open the holdout results; that workflow remains unfinished.

## Reproduce and verify a saved run

```powershell
& $java -jar $jar replay-verify .\Intra-Paper-System\examples\synthetic-futures-bars.csv .\Intra-Paper-System\examples\synthetic-run.properties $runFolder
```

`replay-verify` validates the saved output hashes, reloads the supplied bars and run properties, recomputes the content ID from input hashes, configured build commit, and strategy identity/parameters, runs the simulator in a new temporary directory, and compares all nine output ledgers plus `run-manifest.json` byte-for-byte. The run folder ID must match the supplied inputs and configured build commit. The temporary replay directory is removed after success or failure. This verifies deterministic reproduction; it does not verify data rights or the truth of the source data.

Runs are stored under `%LOCALAPPDATA%\Intra-Paper-System\runs\<content-id>`. Set `INTRA_PAPER_RUNS_DIR` before starting Java to select a different directory. Existing run folders are never overwritten.

## Normalized bars CSV

The header must include these fields in any order:

`timestamp,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,interval,timestamp_role,open,high,low,close,volume,open_interest,source,raw_record_hash`

CSV fields may be unquoted or enclosed in double quotes. Escape an embedded quote by doubling it. Stray quotes, characters after a closing quote, and quoted fields spanning multiple lines are rejected.

- `timestamp` must include an explicit UTC offset. `timestamp_role` must be `BAR_START` or `BAR_END` and must agree with the run configuration.
- The supported interval labels are `ONE_MINUTE`, `FIVE_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, and `ONE_DAY`.
- OHLC prices must be positive, internally consistent, and exact multiples of `tick_size`. Volume and optional open interest must be non-negative.
- `raw_record_hash` must be lowercase SHA-256 hex. The run manifest separately hashes the complete normalized bars CSV, reference CSV, calendar CSV, and configuration file.
- `volume` and `open_interest` may be blank when absent; blank is preserved as null and numeric zero remains zero.
- Each CSV input's SHA-256 is computed while the parser consumes that exact byte stream, so a later file change cannot make the manifest hash refer to different bytes than the parsed records. The properties file is likewise read once and hashed from the byte snapshot used to parse it. This establishes byte provenance for the local parser input; it does not establish source authenticity, entitlement, or correctness.
- The loader checks the row-hash format but cannot independently verify its source relationship without the original raw packet/file and a source-specific raw-record decoder.
- Bar dates are derived in the configured timezone and must exist in the supplied open-session calendar.
- Adjacent bars within a session must be exactly one configured interval apart by default. `quality.allow_intrasession_gaps=true` permits spacing anomalies only as a reviewed diagnostic. `quality.max_missing_intervals_per_session` caps the estimated deficit against the calendar's expected bar count (zero in the example); exceeding the cap always fails input validation. The manifest records the configured cap and observed anomalies.

## Dated instrument master

The UTF-8 CSV has columns:

`instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,effective_from,effective_through`

Every bar resolves by exchange, segment, source token, and session date. Its contract identity, expiry, strike/right where applicable, lot size, tick size, and symbol must match the resolved row. Each run must contain exactly one dated `FUTURE` or `OPTION` contract. Bars after expiry are rejected. If the dataset includes an expiry-date session, an explicit settlement input is required.

### Expiry settlement input

Set optional `settlement.file` in the properties file when the dataset reaches the contract's actual last trading/settlement session. The UTF-8 CSV must contain exactly one row and these exact headers:

`instrument_id,expiry,settlement_date,settlement_timestamp,settlement_kind,settlement_value,source_reference`

`settlement_kind` must be `FUTURES_FINAL_PRICE` for a future or `UNDERLYING_FINAL_CLOSE` for an option. The value is the supplied futures final settlement price or the option's underlying final close, respectively. For a long option, the simulator computes intrinsic settlement value from the row, strike, and call/put right. The source reference is a logical identifier for the source record; it is not independently verified. The settlement timestamp must be offset-bearing, fall on the settlement date in `dataset.timezone`, and be no earlier than the final bar end. The settlement date must be the final dataset session, have complete bars, and not be after the instrument expiry. This allows a source-specified prior trading day without hardcoding an expiry weekday.

The settlement input hash participates in the run identity and manifest. `settlements.csv` records the input value, derived per-unit exit value, settled units, P&L, and source reference. Settlement charges and taxes are not modeled; `fees_paid_inr` covers simulated order fills only. Results remain diagnostic and ineligible for performance claims.

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
- `quality.max_missing_intervals_per_session`: maximum estimated bar deficit for any open session. Keep this at zero unless an incomplete diagnostic run is explicitly intended and the schedule/data gap has been reviewed.
- `risk.max_position_lots`, `risk.max_notional_inr`, `risk.margin_per_lot_inr`, `risk.max_margin_inr`, and `risk.max_daily_loss_inr`: mandatory configured limits. Margin is a fixed user-supplied proxy used only to cap simulated entries; it is not an exchange SPAN/ELM calculation. Sample values are arbitrary and must never be carried into a real deployment.
- `risk.min_seconds_between_entry_orders`: minimum elapsed time between new BUY order intents across the replay. Zero disables the throttle. Risk-reducing SELL exits bypass it. This is an explicit local simulation guard, not a broker/exchange rate-limit setting.
- `orders.allow_next_session`: whether an order created at a session close may fill at the next session's open. It is `false` in the sample. Set it explicitly for daily bars or any workflow that permits cross-session pending orders.
- `position.cross_session_policy`: `ALLOW_OVERNIGHT` permits positions to cross session boundaries and be marked at end of data. `REQUIRE_FLAT` rejects a run if a position crosses a session boundary or remains open at the end. `AUTO_FLATTEN_SESSION_END` schedules a simulated exit for the final session bar's open from the prior bar close, and blocks entries at that prior close; the run fails if a position remains open at the final bar. This uses the supplied session calendar and next-bar-open fill model; it is not exchange market-on-close behavior. Either flat-at-session-end policy requires `orders.allow_next_session=false`.
- `cost.status`: `UNVERIFIED` or `VERIFIED`. `VERIFIED` requires `cost.evidence_reference`. Fee-per-order, fee-per-unit, notional fee basis points, and adverse slippage ticks are always explicit. The notional component is rounded HALF_UP to paise per fill; this configurable component is not a substitute for a reviewed dated broker/exchange schedule.
- Optional `cost.schedule_file` selects a dated schedule CSV with exact columns `effective_from,effective_through,side,fee_per_order_inr,fee_per_unit_inr,notional_fee_bps,slippage_ticks`. It requires non-overlapping BUY and SELL ranges and coverage for both sides on every open session in the dataset. When supplied, schedule values override the static cost inputs for each fill date and side; the file hash is included in the run identity and manifest. Blank `effective_through` means no scheduled end date.
- `run.commit_id`: the source commit associated with the run. It defaults to `BUILD_COMMIT`, read from the JAR manifest. The build records a commit only from a clean Git checkout; dirty builds are marked `unknown-dirty`. A non-synthetic replay requires a full 40-character commit ID, so it fails closed with an unidentified/dirty build. The sample uses a synthetic fixture label.

## Fill, risk, and accounting behavior

Before artifacts are written, independent reconciliation passes check realized trade P&L, fill fees and slippage, every per-order state transition and event timestamp, per-session totals, and the last equity mark. A separate risk audit rechecks each BUY fill against configured lot, notional, and margin-proxy limits, requires one halt event at the actual loss-breach equity timestamp, reconciles halt events to the run counter, and rejects later same-session entries. Another audit matches every fill to its bar and recomputes adverse slippage and per-order, per-unit, and notional-bps charges from the configured or effective-dated schedule. Any mismatch fails the run.

The strategy reads a completed bar's close and may create a local market order. It can fill only at the next bar's open. BUY fills add the applicable configured/scheduled slippage ticks; SELL fills subtract them. Each fill fee includes the applicable per-order charge, per-unit charge, and notional basis-point component. A candle's high/low touching a price never creates a passive fill because passive orders are not supported.

The simulator is long-only and uses full fills. Scale-in targets are recorded as rejected while a position is open; this model supports one full entry and one full exit per trade. Lot-to-unit conversions use checked integer multiplication and fail closed if an input quantity exceeds the ledger's integer range. For options it treats the supplied premium bars as marks and fills; it does not calculate theoretical option values or Greeks. It checks maximum lots, notional, and the fixed user-supplied margin proxy before a new entry. Session P&L is marked at each close; when configured maximum daily loss is reached, the session is halted and an exit is scheduled for the next eligible bar. Cross-session pending fills are disabled unless `orders.allow_next_session=true`. No partial fills, latency, exchange priority, exchange margin formula, settlement charges, or broker state are modeled. If an expiry settlement row is provided, a long position is cash-settled from that explicit input. A position may otherwise remain open after the last bar or across a session boundary.

## Run artifacts

- `run-manifest.json`: dataset and reference identifiers, input hashes, model/configuration, risk and cross-session position policy, output hashes, and the always-false performance-claim flag.
- `summary.json`: diagnostic counts and P&L accounting. It also reports completed-trade win/loss counts, completed-trade net P&L, win rate, average net P&L, net profit factor, average holding time, filled units, and filled turnover. Undefined trade statistics are `null`; open-position mark-to-market remains in the overall P&L fields. All remain diagnostic-only.
- `signals.csv`: one row per completed-bar strategy decision, including the indicator values or warm-up state.
- `sessions.csv`: per-session bar coverage, starting/ending position, realized/unrealized P&L change, fees, modeled slippage, order/fill counts, and risk halts. Net P&L change reconciles to realized plus unrealized change less fees; slippage is reflected in simulated fill prices and also reported as a diagnostic.
- `orders.csv`, `order-events.csv`: local simulated lifecycle; not broker observations.
- `fills.csv`: reference open, modeled fill, slippage, fees, and resulting position.
- `trades.csv`: completed long trades. Open positions are represented in the equity file and final position summary.
- `equity.csv`: per-bar marked realized/unrealized/net P&L and risk state.

Synthetic results are plumbing-only. Non-synthetic results remain diagnostic-only in this build. Use the tracker to see the gates that still need evidence before any performance assessment or forward-paper stage.
