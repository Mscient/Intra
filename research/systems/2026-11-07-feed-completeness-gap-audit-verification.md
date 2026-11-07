# Feed Completeness and Gap Audit: local Java 21 validation

Implemented on isolated branch `codex/feed-completeness-audit-20261107`, based on freshly fetched `origin/main` at `e858e1f`. Inspected existing local branches and their unpublished commits, including the permission/preflight work; no local branch content was assumed published, imported, reset, cleaned, overwritten, pushed, or merged. The original dirty `main` worktree and all other worktrees remain preserved.

Implementation commit: `1ed6e4da80548d73bad8aecb80f096e7a32fd4d9`. The production JAR was built from that clean commit and its `Build-Commit` manifest field was checked against it. The subsequent evidence commit adds this record, retained logs/reports, and corrects the machine-readable policy schema's maximum integer literal to exact `9223372036854775807`; Java's existing signed-long validation and all executable classes are unchanged.

The system is a separate offline CLI/PowerShell runner. It verifies and snapshots supplied inputs, compares explicit scoped schedules and policies to raw/lifecycle evidence, reports row-level anomalies, and atomically publishes deterministic reports. It supports optional dated mapping and exact canonical quote reconciliation. It reuses existing journal readers and exposes the existing pure canonical quote decoder; it adds no feed adapter or broker/order behavior. The build uses a javac argument file to avoid Windows command-line length limits. Source, packaged, and drill checks are integrated into the Windows Java 21 CI runner.

## Exact results

| Validation | Result |
| --- | --- |
| Source acceptance | **507 checks passed**, exit 0 |
| Packaged acceptance | **507 checks passed**, exit 0 |
| Packaged synthetic drill | **557 checks passed**, exit 0; additionally executes CLI run for all 50 diagnostic scenarios |
| Source versus packaged output | **200 artifact files identical byte-for-byte**; includes both repetitions of report and artifact manifests for 50 scenarios |
| Dedicated PowerShell runner | **15 mode checks passed** across five committed examples; PASS 0, FAIL 2, INCOMPLETE 3, valid artifact 0 |
| Machine-readable JSON schemas | **15 input + 2 representative output schema checks passed** |
| PowerShell build/runner/CI syntax | **5 scripts parsed without errors** |
| Existing `test-instrument-sequence.ps1` | InstrumentSequenceAnalyzer and InstrumentFeedCoverageAnalyzer checks passed, exit 0 |
| Existing `test-mapping-registry.ps1` | DatedInstrumentMappingRegistry checks passed, exit 0 |
| Existing packaged `raw-capture-verify` | 3 events, 1 sequence stream, 0 sequence gaps, 0 nonincreasing transitions, exit 0 |
| Existing packaged `feed-lifecycle-report` | 5 events, 1 connection, 0 transition anomalies, 1 active subscription, exit 0 |
| Existing packaged `feed-capture-reconcile` | 3 matched messages, 1 acknowledged interval, 0 empty intervals or timeline anomalies, exit 0 |
| Full source Java 21 build | Passed; exact manifest identity checked |
| `git diff --check` | Passed before implementation commit and final evidence commit |

The acceptance harness also invokes original raw-capture verification, lifecycle analysis, and reconciliation APIs across its synthetic diagnostic cases. It checks whole/partial/leading/trailing gaps, closures/pauses/session end, disconnect/reconnect, absent/rejected subscriptions, sequence anomalies/reset policies, separate clock roles and exact skew/stale boundaries, heartbeat expectations, no-message/connection-only cases, unknown tokens/mapping changes, malformed journals/source mismatches, missing/ambiguous policy, tampering (including rehashed report edits), multiple journal parts/instruments, row ordering, locale/timezone independence, and repeated identical bytes. Rejected inputs publish no report.

The complete pre-existing CI suite and hosted GitHub Actions were **not** run for this branch. The newly integrated checks and relevant regressions above were run locally. There was no push, merge, network feed access, data acquisition, broker access, or credential use. Initial sandboxed test compilation encountered Java filesystem/classpath access denial; rerunning the authorized local validation outside that sandbox resolved it. No failing acceptance checks remain.

## JDK and package identity

Microsoft OpenJDK **21.0.13.0.2**, runtime **21.0.13.0.2+1-LTS**, vendor build **Microsoft-15780113**, Windows amd64; `javac 21.0.13.0.2`.

Source compilation and production build used `C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot`. Packaged/drill scripts used the existing `JAVA_HOME` installation `C:\Users\prash\AppData\Local\Programs\Microsoft\OpenJDK21\jdk-21.0.13.0.2+1`, with the same runtime/compiler identity. Cross-mode artifact equality confirms identical production class fingerprints.

- JAR SHA-256: `e8ace5ff8a98a57676b91c63bd098624af6143791430f2548125dac6d863568e`
- Audit production-class fingerprint: `afb1cf1fc1c34710156ec4196b2dc86541940bda00d31a8c538958aafd36757b`
- JAR path: `C:\Users\prash\AppData\Local\Temp\feed-audit-validation-20261107\package\intra-paper-system.jar`

## Retained evidence

Committed concise evidence is in [feed-audit-2026-11-07](feed-audit-2026-11-07/): source/packaged/drill logs, dedicated runner output, existing feed CLI and regression logs, cross-mode comparison, JDK identity, exact source/example/package hashes, and five example reports with their artifact manifests. The five reports can be reverified against their matching `Intra-Paper-System/examples/feed-audit/inputs/<case>/evidence.json` and the tested JAR. `evidence-sha256.txt` binds the retained evidence files (excluding itself).

Retained text logs and hash inventories use LF newlines for clean Windows Git diffs. Report/artifact JSON bytes are copied unchanged. All retained evidence bytes, including the local attributes file, are pinned against checkout newline conversion.

Full generated inputs, classes, repeated reports, and logs remain at:

```text
C:\Users\prash\AppData\Local\Temp\feed-audit-validation-20261107\
  source\0690e95e777245c0b20c1e091fd1e0b6\
  packaged\d3776a84dc864d4c8f616528176d55bc\
  drill\23045fe019d849279af62cba6cb6a1c3\
  package\intra-paper-system.jar
```

See the [operating guide](../../Intra-Paper-System/docs/FEED-COMPLETENESS-GAP-AUDIT.md) for schemas, fields, interval rules, threshold/reset semantics, supported decoders, commands, report reason codes, and limitations.

## Remaining limits

Every source, schedule, threshold, identity, mapping, and decoder declaration remains caller-supplied and unauthenticated. The v1 decoders support declared audit JSON envelopes and existing canonical quote payloads; arbitrary vendor packets need a future reviewed offline decoder. Hashes and journal chains cannot prove original capture coverage or authenticity. Reports do not repair data, synthesize bars, forward-fill quotes, infer quiet markets, prove packet loss, or establish exchange/vendor guarantees. PASS means only no configured anomaly detected. Atomic publication establishes visibility, not universal power-loss durability. Inputs and rows must fit available local memory/disk. **G2/G4 remain open** until the relevant real evidence and independent review exist.
