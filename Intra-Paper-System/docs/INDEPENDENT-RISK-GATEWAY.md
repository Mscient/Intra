# Independent risk gateway

This package is a deterministic, in-memory Java 21 library. It is isolated from the simulator and accepts only caller-supplied values and events. It has no network client, broker adapter, account interface, credential handling, or order-submission method. An accepted proposal creates an internal working-order reservation and an `OrderAcceptedEvent`; it does not send an order anywhere.

## State and calculations

- Executed positions are signed contract quantities derived only from accepted execution events.
- Working-order reservations retain the remaining quantity, limit price, and source-attributed margin input for each accepted order.
- Gross notional is the sum of absolute executed position notional at supplied marks and working reservations at their limit prices. Opposite positions and opposite working orders are not netted for gross exposure.
- Position limits use a worst-case interval from the executed position and all remaining buy/sell reservations. The proposal is included before acceptance.
- Supplied margin is aggregated per contract for executed positions and working reservations. Inputs retain source, reference, session, and as-of timestamp. These values are caller-provided proxies, not SPAN/ELM calculations or exchange margin determinations.
- Daily P&L is computed with decimal arithmetic from session opening positions/marks, executions' cashflows, contract multipliers, and supplied marks. A missing or stale mark makes open-position P&L unavailable and prevents new proposals from passing.
- A daily loss at or beyond the configured limit latches the session halt. Later mark recovery does not clear it; a new session event resets the halt.

## Risk-reducing closeout policy

After a daily-loss halt, a proposal is eligible for the closeout exception only when it is on the opposite side of an executed nonzero position, its quantity does not exceed that position, and no working order exists for that instrument. The exception still requires valid price, margin, session, and event-time inputs and still observes the position cap. Because a close order temporarily adds an unfilled reservation, the exception can exceed gross-notional and supplied-margin caps while it is working. It cannot cross the executed position through zero. Other proposals remain rejected until the next session.

## Event contract

Event timestamps must be nondecreasing in application order. Reapplying an event ID with an identical payload is a no-op. Reusing an ID with a different payload throws `RiskEventException`. Executions must reference an accepted working order, match its instrument and side, and not exceed its residual quantity. A cancellation releases all residual quantity. Session starts require no working orders and opening marks for carried positions.

The immutable `RiskState` snapshot distinguishes executed positions, all order lifecycle records, active working-order reservations, marks, P&L, halt state, and accepted event history. Rejected proposal decisions are cached in the gateway process but are not risk-state events.

## Local event journal

`RiskGatewayJournal` stores typed events in a versioned binary payload encoded as line-oriented Base64, with canonical sequence numbers, nondecreasing event timestamps, a SHA-256 hash chain, file locking, and forced append writes. It rejects incomplete final rows, malformed or noncanonical records, duplicate/conflicting event IDs, hash changes, and files above 256 MiB. Text fields have a 64 KiB UTF-8 limit and must contain valid Unicode; an encoded event is limited to 1 MiB, and collections to 100,000 entries. A collection can hit the event-byte limit first. Encoding stops at the event budget rather than accumulating the full oversized event.

`read` verifies and decodes the entire journal; `RiskGateway.restore(configuration, events)` rebuilds in-memory state from an already verified ordered list. `RiskGatewayJournal.restore(path, configuration)` combines these steps. These lower-level APIs do not establish completeness: a missing file is read as an empty history, and deleting whole trailing rows or rewriting a chain can produce another structurally valid journal. They are diagnostic building blocks, not the recovery authorization path.

`DurableRiskGateway` takes an exclusive lifetime file lock. The public lower-level append API now takes the same lock, so it cannot write while the facade owns the journal. Calls on one facade are synchronized. Competing owners fail promptly with an exception; there is no automatic retry. `read` takes the shorter journal lock, and can also report contention. These locks coordinate cooperating callers using the same local journal path.

## Creation, restart and failure handling

**Compatibility change:** the two-argument constructor now creates a new journal with `CREATE_NEW` and refuses an existing path. Recovery requires the three-argument constructor with a separately retained `RecoveryCheckpoint`. Existing callers that used the two-argument constructor to reopen a journal must migrate.

```java
DurableRiskGateway.RecoveryCheckpoint retained;
try (var gateway = new DurableRiskGateway(newJournalPath, configuration)) {
    gateway.apply(sessionStart);
    var decision = gateway.evaluate(proposal);
    retained = gateway.checkpoint();
    // Caller must durably retain the complete checkpoint separately from the journal.
}
try (var recovered = new DurableRiskGateway(newJournalPath, configuration, retained)) {
    var state = recovered.state();
}
```

The checkpoint contains the immutable risk configuration and the acknowledged event count, final hash, and last timestamp. Restart requires all fields and the configuration to match exactly, then replays every event. Missing, empty, shortened, extended or rehashed histories fail if they differ from the retained checkpoint. An intentionally empty journal can reopen with its previously retained genesis checkpoint. Configuration equality includes decimal representation; preserve the exact configuration, not just numerically similar limits.

The library does not provide a trusted checkpoint store. Retaining a checkpoint only in a Java variable does not survive a process restart. The caller must save it with acknowledgement records in a separately controlled durable location. A lagging checkpoint causes a safe restart refusal. Do not derive a replacement checkpoint from an uncertain journal or fall back to the new-journal constructor after recovery fails. That would discard the evidence needed to detect lost exposure. Migration of an existing unanchored journal requires explicit reconciliation of its accepted proposals, fills, cancellations and halts before establishing an initial trusted checkpoint; structural `verify` alone is insufficient.

Before every `apply`, `evaluate` (including duplicate/rejected proposals), and `checkpoint`, the facade verifies the journal against its acknowledged head. It evaluates mutations on an internal candidate and publishes the candidate only after append and `FileChannel.force(true)` return successfully. Invalid input discards the candidate, leaving both exposure and the process-local decision cache intact. A journal-check or persistence exception permanently disables that facade instance: `state`, `apply`, `evaluate` and `checkpoint` throw until it is closed. It never reloads a smaller history or automatically resumes after an exception. A complete readable row after a write/force exception remains uncertain and does not authorize continued activity.

After failure, retain the journal bytes, the previous checkpoint and the failed call details for reconciliation. A new instance may recover only an exactly matching trusted checkpoint; a partial or additional unacknowledged row requires deliberate reconciliation outside this library. No automatic tail trimming, repair, reset, or clearing of exposure is provided. `state()` itself is the last acknowledged snapshot, not a disk-health probe; it becomes unavailable once an operation detects uncertainty.

Accepted proposal retries return the original decision, including its historical reservation snapshot; inspect `state()` for current residuals. An accepted retry is not an instruction to submit a second order. Rejected proposal decisions remain process-local and can be reevaluated after restart; applications needing durable negative-decision identity must add an independent decision log. A new session resets the daily-loss latch only through a valid journaled session-start event; it carries executed positions and requires no working orders and valid opening marks.

## Focused offline verification

From `Intra-Paper-System`, with `JAVA_HOME` pointing to JDK 21:

```powershell
.\test-risk-gateway.ps1
# Optional explicit locations:
.\test-risk-gateway.ps1 -JdkHome 'C:\path\to\jdk21' -OutputRoot 'C:\path\to\local-evidence'
```

The runner compiles only the three risk gateway classes, packages them as `risk-gateway.jar`, and compiles/runs `RiskGatewayDurabilityTest` against that JAR. It needs no Maven, downloaded dependency, broker connection, credential, or network. Each run retains checks, small generated journals, JDK release metadata and binary hashes, source hashes, and the tested JAR hash in a unique directory under `build/risk-gateway-verification/` by default. One generated 256 MiB-plus-one-byte file is removed after its size rejection check; there is no recursive cleanup. The suite uses up to 768 MiB Java heap.

Coverage and measured results are in [the Task 2 verification report](../../research/systems/2026-11-05-risk-gateway-durability-verification.md). Concurrency is tested with threads and overlapping file locks within one JVM. Fault injection replaces only the package-private write/force operation while using actual local journal files. It exercises exceptions before writing, after a short prefix, after a complete write before force, after force, and during a runtime failure. It does not simulate a machine power loss or establish storage hardware durability.

## Subprocess ownership and crash verification

Run the separate process harness on JDK 21:

```powershell
.\test-risk-gateway-process.ps1
# Optional explicit locations, with a protected local filesystem:
.\test-risk-gateway-process.ps1 -JdkHome 'C:\path\to\jdk21' -OutputRoot 'C:\path\to\local-evidence'
```

This runner packages only the three gateway classes and compiles `RiskGatewayProcessTest` against that JAR. Its child JVMs use the same JDK and classpath as the parent, with assertions enabled and a 256 MiB heap cap per JVM. It retains all fixtures, child transcripts, kill exit results, checks, JDK identity and source/package hashes under a unique `build/risk-gateway-process-verification/` directory. It does not compile or connect to the simulator. A test-only binary checkpoint fixture carries the complete exact configuration and acknowledged verification between JVMs; this codec is not a supported checkpoint-store API or durability implementation.

Two child writers reach explicit readiness barriers before concurrent release. The winning writer holds its lifetime lease until both ownership outcomes are observed. Exactly one may own a new or recovered journal, and a third JVM's public low-level append must reject while that lease is held. The winning ID is intentionally unspecified; the ownership and reservation invariants are deterministic.

For acceptance, execution, cancellation and a loss-latching mark, the parent forcibly terminates its own child at each controlled boundary: before writing, after 17 bytes, after all bytes except LF, after a complete row before force, after force before acknowledgement, and after a successful public operation plus separately saved checkpoint. Append-in-progress boundaries use the existing package-private write/force seam to pause with real channels and both journal locks open; post-acknowledgement cases use the public constructor and production writer. Standard-input handshakes and atomically published marker files select boundaries; elapsed time only bounds failure waits. The test kills a paused process, not an in-progress kernel write or force syscall.

After termination, verification/restart occurs in new JVMs. Zero-byte outcomes recover the previous acknowledged state. Partial rows reject verification and recovery. Complete unacknowledged rows may verify and expose the attempted risk state diagnostically, but the previous checkpoint rejects their extended history. A successfully acknowledged event with its saved matching checkpoint must recover exactly, and an accepted-ID retry must not reserve twice. No test trims tails or constructs a replacement checkpoint from uncertain bytes. Tests also reject malformed, reordered, corrupted and rehashed-invalid tails, whole-row rollback, missing journals, stale checkpoints and configuration changes. See [the subprocess report](../../research/systems/2026-11-06-risk-gateway-process-recovery-verification.md) for results and regression commands.

The measured platform is Windows 11 / amd64, local NTFS and Microsoft JDK 21.0.13.0.2+1-LTS. The harness requires local filesystem support for Java cross-process file locks, atomic same-directory marker moves, and visibility of completed writes after a process exits. Journal/checkpoint directories and both lock files must remain stable and protected; every writer must cooperate through the supported APIs and the same normalized path. Other OS/filesystem combinations must rerun the suite before claiming equivalent evidence.

**Process termination is not power loss.** The OS and its caches survive these tests. `force(true)` does not establish directory-entry persistence, device-cache behavior or independent checkpoint/acknowledgement-store durability. These tests do not cover full disks, storage faults, reboot, sudden power loss, arbitrary syscall interruption, parent-process termination, adversarial aliases/lock replacement or network filesystems. There is no external reconciliation or automatic recovery controller.

## Limits

The gateway's active state is single-process and reconstructed from local journal events on restart. No separate process, watchdog, external execution feed, authenticated operator action, account reconciliation or simulator integration is included. The journal and checkpoints are not authenticated. Protect journal directories, lock files and retained checkpoints from replacement; adversarial path aliases, hard links, ancestor junctions, lock-file replacement, noncooperating writers and network filesystems are outside this contract.

Recovery is deterministic for the same ordered bytes and configuration; scheduling does not determine a reproducible winning order among concurrent proposals. Verification and candidate copying scale with history, so this is not a throughput or latency result. File forcing does not certify directory-entry durability, device caches, sudden power loss, out-of-memory recovery or arbitrary filesystem behavior. Process-termination evidence is limited to the controlled boundaries and measured platform above. The original suite checks oversize-file rejection, not a fully populated valid 256 MiB journal or sustained operation at that cap.

Quantities are whole contracts, all instruments use one configured currency, and each instrument has a fixed contract multiplier and position cap. Caller-supplied marks, margin inputs, timestamps, instrument metadata, and event ordering are not independently sourced or verified. The library is not exchange behavior, an operational recovery controller, a margin model, strategy validation, or live-trading authorization.
