# Offline external-ledger reconciliation

`ExternalLedgerAuditCli` is a standalone Java 21 audit of a completed replay against caller-supplied normalized orders, fills, itemized charges and fee totals, position checkpoints, cash flows and settlement records. It requires full artifact verification and deterministic reconstruction from the original replay inputs. `SINGLE` uses the single-contract SMA replay verifier; `PORTFOLIO` uses the portfolio v3 verifier and supports itemized charges. Preserve the replay build and exact audit JAR: build identity and every reconstructed output must agree. Unverified financial values are never used as the baseline.

The result is diagnostic evidence of agreement under the declared normalization. Source authenticity, broker execution, account completeness, exchange rules, account balances and G4 readiness are not established. The audit has no broker adapter, account credentials, network calls or order route. Bundled inputs and all acceptance tests are synthetic. Replay outputs are never rewritten.

## Run and verify

```powershell
# Set JAVA_HOME to an installed JDK 21.
.\build-external-ledger.ps1
$jar = '<full external-ledger-audit.jar path printed by the build>'

.\external-ledger-audit.ps1 -JarPath $jar audit PORTFOLIO <replay-folder> <portfolio-inputs.csv> <portfolio.properties> <external.properties> <audit-output-root>
.\external-ledger-audit.ps1 -JarPath $jar audit SINGLE <replay-folder> <bars.csv> <run.properties> <external.properties> <audit-output-root>
.\external-ledger-audit.ps1 -JarPath $jar verify <audit-folder> PORTFOLIO <replay-folder> <portfolio-inputs.csv> <portfolio.properties> <external.properties>
.\external-ledger-audit.ps1 -JarPath $jar verify-artifact <audit-folder>

.\test-external-ledger-reconciliation.ps1
.\test-external-ledger-reconciliation.ps1 -JarPath $jar -ReplayRegressions
.\synthetic-external-ledger-drill.ps1 -JarPath $jar
```

Direct invocation is `java -cp <jar> com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAuditCli` followed by the same arguments. `audit` publishes a report and exits **0 / PASS**, **2 / FAIL**, or **3 / INCOMPLETE**. `verify` reproduces its manifest and every report byte, with the same status exits. Invalid arguments, publication errors and report verification failures exit **1**. `verify-artifact` exits 0 for valid content hashes/layout, including a correctly hashed FAIL or INCOMPLETE report; it does not reconstruct the replay or authenticate its conclusions.

The standalone build also supports `java -jar <external-ledger-audit.jar> ...`. It uses a compiler argument file for Windows command-length limits, retains its classes in a new local build directory and does not replace the existing replay runner. To create a compatible synthetic replay with that same JAR, use the drill below or `java -cp <jar> com.mscient.intrapaper.cli.IntraPaperCli portfolio-run ...`. An existing JAR from `build.ps1` also works through the classpath command if it contains this package.

The [fixed fixture](../examples/external-ledger-reconciliation/README.md) is independently authored for `examples/portfolio-settlement`. The drill builds that replay with the supplied JAR, binds its exact run ID and local input hashes, then runs all three audit modes. It accesses only the synthetic fixtures. `-ReplayRegressions` additionally runs the existing portfolio accounting, engine, execution-cost, artifact-verifier, settlement and settlement-audit suites. Acceptance and regression logs remain under the printed evidence directory.

## Caller manifest

The UTF-8 manifest has exactly these keys, one `key=value` per line, with no comments, blank lines, duplicate keys or substitutions during audit execution:

```properties
schema_version=1
classification=CALLER_SUPPLIED_NORMALIZED
replay_kind=PORTFOLIO
replay_run_id=<exact verified replay ID>
comparison=EXACT
matching=DECLARED_IDS
tolerances=NONE
policy_source_reference=<caller policy document/version and rationale>
cash_basis=SIGNED_NOTIONAL_EXCLUDING_FEES
fee_basis=ITEMIZED_AND_TOTAL
currency=INR
ledger_file=ledger.csv
ledger_sha256=<lowercase SHA-256 of exact ledger bytes>
coverage_file=coverage.csv
coverage_sha256=<lowercase SHA-256 of exact coverage bytes>
source_id=<caller logical source ID>
source_reference=<caller attribution>
```

`classification` is `SYNTHETIC` or `CALLER_SUPPLIED_NORMALIZED`; both are always labeled `external_records=CALLER_SUPPLIED` in the report. The comparison, matching, tolerance, fee, cash and currency policies and `policy_source_reference` are mandatory. Only exact comparison with `tolerances=NONE` is implemented; other tolerance declarations are rejected. No FX rate, multiplier, fee rate, settlement lag, timestamp skew allowance or missing value is defaulted. Decimal values compare numerically, so `1.00` and `1` agree without rounding. Policy provenance is retained in the summary and bound by the manifest input hash. Source IDs/references are caller assertions and never fetched or authenticated.

File names are portable paths relative to the manifest directory. Absolute paths, backslashes, traversal, self-reference and symlinks are rejected. Files are bounded to 16 MiB, parsed and hashed from the same byte snapshot, and checked again for byte changes before publication. A mismatched declared hash produces a hashed FAIL report and that file is not consumed. An input changing during the audit aborts publication.

## Normalized ledger

Exact ordered header:

```csv
record_id,record_type,entity_id,action,replaces_record_id,recorded_at_utc,event_sequence,event_time_utc,replay_record_id,instrument_id,order_id,related_type,related_id,side,status,quantity_units,filled_units,price_inr,amount_inr,component,checkpoint,source_reference
```

Each event requires a unique `record_id`, `record_type`, stable `entity_id`, action, recorded timestamp and globally unique positive source sequence. IDs use `[A-Za-z0-9][A-Za-z0-9._:-]{0,255}`. Entity IDs are unique within a record type. `source_reference` is required for complete evidence; a blank reference produces INCOMPLETE. Instrument IDs identify the replay's full dated contracts. Convert source lots, multipliers, price units and monetary signs into this schema explicitly before auditing.

Timestamps require ISO UTC seconds and a literal `Z`, with up to nine fractional digits. `recorded_at_utc` describes receipt/history ordering; `event_time_utc` describes the economic event or checkpoint and must not follow recorded time. Every effective economic record must lie within the verified replay interval, inclusive. No current clock or file timestamp is substituted.

`NEW` starts an entity and has a blank replacement reference. `CORRECT` supplies a **complete replacement payload**, with the same record type/entity and a reference to the immediately preceding event's `record_id`. Blank replacement amounts are unknown, not inherited. `REVERSE` references that event and leaves every economic/matching field blank, from `event_time_utc` through `checkpoint`. It removes the entity from effective records. A reversal is terminal; subsequent corrections require a distinct entity. Updates must follow their targets in source sequence and recorded time. Full chains resolve through explicit IDs even when physical CSV rows arrive out of order.

Repeated record IDs, repeated NEW entity IDs, duplicate sequences, branched corrections, cycles, cross-entity replacements and updates after reversal fail. Missing referenced history is INCOMPLETE. Corrections and reversals are recorded as INFO exceptions when successfully applied. Source-row and economic-time inversions are reported as INFO and do not prevent agreement after unambiguous normalization. `events.csv` retains original rows, their physical line numbers and ACTIVE / SUPERSEDED / REVERSED / INVALID dispositions.

Only the following economic fields are applicable to each type. Other payload cells must be blank. Applicable values may be blank when unknown; their comparisons become INCOMPLETE.

| Record type | Applicable economic fields |
|---|---|
| ORDER | Economic time is the replay order's decision timestamp; instrument, side, final `status`, requested `quantity_units`, cumulative `filled_units`. Status is an uppercase token compared verbatim, including `FILLED`, `PARTIAL`, `CANCELED`, `CANCELLED`, `MISSED`, `REJECTED_RISK` and `EXPIRED_NO_NEXT_BAR` where emitted by that replay. |
| FILL | Economic execution time; instrument, BUY/SELL, `order_id` referencing the external ORDER entity, positive units, positive INR price per replay unit. Fees are separate. |
| FEE | Target economic time; instrument, `related_type=FILL` or `SETTLEMENT`, `related_id` referencing that external entity, a declared charge `component`, non-negative `amount_inr`. Each component and the explicit `TOTAL` are separate entities. See the itemization contract below. |
| POSITION | Replay opening/final checkpoint time; instrument, `checkpoint=OPENING` or `CLOSING`, signed units. Every instrument needs both records, including explicit zero positions. The verified replay starts flat; no external opening position is assumed. |
| CASH | Target economic time; instrument, target type/entity, component and signed amount. Components are `TRADE_NOTIONAL`, `FEE`, `SETTLEMENT_PROCEEDS`, `SETTLEMENT_FEE`. |
| SETTLEMENT | Actual integrated settlement time; instrument, signed units extinguished, INR value per unit, signed proceeds and `component=CASH_FUTURES_FINAL_PRICE` or `CASH_OPTION_PAYOFF`. Explicit zero option payoff is supported. |

Units are signed 64-bit integers; fills/orders require positive quantities and cumulative filled units are non-negative. Prices/amounts use plain decimals with at most 12 fractional digits and absolute magnitude below 10^30. Exponents, NaN, infinities, malformed UTF-8, BOMs, blank CSV rows, multiline cells, untrimmed/control-containing cells, extra columns and malformed quoting are rejected. LF and CRLF inputs are accepted and hashed distinctly.

## IDs and fallback keys

With `matching=DECLARED_IDS`, every effective row requires `replay_record_id`. A blank ID is INCOMPLETE and never triggers fallback. `matching=UNIQUE_COMPOSITE_KEYS` explicitly permits the exact composite keys in the table below when the replay ID is blank. A supplied ID that does not exist fails and is never rescued by a fallback. An external `entity_id` need not equal a replay ID; related external entities are resolved through their own unique replay matches first. Several entities claiming one replay record fail even if amounts or aggregate quantities agree.

| Type | Explicit replay record ID | Fallback when the ID is blank |
|---|---|---|
| ORDER | Single `order_id` or portfolio `intent_id` | Instrument + exact decision time + side |
| FILL | Single `fill_id` or portfolio `execution_id` | Matched parent replay order + instrument + exact execution time + side |
| FEE | `<target-replay-id>:<component>` | Related type + matched target replay ID + component |
| CASH | `<target-replay-id>:<component>` | Related type + matched target replay ID + component |
| POSITION | `OPENING:<instrument>` / `CLOSING:<instrument>` | Instrument + exact checkpoint time + checkpoint |
| SETTLEMENT | Portfolio event ID; single `SETTLEMENT:<instrument>:<settlement-date>` | Instrument + exact settlement time + settlement component |

Fallbacks require a unique candidate and all key fields. Missing key fields are INCOMPLETE; ambiguous candidates fail. Prices and quantities are compared after matching, so an amount mismatch cannot disappear from a fallback key. No nearest-event, nearest-price, fuzzy-text or arrival-order matching is performed.

Fill granularity must correspond to the replay's execution records. Several distinct partial fills belonging to one order are individually matched and cumulatively reconciled. If the replay has one aggregated fill and the external source has several executions, the caller must explicitly normalize their granularity; the audit does not silently merge unrelated executions or suppress duplicate matches. Canceled remainders contribute no invented fill or cash event.

## Itemized charges

For portfolio daily reconciliation use `fee_basis=ITEMIZED_AND_TOTAL`. Supply a FEE row for each verified component, including explicit zeros, plus a separate `TOTAL` FEE row per fill or settlement. Each row has its own external event/entity IDs and supports the same correction/reversal history. No component is calculated by subtracting other items from a total.

| Verified replay cost model | Required component IDs in addition to `TOTAL` |
|---|---|
| Dated execution schedule | `BROKERAGE`, `EXCHANGE`, `IPFT`, `SEBI`, `STT`, `STAMP_DUTY`, `GST` |
| Caller fill model | `FILL_MODEL`, the explicitly applied fill-model fee; no tax allocation is implied |
| Dated settlement schedule, non-flat position | `CHARGE:<schedule-entry-id>` and `GST:<schedule-entry-id>` for every applied line |
| Dated settlement schedule, flat position | `FLAT_NO_CHARGE`, explicitly zero |
| Missing execution breakdown or `NOT_MODELED` settlement | `UNITEMIZED`, expected amount unknown; reconciliation is INCOMPLETE |

For example `SET-F:CHARGE:F-FIX` identifies the charge in settlement `SET-F` from schedule entry `F-FIX`. Schedule IDs, source file hashes and source references remain in the verified replay artifacts bound by `inputs.csv`; they are not external source authentication. GST taxable bases are not charges and are never added to the fee total.

Every component is compared separately. Effective external items are summed and checked against the independently declared event total. Equal totals cannot hide offsetting component mismatches. Missing or unknown items leave that sum unknown; an explicitly provided total remains a known observation. Fee aggregates sum only `TOTAL` rows, with separate per-component aggregates, so itemization never doubles the charge. Cash fee rows refer to the event's total and reconcile independently.

`fee_basis=TOTAL_PER_EVENT` is also supported for both replay adapters; it admits only `component=TOTAL` and establishes only event-total agreement. It cannot certify component agreement. The bundled `itemized/` fixture demonstrates component reconciliation, while the parent fixture demonstrates this narrower total-only contract. No mode certifies tariff completeness beyond the declared replay model.

## Coverage and separate reconciliation

Exact coverage header:

```csv
record_type,start_utc,end_utc,completeness,source_reference
```

Supply exactly one row for each of the six record types. `completeness` is COMPLETE, INCOMPLETE, UNKNOWN or blank. COMPLETE also requires known start/end boundaries containing the entire replay interval and a source reference. Missing declarations/boundaries/references, narrower windows and partial declarations are INCOMPLETE. Duplicate declarations fail. A header-only ledger can establish zero activity only where the verified replay also has none and the caller explicitly declares complete coverage; required opening/closing positions still need rows.

The audit compares each field, then independently reconciles order filled totals against effective external child fills; BUY/SELL quantities; opening + buys − sells − signed settlement units against closing positions; fee totals; trade cash; fee cash; settlement proceeds; and settlement fee cash. External arithmetic uses the caller's effective payload and relations, not the matched replay's economics. Missing, ambiguous, invalid or unknown inputs keep dependent totals blank. FAIL takes precedence over INCOMPLETE; otherwise all comparisons must pass.

Cash is a replay accounting-flow convention: BUY consumes `units × price`, SELL contributes it, settlement contributes `signed units × value`, and explicit fee cash is the negative of the separate fee total. Trade notional excludes fees. This convention supports an accounting comparison; it is not a statement that a futures broker transfers full notional at execution, calculates daily variation margin this way, or has a known opening/closing account balance. Account balances and variation-margin cash require a future adapter with explicitly supplied accounting rules and balance inputs.

Settlement is compared to the replay's integrated event, not added a second time. Positions and proceeds reconcile separately from charges. If the replay marks settlement costs `NOT_MODELED`, its stored bookkeeping zero does **not** become an expected external zero: expected settlement fee and fee-cash amounts remain blank and the result is INCOMPLETE. Modeled fees remain scoped to their explicit replay cost model and the caller's total-per-event normalization.

## Hashed report and daily evidence

Seven deterministic UTF-8/LF files are published atomically outside the replay under `SHA256(audit-manifest.properties)`:

| File | Evidence |
|---|---|
| `summary.properties` | Overall and separate domain PASS/FAIL/INCOMPLETE, verified replay identity/window, source classification, explicit policies and counts. |
| `comparisons.csv` | Replay/external IDs, physical source line, match method, field values, exact numeric difference and status. Unknown amounts/differences are blank. |
| `exceptions.csv` | Row-level failures, missing/unknown records, history and ordering findings with source/replay IDs and details. |
| `events.csv` | Caller history and final normalization dispositions. |
| `coverage.csv` | Each domain's caller declaration and assessed coverage. |
| `totals.csv` | Separate quantities, positions, fees, cash components and settlement totals. |
| `inputs.csv` | Actual input hashes, declared hashes, observed byte counts, source attribution, exact audit build hash and replay/indirect dataset hashes. Unobserved hashes/lengths are blank. |

The canonical manifest hashes every output, including input provenance. Reports contain no audit clock, random ID, machine path or file metadata; repeated identical input bytes under the same build produce identical report bytes and IDs, including after bundle relocation. Inputs with different bytes, even financial-equivalent reordered CSVs, have different provenance and report IDs. The build is bound by the complete JAR bytes, or a sorted class-byte fingerprint for an unpackaged diagnostic build. Atomic rename/forced file writes do not establish power-loss durability or trusted checkpoint storage.

Retain the sidecar, exact audit JAR, original replay inputs/artifacts and external bundle as daily reconciliation evidence for operator review. The existing daily-operations checker is a separate contract; this command does not operate controls, authenticate external records, automatically approve an operations bundle or close G4. Artifact hashing detects byte changes relative to the retained manifest; a caller can rehash changed evidence, so authenticity requires independent evidence beyond this tool. Semantic `verify` detects rehashed report changes relative to the retained original inputs and build.

## Validation on 2026-11-07

Microsoft OpenJDK **21.0.13.0.2**, with `javac --release 21`: **262 acceptance checks in eight synthetic groups passed** against the standalone JAR. The existing portfolio accounting, replay engine, execution-cost, artifact-verifier, settlement and settlement-audit suites all passed; the settlement-audit suite reported 88 checks. The itemized drill passed audit, semantic reconstruction and artifact verification. Both the executable JAR and PowerShell command runner passed their smoke checks. No network, broker, account data or real statements were used.

- Tested JAR SHA-256: `b49c1f5c77d6fc03d79b3b3b5ec97618dc45d427185bc18c9bff6971d7d19188`.
- Itemized drill report ID: `5ebeb871dd00e41153fc799fda8bef7153efcb833ee074bdd30d34883dc0a777`.
- [Acceptance log](../build/external-ledger-checks/069ecf8d0c85416e8c817efd7899c891/acceptance.log).
- [Regression logs](../build/external-ledger-checks/b0e6c7da440d40a4ac093aa7710bdee5/).

These log links point to retained local build evidence. Keep the exact tested JAR and report inputs when archiving them. Deliberate FAIL and INCOMPLETE fixture reports are successful acceptance cases, not failed test runs.
