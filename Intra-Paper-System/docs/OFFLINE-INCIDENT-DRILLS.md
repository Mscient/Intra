# Offline incident and recovery drills

This Java 21 orchestrator runs synthetic events through the existing `OfflinePortfolioRiskAdapter`, its `RiskGateway`, replay `OrderIntent` validation, and deterministic checkpoint restoration. It adds an owned safety latch and scenario-authorized reset gate. It has no broker, account, transport, credential, order-routing or network API. The separate CLI is not registered with the general simulator CLI.

**Every result is `SYNTHETIC_OFFLINE_DRILL_ONLY`. Synthetic drills do not count as G4 operating sessions and do not prove live recovery.** A PASS means that this scenario's assertions and recovery gates passed. It does not certify market performance, operating readiness, real cancellation, an authenticated operator, or any external order/position state. G4 remains open.

## Run and verify

Use JDK 21 on PATH, or supply `-JdkHome` to these scripts. No Maven, download or network connection is needed.

```powershell
.\build-incident-drill.ps1
# Use the exact JAR path printed by build (the full simulator JAR also supports this entrypoint).
.\incident-drill.ps1 -JarPath <jar> run examples/incident-drills/feed-loss.drill output/incident-drills
.\incident-drill.ps1 -JarPath <jar> verify <printed-bundle-path> <printed-manifest-sha256>
.\test-incident-drill.ps1
# Test a previously retained package:
.\test-incident-drill.ps1 -JarPath <jar>
```

Java entrypoint: `com.mscient.intrapaper.ops.drill.IncidentDrillCli`. Both `run` and `verify` return exit 0 for PASS, 2 for FAIL, and 3 for INCOMPLETE. Exit 1 means malformed input, an invalid artifact, or an execution/publication error; no successful run is implied. Retain the original manifest hash independently. Verification uses the same implementation build, reruns the frozen scenario and compares every file byte, including the manifest. A changed implementation identity requires regenerating evidence, not relabeling an old bundle.

The test runner builds a JAR, checks adversarial scenarios, inspects the entrypoint's transitive dependencies, and repeats all nine example runs and verifications in separate JVMs. Its matrix expects six PASS, two INCOMPLETE, and one deliberate FAIL. It retains source/package hashes, compiler identity, dependency output and transcripts. The package includes existing offline simulator classes; the callable drill entrypoint and dependency graph have no broker/network client. URI parsing in an existing permission dependency is not network I/O.

## Strict V1 scenario format

Each `.drill` file is a self-contained UTF-8 file without BOM, CR, blank lines or NUL, with a final LF. Maximum size is 1 MiB and maximum deliveries is 2,000. The example directory pins LF in Git. The following metadata lines must appear exactly once, in this order, followed immediately by the exact event header:

```text
OFFLINE_INCIDENT_DRILL_V1
scenario_id=feed-loss
authorized_operator=DrillOperator
max_abs_contracts=10
max_gross_notional=10000
max_margin_proxy=10000
max_daily_loss=1000
maximum_age_seconds=5
margin_per_contract=10
sequence,event_id,time_utc,action,order_id,side,quantity,price,position,reservations,actor,reference,expected_state,expected_result
```

There are no defaults or extension fields. Missing, reordered, unknown or duplicate metadata/columns fail loading. Identifiers start with an ASCII letter, contain only letters, digits, `_` and `-`, and are at most 64 characters. Positive integers are 1..999999999. Signed quantities allow zero and the corresponding negative range; leading zeroes, plus signs and negative zero are rejected. Positive decimal prices and money have at most 12 integer and six fractional digits; exponent notation, NaN and infinity are rejected. Event cells are trimmed printable ASCII, at most 4,096 characters. Standard single-line CSV quoting is supported.

The fixed synthetic universe is `SYNTH-1`, INR, one unit per contract, session `SYNTHETIC_DRILL`. Marks and per-contract margin proxies are labeled SYNTHETIC/DRILL_ONLY. The complete original scenario hash is included in the risk-input fingerprint. This is deliberately a small drill model, not arbitrary instrument or multi-session configuration. Margin is not SPAN/ELM, and P&L is gross of fees.

Sequence numbers start at 1 and increase without gaps. Event times are canonical `Instant.toString()` UTC values and must not regress. The first delivery must be FRAME. Distinct FRAME, STALE_MARK and FEED_LOSS events require strictly increasing frame times. Exact duplicates may repeat the original frame time. Order proposals and acknowledgements use the current frame's exact timestamp; add another FRAME to advance it. Other controls use explicit scenario time; freshness expires after `maximum_age_seconds` without any wall-clock sleep.

Every row supplies `expected_state` and `expected_result`. States are RUNNING, SAFE_HALT and RECONCILED. The action table lists the only nonblank payload columns permitted. All other payload cells must be blank.

| Action | Required payload | Effect / principal result |
| --- | --- | --- |
| FRAME | price | Apply complete fresh synthetic mark/margin inputs: FRAME_APPLIED. A gateway daily-loss latch produces HALTED. |
| FEED_LOSS | none | Begin a frame with missing feed inputs and evaluate a probe through the adapter: FEED_LOSS_BLOCKED and SAFE_HALT. |
| STALE_MARK | price | Supply mark as-of older than the age limit by one second, keeping margin fresh; adapter probe rejects: STALE_MARK_BLOCKED and SAFE_HALT. |
| PROPOSE | order_id, side, quantity | BUY/SELL a positive incremental quantity from actual signed state. ACCEPTED creates a reservation only; RISK_REJECTED latches a halt. |
| LIMIT_BREACH | order_id, side, quantity | Same gateway evaluation, requiring a position, gross-notional, margin or session-halt rejection: LIMIT_BLOCKED. A non-breaching proposal makes the drill FAIL and halts with any accepted reservation retained. |
| ACK_MISSED / UNCERTAIN_ORDER | order_id | Require an existing working order, retain its reservation, mark adapter uncertainty and missing acknowledgement: HALTED. |
| RETRY | order_id | Reevaluate the original immutable intent, including after checkpoint replay. RETRY_ACCEPTED / RETRY_REJECTED is a historical result and cannot authorize a second execution. RETRY_UNKNOWN identifies an absent original intent. |
| FILL_ACK | order_id, quantity, price | Apply a confirmed incremental fill at or below the risk valuation ceiling: FILL_APPLIED. Overfill, unknown/terminal order, wrong time or blocked checkpoint gives ACK_REJECTED and SAFE_HALT. A loss-latching fill immediately halts. |
| CANCEL_ACK | order_id | Confirm cancellation of the whole residual: CANCEL_APPLIED. A requested cancellation alone is not an acknowledgement. |
| CHECKPOINT | reference | Retain immutable trusted adapter bytes, independent expected digest and a separate candidate copy: CHECKPOINT_SAVED. At most 16 uniquely named checkpoints; blocked recovery cannot establish a new anchor. |
| CORRUPT_CHECKPOINT | reference | Flip one candidate byte, retain trusted bytes/digest, latch SAFE_HALT and checkpoint block: CHECKPOINT_CORRUPTED. |
| RESTART | reference | Restore candidate only if its trusted digest equals the latest acknowledged adapter head and the bytes verify: RESTARTED_HALTED. Missing, corrupted or lagging checkpoints give CHECKPOINT_REJECTED; state/reservations are retained. |
| RECOVER_TRUSTED | reference | Explicitly retry recovery from the separately retained original trusted bytes under the same acknowledged-head check. Never rehash/repair uncertain bytes or start an empty ledger. |
| RECONCILE | position, reservations | In SAFE_HALT with healthy controls and trusted state, exactly compare signed position and every residual: RECONCILED or RECONCILIATION_REJECTED. Cannot overwrite exposure. |
| RESET | actor, reference | Require scenario allowlisted actor and reference to the successful current reconciliation's event ID: RESET_AUTHORIZED or RESET_DENIED. |

Reservations use `-` for an explicitly empty set, or sorted `order-id:positive-quantity` pairs separated by `;`. Omitted/duplicate/unsorted residuals are rejected or fail exact reconciliation. Nonzero signed positions may be retained after successful reconciliation/reset; PASS does not mean flat.

An identical event ID and payload (including timestamp, excluding delivery sequence and expectations) is DUPLICATE and has no effect. Conflicting reuse is EVENT_ID_CONFLICT and halts without applying the new payload. Distinct event IDs for incremental fills represent distinct fills, not cumulative quantities. Reusing an order ID through PROPOSE is rejected; use RETRY to obtain its original decision. ACTION_REJECTED identifies invalid command ordering, checkpoint limits or component failures; unexpected component exceptions always force FAIL even if a scenario expects this generic result. New proposals while halted or reconciled return BLOCKED_SAFE_HALT.

Compounded feed/stale incidents may encounter the adapter's existing EXECUTION_UNCERTAIN rejection first. Evidence records the actual reason; the original reservation and uncertainty remain. A delivery mismatch never gets hidden by subsequent recovery.

## Recovery and completion gates

The required path is `RUNNING → SAFE_HALT → RECONCILED → RUNNING (authorized RESET)`.

Fresh feed/marks, retries and checkpoint restoration never clear the drill halt. Reconciliation calls the existing adapter's exact position/reservation comparison and binds reset to both that event ID and the resulting risk-checkpoint hash. New incidents, frames or acknowledgements invalidate that binding. Reset additionally requires fresh healthy inputs, no unresolved acknowledgement, no working reservation, no adapter uncertainty, a trusted checkpoint state, and no gateway daily-loss latch. A terminal fill/cancellation resolves the missing acknowledgement for that order; merely restating its residual does not. A denied reset changes no exposure and grants no permission.

PASS requires every expected state/result to match, at least one incident and successful recovery/reset cycle, RUNNING at the end, fresh inputs and all reset safety conditions still satisfied. FAIL takes precedence for any mismatch or internal safety/component failure. Otherwise the outcome is INCOMPLETE, including a missing acknowledgement, unresolved restart, unreset halt, daily-loss latch, or a scenario containing only healthy frames. An expected refusal can be part of a PASS drill if later legitimate recovery completes. A later incident invalidates an earlier completion.

The daily-loss latch is never cleared by this runner; V1 has no session-rollover shortcut. Lagging checkpoints remain INCOMPLETE unless another previously retained checkpoint exactly matches the acknowledged head. The tool never trims journals or derives a new trusted head from uncertain history.

## Evidence and limits

The bundle directory name is SHA-256 of `manifest.properties`. A complete bundle contains frozen `scenario.drill`, `report.properties`, hash-chained `events.csv`, adapter `risk-decisions.csv` / `risk-events.csv`, the final `risk-checkpoint.txt`, and each saved trusted/candidate checkpoint. The manifest hashes every other file and includes an implementation digest of the drill and directly used risk/replay classes. Events bind delivery, expected/actual results, before/after states, signed exposure, residuals, missing acknowledgements, uncertainty, checkpoint block, and adapter checkpoint hash.

Bytes and identities contain no runtime date, random ID, absolute path or measured latency. Temporary publication directories are unique, but final artifacts depend only on input and implementation. Output refuses overwrite; publication moves a fully written sibling directory, retaining failed staging for diagnosis. Filesystem/OS durability and adversarial path replacement are not certified. Evidence is limited to 128 MiB. Hashes detect discrepancies under an independently retained root; they are not signatures or source authentication.

RESTART is **in-process reconstruction of the existing adapter checkpoint**, with the synthetic controller retaining its scenario, acknowledgement history and safety gates. It is not a durable control-plane journal, process-kill test, full strategy/accounting restart, OS reboot, power-loss test, broker reconnect or live recovery experiment. The `authorized_operator` field is a caller-supplied synthetic allowlist, not authentication or independent human approval. No file, credential, broker/network endpoint, shell command or callback can be supplied in a scenario. Real operating sessions, incident evidence, operator authentication, independent position/order reconciliation and recovery authorization remain separate work.
