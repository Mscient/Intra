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

Implementation and tested Build-Commit:
`53a65c091eb1bcd13d4406f6e0bcf07e41776809`.
The checkout was clean before the driver and before production packaging. The final
record commit changes documentation, evidence and fixture line-ending attributes;
Java sources and fixture bytes remain those tested here. Hosted CI has not run for
this branch. Nothing was pushed or merged.

Exact successful command from the isolated repository root:

```powershell
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot'
./.github/scripts/test-offline-ci.ps1 -OutputRoot C:/Users/prash/AppData/Local/Temp/ahi-ci-53a65c0-run2 -ExpectedCommit 53a65c091eb1bcd13d4406f6e0bcf07e41776809
```

**PASS: all 21 source suites and all 17 build/package/drill/reproduction/manifest
stages (38 driver stages).** The exact driver at the tested commit contains all
child commands and arguments. [Retained logs](historical-ingestion-2026-11-09/ci-local/)
include per-stage script identities/results and
[environment](historical-ingestion-2026-11-09/ci-local/environment.log).
The [summary](historical-ingestion-2026-11-09/ci-local/summary.log) SHA-256 is
`8021b0d9957f9cf09289b4381e4f611386e72f760d6efa892d66fcc476d56301`.
The [log hash inventory](historical-ingestion-2026-11-09/ci-log-sha256.csv) SHA-256 is
`76df10f10a0f7fd68f8a0b8a14c1ba4e5c827796da953def5763e30d23e1c822`.
Git attributes preserve the exact log bytes and LF fixture bytes on checkout.

| Suite | Observed checks and result |
| --- | --- |
| Historical ingestion | 251 source + 251 production-JAR checks PASS, including CLI child JVMs |
| Risk gateway | 447 checks / 13 groups PASS |
| Risk gateway process | 728 checks / 308 child JVMs / 24 forced terminations PASS |
| Portfolio replay and artifact verifier | Engine, execution cost, v3 publication/tampering/determinism and signed settlement checks PASS; these harnesses emit no numeric count |
| Portfolio risk replay | 84 checks and replay/artifact regressions PASS |
| Portfolio accounting | PASS; harness emits no numeric count |
| Portfolio risk audit | 79 audit + 274 settlement-risk checks PASS |
| Portfolio settlement audit | 88 checks PASS |
| Portfolio evaluation | 165 checks + 30,019 HAC checks + replay/artifact regressions PASS |
| Daily operations evidence | 455 source + 455 packaged assertions, nine-case packaged drill PASS |
| Intended-use preflight | 300 source + 300 packaged checks; ten-case packaged drill PASS |
| Permission coverage | 102 scenarios / 1,648 assertions each for source, standalone JAR and production JAR PASS |
| Incident drills | 198 source + 198 packaged checks and nine scenarios each PASS |
| Feed audit | 507 source + 507 packaged checks; 557 packaged-drill checks PASS |
| G0 charter | 551 source + 551 packaged checks and cross-JVM reconstruction PASS; synthetic attestations only |
| Hypothesis cards | 168 source + 168 packaged checks PASS |
| Mapping, instrument sequence/feed coverage, synchronized bars, kill controller | All focused runners PASS; no numeric counts emitted |
| G2 contracts | 365 source + 365 packaged checks PASS |
| G2 reproduction | 42 complete artifact files plus normalized journal byte-identical across separate JVMs in UTC/en-US and Pacific/Apia/tr-TR |
| Production/standalone builds and manifest | PASS; production Build-Commit equals the tested implementation commit |

The first CI launch was an operator-command error: it used output root
`C:/Users/prash/AppData/Local/Temp/ahi-ci-53a65c0` and the incorrect
`-ExpectedCommit 53a65c0f585377954c4c7d09e3d556a748c94d1f` with the same Java setting
and driver. It exited 1 at the commit-identity guard with every test NOT_RUN.
The corrected full run above exited 0. This failure is not counted as a passing run.

## Exact artifacts and entrypoint smoke test

Retained production JAR:
`C:/Users/prash/AppData/Local/Temp/ahi-ci-53a65c0-run2/package/intra-paper-system.jar`.

| Identity | SHA-256 |
| --- | --- |
| Production JAR | `f34ddf749618eeefad48c8fbfd87b1709e70f8e35bcc0ab322dbd3bbf3548266` |
| Compiled main-class tree | `a4e08b2901e995c831b03d7adbe9f052a68e1a2947cf8b55b9e48909be0a9d48` |
| Packaged ingestion manifest | `d3846b5e8dfccb076f6e064bccc83b164144405fe2cf396d77532141b768d6d8` |
| Synthetic raw candles | `909fc254bf73f219053840837f16a0920f155dd6eac1cd42769dc9aa34ccc223` |
| Normalized CSV | `41c2b890b3c0d8c998851f12ed7c2dcd270ef96ad9b6797935d3d069088787c7` |
| Request | `b88715abd9589258a4f47c71748c040f8805fd58b0ae6d6b57bd081c63e5a994` |
| Policy | `4f6143621e726321e50f948dd8f7960127c6bf1379294cb523d3091f7725fc23` |
| Dated mapping | `e70e7ceb88cfb49958b5de1cbb6b55ebb1bd6ae9f67ffb2f30e35a324a170273` |
| Instrument master | `f5acdb9e3a4472b06f6ee437c3f6bf10753c29e824e6c6e51d91bffb29c16b07` |
| Session calendar | `92f056fb2358e337f086bae4b47ea134a5e697d9a3073c29d0ab0c5e5d3fae5a` |

Both successful source runs reproduced the same manifest hash recorded above. The
packaged manifest differs solely as intended by the JAR build identity; normalized
CSV bytes agree. Each focused harness also compares entire bundles across repeats,
locales and independent CLI processes and rejects changed references/forged hashes.

Additional exact PowerShell wrapper smoke commands, from `Intra-Paper-System` with
the Java setting above (the parent directory was created first):

```powershell
New-Item -ItemType Directory -Force C:/Users/prash/AppData/Local/Temp/ahi-cli-smoke | Out-Null
./historical-ingestion.ps1 -JarPath C:/Users/prash/AppData/Local/Temp/ahi-ci-53a65c0-run2/package/intra-paper-system.jar plan --request ./examples/historical-ingestion/request.json --policy ./examples/historical-ingestion/policy.json --mapping ./examples/historical-ingestion/mapping.csv --out C:/Users/prash/AppData/Local/Temp/ahi-cli-smoke/plan-v1
./historical-ingestion.ps1 -JarPath C:/Users/prash/AppData/Local/Temp/ahi-ci-53a65c0-run2/package/intra-paper-system.jar verify --operation plan --request ./examples/historical-ingestion/request.json --policy ./examples/historical-ingestion/policy.json --mapping ./examples/historical-ingestion/mapping.csv --out C:/Users/prash/AppData/Local/Temp/ahi-cli-smoke/plan-v1
```

Both exited 0: PASS_PLANNED and VERIFIED status=PASS_PLANNED. Plan manifest SHA-256:
`0e56bb2c971c95da6cad733ef78f1b850faeadd4f219f2eb27a2f9a18b1aa9ad`.

Limits remain: Windows/NTFS local-file validation, trusted stable local inputs and
retained executable, no proof of power-loss durability or hostile-filesystem safety,
no authenticated data/calendar/permission source, no provider acquisition or cloud
rights. A proposed 20-day retention remains OWNER_PROPOSED_UNVERIFIED for provider
requests. Ingestion of ANGEL_ONE stays denied; synthetic results cannot promote G0,
G2 or production eligibility.
