# Multi-instrument portfolio replay engine

`PortfolioReplayEngine` connects exact-time frames from `SynchronizedBarFrameBuilder` to the standalone `PortfolioAccountingLedger`. It is an offline in-memory API for replay research; it is not a broker simulator or order route.

`ValidatedPortfolioDataset.load` accepts a map from stable instrument ID to a pair of normalized bar CSV and existing single-contract run-properties file. It calls `RunConfig.load` and `ValidatedDataset.load` separately for every contract, rejects any dataset whose actual dated instrument ID differs from its map key, and then requires exact synchronized timestamps through `SynchronizedBarFrameBuilder`. The existing validator checks each contract's dated master, session calendar, source metadata, market-rule regime, OHLC/tick values, and any configured optional inputs before the portfolio is assembled. The loader accepts BAR_START only. No bar is forward-filled or interpolated. The resulting canonical dataset fingerprint binds every validated bar/config/calendar/master/rule/input hash and caller-declared data-rights metadata; `PortfolioReplayEngine.run(ValidatedPortfolioDataset)` records it in the result and replay fingerprint.

## Timing and no-lookahead boundary

- Version 1 accepts only BAR_START frames. All frame timestamps and the supported interval must be explicit.
- A strategy receives an immutable prefix ending at the current completed bar, a decision timestamp at that bar's interval end, and the completed-bar account snapshot. It never receives future frames.
- The fill model is called only for a target change scheduled at the next frame. It receives an `OpeningMarketPoint` containing instrument, timestamp, session, contract kind, open price, tick, and lot size. It does not receive that frame's high, low, close, volume, or later quotes.
- A BAR_START frame is marked once at its open and once at its close. The next frame's open may share the previous frame's close timestamp; both marks are retained in order to expose the opening gap.
- If enabled, within-session continuity requires exactly one configured interval between frames. Synchronized missing timestamps still require separate source/completeness validation.

## Targets, fills, and boundaries

Every strategy decision must provide a signed target lot count for exactly every instrument. The caller supplies a maximum absolute lot limit per instrument. BUY/SELL direction and requested units are derived from the current ledger positions and dated lot size.

The caller-supplied fill model returns a full fill, a whole-lot partial fill, or an IOC miss. The engine validates fill quantity, dated tick alignment, and position caps. A partial fill cancels its residual; the next strategy decision may submit a new target. Fee and fill-price assumptions come only from this model.

Pending targets at a session transition must either be cancelled or explicitly allowed at the next session open. Position policy is also explicit: `ALLOW_OVERNIGHT` or `REQUIRE_FLAT`. `AUTO_FLATTEN_SESSION_END` is rejected until a separately reviewed closeout policy exists. `REQUIRE_FLAT` checks positions at each session and final-data boundary.

## Output and limits

Results include decisions, intents/outcomes, simulated executions, opening/closing portfolio snapshots, strategy/fill parameters, caller configuration, input/fingerprint hashes, and build commit. Classification is always `REPLAY_DIAGNOSTIC_ONLY`; `performanceClaimEligible()` is always false. `PortfolioReplayArtifactWriter` requires a validated-dataset fingerprint, atomically publishes `decisions.csv`, `orders.csv`, `executions.csv`, and `equity.csv` under the replay fingerprint, and refuses overwrites. `PortfolioReplayArtifactVerifier` checks the fixed file set, manifest IDs and schema, diagnostic-only status, and each output hash. The verifier proves consistency with the local manifest; it does not authenticate the manifest or independently retrieve/revalidate source inputs.

## CLI workflow

Create a UTF-8 input manifest with exactly these columns. File paths may be absolute or relative to the manifest directory.

```csv
instrument_id,bars_csv,run_properties,max_abs_lots
NIFTY-FUT,data/nifty-fut.csv,data/nifty-fut.properties,2
NIFTY-CALL,data/nifty-call.csv,data/nifty-call.properties,1
```

Create a UTF-8 portfolio settings file with exactly the following keys (comments and blank lines are allowed):

```properties
portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY
portfolio.position_boundary_policy=REQUIRE_FLAT
portfolio.require_contiguous_frames_within_session=true
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

The CLI strategy independently holds `long_lots` per contract when that instrument's fast close SMA is strictly above its slow close SMA; otherwise it targets flat. It waits for the complete slow window. Its fill model fully fills the target delta at the next bar open adjusted by configured adverse ticks and charges the configured fee per execution. These are plumbing assumptions, not exchange-calibrated fills or evidence of strategy edge. Set `position_boundary_policy=REQUIRE_FLAT` to require flat positions at every session and final-data boundary; `ALLOW_OVERNIGHT` carries them. Cross-session pending target behavior is explicit. The deterministic verifier requires the same source files, settings, and build identity; it hashes the saved artifact and byte-compares regenerated CSV outputs.

The replay does not calculate exchange costs/taxes/margin, settle expiry, enforce independent portfolio risk, or establish data rights/completeness beyond the existing caller-supplied validation checks. The artifact manifest is not authenticated; preserve its hash externally if a trusted anchor is needed. Strategies and fill models must be deterministic for repeated runs; their identifiers and parameters are recorded, but behavior cannot be proven deterministic by the engine. Long/short targets remain accounting/research inputs only and imply no trading authorization. No broker, network, live-feed, or order-submission capability is present.

Run focused checks with `.\test-portfolio-replay-engine.ps1` in PowerShell under JDK 21.
