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

The validated loader consumes a caller-supplied map of per-contract files, but no command-line portfolio configuration or full rerun verifier is available yet. The replay does not calculate exchange costs/taxes/margin, settle expiry, enforce independent portfolio risk, or establish data rights/completeness beyond the existing caller-supplied validation checks. Strategies and fill models must be deterministic for a repeated run; their identifiers and parameters are recorded, but their behavior cannot be proven deterministic by the engine. Long/short targets are accounting/research inputs only and imply no trading authorization. No broker, network, live-feed, or order-submission capability is present.

Run focused checks with `.\test-portfolio-replay-engine.ps1` in PowerShell under JDK 21.
