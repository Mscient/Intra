# Offline daily operations evidence checker

`DailyOpsEvidenceCli` checks a caller-prepared, version-1 session evidence bundle. It reports **PASS**, **FAIL**, or **INCOMPLETE** for seven domains and for the bundle as a whole. It uses local files only. It never starts a gateway or replay, connects to a broker/feed, or performs an operator action.

Every report and every domain result says **caller-supplied diagnostic evidence**, records the SHA-256 of the exact bytes consumed, and sets `g4_closed=false` and `operational_readiness_eligible=false`. These classifications also apply to malformed and incomplete evidence. Hashes detect changes relative to the caller's manifest; they do not authenticate its author or establish source truth.

## Commands

From `Intra-Paper-System/` in PowerShell with `JAVA_HOME` pointing to JDK 21:

```powershell
$env:INTRA_PAPER_BUILD_DIR = Join-Path $PWD 'build\daily-ops-evidence'
.\build.ps1
$jar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $java -cp $jar com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceCli check `
  .\examples\daily-ops-evidence\clean-completion\session-evidence.json `
  .\output\daily-ops-evidence\clean-completion.json

# Wrapper for the same separate entrypoint; also accepts caller-prepared bundles.
.\daily-ops-evidence.ps1 -JarPath $jar check .\my-bundle\session-evidence.json .\output\my-report.json

# Rehearse all nine bundled synthetic cases and check their expected outcomes.
.\synthetic-daily-ops-drill.ps1 -JarPath $jar -OutputRoot .\output\synthetic-daily-ops-drill

# Focused source tests; optionally run existing relevant suites and packaged checks.
.\test-daily-ops-evidence.ps1
.\test-daily-ops-evidence.ps1 -RunRelevantChecks
.\test-daily-ops-evidence.ps1 -JarPath $jar
```

The checker exits `0` for PASS, `1` for FAIL, `2` for INCOMPLETE, `64` for incorrect arguments, and `74` for report I/O/path errors. Without a report argument it writes canonical JSON to stdout. With an argument it publishes the same UTF-8/LF bytes through a sibling temporary file and replacement move. Reports must be outside the bundle directory so evidence cannot be overwritten. Existing report files may be replaced. The drill exits zero when every fixture gives its **expected** outcome, including expected failures and incomplete evidence.

## Status and unknowns

- **PASS:** all requirements for this domain are explicitly supplied and internally consistent under this schema.
- **FAIL:** supplied evidence contradicts a requirement: for example, stale input permitted new risk, an execution lacks a reservation, a position remains open, or artifact bytes changed.
- **INCOMPLETE:** evidence is absent, null, blank, unreadable, declared partial, or contains fewer records than its explicit expected count. Missing values remain unknown. An empty list establishes neither healthy operation nor absence of activity.

FAIL takes precedence over INCOMPLETE, which takes precedence over PASS. A contradiction can therefore fail a bundle that also has missing evidence; each finding retains its own status. Invalid types, duplicate JSON keys, malformed UTF-8, unsupported schema values and invalid timestamps fail. A missing bundle or `{}` leaves all seven domains INCOMPLETE. Missing file hashes are JSON `null`, never an empty-file hash or an invented zero.

Zero activity needs independent, explicit fields in the supplied bundle: complete/count declarations, reviewed timestamps, zero starting/ending snapshots, `no_activity_reason` for an empty risk ledger, and `no_execution_reason` for an empty execution ledger. Empty incident/stop registers also need their reviewed timestamps, reviewer, zero counts, explicit `no_incidents_confirmed` / `no_stops_confirmed`, and an explicit zero severe-incident count. Feed windows and observed input records must be nonempty even when the caller declares a zero count.

## Caller bundle contract

Start from the fully populated [clean synthetic bundle](../examples/daily-ops-evidence/clean-completion/session-evidence.json). All evidence is normalized caller data in one JSON object plus declared artifacts; this is **not** a drop-in parser for existing quote journals, gateway journals, broker statements or portfolio replay directories. Preparing this normalized bundle does not verify those underlying sources.

The root requires `schema_version=1`, `classification` (`SYNTHETIC` or `CALLER_SUPPLIED_DIAGNOSTIC`), nonblank `session_id` / `run_id`, `outcome` (`COMPLETED` or `HALTED`), and `session_start_utc` / `session_end_utc`. End must follow start. A halted bundle uses its actual evidence cutoff, not a fabricated full scheduled session. `instruments` is a nonempty array of unique `instrument_id`, positive decimal `multiplier`, and explicit boolean `settlement_due`.

Timestamps require seconds and a literal UTC `Z`; up to nine fractional digits are accepted. Time zones, current time, file timestamps, or missing times are not substituted. Units are explicit 64-bit integers; fills/reservations/cancels use positive `quantity`, settlements use nonzero `signed_quantity`. Monetary inputs are JSON decimals with at most 30 significant digits and scale from -9 through 9. Reconciliation uses exact decimal arithmetic and no inferred tolerance or fee rate.

Each section requires `complete=true`; `false` means evidence remains partial. Sections require `checked_at_utc`: pre-session review must precede start, other reviews must be at/after end. `run` instead requires `completed_at_utc`. Arrays require the count fields shown below. Removing either a count or a completeness declaration makes the related evidence unknown.

| Domain | Required records and comparisons |
|---|---|
| Pre-session | `pre_session.checks`, `expected_check_count`. Exactly the six named checks `CLOCK`, `RUN_CONFIG`, `INPUT_PLAN`, `RISK_LIMITS`, `STARTING_RECONCILIATION`, `STOP_PATH`; each has `passed`, `checked_at_utc`, `operator_id`, and a declared `evidence_path`. All checks and review precede the readiness acknowledgement and session start. |
| Inputs/feed | `coverage.windows`, `expected_window_count`. Each unique window declares instrument/source, UTC start/end, `acknowledged_at_utc`, explicit `max_age_ms` / `max_silence_ms`, `complete`, `expected_observation_count`, and nonempty `observations`. Each observation has event/instrument/source IDs, `input_at_utc`, `observed_at_utc`, `new_risk_allowed`, and `action` (`ALLOW`, `PAUSE`, `HALT`). Input artifact timestamps must match. Declared windows cover the session for each instrument/source; receive-time boundary/interior gaps must respect the supplied threshold. Future inputs fail. Stale data must block new risk and identify matching incident and stop records. |
| Gateway | `risk` binds session/run IDs and the configuration artifact SHA-256. `initial` / `final` contain timestamp, `state`, `reserved_units`, `working_order_count`, and an explicit `positions` entry for every declared instrument. Initial positions/orders/reservations must be zero. `events` plus `expected_event_count` reconstruct `RESERVE`, `FILL`, `CANCEL`, `SETTLE`, `HALT` in timestamp order. Reserve/fill/cancel records carry order/instrument/side/quantity. Reserve records reference `coverage_window_id` / `coverage_event_id`; the input must already be available and fresh at reservation time. Fills carry `execution_id`; settlement events carry `settlement_event_id` / signed quantity; halts carry `stop_id`. Partial fills and remainder cancellations release exact reservations. Final working orders/reservations must be zero; state and positions must match reconstructed evidence. New reservations and risk-increasing fills after halt fail. |
| Identity/integrity | `run` binds session/run/replay IDs, declares `expected_artifact_count`, `artifacts`, and nonempty `replay_pairs`. Artifacts have role, portable relative `path`, and lowercase 64-hex `sha256`. Required roles: INPUT, exactly one CONFIG, OUTPUT, REPLAY_OUTPUT. Each OUTPUT has exactly one separate REPLAY_OUTPUT pair, and every replay output is paired. Checker compares their actual bytes, not a caller's passed flag. Output JSON identities/timestamps must agree with the bundle. |
| Settlement/costs | `settlement.applicable` must agree with every instrument's explicit `settlement_due`. False requires `not_applicable_reason` and explicit zero `expected_event_count` / empty events; true requires settlement events for each due instrument. Events include ID, instrument, timestamp, signed quantity, price, signed `cash_amount`, `fee`, and `source_ref`. Cash equals signed quantity × price × multiplier. `settlement.costs` declares completeness/review, nonempty `required_components`, `expected_charge_count`, and itemized `charges` for every execution and settlement. Charge fields: unique ID, `target_kind` (`EXECUTION` / `SETTLEMENT`), target ID, component, amount, source reference, rounding description, and effective-from/to UTC dates. Date intervals are half-open. Every target/component needs an explicit line, including an explicit zero line for a zero charge. Sums must match event fees. Source/rate correctness and completeness of the caller's component list remain unverified. |
| Closing | `closing.executions`, `expected_execution_count`, explicit `positions` for the full universe, `initial_cash`, `gross_pnl`, `fees`, `net_pnl`, `ending_cash`. Executions carry execution/order/instrument IDs, UTC timestamp, side, quantity, price, fee. Starting positions are explicitly flat; BUY adds units and consumes price × multiplier × units, SELL does the reverse. Signed settlement closes units and contributes supplied/reconciled cash. End positions must be flat and agree with gateway and output snapshots. For flat start/end, signed cashflows equal gross P&L, event fees equal total fees, net equals gross minus fees, and ending cash equals initial cash plus net. |
| Operator/incident/stop | `operator.acknowledgements`, `expected_acknowledgement_count`: `PRE_SESSION_READY`, `SESSION_CLOSE`, `RECONCILIATION_REVIEW`, `INCIDENT_REVIEW`, plus `HALT_AUTHORITY` for HALTED. Each has timestamp, operator, and evidence reference. Closing/reconciliation acknowledgements follow reviews and run completion. Incident/stop counts, registers, reviewed timestamps, reviewer, zero-register confirmations and severe-open count are mandatory. Incidents have severity/state, opened/acknowledged timestamps, acknowledgement operator and reason; closed incidents also have resolved timestamp and resolution reference. Stops have kind, incident reference, requested/effective/acknowledged timestamps and operators, reason, and explicit successful `new_risk_blocked`, `orders_reconciled`, `positions_reconciled`. Halt timestamps bind to risk halt events. Severe open incidents fail. |

INPUT artifacts are JSON objects with `complete`, `expected_observation_count`, and nonempty normalized `observations` containing event/instrument/source IDs and input/observed UTC timestamps. Every declared coverage observation matches an input record, and extra supplied input records outside coverage fail when both sides are explicitly complete. OUTPUT and REPLAY_OUTPUT artifacts are JSON summaries with session/run IDs, completed UTC timestamp, positions, gross P&L, fees, net P&L, and ending cash. CONFIG is a hashed local file; this checker does not execute or independently interpret its risk rules.

Artifact files are read once and hashed from the same byte snapshot used for parsing/comparison. Paths cannot be absolute, contain traversal or backslashes, alias the bundle, use symbolic links, or resolve outside the bundle directory. There are at most 256 artifact declarations and 16 MiB per file. Only declared artifacts are consumed; unrelated files are not proof of a completed check. Caller-array order is preserved, object keys are sorted, and reports contain no checker clock, random ID, absolute location, or file metadata. Identical bytes and paths within a relocated bundle produce byte-identical reports. Fixture JSON is pinned to LF using local `.gitattributes` so Git checkout does not change its recorded hashes.

## Synthetic rehearsal versus an actual operational drill

The [fixture matrix](../examples/daily-ops-evidence/README.md) rehearses evidence preparation and checker outcomes. The halted fixture can PASS because it explicitly records a flat, reconciled, acknowledged halt. That does not count as a complete scheduled trading session. Replay comparison checks two supplied local outputs; it does not rerun a strategy or establish that an independent replay produced the copy. Gateway/stop/operator records describe caller observations; the checker does not operate controls or observe their completion independently.

An actual operational drill exercises controls and operator response in the intended environment, with measured clocks, authorized inputs, observed subscription/freshness behavior, independent account/order/position/fee reconciliation, demonstrated stop paths, and reviewed incident evidence. The project’s G4 gate still requires 60 complete forward-paper sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful actual drills, and no open severe incident. This synthetic diagnostic supplies none of those operational sessions or independent attestations and **does not close G4**. It authorizes no live route.

The relevant-check option runs the existing gateway durability/process, K0–K3, instrument sequence/feed coverage, portfolio risk replay (including replay/cost/artifact/settlement checks), accounting, risk audit, settlement audit and trial evaluation runners, then builds the JAR, repeats the evidence checks against the package, and runs the synthetic matrix. Generated evidence and logs stay in ignored build/output directories. This is local Java 21 validation, not hosted CI evidence.
