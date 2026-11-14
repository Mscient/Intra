# Offline correctness verification — 2026-11-14

Implementation and tests are complete in isolated worktree `offline-correctness-20261114`, branch `codex/offline-correctness-20261114`, seeded from the dirty checkout at HEAD `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`. Another task was editing the original checkout, so this increment does not overwrite or merge into that live working tree. `review-package-final/correctness-increment.patch` contains only this increment relative to the preserved dirty baseline. The original checkout's later concurrent state is not covered by these test results.

Before editing, Git status, tracked/staged diffs, six recent commits, build/CI scripts, executable tests, and the `RunIdentity`, `ValidatedDataset`, and `BacktestEngine` contracts were inspected. A byte snapshot of 532 existing modified/untracked project files plus the tracked patch and status was saved in `../verification-20261114-preserved`. All inherited additions remain present in the isolated project; only the files listed below were changed by this increment. No reset, clean, merge, or unrelated reformat was performed.

## Files changed

Paths below are relative to this worktree; the source prefix is `Intra-Paper-System/src/`.

| Files | Change and reason |
| --- | --- |
| `main/java/com/mscient/intrapaper/simulation/RunIdentity.java` | Shared canonical admitted-input map and explicit identity-format declaration; preserves the baseline V12/V13 encoding |
| `main/java/com/mscient/intrapaper/simulation/RunArtifactWriter.java` | Reject incomplete inputs before publication; persist identity format and complete input map in manifest schema 15 |
| `main/java/com/mscient/intrapaper/data/ValidatedDataset.java` | Explicit simulation loader/readiness guard while retaining diagnostic inspection and gap counts |
| `main/java/com/mscient/intrapaper/simulation/BacktestEngine.java` | Use the shared admission boundary before strategy execution/recovery |
| `main/java/com/mscient/intrapaper/evaluation/EvaluationPartitionReport.java` | Reject incomplete inputs before partition calculations |
| `main/java/com/mscient/intrapaper/cli/IntraPaperCli.java` | Print diagnostic admission/override details and reject incomplete ordinary runs early |
| `main/java/com/mscient/intrapaper/marketdata/QuoteJournalReader.java`, `QuoteJournalWriter.java` | Verify recovery through the already locked handle, fixing Windows' second-handle lock rejection without unlocking the journal |
| `main/java/com/mscient/intrapaper/simulation/BacktestCheckpoint.java`, `BacktestCheckpointStore.java` | Schema-2 deltas retain changed existing orders and restore by stable ID, fixing partial IOC checkpoint recovery |
| `test/java/com/mscient/intrapaper/simulation/SingleContractAdmissionTest.java` | 28 synthetic identity, saved replay, diagnostic and blocked simulation/scoring regressions |
| `test/java/com/mscient/intrapaper/simulation/OfflineCorePlumbingTest.java` | Expanded to 514 checks: partial fee/basis/recovery/artifact/OMS coverage, stale journal anchors and exclusive ownership; Windows-compatible corruption check |
| `Intra-Paper-System/test-offline.mjs` | Execute every Java test/fixture main and pricing harness, fail on unlisted mains, expose failures and source/package hashes, distinguish focused coverage, preserve fresh output |
| `.github/workflows/java21-build.yml` | Correct the step label to describe all Java entry points; inherited workflow/CI runner logic is preserved |
| `README.md`, `Intra-Paper-System/README.md` | State the integrated Java implementation and distinguish historical Python/CI observations |
| `Intra-Paper-System/IMPLEMENTATION-TRACKER.md`, `research/systems/IMPLEMENTATION-TRACKER.md` | Current local verification and CI scope; dated observations remain historical; external gates remain open |
| `Intra-Paper-System/docs/OPERATING-GUIDE.md` | Correct full-fill claims, describe whole-lot partial IOC execution and diagnostic admission |
| `Intra-Paper-System/docs/WINDOWS-JAVA21-CI.md` | Replace the stale seven-runner inventory with the actual comprehensive Java runner and explicit QuantLib/hosted-CI limits |
| `Intra-Paper-System/docs/OFFLINE-VERIFICATION.md`, this report | Commands, compatibility, evidence scope and exact verification results |
| `research/systems/NIFTY-INTRADAY-PAPER-SYSTEM-DESIGN.md`, `PAPER-TRADING-BACKTESTING-ROADMAP.md`, `ISOLATED-TESTING-PLAN.md` | Replace separate Python/lab runtime descriptions with integrated Java research/replay and per-strategy run isolation |

There are 25 implementation/test/documentation files in the incremental patch. Build scripts, permission/operations/settlement additions, and other pre-existing work are inherited, not claimed as new work here. `package-delta.mjs` is a local delivery helper outside the runtime; its preservation/source checks and patch hashes are recorded in `review-package-final/preservation-and-changes.json`. It verified that 518 carried files outside this increment remain byte-identical and that every final tested production/test source still matches its recorded hash.

Integration check: `git apply --reverse --check` passed against the isolated result. A read-only `git apply --check` against the original checkout returned exit 1 because concurrent changes overlap 14 of the patched paths (CI, READMEs/tracker, operating guide, runner, quote reader/writer, engine, identity/writer, core tests, design and roadmap). Nothing was applied to the original tree. The patch assumes the preserved dirty baseline and needs reconciliation with those newer overlapping edits; it must not be forced over them. The independently verified worktree remains available for review and integration.

## Identity and admission

Every admitted dataset byte input participates in the sorted, length-prefixed identity map, including market rules, optional costs/margins/settlement/quotes/valuations, and permission evidence. Existing config/build/strategy encodings remain; trial registration binds card/split/parameter hashes. Changing market-rule bytes with unchanged properties changes the ID and rejects replay against the old saved run. Identical reloaded inputs and differing map insertion order reproduce the same ID.

`load(...)` supports inspection within an explicit diagnostic gap allowance. `loadForSimulation(...)`/`requireSimulationReady(...)` require complete supplied sessions, zero missing intervals/anomalies, and matching observed/expected bars. Engine, CLI, partition reporting, and publication use that boundary. Errors preserve gap counts and both configured quality settings. Strategy sentinels prove rejection precedes strategy access; checkpointed and ordinary engine overloads are blocked. No bars are fabricated or interpolated.

Compatibility: relative to HEAD, the preserved baseline intentionally changes identity V10/V11 to V12/V13. New manifests use schema 15. Old artifacts remain eligible for supported output-only hash verification, but exact replay requires the matching identity/output build. New checkpoint payloads use schema 2; schema-1 reading remains supported, but missing historical updates cannot be fabricated, and old binaries reject schema 2. See [the detailed contract](OFFLINE-VERIFICATION.md).

## Exact commands and results

JDK: Microsoft `javac 21.0.13.0.2`, runtime `21.0.13.0.2+1-LTS`, Windows 11/NTFS. Build is direct Java 21 `javac`/`jar`; tests are executable Java mains, with no Maven/Gradle or external Java test framework. Node orchestrates the full offline suite. All runs use synthetic or illustrative mathematical fixtures.

From the original checkout:

```powershell
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot" --output-root verification-20261114-baseline
```

Baseline result: **exit 1**. Production/test compilation passed; 22 listed entry points passed and `OfflineCorePlumbingTest` failed at `QuoteJournalWriter.resumeInternal` with `IOException: The process cannot access the file because another process has locked a portion of the file`. Packaging was skipped. This baseline runner omitted the standalone settlement-fixture main and Java pricing harness, so its result was not comprehensive. The actual baseline `build.ps1`, with the installed JDK and `INTRA_PAPER_BUILD_DIR=verification-20261114-production-baseline`, independently passed (**exit 0**).

From the isolated worktree:

```powershell
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot" --output-root verification-focused-2 --suite simulation.SingleContractAdmissionTest
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot" --output-root vcore2 --suite simulation.OfflineCorePlumbingTest
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot" --output-root vfinal
```

Results: focused admission **exit 0 / 28 checks**; expanded core **exit 0 / 514 checks**; final default runner **exit 0 / Overall PASS**. All **26 Java entry points** passed, plus production/test compile, production packaging, actual JAR manifest extraction, and build-identity verification. No listed Java suite remained `NOT_RUN`. The JAR correctly records `Build-Commit: unknown-dirty`; the report records the HEAD and exact production/test source hashes rather than claiming this uncommitted build is the HEAD commit.

Representative suite results: gateway durability **447**, process recovery **728 / 308 child JVMs / 24 forced terminations**, portfolio risk replay **84**, risk audit **79**, settlement-risk fixtures **274**, settlement audit **88**, trial evaluation **161**, holdout controller **158**, incident drills **198**, daily evidence **455**, permission preflight **300**, external-ledger reconciliation **262** checks. Accounting, replay, costs, mapping, synchronization, sequence/coverage and artifact suites also passed. Java pricing passed **22 R11 cases, 16 boundaries and 5,805 no-arbitrage checks**.

The expanded core first failed (**exit 1**, `vcore-final`) with `final order status/event mismatch for O0000002`; the new schema-2 order-update delta fixed the reproduced partial recovery error. The final suite includes this regression.

The actual final production script was also executed:

```powershell
rtk proxy pwsh -NoProfile -Command '$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"; $env:INTRA_PAPER_BUILD_DIR = Join-Path (Get-Location) "vbuild2"; & ./Intra-Paper-System/build.ps1'
```

Result: **exit 0**, `vbuild2/intra-paper-system.jar`, SHA-256 `8a446f6b30ee6e8542a69f2a1243057c246683fd254876c2273bbcb88ac116de`.

Packaged CLI checks used that JAR with the final admission fixture:

```powershell
& 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot\bin\java.exe' -jar vbuild2/intra-paper-system.jar validate vfinal/admission/synthetic-futures-bars.csv vfinal/admission/synthetic-run.properties
& 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot\bin\java.exe' -jar vbuild2/intra-paper-system.jar run vfinal/admission/synthetic-futures-bars.csv vfinal/admission/synthetic-run.properties
```

Diagnostic result: **exit 0**, 29 observed/30 expected bars, 0/1 complete sessions, one missing interval/anomaly, override true/cap 1, `DIAGNOSTIC_INSPECTION_ONLY`. Ordinary run: **expected exit 2**, `INCOMPLETE_DATA`, before trial resolution/checkpoint/publication.

Fresh-output rejection was tested by rerunning the admission command with `--output-root vfull2`: **expected exit 1**, `EEXIST`, preserving the previous full-run results. Earlier runner failures likewise printed errors, recorded failed exits, and skipped packaging. Initial sandboxed shell reads stalled; a later sandboxed `verification-full-20261114` attempt could not resolve compiled classes during test compilation and returned exit 1. The working final run used the installed JDK outside that sandbox and a short output path. No failed attempt is counted as a pass.

## Remaining verification and external gates

All available Java entry points were run. The separate QuantLib-Python 1.44 comparison was **not rerun**: no Python/py executable was found on the inspected PATH and the documented temporary QuantLib environment was absent. Its historical evidence remains dated; no dependency was downloaded. Current hosted CI and a clean expected-commit run are unverified because this is an uncommitted isolated snapshot. This is complete Java suite execution, not comprehensive external validation or exhaustive branch coverage; optional sidecars, raw/lifecycle recovery, actual full-disk/power-loss behavior, and connected paper operation still need focused/operating evidence.

External gates remain open for lawful data rights/retention and intended use, authorized multi-expiry dated history, current rule/calendar/settlement authenticity, sourced all-in costs and margins, fill/latency/capacity calibration, independent custody/review, operator authority/risk budgets, and actual forward-paper operating/reconciliation/pre-open/drill evidence. Synthetic fixtures cannot close those gates. No network, account, credential, market-data capture/download, broker request, or order-routing access occurred in this increment.
