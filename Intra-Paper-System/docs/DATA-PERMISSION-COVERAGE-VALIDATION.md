# Data-permission coverage completion record

Date: 2026-11-07. Branch: `codex/data-permission-coverage-resumed-20261107`.
Base: freshly fetched `origin/main`,
`e858e1fa0d9f659c88b023ce363e5612fcbda6f7` (not an assumed SHA).
The implementation commit is the commit adding this file; see `git log -1 --format=%H -- docs/DATA-PERMISSION-COVERAGE-VALIDATION.md`.

The partial feature was copied from the existing
`codex/data-permission-coverage-20261107` worktree into a new isolated worktree.
The source worktree, original dirty main worktree, existing branches and all other
worktrees were preserved. Only new feature files are included. No shared CLI,
replay engine, evaluator, broker, existing permission record or research record
was changed. Nothing was pushed or merged.

## JDK and validation

```text
openjdk version "21.0.13.0.2" 2026-10-22 LTS
OpenJDK Runtime Environment Microsoft-15780113 (build 21.0.13.0.2+1-LTS)
OpenJDK 64-Bit Server VM Microsoft-15780113 (build 21.0.13.0.2+1-LTS, mixed mode, sharing)
javac 21.0.13.0.2
```

Compiler flags: `--release 21 -encoding UTF-8`. Installation used:
`C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot`.

| Validation | Exact result |
| --- | --- |
| `./build-data-permission-coverage.ps1` | Standalone Java 21 compilation and executable JAR packaging passed |
| `./test-data-permission-coverage.ps1 -JarPath <standalone-jar>` | 102 scenarios, 1648 assertions passed; exit 0 |
| `./test-data-permission-coverage.ps1 -RunRelevantChecks` | All main sources and all test sources compiled; full project JAR packaged; exit 0 |
| Focused suite against full project classes | 102 scenarios, 1648 assertions passed |
| Existing `SynchronizedBarFrameBuilderTest` | Checks passed (six test methods; suite prints no assertion count) |
| Existing `PortfolioReplayArtifactVerifierTest` | v3 publication, tamper, signed partial, and deterministic replay checks passed |
| Existing `PortfolioRiskAuditTest` | 79 focused checks passed |
| Existing `PortfolioSettlementAuditTest` | 88 focused checks passed |
| Existing `DailyOpsEvidenceCheckerTest` | 455 assertions in 5 synthetic groups passed |
| Dedicated PowerShell runner `check` on bundled example | SCOPE_MATCH, exit 0, both approval/eligibility flags false |
| Dedicated PowerShell runner `verify` on that report | VERIFIED SCOPE_MATCH, exit 0, evidence_authenticated=false |

The synthetic smoke report's canonical hash is
`3703d9bde44f88ef8cee02cf656e95d49b96fa95bf6f6813c5cf68775a9961e2`.
Schema export equality, incomplete-report verification, raw/canonical input
identities, incorrect report hashes, rehashed decision/provenance forgeries,
manifest/data/evidence tampering, repeat-byte identity, row ordering, and mandatory
false labels are included in the focused suite. Determinism includes three
locales times four timezones and separate JVMs using en-US/UTC and tr-TR/Pacific/Apia.

Logs and synthetic output remain in ignored build directories. The completed
standalone run is `build/data-permission-coverage-checks/6a982807adf7418f9e13ffacce469af1`;
the completed full-project/regression run is
`build/data-permission-coverage-checks/cd79a6b6d40a4c6a866f8d5362a3f536`.
The standalone JAR is under
`build/data-permission-coverage/e34bf541479c40069aae3bc565289bdb`.

Initial sandboxed Java compilation encountered Windows access restrictions
(including a compiler AccessDeniedException opening a generated JAR and inability
to resolve generated class files). The focused and full-regression commands were
rerun outside the sandbox with the same JDK and passed. No application permission
checks were disabled. An intermediate Java syntax error was fixed before the
successful validation runs.

Final delivery checks: run `git diff --cached --check` before the implementation
commit and `git diff --check` plus `git status --porcelain` afterwards; the final
completion message records their outcome and exact commit SHA.

## Added files

All paths below are relative to `Intra-Paper-System`:

```text
build-data-permission-coverage.ps1
data-permission-coverage.ps1
test-data-permission-coverage.ps1
docs/DATA-PERMISSION-COVERAGE.md
docs/DATA-PERMISSION-COVERAGE-VALIDATION.md
src/main/java/com/mscient/intrapaper/permission/coverage/CoverageJson.java
src/main/java/com/mscient/intrapaper/permission/coverage/CoverageSchemas.java
src/main/java/com/mscient/intrapaper/permission/coverage/PermissionCoverage.java
src/main/java/com/mscient/intrapaper/permission/coverage/PermissionCoverageArtifacts.java
src/main/java/com/mscient/intrapaper/permission/coverage/PermissionCoverageCli.java
src/test/java/com/mscient/intrapaper/permission/coverage/PermissionCoverageTest.java
schemas/data-permission-coverage/.gitattributes
schemas/data-permission-coverage/dataset.schema.json
schemas/data-permission-coverage/intent.schema.json
schemas/data-permission-coverage/register.schema.json
schemas/data-permission-coverage/report.schema.json
examples/data-permission-coverage/.gitattributes
examples/data-permission-coverage/dataset.json
examples/data-permission-coverage/intent.json
examples/data-permission-coverage/register.json
examples/data-permission-coverage/synthetic-data.csv
examples/data-permission-coverage/synthetic-evidence.txt
```

## Remaining limits

See [the contract documentation](DATA-PERMISSION-COVERAGE.md) for exact matching,
retention, exception, overlap, supersession, size limits, and canonicalization
semantics. Only synthetic evidence was used. Nothing establishes legal validity,
reviewer authority, source authenticity, entitlement, actual dataset coverage, or
observed deletion. Hashed identifiers are not guaranteed anonymous. Byte identity
depends on the executable class identity; input row reordering changes raw hashes
while preserving comparison decisions and canonical input hashes.

Real permissions still require **owner-provided evidence and qualified review**.
Every report remains permission-unapproved and production-ineligible. This tool
does not close G2.
