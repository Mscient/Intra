# Isolated Lab Data and Run Schema

**Status:** Proposed contract; no ingestion code or dataset exists.
**Reviewed:** 2026-11-01 (Asia/Kolkata).
**Purpose:** Define the files and validation rules for the separate `Intra-Systems-Lab`. This specification keeps account connectivity and experiment execution outside HFT 2.

## Boundary

- A data collector or exporter, if later approved, runs outside the lab and uses only the user's local credential store. No SmartAPI SDK, account credential, order-routing code, production config, or broker-state file enters the lab.
- Stage only data that the user is entitled to store and replay. Keep restricted raw data outside Git and in a read-only input directory for runs.
- The lab runs offline after input staging. HFT 2 receives only a reviewed result summary and manifest hash.
- Never include client code, API keys, PIN/TOTP, registered IP address, session tokens, account IDs, or personal data in input files or manifests.

## Dataset files

### 1. `bars` — historical candles

One row per instrument and interval:

| Field | Meaning |
|---|---|
| `dataset_id` | Immutable ID of the staged dataset. |
| `instrument_id` | Stable lab identifier mapped through the dated instrument-master snapshot. |
| `exchange`, `segment`, `symbol_token`, `trading_symbol` | Source instrument identity; token/symbol meaning must be tied to the master snapshot. |
| `contract_type`, `underlying`, `expiry`, `strike`, `option_type` | Contract metadata; null where not applicable. |
| `interval` | Source interval such as `ONE_MINUTE`. |
| `source_timestamp_raw` | Timestamp string exactly as returned by the source. |
| `bar_timestamp_utc`, `bar_timestamp_ist` | Parsed timestamp in both canonical UTC and Asia/Kolkata forms. |
| `timestamp_role` | `bar_start`, `bar_end`, or `unknown`; do not infer until verified against a sample. |
| `open`, `high`, `low`, `close` | Source OHLC values without adjustment. |
| `volume`, `open_interest` | Source values; null if absent. Preserve zero as zero. |
| `source`, `source_endpoint`, `ingested_at_utc` | Source provenance and acquisition time. |
| `raw_record_hash` | Hash of the source row or source file used for this normalized record. |

Do not stitch NIFTY futures expiries into a continuous contract by default. If a later study constructs a continuous series, preserve each actual contract row and store the roll mapping and adjustment method as a separate, versioned transformation.

### 2. `session_scores` — derived Wave A research metric

One row per system and regular session. This derived table is produced only inside the isolated lab after the input bars pass validation.

| Field | Meaning |
|---|---|
| `run_id`, `dataset_id`, `system_id` | Run, immutable input, and fixed rule identity. |
| `session_date` | Exchange session date in Asia/Kolkata. |
| `eligible` / `exclusion_reason` | Whether required signal inputs and forward labels are complete; missing-data sessions are excluded, not scored as zero. |
| `signal_count` | Number of signals emitted during the eligible session. |
| `conditional_mean_aligned_log_return_15m` | Mean direction-aligned 15-minute index log return over that session's signals; null if no signals. |
| `session_score` | Equal-weight session contribution: the conditional mean when signals exist, zero for a complete eligible session with no signal, null when ineligible. |
| `rule_spec_hash` | Hash of the frozen signal specification used to derive the row. |

The primary estimate is the mean of eligible `session_score` values. Keep per-signal markouts for audit and descriptive summaries, but do not treat multiple same-session signals as independent observations.

### 3. `instrument_master` — dated contract reference

Store each acquired snapshot as an immutable file and record its retrieval date/time and hash. Normalize at least: exchange, segment, symbol token, trading symbol, underlying, instrument type, expiry, strike, option type, lot size, and tick size. Never replace an older snapshot with a newer one; each run must resolve tokens using the snapshot effective for its data.

### 4. `quotes` and `quote_depth` — prospective market-data capture

These files are optional and remain empty until a lawful, separate capture is available.

- `quotes`: dataset/instrument IDs, source mode, source exchange timestamp, source last-trade timestamp, local receive wall-clock UTC, local receive monotonic time, LTP, OHLC, last-trade quantity, cumulative volume, open interest, total buy/sell quantities, and nullable sequence number.
- `quote_depth`: parent quote ID, side (`buy`/`sell`), level, price, displayed quantity, and order count when supplied.
- Keep each received packet as an immutable raw record before normalization. Record reconnects, subscription failures, stale periods, and packet gaps in a separate capture log.
- Treat SmartAPI's documented packet-receive timestamp as unusable. The collector must record local receive timestamps. Index sequence numbers may be unavailable; do not create synthetic sequence values.
- Five-level snapshots are not a full order-by-order book. This schema does not imply that queue position or all cancellations can be reconstructed.

### 5. `sim_orders` and `sim_fills` — simulator output only

Use generated local IDs; do not reuse broker IDs.

- `sim_orders`: run ID, simulated order ID, parent ID, instrument ID, decision timestamp, side, requested quantity, order type, limit/stop prices if applicable, simulated submit time, expiration time, and rule/config version.
- `sim_fills`: run ID, simulated order ID, fill timestamp, fill quantity, fill price, modeled fee/slippage components, and fill-model version.
- A simulated order transition log may include created, acknowledged-by-simulator, partial, filled, cancelled, expired, or rejected states. These are lab events, not broker observations.
- The first Wave A index studies use no order/fill table and report markouts only.

## Run manifest

Every experiment gets a UTF-8 JSON manifest stored beside its outputs. Required keys:

```json
{
  "run_id": "unique-id",
  "system_id": "S01",
  "lab_commit": "full-git-hash",
  "rule_spec": "WAVE-A-SIGNAL-SPEC.md@hash",
  "dataset_id": "immutable-id",
  "input_file_hashes": {},
  "instrument_master_hash": "sha256",
  "session_calendar_hash": "sha256",
  "timezone": "Asia/Kolkata",
  "interval": "ONE_MINUTE",
  "date_range": {"start": "YYYY-MM-DD", "end": "YYYY-MM-DD"},
  "split_boundaries": {"development_end": "YYYY-MM-DD", "validation_end": "YYYY-MM-DD"},
  "primary_horizon_minutes": 15,
  "fill_model": "signal-markout-only",
  "statistical_plan": "WAVE-A-SIGNAL-SPEC.md@hash",
  "bootstrap_method": "stationary-session-bootstrap",
  "mean_block_length_sessions": 5,
  "bootstrap_replicates": 10000,
  "multiplicity_method": "Bonferroni",
  "family_size": 6,
  "familywise_alpha": 0.05,
  "cost_model_id": null,
  "parameters": {},
  "random_seed": null,
  "output_file_hashes": {},
  "created_at_utc": "ISO-8601 timestamp"
}
```

Do not place sensitive fields, raw data, or order credentials in the manifest. If this example changes, bump the schema version and record the version in every manifest.

## Validation gates before any run

1. **Provenance:** source, entitlement/terms, acquisition time, file hash, and contract-master hash are present.
2. **Timestamp:** preserve raw timestamp; verify timezone and bar-start/end semantics; require monotonic ordering within each instrument. No assumed timestamp role.
3. **Bar integrity:** require `high >= max(open, close)`, `low <= min(open, close)`, `high >= low`; report duplicate/missing bars. Do not silently deduplicate, forward-fill, or drop anomalies.
4. **Session coverage:** compare rows with the dated session calendar; report partial sessions and special sessions before deciding whether to exclude them.
5. **Instrument mapping:** ensure every row maps to exactly one dated master record. Keep expiries separate and validate strike/tick/lot metadata as-of date.
6. **Volume and OI:** distinguish absent/null from a numeric zero. NIFTY index volume zero is expected and must not be treated as traded volume.
7. **Split integrity:** split by session date, not shuffled rows. Keep forward-label windows within sessions and hold the final time block sealed.
8. **Statistical plan:** record the split dates, session-score definition, bootstrap seed and block-length sensitivity, six-test correction family, and development-only precision/power summary before opening validation or holdout outcomes.
9. **Isolation:** after staging read-only inputs, disable network access; verify no broker client, credential, app import, or external routing path exists in the experiment environment.

This schema is a planning document only. It does not create a lab repository, ingest data, validate a broker payload, or authorize a test run.
