# Windows Java 21 CI — local validation

Date: 2026-11-06, Asia/Kolkata. Session:
`9f48adb9-0344-44d9-b215-27c1ac43cec0`.

The [workflow](../../.github/workflows/java21-build.yml) and its
[operating guide](../../Intra-Paper-System/docs/WINDOWS-JAVA21-CI.md) now configure
Windows offline validation followed by production packaging, manifest revision
verification, and 14-day log/JAR artifact retention. This report records local
execution only. No workflow was pushed, no pull request was opened, and no hosted
GitHub Actions success is claimed.

## Isolation and JDK

Created a separate local clone from source baseline
`2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`, copied the six workflow/script/README/CI
guide files into it, and committed them on local branch `test/windows-java21-ci`.
The clean positive-test checkout revision was
`ed8cb1926bae212b4067648768528bc77d66e5fa`. The clone was moved to
`C:\Users\prash\AppData\Local\Temp\ci21-f8581a05` for short Windows paths.
These validation commits exist only in that disposable clone.

The pinned installer downloaded the Microsoft Windows x64 OpenJDK
**21.0.13.0.2 LTS** archive. Its SHA-256 matched the repository pin and Microsoft's
published checksum:
`c5706a9cf482ef1a0622a86eea5b13d557b07f04554572af061e2d02b58c0f99`.
Execution used that extracted JDK, not a floating or preinstalled substitute.
Logs recorded vendor `Microsoft`, compiler `javac 21.0.13.0.2`, runtime
`21.0.13.0.2+1-LTS`, and PowerShell `7.6.9` on Windows 11 amd64.

The initial deeply nested local copy exceeded Windows command-line/path limits.
The shorter sandboxed copy then failed compiler resource cleanup. The authorized
unsandboxed run passed, with a process-scoped Git `safe.directory` entry for the
isolated sandbox-owned clone. No global Git configuration was changed. These
local environment failures are not reported as successful runs or test failures
caused by the injected assertion.

The isolated copy did not contain `test-risk-gateway-process.ps1`. That separate
uncommitted subprocess harness was neither invoked nor incorporated into the CI
workflow. Existing unrelated working-tree changes were preserved.

## Positive and negative results

| Runner/check | Clean run | Deliberately failing run |
| --- | --- | --- |
| `test-risk-gateway.ps1` | PASS; 447 checks / 13 groups | PASS |
| `test-portfolio-replay-engine.ps1` | PASS; four entrypoints | PASS |
| `test-portfolio-risk-replay.ps1` | PASS; 84 risk-replay checks and shared regressions | PASS |
| `test-portfolio-accounting.ps1` | PASS | FAIL; child exit 1 |
| `test-portfolio-risk-audit.ps1` | PASS; 79 audit + 274 settlement checks | PASS |
| `test-portfolio-settlement-audit.ps1` | PASS; 88 checks | PASS |
| `test-portfolio-trial-evaluation.ps1` | PASS; 161 evaluation checks and shared regressions | PASS |
| Production JAR build | PASS | NOT_RUN |
| JAR manifest commit verification | PASS | NOT_RUN |
| CI runner process exit | **0** | **1** |

The positive JAR's `Build-Commit` was exactly
`ed8cb1926bae212b4067648768528bc77d66e5fa`, matching the checked-out `HEAD` and
the expected workflow revision supplied to the runner. Its SHA-256 was
`43a042e96e86b0d8b9a2738a14c07322aa36dc469e176a039fed79919990ba2e`.

For the negative run, inserted an assertion at the start of
`PortfolioAccountingLedgerTest.main` in a disposable committed revision
`a38bad5707aee3b11ff1a42d4e8b96d9c96437c6`. The log retained
`java.lang.AssertionError: intentional CI failure`, Java exit 1, runner exit 1,
and the CI failure summary. Other selected suites ran; packaging and manifest
verification were skipped, and no production JAR existed in the negative output
directory. Restored the test bytes in local revision
`008ddee`; its source tree matched the positive-test revision with no remaining
working-tree changes. The primary checkout's accounting test was never edited.

`actionlint 1.7.12` checked the workflow YAML, action configuration and expressions
with exit 0 and no findings. Both new PowerShell scripts passed the PowerShell
syntax parser. Full source compilation is not claimed as execution of every
repository test. Shared regression entrypoints repeat across some runners and
their counts are not summed into a unique-coverage claim.

## Retained local evidence and limits

Copied both runs' concise logs, step summaries, installer log, result record and
verified production JAR into ignored local output:
`Intra-Paper-System/build/windows-ci-validation/evidence/`.
There are **21 suite/build/environment/summary/error log files, 17,360 bytes** in
the two run-log directories. `sha256.txt` identifies every retained evidence
file. The JAR copy's SHA-256 was verified against the positive build.

- [Clean-run summary](../../Intra-Paper-System/build/windows-ci-validation/evidence/positive/logs/summary.log)
- [JAR manifest verification](../../Intra-Paper-System/build/windows-ci-validation/evidence/positive/logs/manifest-check.log)
- [Failing-run summary](../../Intra-Paper-System/build/windows-ci-validation/evidence/negative/logs/summary.log)
- [Injected assertion log](../../Intra-Paper-System/build/windows-ci-validation/evidence/negative/logs/portfolio-accounting.log)
- [Local result record](../../Intra-Paper-System/build/windows-ci-validation/evidence/results.txt)
- [Evidence hashes](../../Intra-Paper-System/build/windows-ci-validation/evidence/sha256.txt)

Local evidence is retained in the workspace; the workflow's 14-day policy applies
to future GitHub artifact uploads. Local execution does not verify hosted runner
provisioning or actual artifact uploads. A hosted acceptance run remains subject
to separate authorization to push a test branch and execute Actions.

Only repository synthetic fixtures were used. Public downloads were limited to
the pinned JDK and the local actionlint validator/checksums. No market dataset,
credentials, broker/network route, subprocess harness, statistical formula
change, statistical validation or performance claim was involved.

Sources: [Microsoft JDK downloads](https://learn.microsoft.com/en-us/java/openjdk/download),
[the versioned archive checksum](https://aka.ms/download-jdk/microsoft-jdk-21.0.13.0.2-windows-x64.zip.sha256sum.txt),
[setup-java Microsoft resolver](https://github.com/actions/setup-java/blob/v6/src/distributions/microsoft/installer.ts),
and [actionlint 1.7.12](https://github.com/rhysd/actionlint/releases/tag/v1.7.12).
