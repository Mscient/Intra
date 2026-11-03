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

`verify` recomputes the SHA-256 hashes for the twelve current output ledgers in `run-manifest.json` and checks the folder ID. It also accepts earlier nine-, ten-, and eleven-output manifests. It does not re-open or re-authorize source inputs.

Reconstruct the completed run's saved OMS state after verifying all run-artifact hashes:

```powershell
& $java -jar $jar oms-recover $runFolder
```

The command replays order events through the state machine, restores execution-ID deduplication from `fills.csv`, calculates any residual pending/unknown unit reservations, and reconciles execution plus settlement position against the final `equity.csv` row. It fails if artifact hashes, lifecycle transitions, quantities, or final position disagree. This reconstructs a completed run and does not restore broker/feed state. The offline replay engine has its own deterministic checkpoints, which resume by rerunning `run` with the same content identity.

Run output is published by atomic directory move after all ledgers and hashes are written. An active writer holds an operating-system lock beside its temporary staging folder. On a later run write, recognized staging folders are removed only when their lock can be acquired. Legacy folders without a lock marker and currently active writes are preserved. The published run is never resumed in place; use deterministic replay verification to compare it with frozen inputs.

## OMS state primitives

`OmsOrderStateMachine` provides the venue-neutral Created, PendingNew, Working, PartiallyFilled, PendingCancel, PendingReplace, Filled, Canceled, Rejected, Expired, and Unknown states. Pending and Unknown states reserve the still-unconfirmed requested units after known executions; terminal states reserve zero, and Filled requires all requested units. For PendingReplace, callers supply the larger old/new quantity as `requestedWorstCaseUnits`; Unknown-state callers supply the applicable route-specific worst case. This computes units only, so a risk gateway must still value that reservation. `ExecutionIdempotencyLedger` applies each execution ID once, ignores an exact duplicate, and rejects reuse with a different order/time/side/quantity/price payload. Its restore operation rebuilds the ledger from persisted execution events and clears partial state if replay fails.

The existing bar simulator uses local immediate acknowledgements and full simulated fills; the generic partial-fill and Unknown-state primitives are not connected to a feed, broker, or live risk gateway. The independent run reconciliation rejects duplicate execution IDs in the persisted simulated-fill ledger.

## Verify a quote journal

```powershell
& $java -jar $jar journal-verify .\path\to\quotes.qjr
```

`journal-verify` reads the complete journal and checks its header, record framing, canonical quote encoding, strictly increasing sequence numbers, and SHA-256 hash chain. It emits a final event count, sequence number, byte count, and chain hash, plus the number of sequence jumps and total missing sequence integers between recorded events. A jump is diagnostic only: whether it represents loss depends on the upstream sequence domain and subscription coverage. The first recorded sequence is not compared to an assumed origin. The verifier does not contact or authenticate to a market-data source.

To produce a quote-quality report while performing that same complete integrity scan:

```powershell
& $java -jar $jar quote-quality-report .\path\to\quotes.qjr $maximumAgeMs $maximumFutureSkewMs
```

Set `$maximumAgeMs` and `$maximumFutureSkewMs` to reviewed freshness thresholds for the source and use case; the command has no assumed feed tolerance. The policy compares exchange timestamps with each quote's local receive timestamp. The report prints clean and flagged event counts, exchange-timestamp coverage, maximum observed age and future skew, the chosen thresholds, counts for each `QuoteQualityFlag`, and the journal's integrity and sequence summary. An event with multiple findings increments each corresponding flag count, so per-flag counts are not mutually exclusive. Missing exchange timestamps are flagged but do not contribute to age/skew extrema. The command reports findings without filtering quotes or certifying source completeness, entitlement, or suitability.

Java callers can create a local raw-payload journal with `RawCaptureJournalWriter.create(path)` and append each received payload with its source, connection/subscription identifiers, optional upstream sequence, and receive wall/monotonic timestamps. `RawCaptureJournalWriter.resume(path)` locks and verifies the entire journal, restores the final chain hash, then appends at the verified end; corrupt or truncated journals are rejected without repair. Payload bytes are stored unchanged inside a versioned length-delimited frame and SHA-256 chain. `raw-capture-verify <journal>` verifies framing and the complete chain without making a network connection. It also reports upstream sequence jumps and non-increasing transitions within each source/connection/subscription stream. These counts are diagnostic: sequence jumps do not prove packet loss, source sequences may reset or use other semantics, and no starting sequence is assumed. Capture adapters, connection-state events, retention authorization, and privacy controls are not implemented here; this API does not establish the source, entitlement, or completeness of captured bytes.

Java callers can use `RawCaptureQuoteImporter.importJournal(rawPath, quotePath, decoder)` to replay a verified raw journal through a caller-supplied `RawQuoteDecoder`. The decoder may skip non-quote messages with `Optional.empty()` and must return normalized quote values whose source and receive clocks match the capture metadata. The importer assigns a new contiguous local quote-journal sequence, verifies the complete output journal, then atomically publishes it at a new path through same-filesystem hard-link creation; it fails closed if the filesystem lacks hard-link support. It does not create bars, establish contract mapping or source semantics, include a vendor decoder, or connect to a feed.

Java callers may use `QuoteJournalWriter.resume(path)` to continue an existing journal. Resume takes an exclusive file lock, scans and validates the complete journal before returning a writer, restores the last sequence number and chain hash, and appends at the verified end. Corrupt, truncated, or concurrently changed journals are rejected; no automatic truncation or repair is attempted. All writers using this API honor the exclusive lock. This restores the quote journal only; it does not restore simulator, OMS, or risk state.

Java callers may apply `QuoteQualityPolicy(maximumAge, maximumFutureSkew)` to get stale/future exchange-timestamp flags in addition to the quote's structural quality flags. Both durations are explicit inputs; the assessor retains the snapshot and does not choose an exchange or broker tolerance. Missing exchange time remains flagged by the base quote assessment.

`TopOfBookFeatureExtractor.extract(quote, expectedInstrumentId, decisionTimeUtc, qualityPolicy)` derives L1 spread, midpoint, displayed-size-weighted microprice, and size imbalance in ticks/units from a single normalized snapshot. The returned `decisionEligible` value requires the expected instrument, a receive time no later than the decision clock, and no structural/freshness flags. Values are still snapshot diagnostics: this API does not combine depth, trades, or historical flow, and it does not infer queue priority or connect to the bar backtest engine.

`TopOfBookFeatureStream` processes snapshots in increasing local quote-journal sequence. It emits an L1 snapshot order-flow estimate only when adjacent local sequence numbers, the source identifier, and both snapshots' eligibility agree. A sequence gap or source change suppresses that estimate. These are normalized-journal continuity checks; they do not replace upstream sequence diagnostics from raw capture or establish source completeness. The estimate is based on best-price and displayed-size changes, not order-level executions.

Export the features from a complete quote journal to a new CSV file:

```powershell
& $java -jar $jar quote-feature-export .\path\to\quotes.qjr $instrumentId $maximumAgeMs $maximumFutureSkewMs .\output\quote-features.csv
```

Set the instrument ID and freshness limits explicitly. Replay uses each record's receive timestamp as its virtual decision clock. The exporter verifies the journal, streams a second replay to a temporary file, and compares both integrity summaries before publishing by same-directory hard link; it never overwrites an existing output. Rows preserve the source journal's final hash, expected instrument, freshness limits, snapshot flags and metrics, local sequence-gap details, and the L1 order-flow estimate where available. Choose an output folder outside the repository for private/licensed data. Filesystems without hard-link support fail closed.

`QuoteExecutionGate.assess(...)` evaluates a normalized quote at the caller's decision time. It rejects a contract mismatch, a receive timestamp later than the decision, any structural/freshness flag, insufficient displayed size, integer price overflow, or a non-positive adverse-slippage price. BUY uses the ask and SELL the bid, then applies caller-supplied adverse ticks. It accepts only full displayed-size top-of-book snapshots and makes no execution guarantee.

`QuoteOrderReplay.execute(...)` is an offline IOC replay over caller-supplied quote history. The caller must first verify the source journal with `journal-verify`; each intent supplies its source, instrument, decision time, decision-to-arrival latency, side, IOC type, quantity, adverse ticks, tick size, and optional limit ticks. The simulator selects the latest matching snapshot observable at arrival, applies the quote-quality policy, and fills no more than displayed top-level quantity. It returns OMS state transitions, an execution event for the filled portion, and a canceled residual for partial IOC orders. Non-marketable limits are missed; passive queue fills are not inferred without trade/order-level data. This per-intent API does not reserve or deplete displayed size across separate intents, so it is not a capacity estimate or conservative fill lower bound. Latency is an explicit per-intent input; this API does not estimate a route distribution, apply fees, write run artifacts, or submit broker orders. The current bar-run CLI does not call it.

Run batches from a verified journal with a UTF-8 CSV that has exactly these columns in this order: `order_id,source,instrument_id,decision_time_utc,latency_ms,side,type,requested_units,limit_price_ticks,adverse_slippage_ticks,tick_size`. Timestamps use ISO-8601 UTC instants. `side` is `BUY` or `SELL`; `type` is `MARKET_IOC` or `LIMIT_IOC`; leave `limit_price_ticks` empty for market IOC and provide a positive integer tick price for limit IOC. Order IDs must be unique. Example:

```powershell
& $java -jar $jar quote-order-replay .\quotes.qj .\intents.csv 2000 250 .\quote-replay.csv
```

The command verifies the full quote journal before writing, then builds a per-source/instrument index ordered by receive time for batch lookup. Each output row records the journal final hash, exact intent CSV hash, model ID, selected quote, outcome, OMS events, and execution identity. Output is atomically created without overwriting an existing file. The batch sorts intents by arrival time and uses input row order to break ties. For each selected quote and side, it consumes displayed quantity once until another snapshot is selected. The command holds the complete quote history and its index in memory because event timestamps are not required to be monotonic in journal order; select journals that fit available memory. This snapshot-depletion rule does not model hidden liquidity, market impact, quote withdrawal before arrival, or passive queue fills, so results are not an aggregate capacity estimate or conservative fill lower bound. The replay is not integrated into the bar strategy runner and does not model fees, route distributions, or broker state.

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

`replay-verify` validates the saved output hashes, reloads the supplied bars and run properties, recomputes the content ID from input hashes, configured build commit, and strategy identity/parameters, runs the simulator in a new temporary directory, and compares all twelve current output ledgers plus `run-manifest.json` byte-for-byte. The run folder ID must match the supplied inputs and configured build commit. The temporary replay directory is removed after success or failure. This verifies deterministic reproduction; it does not verify data rights or the truth of the source data.

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

Or, to provide an option exercise turnover basis, append `sebi_exercise_turnover_value_per_unit_inr` after `source_reference`. Its value is blank or a positive per-unit INR amount. The older seven-column form remains accepted.

`settlement_kind` must be `FUTURES_FINAL_PRICE` for a future or `UNDERLYING_FINAL_CLOSE` for an option. The value is the supplied futures final settlement price or the option's underlying final close, respectively. For a long option, the simulator computes intrinsic settlement value from the row, strike, and call/put right. The optional `sebi_exercise_turnover_value_per_unit_inr` column supplies the per-unit turnover base for SEBI's additional exercise fee; source and exact base must be confirmed for the applicable contract and trade date. If a component cost schedule has a positive SEBI fee and an ITM long option is settled, the run fails closed unless this basis is supplied. The source reference is a logical identifier for the source record; it is not independently verified. The settlement timestamp must be offset-bearing, fall on the settlement date in `dataset.timezone`, and be no earlier than the final bar end. The settlement date must be the final dataset session, have complete bars, and not be after the instrument expiry. This allows a source-specified prior trading day without hardcoding an expiry weekday.

The settlement input hash participates in the run identity and manifest. `settlements.csv` records the input value, derived per-unit exit value, settled units, P&L, settlement fees, and source reference. With `cost.components_file`, long-option exercise STT uses `exercise_stt_bps` from the effective OPTION BUY row and the total intrinsic-value basis; the SEBI fee uses `sebi_inr_per_crore` and the optional source-supplied turnover basis. `settlement-charges.csv` records the bases and calculated components. GST on these exercise charges follows the explicit `gst_on_stt` and `gst_on_sebi` flags; callers must validate them against the broker invoice and tax treatment. An optional `settlement.cost_schedule_file` can add source-attributed fees for any supported settlement type; `settlement-cost-lines.csv` records each component, calculation base, rate, GST base, source reference, and amount. Those supplied lines do not establish a complete or verified account tariff. `fees_paid_inr` includes the modeled exercise and additional settlement fees. Results remain diagnostic and ineligible for performance claims.

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
- `risk.max_position_lots`, `risk.max_notional_inr`, `risk.max_margin_inr`, and `risk.max_daily_loss_inr`: mandatory configured limits. Without a schedule, `risk.margin_per_lot_inr` is also required and is a fixed user-supplied proxy. Alternatively, `risk.margin_schedule_file` selects a source-attributed, effective-dated per-contract margin input; when present, omit `risk.margin_per_lot_inr` or set it to zero. These values cap simulated BUY entries and are written into each fill audit. Neither option computes NSE SPAN/ELM or maintenance margin, and sample values are arbitrary.
- `risk.margin_schedule_file`: optional CSV with exact headers `effective_from,effective_through,instrument_id,contract_kind,margin_per_lot_inr,source_reference`. It requires non-overlapping ranges and a matching row for every open session in the replay. The schedule applies to the one dated `FUTURE` or `OPTION` contract in the dataset; a zero value is allowed when supported by the source and must not be used to imply that other risk controls are unnecessary. The file hash is included in the run identity and manifest, and its rate plus source reference are recorded for each fill. These caller inputs require independent agreement with their cited source and account treatment.
- `recovery.checkpoint_interval_bars`: optional non-negative integer; defaults to 250 and zero disables durable replay checkpoints. The CLI saves a checkpoint after each interval and at the final processed bar. A checkpoint is an append-only delta with a checksum/hash link, published atomically under `<runs-root>/.checkpoints/<run-id>` while holding an exclusive lock. Re-running the exact same bars, configuration, strategy, and build resumes from the last complete checkpoint. The built-in SMA harness declares deterministic restart support; a custom Java strategy must explicitly declare this contract before the checkpointed engine overload accepts it. A corrupt or input-mismatched chain fails closed. Checkpoints are deleted only after the completed run folder is published. This restores an offline replay, not a broker session or live OMS state.
- `risk.min_seconds_between_entry_orders`: minimum elapsed time between new BUY order intents across the replay. Zero disables the throttle. Risk-reducing SELL exits bypass it. This is an explicit local simulation guard, not a broker/exchange rate-limit setting.
- `orders.allow_next_session`: whether an order created at a session close may fill at the next session's open. It is `false` in the sample. Set it explicitly for daily bars or any workflow that permits cross-session pending orders.
- `position.cross_session_policy`: `ALLOW_OVERNIGHT` permits positions to cross session boundaries and be marked at end of data. `REQUIRE_FLAT` rejects a run if a position crosses a session boundary or remains open at the end. `AUTO_FLATTEN_SESSION_END` schedules a simulated exit for the final session bar's open from the prior bar close, and blocks entries at that prior close; the run fails if a position remains open at the final bar. This uses the supplied session calendar and next-bar-open fill model; it is not exchange market-on-close behavior. Either flat-at-session-end policy requires `orders.allow_next_session=false`.
- `cost.status`: `UNVERIFIED` or `VERIFIED`. `VERIFIED` requires `cost.evidence_reference`. Without either dated schedule, fee-per-order, fee-per-unit, notional fee basis points, and adverse slippage ticks are required. The legacy notional component is rounded HALF_UP to paise per fill. With `cost.components_file`, legacy fee/slippage inputs may be omitted or set to zero; non-zero legacy values are rejected, and the dated component rows control each fill.
- Optional `cost.schedule_file` selects a dated schedule CSV with exact columns `effective_from,effective_through,side,fee_per_order_inr,fee_per_unit_inr,notional_fee_bps,slippage_ticks`. It requires non-overlapping BUY and SELL ranges and coverage for both sides on every open session in the dataset. When supplied, schedule values override the static cost inputs for each fill date and side; the file hash is included in the run identity and manifest. Blank `effective_through` means no scheduled end date.
- Alternatively, `cost.components_file` selects a contract-kind-aware schedule with exact columns `effective_from,effective_through,contract_kind,side,brokerage_per_order_inr,brokerage_per_unit_inr,brokerage_bps,exchange_inr_per_crore,ipft_inr_per_crore,sebi_inr_per_crore,stt_bps,exercise_stt_bps,stamp_duty_bps,gst_bps,gst_on_brokerage,gst_on_exchange,gst_on_ipft,gst_on_sebi,gst_on_stt,gst_on_stamp_duty,slippage_ticks`. It requires non-overlapping ranges and coverage for both sides on every open session for the run's `FUTURE` or `OPTION`; premium STT is accepted only on SELL rows, exercise STT only on OPTION BUY rows, and stamp duty only on BUY rows. Values are per fill; percentage fees use fill traded value (future notional or option premium), while exchange/IPFT/SEBI charges use INR per crore. Each fee component is rounded HALF_UP to paise; GST uses the sum of rounded components selected by the `gst_on_*` flags, and its taxable base is recorded in `fee-breakdowns.csv`. For an ITM long-option expiry settlement, exercise STT applies to total intrinsic value and exercise SEBI turnover applies to the settlement row's source-supplied per-unit base. The resulting components and their bases are independently reconciled and recorded in `settlement-charges.csv`. The flags and GST base are caller inputs and are not legally validated. This schedule is mutually exclusive with `cost.schedule_file`, is hashed into run identity/manifest, and does not model other settlement charges.
- `settlement.cost_schedule_file`: optional additional source-attributed expiry charges, requiring `settlement.file`. Exact columns are `effective_from,effective_through,contract_kind,component_code,basis,rate,gst_bps,gst_on_charge,source_reference`. Ranges cannot overlap for the same contract kind and component code, and at least one row must cover the settlement date and contract. `FIXED_PER_SETTLEMENT` uses an INR amount once for a non-flat settlement; `PER_SETTLED_UNIT` uses INR per settled unit; `PER_EXERCISED_OPTION_UNIT` uses INR per ITM option unit; `BPS_OF_SETTLEMENT_NOTIONAL` uses basis points on settlement value times settled units; `BPS_OF_OPTION_INTRINSIC` uses basis points on intrinsic value times exercised units. `rate` is INR for the first three bases and basis points for the last two. If `gst_on_charge=true`, GST is calculated only on that rounded line charge using `gst_bps`; otherwise its taxable base is zero. Charge and GST amounts round HALF_UP to paise per line. Every line requires a logical `source_reference`; rate, basis, and GST treatment remain caller-supplied and are not legally or invoice-validated. The schedule hash is included in the run ID and manifest.
- `run.commit_id`: the source commit associated with the run. It defaults to `BUILD_COMMIT`, read from the JAR manifest. The build records a commit only from a clean Git checkout; dirty builds are marked `unknown-dirty`. A non-synthetic replay requires a full 40-character commit ID, so it fails closed with an unidentified/dirty build. The sample uses a synthetic fixture label.

## Fill, risk, and accounting behavior

Before artifacts are written, independent reconciliation passes check realized trade P&L, fill fees and slippage, every per-order state transition and event timestamp, per-session totals, and the last equity mark. A separate risk audit rechecks each BUY fill against configured lot, notional, and margin-proxy limits, requires one halt event at the actual loss-breach equity timestamp, reconciles halt events to the run counter, and rejects later same-session entries. Another audit matches every fill to its bar and recomputes adverse slippage and per-order, per-unit, and notional-bps charges from the configured or effective-dated schedule. Any mismatch fails the run.

The strategy reads a completed bar's close and may create a local market order. It can fill only at the next bar's open. BUY fills add the applicable configured/scheduled slippage ticks; SELL fills subtract them. Each fill fee includes the applicable per-order charge, per-unit charge, and notional basis-point component. A candle's high/low touching a price never creates a passive fill because passive orders are not supported.

The bar-based strategy runner is long-only and uses full fills. Scale-in targets are recorded as rejected while a position is open; this model supports one full entry and one full exit per trade. Lot-to-unit conversions use checked integer multiplication and fail closed if an input quantity exceeds the ledger's integer range. For options it treats the supplied premium bars as marks and fills; it does not calculate theoretical option values or Greeks. It checks maximum lots, notional, and the configured margin input before a new entry. Session P&L is marked at each close; when configured maximum daily loss is reached, the session is halted and an exit is scheduled for the next eligible bar. Cross-session pending fills are disabled unless `orders.allow_next_session=true`. The separate quote-order replay supports visible-size IOC partials and misses with per-intent latency, but it is not part of this bar engine. Neither models passive queue priority, exchange margin formula, or broker state. If an expiry settlement row is provided, a long position is cash-settled from that explicit input; option exercise STT and SEBI turnover are available only with the itemized cost schedule and required sourced basis. Additional settlement charges are calculated only from the optional caller-supplied schedule. A position may otherwise remain open after the last bar or across a session boundary.

## Run artifacts

- `run-manifest.json`: dataset and reference identifiers, input hashes, model/configuration, risk and cross-session position policy, output hashes, and the always-false performance-claim flag.
- `summary.json`: diagnostic counts and P&L accounting. It also reports completed-trade win/loss counts, completed-trade net P&L, win rate, average net P&L, net profit factor, average holding time, filled units, and filled turnover. Undefined trade statistics are `null`; open-position mark-to-market remains in the overall P&L fields. All remain diagnostic-only.
- `signals.csv`: one row per completed-bar strategy decision, including the indicator values or warm-up state.
- `sessions.csv`: per-session bar coverage, starting/ending position, realized/unrealized P&L change, fees, modeled slippage, order/fill counts, and risk halts. Net P&L change reconciles to realized plus unrealized change less fees; slippage is reflected in simulated fill prices and also reported as a diagnostic.
- `orders.csv`, `order-events.csv`: local simulated lifecycle; not broker observations.
- `fills.csv`: reference open, modeled fill, slippage, fees, and resulting position.
- `fee-breakdowns.csv`: per-fill contract kind, traded-value basis, legacy all-in amount or itemized brokerage, exchange, IPFT, SEBI, premium STT, stamp duty, GST taxable base, and GST amount.
- `settlement-charges.csv`: expiry-level cost model, settled units, intrinsic/STT basis, source-supplied SEBI turnover basis, exercise STT, GST, and total additional settlement fees.
- `settlement-cost-lines.csv`: itemized caller-supplied additional settlement charge lines with basis, rate, calculation base, source reference, GST base, and amount. No line is created unless the optional settlement cost schedule is supplied.
- `trades.csv`: completed long trades. Open positions are represented in the equity file and final position summary.
- `equity.csv`: per-bar marked realized/unrealized/net P&L and risk state.

Synthetic results are plumbing-only. Non-synthetic results remain diagnostic-only in this build. Use the tracker to see the gates that still need evidence before any performance assessment or forward-paper stage.
