# Risk gateway journal across processes and crash recovery

**Result:** the isolated JDK 21 subprocess suite passed **728 parent checks in five groups**, launching **308 child JVMs** and forcibly terminating **24** of its own writers. The existing risk-gateway suite passed **447 checks in 13 groups**. Relevant portfolio risk, replay, costs, artifact, settlement, accounting, audit and registered-evaluation regressions passed. No gateway production change or journal-format migration was needed. Process-kill evidence does not establish power-loss durability.

## Implementation and scope

- [Isolated Java harness](../../Intra-Paper-System/src/test/java/com/mscient/intrapaper/riskgateway/RiskGatewayProcessTest.java).
- [Dedicated PowerShell runner](../../Intra-Paper-System/test-risk-gateway-process.ps1).
- [Operating contract and limitations](../../Intra-Paper-System/docs/INDEPENDENT-RISK-GATEWAY.md).
- [Earlier durability report](2026-11-05-risk-gateway-durability-verification.md), inspected before extending coverage.

The runner compiles only `RiskGateway`, `RiskGatewayJournal` and `DurableRiskGateway`, packages them, then compiles the test against that JAR. Parent and child processes require Java feature version 21. The harness has no broker, account, credential, network client or order routing. Existing simulator adapters were exercised only by existing regression tests; this change adds no simulator integration. The three production classes and the V1 on-disk encoding remain unchanged.

Child JVMs use the parent's `java.home` and classpath through argument-list `ProcessBuilder`, with no shell command construction. Transcripts go to per-child files. IPC uses standard input and complete marker files published by atomic same-directory rename. Both writers must report ready before release, and the owner remains alive and locked until both outcomes are available. The winning writer ID is not asserted. Thirty-second waits are safety deadlines, not scheduling or crash-selection assumptions. The harness checks specific exit statuses; unexpected exceptions, premature child exits, timeouts or success in a rejection case fail the suite. Cleanup terminates only processes launched by the harness and retains fixtures rather than recursively deleting them.

The test-only checkpoint codec transfers the complete exact configuration, acknowledged count/hash and last timestamp. Seed checkpoints are obtained after successful public operations and saved separately from the journal. Deliberately incorrect checkpoints are negative fixtures. Diagnostic verification of an uncertain complete row never supplies a new checkpoint for recovery. The codec and saved test files do not provide a production trusted/durable acknowledgement store.

## Coverage and outcomes

| Scenario | Evidence |
|---|---|
| Concurrent writers | Three new-journal races and three checkpoint-recovery races. Exactly one lifetime owner; losing JVM rejects specifically for cross-process lock contention. Third-process public append rejects under the owner's lease. Only the owner adds a reservation, and its resulting checkpoint recovers all events. |
| Matching restart | Separate JVM restores complete risk state and its exact checkpoint, including the executed position and remaining working quantity. Repeated failed constructors do not leak the ownership lease. |
| Stale/mismatched checkpoint | Earlier session/acceptance checkpoints, changed count/hash/timestamp, and changes to currency, position cap, multiplier, gross/margin/loss limits, input age and instrument universe reject recovery repeatedly without modifying bytes. |
| Accepted-ID retry | Three restarted JVMs return the original accepted decision after a later execution. Identical accepted and execution events do not append or mutate state; conflicting proposal ID rejects. Public low-level accepted/executed retries are also idempotent. An acknowledged acceptance retried after a process kill retains one reservation. |
| Invalid tails | Partial final row, missing LF, malformed fields, blank tail, CRLF tail, swapped trailing rows, altered payload/hash/link, invalid Base64 with recomputed hash, and unsupported binary schema with recomputed hash reject verification, append and recovery. New-journal creation refuses existing damaged files. Bytes remain intact. |
| Valid rollback/missing file | Structurally valid histories shortened to zero/one/two complete rows still fail against the three-event checkpoint. A missing acknowledged journal rejects recovery and is not recreated. |
| Killed writers | Acceptance, second partial execution, residual cancellation and a mark latching the daily loss at equality, each tested at six controlled boundaries below. Child lock probes confirm lifetime and append-lock ownership while append is paused. Fresh verification/recovery JVMs prove lock release after termination. |

Each crash fixture starts from an acknowledged session, accepted quantity three, and one executed contract. Reconstructed state is compared in full with ordered reference events, including positions, all order lifecycle records, residual reservations, opening basis, marks, timestamps, cashflows, P&L, halt and event history. Reference assertions also require the new acceptance quantity, second execution position, released cancellation remainder or latched loss, as applicable.

| Kill boundary (four operations each) | Required result |
|---|---|
| Before writing | Exact prior journal/checkpoint survive; verification and checkpoint-bound restart restore prior state. |
| After a 17-byte prefix | Acknowledged prefix survives, incomplete final row remains; verification, append and restart reject. |
| After all row bytes except LF | Exact missing-LF tail remains; verification, append and restart reject. |
| Complete row, before force | Verification sees all attempted state; prior checkpoint rejects the additional unacknowledged row. No successful acknowledgement or replacement checkpoint is invented. |
| After force, before return/acknowledgement | Same fail-closed recovery outcome as above; a readable forced row is insufficient acknowledgement evidence. |
| After successful public operation and saved matching checkpoint | Exact attempted state recovers through the saved checkpoint; old checkpoint rejects. Accepted retry remains idempotent. |

The first five boundaries pause through the existing package-private `AppendIO` seam using real local file channels; the parent calls `Process.destroyForcibly()` and waits for nonzero process exit. Partial-write fixtures deliberately write the chosen prefix, then block with both locks/channels open. Complete-row fixtures deliberately block before or after force. The post-acknowledgement cases use the public constructor and production `FORCED_WRITE`. No kill interrupts an in-kernel write/force syscall. Before and after recovery attempts, disk bytes and the prior checkpoint are compared; no automatic repair or silent exposure loss is accepted.

## Reproduce

From `Intra-Paper-System`, set `JAVA_HOME` to a JDK 21 installation:

```powershell
.\test-risk-gateway.ps1
.\test-risk-gateway-process.ps1
.\test-risk-gateway-process.ps1 # Repeat; owner ID may differ, invariants must agree.

# Ensure the existing PATH-based risk-replay runner uses the same installed JDK:
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [System.IO.Path]::PathSeparator + $env:Path
.\test-portfolio-risk-replay.ps1
.\test-portfolio-risk-audit.ps1
.\test-portfolio-accounting.ps1
.\test-portfolio-trial-evaluation.ps1
```

Both risk runners accept `-JdkHome` and `-OutputRoot`. No Maven, dependencies or downloads are required. Each process-runner execution retains its packaged JAR, classes, all journals/checkpoints, child logs, kill outcomes, JDK identity, source hashes and checks under a fresh `build/risk-gateway-process-verification/<UUID>/` directory. The parent and children each use `-ea -Xmx256m`; at most the parent and two live children are needed simultaneously. Failed children and cleanup waits have bounded deadlines.

| Existing regression | Result |
|---|---|
| Risk gateway durability | 447 checks / 13 groups; lifecycle replay digest unchanged: `c29af67dbe2c00a6c5dbdf76ebe595517e6737323041eabefc1957751c6d2d7b`. |
| Portfolio risk replay | 84 checks; replay engine, dated execution costs, v3 artifact/tamper/determinism and signed settlement suites passed. |
| Portfolio risk audit | 79 focused checks and 274 settlement-risk checks passed. Expected BREACH/INCOMPLETE/invalid-header outputs are negative test cases. |
| Portfolio accounting | Ledger checks passed. |
| Registered trial evaluation | 161 focused checks plus replay/cost/artifact regressions passed. |

## OS/filesystem assumptions and limits

Measured host: Windows 11 / amd64 (`os.version=10.0`), local **NTFS**, Microsoft JDK **21.0.13.0.2+1-LTS**, implementor version **Microsoft-15780113**. Evidence includes installed release metadata and SHA-256 hashes of JDK tools, test/production sources, runners and the tested JAR. JAR ZIP timestamps may change its hash between builds; they are not journal nondeterminism.

The contract assumes supported Java cross-process file locks, a stable normalized journal path, stable protected runtime/append lock files, cooperating writers, completed-write visibility after process exit and atomic same-directory marker rename. It excludes alias/hard-link/ancestor-junction attacks, replacement of lock files and noncooperating writers. Only the measured local platform has execution evidence; other OS/filesystem combinations must run the suite themselves.

Process termination leaves the operating system and its page cache running. These results do **not** prove sudden power-loss, reboot, directory-entry, storage-device-cache or separately retained checkpoint-store durability. They also do not test full disks, hardware faults, arbitrary syscall interruption, OOM, parent termination, network filesystems, sustained throughput or the maximum journal capacity. Checkpoint storage, independent reconciliation and recovery approval remain caller responsibilities. Historical accepted decisions do not authorize another order submission. No live-routing, watchdog or production-readiness gate is closed.

## Evidence

Portable transcripts and identity files are retained in [the evidence directory](risk-gateway-process-recovery-2026-11-06/). Both subprocess executions passed 728 checks / 308 child JVMs / 24 kills. The first run is `build/risk-gateway-process-verification/3cd1ee02cf3648bc91218f566983cce0`; the final repeat, using the public writer for post-acknowledgement cases, is `build/risk-gateway-process-verification/e78950702fba4d4d9282f04c38d499f7`. All **42 non-ownership journal fixtures** matched byte for byte across runs, including matching absence of the deliberately missing journal; [their hashes](risk-gateway-process-recovery-2026-11-06/repeat-journal-sha256.txt) are retained. Ownership journals are excluded from that comparison because the winning writer ID is intentionally unspecified.

The final isolated package SHA-256 is `872b4b6e5e0e3013eeb7c490e959648f08dfb77fe24944858aa155d3b6bdc795`. Installed JDK/tool and tested source/runner identities are in the copied evidence. These are tested working-copy builds, not clean-commit or hosted CI claims. Unrelated research edits already present in the checkout are preserved.
