# Offline core run-identity regression verification

`OfflineCorePlumbingTest` now verifies that reversing insertion order in the manifest's input-hash map preserves the run ID, and that changing each admitted hash independently changes it. The existing synthetic fixture supplies `bars`, `instrument-master`, `session-calendar`, `market-rule-regimes`, and `cost-schedule` hashes.

The test copies the dataset and manifest, replacing only `inputFileSha256`. All other fields, including separately stored file hashes, configuration, build identity, and strategy, remain fixed. An unchanged-map copy also preserves the original ID. These are seven additional assertions.

On 2026-11-16, the isolated focused suite passed **972 synthetic plumbing checks**, exit **0**, on Microsoft JDK **21.0.13.0.2**. Fresh production compilation, focused-test compilation, and the tested-source change guard all passed. The guard covers every production Java source/resource, the focused test, and the generated focused runner. This run used compiled classes with copied production resources.

Command from the repository root:

```powershell
rtk proxy node Intra-Paper-System/build/offline-core-focused-runner.mjs Intra-Paper-System/build/offline-core-20261116-hash-identity-03 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
```

Retained evidence:

- [Focused summary and exact source/runner hashes](../build/offline-core-20261116-hash-identity-03/logs/summary.json): `PASS_FOCUSED`.
- [Focused suite log](../build/offline-core-20261116-hash-identity-03/logs/simulation.OfflineCorePlumbingTest.log): 972 checks, `EXIT_CODE=0`.
- [Generated focused runner](../build/offline-core-focused-runner.mjs).

The standard `test-offline.mjs --suite simulation.OfflineCorePlumbingTest` was attempted first with the same JDK and output roots ending in `-01` and `-02`. Both attempts compiled production code but stopped before executing the suite:

- [Attempt 01 summary](../build/offline-core-20261116-hash-identity-01/logs/summary.json): test compilation could not resolve the production classpath under the Windows sandbox.
- [Attempt 02 test-compilation log](../build/offline-core-20261116-hash-identity-02/logs/test-compile.log): the approved outside-sandbox rerun encountered malformed string/character literals in the unrelated, concurrently added `NiftyResearchWorkflowTest`. The generated fallback compiled only `OfflineCorePlumbingTest` against all freshly compiled production sources; unrelated tests were excluded.

**The full offline runner remains required after the current source set stabilizes and before integrating the accumulated changes.** This focused result does not establish full-suite or packaged-JAR acceptance. All fixtures and results are synthetic plumbing only.

## Subsequent Stage 1 verification â€” 2026-11-16

The existing identity regressions were retained. Additional gap-admission coverage verifies that a checkpointed historical run rejects incomplete diagnostic data before strategy identity, parameters, or evaluation are read. The CLI continues to allow `validate` to inspect the 29-bar diagnostic fixture, while `run` rejects it before creating a checkpoint or output directory. The focused suite passed **972 synthetic checks** on Java 21. The Stage 1 source files in that compile snapshot matched their captured hashes.

The standard full offline runner was then attempted with a fresh output root. Production compilation, test compilation, packaging, manifest inspection, and the focused core suite passed. `NiftyResearchWorkflowTest` failed with `Invalid portfolio settlement/accounting artifact: Dataset/portfolio universe mismatch` during development/validation replay; the runner consequently did not publish a candidate JAR. A post-run comparison found seven production/test files had changed since the runner's compile snapshot, so this attempt is not full-suite acceptance for the current tree. See `../build/stage1-full-20261116-final/logs/summary.json`.

The runner now performs and records its final source-stability check even when a suite fails, so a future full run will distinguish source drift from suite failures. `node --check test-offline.mjs` and `git diff --check` pass after that change. Full current-tree acceptance remains pending until the concurrent Nifty research/portfolio edits stabilize and the fresh full runner passes.
## Final Stage 1 verification â€” 2026-11-16

After the concurrent Nifty research/portfolio edits stabilized, a fresh standard offline run passed **32/32 suites**. Production and test compilation, packaging, manifest inspection, and the final source-stability check all passed (`source_stable=true`). The tested dirty-worktree JAR SHA-256 is `615f93db370af6d69417a5506ebe7495319f0bcd4215a796fb87f0457bf7f070`.

Command:

```powershell
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot' --output-root Intra-Paper-System/build/stage1-full-20261116-final2
```

Exact suite results, source hashes, runner hash, JAR hash, and logs are in `../build/stage1-full-20261116-final2/logs/summary.json`. The earlier source-drift attempt remains diagnostic history; this source-stable run is the current offline acceptance evidence. Hosted CI was not run. Synthetic checks do not close operational or project gates.