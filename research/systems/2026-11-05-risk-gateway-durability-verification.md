# Task 2 — Risk gateway durability and recovery verification

**Result:** 447 checks passed in 13 focused offline groups on Microsoft JDK 21.0.13.0.2. Recovery reproduced complete risk state at every tested lifecycle prefix. Injected journal corruption and write/force exceptions failed closed in the durable facade. All runtime checks used local synthetic files and same-JVM threads; no broker connection, network, live order route, process-isolation result, hosted CI result, or production-readiness claim is included.

## Deliverables and scope

- [Dedicated PowerShell runner](../../Intra-Paper-System/test-risk-gateway.ps1).
- [Dependency-free Java verification suite](../../Intra-Paper-System/src/test/java/com/mscient/intrapaper/riskgateway/RiskGatewayDurabilityTest.java).
- Isolated fixes in `RiskGateway`, `RiskGatewayJournal`, and `DurableRiskGateway`.
- [Operating notes, restart migration and limitations](../../Intra-Paper-System/docs/INDEPENDENT-RISK-GATEWAY.md).
- Portable evidence: [checks](risk-gateway-durability-2026-11-05/checks.txt), [JDK identity](risk-gateway-durability-2026-11-05/jdk-identity.txt), [source hashes](risk-gateway-durability-2026-11-05/source-sha256.txt), and [tested package hash](risk-gateway-durability-2026-11-05/package-sha256.txt).

The verification implementation edits are limited to the risk package, its dedicated runner/suite, its contract document and this task's report/evidence/session record. Other portfolio and research work was already active in the shared checkout. At verification delivery, changes remained uncommitted. The user's subsequent instruction authorized a local Task 2 commit plus README and implementation-tracker updates; only those scoped changes are included, preserving unrelated edits. The implementation/report commit is recorded in the session index. No merge or network publication was requested or performed. Source hashes identify the tested working-copy files; this is not a clean-commit build claim. The runner compiles only the three risk classes and runs the suite against their packaged JAR, so it is independent of Task 1's compilation and tests.

## Defects found and fixes

1. **Acknowledged exposure could disappear during error recovery.** A pre-fix offline probe started a session, accepted three contracts and filled one. After the journal was deleted, an invalid cancellation threw its validation error, but the facade reloaded the missing journal as empty. The visible state changed from executed `{F=1}` plus two reserved contracts to empty positions and reservations. The regression now detects the changed/missing journal before mutation and disables the instance. Invalid inputs against a healthy journal discard an internal candidate; they do not reload the journal or flush cached negative decisions.
2. **A readable append after an exception was not sufficient recovery evidence.** The facade now publishes a candidate only after append and force both return successfully. Every journal-check or persistence exception latches the instance unavailable, including exceptions after all bytes are written or after force returns. It does not resume automatically from a structurally readable tail.
3. **Hash-chain completeness needed an independent anchor.** A chain cannot detect deletion at a complete-row boundary, replacement by another valid chain or deletion of the entire file without a retained expectation. Recovery now requires a `RecoveryCheckpoint` containing the exact immutable risk configuration and acknowledged journal verification. Missing, shortened, extended and rehashed journals are rejected when they differ from it. Configuration changes cannot silently relax reservations or alter reconstructed halts.
4. **The low-level writer could bypass facade ownership.** Public `RiskGatewayJournal.append` now acquires the same lifetime lease as the facade. Supported concurrent writers cannot append around its in-memory state. Head comparison and append also occur under the shorter journal lock.
5. **Serialization and extreme-quantity handling needed stricter rejection.** The journal rejects noncanonical sequence spelling and binary encodings, malformed Unicode, invalid UTF-8 and excessive UTF-8 field lengths. It checks the event budget during encoding and bounds Base64 length before decoding. A projected `Long.MIN_VALUE` position can no longer evade a position cap through `Math.abs` overflow.

**API migration:** `new DurableRiskGateway(path, configuration)` creates a new journal exclusively and refuses an existing path. Reopening requires `new DurableRiskGateway(path, configuration, retainedCheckpoint)`. The checkpoint must come from a separately controlled durable acknowledgement record. The library does not supply that store. Never use the creation constructor as fallback when recovery fails, and never treat `verify(path)` on an uncertain journal as authorization to mint a new checkpoint.

## Verification coverage

| Group | Checked behavior and outcome |
|---|---|
| Append/restart/lifecycle | Typed payload roundtrip, event count/hash, identical bytes from repeated event sequences, complete state equality at every prefix, signed positions, marks, cashflows, P&L and all order lifecycle records. |
| Accepted proposals and IDs | Accepted reservation persisted before success; original accepted decision survives later fills and restart; duplicate proposals/fills do not reserve or execute twice; conflicting and cross-kind IDs reject without appending. |
| Ordering and invalid events | Tied timestamps accepted; decreasing timestamps reject in gateway and journal; unknown order/instrument/session, wrong side, zero/negative quantity, overfill, invalid price, late fill and terminal-order cancellation reject without state or byte changes. |
| Session boundaries | Working orders block rollover; carried positions require opening marks; a valid new session carries positions and resets the opening P&L basis deterministically. |
| Halts and closeout | Loss threshold equality latches; mark rebound and restart retain halt; new risk, crossing zero and closeout with an existing working order reject; strict closeout and partial closeout reservations rebuild; only valid journaled rollover resets the daily-loss latch. |
| Inputs and quantities | Missing/stale marks fail closed, reservations count toward the position cap, exact cap equality passes, and extreme signed-quantity arithmetic cannot evade limits. |
| Concurrent writers | Second facade and lower-level append fail while a lifetime lease is held; 16 concurrent facade proposals accept only ten one-contract reservations under a ten-contract cap; competing low-level writers produce a verified journal containing every successful append. Scheduling order is not asserted. |
| Truncation/tampering | Partial final row, missing LF, CRLF, blank rows, reordered/deleted middle rows, changed sequence/hash/link, invalid Base64, empty/whole-row rollback and rehashed replacement. Invalid journals remain unchanged by rejected appends. Checkpoints reject otherwise structurally valid rollbacks/replacements. |
| Malformed binary payloads | Unsupported schema/type, truncated/trailing bytes, negative/oversize lengths, malformed UTF-8, invalid collection counts, duplicate instrument marks, noncanonical boolean/Base64 encoding, duplicate/conflicting IDs and decreasing timestamps with recalculated hashes. |
| Semantic recovery | Structurally valid journals containing acceptance before session start, acceptance beyond limits, unknown execution, overfill and fill after cancellation fail risk replay on repeated recovery attempts. |
| Size limits | Exactly 64 KiB ASCII field accepted; larger fields and multibyte UTF-8 overflow reject; malformed Unicode rejects; collection above 100,000, event above 1 MiB and file above 256 MiB reject. Rejected encodings preserve previous bytes. |
| Write/force failures | Twenty operation/stage combinations: acceptance, fill, cancellation and a mark that actually latches halt, each failing before write, after a 17-byte prefix, after complete write before force, after force, or with a runtime persistence exception. No call reports success; state/mutation/checkpoint APIs remain blocked. |
| External failures/checkpoints | Missing, empty, shortened, replaced and directory-substituted journals; real journal-lock contention and serialization I/O failure; closed-instance behavior; constructor-failure lock cleanup; configuration binding and an explicitly retained genesis checkpoint. Rejected proposal identity remains process-local, as documented. |

For zero-byte failures, the previous journal restores exactly the acknowledged position and reservations; deliberate reopening with the retained checkpoint succeeds. For complete uncertain rows, the disk journal replays exactly the attempted execution/reservation/halt state, but the failed facade stays disabled and the previous checkpoint cannot reopen the extended history. Partial rows reject verification and recovery. The suite never repairs a tail by silently dropping exposure.

## Exact run and JDK package identity

Run command, from `Intra-Paper-System`:

```powershell
.\test-risk-gateway.ps1
```

Final evidence directory:

```text
C:\Users\prash\AppData\Local\Temp\codex-intra-origin-20261103\Intra-Paper-System\build\risk-gateway-verification\f136aed67c104bc68ecb67824507a53d
```

The runner used `javac --release 21 -encoding UTF-8`, built `risk-gateway.jar` with the installed JDK jar tool, then compiled and ran the focused suite against that JAR using assertions and a 768 MiB heap cap. Result: **447 checks / 13 groups passed**. The earlier final-Java-source run also passed 447 checks; the last run verified the runner's corrected metadata formatting. The lifecycle digest remained `c29af67dbe2c00a6c5dbdf76ebe595517e6737323041eabefc1957751c6d2d7b`. ZIP timestamps can change the JAR hash between runs; journal byte determinism is tested independently.

- Implementor: **Microsoft**; implementor version: **Microsoft-15780113**.
- Java/compiler: **21.0.13.0.2**; runtime: **21.0.13.0.2+1-LTS**, dated **2026-10-22**.
- JDK release source: **.:git:86770f4f323a**; package platform: **Windows x86_64**; runtime host: **Windows 11 / amd64**.
- Installation: `C:\Users\prash\AppData\Local\Programs\Microsoft\OpenJDK21\jdk-21.0.13.0.2+1`.

| Artifact | SHA-256 |
|---|---|
| Installed JDK `release` | `f5cc58569a90397a90899f0d02ec76bfedb7d208224e7d964b5e6d72c4c04924` |
| Installed `java.exe` | `65d23a3b6e1dfbfe2ae268899278bc1bdb63917c5ccd7094337ba8b242e4d77f` |
| Installed `javac.exe` | `9d385fd280a0f8a9421ff870b894c5ff78dd094b9a36027d8f114e363e1fc4cb` |
| Installed `jar.exe` | `db0cd1babb32b58e316f1850bfbd4756566e089bcfc8e4d315c92f37bd8b8c07` |
| Final tested `risk-gateway.jar` | `dd7e8f6dd186050cd2a34eef401973667999469226b3ef08b2f7c0a8a2da7036` |

The sandbox initially denied the installed JDK path; permission was granted to read its metadata and run the focused offline script. No dependencies or JDK packages were downloaded. Source and package hashes are retained alongside the report. `git diff --check` found no whitespace errors in the tracked Task 2 changes; the new runner, suite and report were also reviewed directly.

## Practical limits

Recovery is deterministic for a fixed ordered journal and exact configuration. Concurrent thread scheduling can select different accepted IDs/orderings, while each resulting journal still determines its recovery state. This is same-JVM concurrency evidence only, with no process-isolation or cross-process test claim.

The journal and checkpoint are not authenticated. Structural low-level `read`/`verify`/`restore` APIs can accept a valid shorter journal and read a missing file as empty. Safe facade restart relies on separately retained trusted checkpoints and explicit reconciliation of uncertain outcomes. Checkpoint storage, independent positions/fills, durable rejected-decision identity, recovery approval and operational watchdog behavior remain outside this library. An accepted retry returns a historical decision, not fresh authorization to submit another order.

Failure injection uses real files but replaces the package-private write/force function. It does not prove behavior under sudden power loss, killed processes, full disks, device-cache faults, lost directory entries, out-of-memory conditions, arbitrary filesystem errors or noncooperating writers. Protected local paths and stable lock files are required; adversarial aliases, hard links, ancestor junctions, lock replacement and network filesystems are outside the verified contract. `state()` reports the last acknowledged snapshot until another operation detects uncertainty; it is not a health monitor.

The 256 MiB check rejects an oversized file before reading it; the suite does not populate a valid journal to the exact file cap or measure sustained operation there. Verification re-reads history and candidate construction copies state, so the size cap is not a memory, throughput or latency guarantee. No simulator integration or broad unrelated portfolio suite was run for Task 2.

Caller-supplied marks, margin proxies, metadata, timestamps and events remain synthetic/unverified inputs. These results establish the focused offline durability/recovery behavior under the tested conditions, without establishing account reconciliation, exchange behavior or live-trading readiness.
