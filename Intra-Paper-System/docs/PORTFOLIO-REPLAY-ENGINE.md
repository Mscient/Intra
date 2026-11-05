# Multi-instrument portfolio replay engine

`PortfolioReplayEngine` connects exact-time frames from `SynchronizedBarFrameBuilder` to the standalone `PortfolioAccountingLedger`. It is an offline in-memory API for replay research; it is not a broker simulator or order route.

`ValidatedPortfolioDataset.load` accepts a map from stable instrument ID to a pair of normalized bar CSV and existing single-contract run-properties file. It calls `RunConfig.load` and the shared `ValidatedDataset` checks in a dedicated portfolio settlement context for every contract, rejects any dataset whose actual dated instrument ID differs from its map key, and then requires exact synchronized timestamps through `SynchronizedBarFrameBuilder`. The existing validator checks each contract's dated master, session calendar, source metadata, market-rule regime, OHLC/tick values, and any configured optional inputs before the portfolio is assembled. The loader accepts BAR_START only. No bar is forward-filled or interpolated. The resulting canonical dataset fingerprint binds every validated bar/config/calendar/master/rule/input hash and caller-declared data-rights metadata; `PortfolioReplayEngine.run(ValidatedPortfolioDataset)` records it in the result and replay fingerprint.

## Timing and no-lookahead boundary

- Version 1 accepts only BAR_START frames. All frame timestamps and the supported interval must be explicit.
- A strategy receives an immutable prefix ending at the current completed bar, a decision timestamp at that bar's interval end, and the completed-bar account snapshot. It never receives future frames.
- The fill model is called only for a target change scheduled at the next frame. It receives an `OpeningMarketPoint` containing instrument, timestamp, session, contract kind, open price, tick, and lot size. It does not receive that frame's high, low, close, volume, or later quotes.
- A BAR_START frame is marked once at its open and once at its close. The next frame's open may share the previous frame's close timestamp; both marks are retained in order to expose the opening gap.
- If enabled, within-session continuity requires exactly one configured interval between frames. Synchronized missing timestamps still require separate source/completeness validation.

## Targets, fills, and boundaries

Every strategy decision must provide a signed target lot count for exactly every instrument. The caller supplies a maximum absolute lot limit per instrument. BUY/SELL direction and requested units are derived from the current ledger positions and dated lot size.

The caller-supplied fill model returns a full fill, a whole-lot partial fill, or an IOC miss. The engine validates fill quantity, dated tick alignment, and position caps. A partial fill cancels its residual; the next strategy decision may submit a new target. Fill-price assumptions come from this model. Fees come either from the fill model or from the optional dated execution-cost schedule described below, never both.

Pending targets at a session transition must either be cancelled or explicitly allowed at the next session open. Position policy is also explicit: `ALLOW_OVERNIGHT` or `REQUIRE_FLAT`. `AUTO_FLATTEN_SESSION_END` is rejected until a separately reviewed closeout policy exists. `REQUIRE_FLAT` checks positions at each session and final-data boundary; the final check follows any explicit settlement batch.

## Output and limits

Results include decisions, intents/outcomes, simulated executions, opening/closing/settlement snapshots, settlement and charge records, configuration, input hashes, and build commit. Classification is always `REPLAY_DIAGNOSTIC_ONLY`; `performanceClaimEligible()` is false. The v3 writer atomically publishes `datasets.csv`, `decisions.csv`, `orders.csv`, `executions.csv`, `execution-fees.csv`, `equity.csv`, `settlements.csv`, and `settlement-cost-lines.csv` under the replay fingerprint and refuses overwrites. The verifier checks the fixed layout, manifest identity, counts, diagnostic flags, hashes, and fee provenance, independently reconstructs execution basis, and reconciles settlement/charge arithmetic and equity. Source truth and manifest authentication remain external. See [settlement inputs, outputs, and compatibility](PORTFOLIO-SETTLEMENT.md).

## Effective-dated execution costs

Omit `portfolio.execution_cost_schedule_csv` or set it to `NONE` to retain the legacy caller-supplied fill-model fee. Otherwise set it to a UTF-8 CSV path, absolute or relative to `portfolio.properties`. When a schedule is enabled, `fill.fee_per_execution_inr` must be zero. The engine also rejects any custom fill model that returns a nonzero fee while a schedule is active, preventing accidental double counting.

The schedule headers are exact and in this order:

```csv
schedule_id,entry_id,effective_from,effective_until,instrument_id,contract_kind,side,brokerage_per_order_inr,brokerage_per_unit_inr,brokerage_bps,exchange_inr_per_crore,ipft_inr_per_crore,sebi_inr_per_crore,stt_bps,stamp_duty_bps,gst_bps,gst_on_brokerage,gst_on_exchange,gst_on_ipft,gst_on_sebi,gst_on_stt,gst_on_stamp_duty,source_reference
portfolio-tariff-v1,nifty-fut-buy-2026,2026-01-01,2027-01-01,NIFTY-FUT,FUTURE,BUY,0,0,0,0,0,0,0,0,0,false,false,false,false,false,false,caller-controlled-tariff-document-reference
```

- `schedule_id` must be identical on every row. `entry_id` must be unique in the file. IDs and `instrument_id` use stable ASCII identifier syntax.
- `effective_from` is inclusive and `effective_until` is exclusive: `[effective_from,effective_until)`. A blank `effective_until` means unbounded future coverage. Until must be strictly after from.
- Resolution is exact by execution session date, stable instrument ID, dated bar `contract_kind`, and side. Wildcards and fallback rows are not supported. Missing coverage fails the replay. Overlapping ranges for the same instrument/kind/side and duplicate entry IDs fail loading.
- `contract_kind` is `FUTURE` or `OPTION`; side is `BUY` or `SELL`. Consistent with the established single-contract fee semantics, execution `stt_bps` must be zero on BUY rows and `stamp_duty_bps` must be zero on SELL rows. Exercise STT is not part of this contract.
- Every rate is caller supplied and must be non-negative. The application does not contain current brokerage, exchange, IPFT, SEBI, STT, stamp-duty, or GST rates. `source_reference` is required on every row and should identify the controlled tariff, circular, statute, or internal assumption used by the caller.
- Each `gst_on_*` field is exactly `true` or `false`, explicitly selecting which rounded charge components enter the GST base.

For fill price `P` and quantity `Q`, traded value is `P × Q`. Calculations use `BigDecimal`; no binary floating point is used. Brokerage is `brokerage_per_order_inr + brokerage_per_unit_inr × Q + traded_value × brokerage_bps / 10000`. Exchange, IPFT and SEBI charges are `traded_value × *_inr_per_crore / 10000000`. STT and stamp duty are `traded_value × *_bps / 10000`. Division is evaluated to 12 decimal places with `HALF_UP`; each charge component is then rounded independently to INR 0.01 with `HALF_UP`. The GST base is the sum of the explicitly selected, already rounded components and is recorded at INR 0.01. GST is calculated from that base and rounded to INR 0.01 with `HALF_UP`. The execution total is the sum of brokerage, exchange, IPFT, SEBI, STT, stamp duty, and GST; the GST base itself is not charged again.

Rows are sorted canonically before validation and resolution, so row ordering cannot change the selected entry or calculated amount. The manifest and replay fingerprint also bind the exact source-file SHA-256 and schedule ID. Reordering the physical CSV therefore intentionally changes provenance and the replay ID even though the resolved fees remain the same.

## CLI workflow

Create a UTF-8 input manifest with exactly these columns. File paths may be absolute or relative to the manifest directory.

```csv
instrument_id,bars_csv,run_properties,max_abs_lots
NIFTY-FUT,data/nifty-fut.csv,data/nifty-fut.properties,2
NIFTY-CALL,data/nifty-call.csv,data/nifty-call.properties,1
```

Create a UTF-8 portfolio settings file with the following required keys and optional execution-cost and settlement keys (comments and blank lines are allowed):

```properties
portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY
portfolio.position_boundary_policy=REQUIRE_FLAT
portfolio.require_contiguous_frames_within_session=true
portfolio.execution_cost_schedule_csv=NONE
strategy.fast_bars=5
strategy.slow_bars=20
strategy.long_lots=1
fill.slippage_ticks=1
fill.fee_per_execution_inr=20
```

Run and verify using the project JAR:

```powershell
java -jar $jar portfolio-run .\portfolio-inputs.csv .\portfolio.properties .\portfolio-runs
java -jar $jar portfolio-replay-verify .\portfolio-inputs.csv .\portfolio.properties .\portfolio-runs\<run-id>
```

The CLI strategy independently holds `long_lots` per contract when that instrument's fast close SMA is strictly above its slow close SMA; otherwise it targets flat. It waits for the complete slow window. Its fill model fully fills the target delta at the next bar open adjusted by configured adverse ticks. It charges either the configured legacy fee per execution or the resolved dated schedule, as selected above. These are plumbing assumptions, not exchange-calibrated fills or evidence of strategy edge. Set `position_boundary_policy=REQUIRE_FLAT` to require flat positions at every session and final-data boundary; `ALLOW_OVERNIGHT` carries them. Cross-session pending target behavior is explicit. The deterministic verifier requires the same source files, settings, schedule bytes, and build identity; it hashes the saved artifact and byte-compares regenerated CSV outputs.

Each per-contract run-properties file must still satisfy the existing `RunConfig` schema, including its `strategy.*` values. The portfolio CLI uses the separate `portfolio.properties` values for its portfolio-wide SMA and fill assumptions; the per-contract strategy values are validated and their config file hashes remain in provenance, but they do not select the portfolio strategy.

The replay calculates only the configured execution components; it does not assert that the schedule is legally complete, current, venue-correct, or account-correct. Final-session expiry settlement and option exercise charges use the separate [caller-sourced event/cost contracts](PORTFOLIO-SETTLEMENT.md). Margin, independent portfolio risk, account reconciliation, and source data-rights/completeness remain outside this feature. The artifact manifest is not authenticated; preserve its hash externally if a trusted anchor is needed. Strategies and fill models must be deterministic for repeated runs; their identifiers and parameters are recorded, but behavior cannot be proven deterministic by the engine. Long/short targets remain accounting/research inputs only and imply no trading authorization. No broker, network, live-feed, or order-submission capability is present.

Run focused checks with `.\test-portfolio-replay-engine.ps1` in PowerShell under JDK 21.
