# Synthetic daily operations evidence verification

- **Session:** `8a1c6f71-5304-4908-9851-d1d9591d2a71`.
- **Date:** 2026-11-07, Asia/Kolkata; work began on 2026-11-06.
- **Baseline HEAD:** `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090` with existing risk-gateway process, Windows CI and research/documentation edits in the working tree. Those edits were preserved.
- **Scope:** local synthetic evidence only. No broker/feed connection, credentials, licensed market data or actual operational drill was used. G4 remains open.

## Delivered behavior

Added the separate `DailyOpsEvidenceCli check <session-evidence.json> [<report.json>]` entrypoint, its PowerShell wrapper, a nine-case synthetic drill, and focused Java 21 tests. The checker reconciles required pre-session checks and timestamps; input artifact observations, declared coverage and stale handling; gateway state/reservations; session/run identities, artifact hashes and original/replayed bytes; applicable signed settlement and dated cost-component coverage; ending positions/P&L/fees/cash; and operator acknowledgements with incident/stop registers.

Missing/null/blank values, absent files, partial declarations and missing records remain unknown and produce INCOMPLETE. Known contradictions produce FAIL; FAIL takes precedence while every finding retains its status. Empty logs alone cannot establish healthy operation or zero activity. Explicit zero snapshots, completeness/count declarations and reviewed no-activity attestations are required. Feed evidence remains nonempty. Case aliases/hard links cannot manufacture separate replay evidence; reservations cannot bypass a newer pause in a complete input timeline. Unknown window boundaries and partial gate timelines cannot manufacture a coverage gap or a healthy gate.

Every report and every domain result includes exact consumed-input SHA-256 hashes and the statement **caller-supplied diagnostic evidence**. Missing hashes are null. Reports have no checker clock, random ID, absolute location or file metadata; `g4_closed` and `operational_readiness_eligible` are false. Preparing normalized records and matching supplied replay copies does not authenticate their source or perform an independent replay.

The [operating contract](../../Intra-Paper-System/docs/DAILY-OPS-EVIDENCE.md) contains the schema, statuses, commands, required timestamps and actual-drill distinction. The [fixtures](../../Intra-Paper-System/examples/daily-ops-evidence/README.md) explicitly use three-minute synthetic intervals. Both implementation trackers retain G4 as **Not started**.

## Java 21 validation

Measured runtime/compiler: Microsoft OpenJDK **21.0.13.0.2+1-LTS**, Windows / amd64. The installed JDK was accessed through approved sandbox escalation; no download occurred.

1. `test-daily-ops-evidence.ps1 -RunRelevantChecks` passed the initial 433 focused assertions against source and package, all nine expected fixture outcomes, and all existing relevant suites listed below.
2. Final checker-only refinements for aliasing, current gate observations and timestamp/unknown-window consistency passed **455 assertions in five synthetic groups** against source and rebuilt package using `test-daily-ops-evidence.ps1 -BuildPackage`.
3. All nine final report SHA-256 values matched across the source test output, packaged test output, and separate CLI drill processes. Each fixture also passed repeated in-process byte comparisons and relocation/file-metadata checks; locale/timezone independence was checked. Caller fixture contents remained unchanged.
4. Owned-file whitespace, PowerShell syntax and LF fixture attributes passed. Production JAR contains both standalone checker/CLI entrypoints.

| Existing runner | Observed result |
|---|---|
| Risk gateway durability | 447 checks / 13 groups passed. |
| Risk gateway subprocess | 728 checks / 5 groups / 308 child JVMs / 24 forced terminations passed. This does not prove power-loss durability. |
| Operational kill controller | Focused K0–K3 checks passed. |
| Instrument sequence | Sequence and instrument feed-coverage focused checks passed. |
| Portfolio risk replay | 84 risk checks plus replay engine, execution-cost schedule, v3 artifact/determinism and signed settlement checks passed. |
| Portfolio accounting | Signed accounting checks passed. |
| Portfolio risk audit | 79 audit and 274 settlement-risk fixture checks passed. |
| Portfolio settlement audit | 88 focused checks passed. |
| Portfolio trial evaluation | 161 focused checks plus replay/cost/artifact checks passed. |

Existing production components were unchanged. Their regressions ran before the final refinements confined to the new evidence package; the final package was rebuilt and its own acceptance suite rerun afterward. No hosted CI result is claimed for this work.

## Exact evidence

The final local JAR SHA-256 is `41c1eadff3925db2c33b48aeceb79297d492c74620561b552bbe35379a4bd92d`. Its manifest records `Build-Commit: unknown-dirty`, `Created-By: 21.0.13.0.2 (Microsoft)` and the existing `IntraPaperCli` main class. This is a working-tree package, not a clean-commit artifact.

[Portable evidence](daily-ops-evidence-2026-11-07/) contains the nine canonical diagnostic reports, their SHA-256 list, source/fixture hashes and package/JDK identity fields. The expected outcomes are three PASS, four FAIL and two INCOMPLETE. The halted PASS records an acknowledged, flat synthetic stop and does not count as a complete scheduled operational session.

Local ignored outputs are retained under `Intra-Paper-System/build/daily-ops-evidence-checks/a0a3bce3e16643d093d14eb0903ee73d/` for existing-suite logs and `Intra-Paper-System/build/daily-ops-evidence-checks/d393c3658dee4cf5a01dbea93fca81f7/` for final focused reports, package and drill. These directories are local evidence, not repository/cloud retention guarantees.

## Remaining operational evidence

An actual operational drill must exercise controls and operator response in the intended environment, with measured clocks, authorized feeds, independently observed risk/order/account reconciliation, completed stop paths and reviewed incidents. G4 still requires 60 complete forward-paper sessions, 20 consecutive passing pre-open checks, daily reconciliation, successful actual drills and no open severe incident. These synthetic diagnostic reports close none of those requirements and authorize no live route.

Changes remain in the working tree, uncommitted/unpushed. Book research/access status is unchanged. Next work is independently observed operational evidence after the existing upstream gates are satisfied.
