# Historical ingestion implementation and validation record

Date: 2026-11-09. Branch: `codex/angelone-historical-ingestion`.
Integration base: `7d6f6c56424b4e50903121eaf8ee491a9ce15607`, confirmed by
`git ls-remote origin refs/heads/main` before creating the isolated worktree.
Starting local main `2b7fe0a` and its unrelated changes were left intact.

This record covers software diagnostics using synthetic fixtures only. It does not
establish Angel One rights, authentic source/calendar evidence, expired-contract
availability, cloud rights or production readiness. G0 OPEN/G2 INCOMPLETE remain.
No Angel One connection, market acquisition, live trading, push or merge occurred.

## Implementation and focused checks

See the [guide](../../Intra-Paper-System/docs/HISTORICAL-INGESTION.md) for schemas,
CLI, permission boundary, deterministic contract and operational limits. The existing
instrument-master reader gained an overload accepting explicit local snapshot metadata;
other replay behavior is unchanged. The CI-local runner includes the focused source
suite and the actual production-JAR entrypoint suite.

Java: Microsoft `21.0.13.0.2`, runtime `21.0.13.0.2+1-LTS`,
IMPLEMENTOR_VERSION `Microsoft-15780113`, Windows amd64, `javac 21.0.13.0.2`.
JAVA_HOME: `C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot`.

Exact successful pre-commit source command, from the isolated `Intra-Paper-System`:

```powershell
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot'
./test-historical-ingestion.ps1 -OutputRoot C:/Users/prash/AppData/Local/Temp/ahi-source-tests
```

Result: **251 checks PASS**, retained in
`C:/Users/prash/AppData/Local/Temp/ahi-source-tests/76493fc18de845e6b165446f34f60041/checks.log`.
Source-classes reference manifest SHA-256:
`9c7b3c0fc000d7c17a169811b6773a8bf079632f6e315f14332a795205dfb53f`.
Normalized synthetic CSV SHA-256:
`41c2b890b3c0d8c998851f12ed7c2dcd270ef96ad9b6797935d3d069088787c7`.
The focused source run uses SOURCE_CLASSES identity; a clean JAR intentionally adds
its actual Build-Commit, so its manifest identity differs while CSV bytes agree.

Checks exercise valid explicit offsets/UTC, existing canonical CSV reader consumption,
null versus zero volume, malformed JSON/UTF-8/BOM, every missing request field, numeric
limits, impossible OHLC/ticks, negative/fractional/overflow volume, duplicate/conflicting
timestamps, row order, range/window boundaries, missing/stale/ambiguous references,
session boundaries/grids/gaps, provider permission denial before raw access, invalid
and expired/wrong-source evidence, tampering including recomputed fake hashes, changed
reference inputs, locale/timezone and separate-JVM reproduction, immutable output,
staged failure cleanup, secret field exclusion and CLI exits.

Development failures were corrected: test generic wildcard types; a date parsing
exception that escaped the diagnostic handler; long Windows classpath access in a
deeply nested build directory. The sandbox also produced a javac JAR-close
AccessDeniedException. Successful validation uses a short external temporary output
root and authorized execution outside that sandbox. No test failure is represented
as a successful run.

## Committed full-matrix validation

The implementation commit is made before running the clean-checkout CI-local driver.
Its exact commit, commands, suite counts, package hashes and outcomes will be appended
in the validation-record commit after that run. Hosted CI has not run for this branch.
