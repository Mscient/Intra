# Futures-first offline release candidate — 2026-11-16

This increment continues the existing Intra-Paper-System. It supplies one local, offline-verifiable futures paper workflow over the existing historical strategy, risk, simulated OMS, execution, fees and accounting core. Options remain a later stage. This is software/plumbing evidence only; no strategy evidence or actual forward session is credited.

## Verified accessible baseline

Local inspection found branch `codex/all-changes-20261114`, HEAD `7012e8f36b7dcd640e94882ab77abf90607b6995`, rather than an unborn main with untracked project files. `055ef0b86bf7acd6ff985d26b240197b97989f18` already contains the accumulated runtime and verification implementation. Local main points at that commit and was not changed.

All named leads were available locally:

| Lead | Actual local evidence | Integration limit |
|---|---|---|
| `codex/all-changes-20261114` / `7012e8f` | Accessible root checkout, with runtime/core code and earlier completion reports | Current working source is dirty; HEAD alone does not identify the candidate |
| `codex/offline-correctness-20261114` / `5d5bb85` | Local ref and `offline-correctness-20261114` worktree | Separate commit/lineage, not merged. Its older engine would remove the newer shared incremental core; missing guards were completed in the accessible implementation instead |
| `codex/g3-remaining-workstreams-20261111` / `78b5403` | Local ref/worktree; workstream document commit `1fc9e6a` inspected | Separate planning/evidence work; no qualified strategy evidence was integrated or inferred |
| G7 `083d7e6` | Local `codex/g7-operations-governance` ref and `g7-worktree`; evidence/validation documents inspected | Separate unadopted proposal. `ops/governance/G7Cli.java` is absent from this root release; G7 was not merged or tested here |

There were no active unmerged index entries (`git ls-files -u`, exit 0, empty). The historical session index records the aborted 41-conflict merge. No merge, fetch, push, reset, clean, checkout, commit or main update was performed in this increment. No named ref was unavailable; their presence does not imply integration.

The initial seven modified files were README, tracker, runtime guide, ExternalLedgerAuditTest, DatedInstrumentMappingRegistryTest, PortfolioSettlementTest and `test-offline.mjs`; the November 15 report and FeedCaptureReconcilerTest were untracked. They were preserved. Separate core-test/CLI admission edits and the `NiftyResearchWorkflow`, `NiftyResearchReport`, `SessionSmaStrategy` and `NiftyResearchWorkflowTest` files appeared during this work. They were inspected and preserved. The final build includes that local research software and its registered synthetic suite without claiming its authorship or qualified strategy evidence. It is distinct from merging the named G3 branch.

Before editing, 2,313 existing project files outside `build/` were copied byte for byte to `C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\baseline`. `baseline-sha256.json` and `changes-from-preserved-baseline.json` retain the inventory and reviewed changes. No existing project file was deleted. No book research/access state changed.

Read the supplied AGENTS/RTK instructions (no on-disk AGENTS was found in the ancestor/project paths), SESSION_PROTOCOL, README/tracker, operating/runtime guides, recent completion reports, relevant sources/tests, build runner and CI. The user's explicit no-commit/no-push/no-main instruction overrides the session protocol's generic publication step.

## Code completed

| Files, relative to this project | Behavior |
|---|---|
| `paper/PaperManifest.java` (under `src/main/java/com/mscient/intrapaper/`) | Canonical input/session provenance using the existing admitted hash map; binds properties, frozen events, strategy/settings and actual running code |
| `paper/PaperRuntime.java`, `paper/PaperSessionStore.java` | V2 identity, input manifest pinned before journal use, output manifest, explicit completeness, active-anchor checks, storage-failure latch, committed-prefix replay verification |
| `marketdata/RawCaptureJournalWriter.java` | Byte comparison through its owned locked handle, with append cursor preserved; reuses the existing journal format and verifier |
| `paper/PaperCli.java`, `cli/IntraPaperCli.java` | `paper-replay-verify`, existing-session requirement for verification, diagnostic validation coverage output, simulation admission before checkpoint/output creation |
| `data/ValidatedDataset.java`, `simulation/BacktestEngine.java` | Strengthened existing complete-coverage guard, including expected bar count, and admission into the shared paper session core |
| `simulation/RunArtifactWriter.java`, `simulation/portfolio/PortfolioReplayArtifactWriter.java`, `evaluation/EvaluationPartitionReport.java` | Incomplete diagnostic datasets cannot enter simulated artifact publication or partition scoring |
| `src/test/java/com/mscient/intrapaper/paper/PaperReleaseCandidateTest.java` | Manifest/hash binding, byte replay, partial-fill recovery with delayed duplicate, active-anchor mutation, ambiguous transaction/storage failure and incomplete admission checks |
| `test-offline.mjs`, `test-paper-release.mjs` | Registers release checks and provides a fresh-output packaged synthetic CLI acceptance workflow with exact arguments, outputs and exit codes |

This task created no second strategy, risk gate, simulated OMS, execution model, cost engine, accounting ledger or settlement subsystem. Existing options and historical settlement code remains available and tested; paper mode rejects options, automatic flattening/settlement, next-session orders and option stress inputs. The existing synthetic futures fixture (`underlying=SYNTHETIC`) is explicitly admitted only with synthetic classification; ordinary inputs require Nifty futures.

Receive UTC drives decisions; source UTC and monotonic receive time remain preserved. The recorded provider deterministically merges quotes, completed bars and observed opens; strategies receive immutable completed prefixes. Exact old event duplicates are ignored before clock advancement. New clock/sequence reversals, conflicting duplicates and invalid contracts halt. Degraded/reconnect states block decisions and fills until explicit recovery/fresh data. Stops never invent liquidation, settlement or a missing price.

Resume verifies the frozen input/code identity, complete journal and retained checkpoint, reconstructs each committed input/ledger pair, and checks the report. Active anchor mutations fail before a new event can overwrite them. Ambiguous tails are refused, including partial-fill boundaries. A local exclusive lease prevents concurrent writers. V1 sessions require their original JAR; there is no migration or cleanup to make them pass.

`paper-verify` checks exact canonical manifests and reconstructed output. `paper-replay-verify` regenerates the committed prefix, including an out-of-band operator stop, and compares all five files byte for byte: input manifest, checkpoint, journal, report and session manifest. Partial/stopped/halted sessions stay explicitly incomplete. Complete synthetic rehearsal remains performance- and operating-evidence-ineligible.

## Current verification

**PASS: all 31 registered suites, both Java compilations, packaging, actual JAR manifest inspection and the final source-change guard. Exit 0.** Microsoft JDK `javac 21.0.13.0.2`, Windows/NTFS. The verified release is the fixed source copy at `C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\frozen-source\Intra-Paper-System`; later shared-checkout edits are outside this verification.

Published JAR: `C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\release-verified\intra-paper-system.jar`.

SHA-256: `53cbf1363ae69e1ddea52ba9b994c49b2680216ba275ab8a90b97beb41d582f2`. Manifest: `Build-Commit: unknown-dirty`. The full [suite summary](../../../intra-forward-rc-20261116/release-verified/logs/summary.json) binds **318 Java/test/resource files** and the runner hash. The [release proof](../../../intra-forward-rc-20261116/release-proof.json) independently rechecked all those source hashes and the published/CLI JAR identity.

The fixed copy retained 2,344 project files, plus the R11 reference CSV. Its source inventory is [frozen-source-sha256.json](../../../intra-forward-rc-20261116/frozen-source-sha256.json). The shared checkout kept changing during earlier attempts. Seven research/portfolio source/test files differed from the failed `offline-verified` build; their latest accessible versions were preserved in the fixed copy and its research suite passed 78 checks. This task did not overwrite those concurrent edits to repair the failure. The read-only launcher supplies `GIT_DIR`/`GIT_WORK_TREE` for baseline Git provenance; candidate identity is the separately hashed dirty source and JAR, not a claim that HEAD contains these bytes.

Exact final commands from the repository root (choose a new output directory when rerunning):

```text
rtk proxy node C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\verify-frozen.mjs C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\release-verified
rtk proxy node C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\frozen-source\Intra-Paper-System\test-paper-release.mjs C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\release-cli-candidate.jar C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\release-cli "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
rtk proxy node C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116\prove-release.mjs
```

All three exited **0**. The first launcher executes the unchanged frozen `test-offline.mjs` with `--java-home C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot --output-root C:/Users/prash/AppData/Local/Temp/intra-forward-rc-20261116/release-verified`. The CLI candidate is an exact byte copy of the production candidate, made before the full run published it; the proof confirms its hash equals the published JAR. The [CLI summary](../../../intra-forward-rc-20261116/release-cli/summary.json) retains every expanded Java executable/argument list, stdout/stderr and exit code.

| Current suite/coverage | Exact outcome |
|---|---|
| Paper release / integration / fault-recovery | PASS: 53 / 60 / 136 checks (249 combined paper checks) |
| Core input, identity, historical replay, risk and accounting | PASS: 972 checks |
| Anchored journal / feed-capture reconciliation | PASS: 732 / 703 checks |
| Permission admission / intended-use preflight | PASS: 21 / 300 checks |
| Risk durability / subprocess recovery | PASS: 447 / 728 checks; 308 child JVMs, 24 controlled terminations |
| Portfolio risk replay / risk audit / settlement fixtures / settlement audit | PASS: 84 / 79 / 274 / 88 checks |
| Registered evaluation / Nifty research workflow / holdout release | PASS: 161 / 78 / 158 checks; no qualified strategy evidence |
| Incident drills / daily evidence / external-ledger audit | PASS: 198 / 455 / 303 checks |
| Frames, dated mapping, instrument sequences/coverage, K0–K3, portfolio accounting/replay/cost/artifact/settlement | All PASS; these ten suites do not publish uniform assertion counts |
| Numerical pricer regression | PASS: 22 R11 cases, 16 boundaries, 5,805 no-arbitrage checks; QuantLib comparison was not rerun |

The synthetic CLI workflow passed **24 invocations and nine harness artifact checks**: **19 exited 0**, and **five expected rejections exited 2**. It validated 30 complete fixture bars; ran 17 events, verified and replay-verified the prefix, then resumed to 61 events; checked a separate uninterrupted run; and compared all five session files byte for byte. Operator stop after six events was applied at event seven with zero fills, and both verification paths passed. Historical `run`, `verify` and `replay-verify` passed on the same JAR. Diagnostic validation of 29 gapped bars exited 0 with incomplete coverage; historical/paper runs rejected them with exit 2. Verification of an absent session and a promoted manifest also exited 2. No expected rejection is counted as an operational session or an actual failed strategy experiment.

`rtk proxy git diff --check` exited 0 (LF/CRLF notices only). The three existing modified regression files, untracked feed test and November 15 report were independently compared with the saved baseline and remained byte-identical. Source hash verification applies to the fixed release copy; the shared checkout is not certified as static or fully verified after concurrent edits.

All generated verification output is outside the repository under `C:\Users\prash\AppData\Local\Temp\intra-forward-rc-20261116`. The candidate is a dirty working-source release: its manifest must say `Build-Commit: unknown-dirty`. Use its source inventory and JAR hash, not a historical commit/report, to identify it. Hosted CI and the clean-commit CI wrapper were not run.

Earlier attempts are retained: `focused-01` exited 1 after production compiled but sandbox test compilation reported 2,577 missing-symbol errors; normal filesystem access was required. `focused-02` exited 1 because the existing synthetic fixture's underlying was initially excluded. `focused-03` exited 0 (`PASS_FOCUSED`, 47 checks), before the final six active-anchor checks. `offline-final` exited 1 after 30 suites passed because the source set changed; it is superseded. `offline-verified` exited 1 with 30/31 suites passing and the concurrently added research workflow failing preregistration matching; seven of its source files subsequently differed. Its candidate was not published. `frozen-focus` exited 0 with 78 research checks before the successful full fixed-copy run above. None of the failed/superseded candidates is the release JAR.

## Documentation and open gates

Updated README, implementation tracker, operating guide and runtime guide. This dated report and session `696fa0fb-ecfc-48ba-88ea-4ee1c04ffbb9` record the current scope/results; the session index has no new commit ID because publication was explicitly prohibited. Earlier reports and unrelated work remain preserved.

| Gate | Still-open evidence |
|---|---|
| G0 | Named operator/reviewer and stop authority; source/account/route eligibility; acquisition/storage/replay/retention rights; approved private risk/capital limits and actual cost/rule evidence |
| G1/G2 | Reviewed hypothesis and realistic execution assumptions; authorized complete dated data; independently authenticated instruments, calendars, rules, costs, margins and settlement inputs |
| G3 | Qualified chronological evaluation, trial records, sealed holdout custody and independent review; synthetic SMA remains a harness |
| G4 | Reviewed operating ingestion, independent checkpoint custody, actual reconciliation/pre-open/stop/reconnect evidence, 60 complete sessions and 20 consecutive passing pre-open checks; synthetic runs earn zero session credit |
| G5/G6 | Real-money pilot is not authorized; later capacity stage not started |
| Proposed G7 | Unadopted, separate worktree; no governance gate closure or operating/scaling authority |

Local verification does not authenticate external data, rules, cost invoices, permissions or account eligibility. Streaming ingestion, live wall-clock watchdogs, long-session throughput/storage bounds, power-loss durability, independent anchors, calibrated execution capacity and exchange/account reconciliation remain unqualified. There was no credential use, account access, live-data collection, broker connection, external service access or real order route/action.

## Subsequent verification on authoritative checkout â€” 2026-11-16

The shared root checkout also passed the standard full offline runner after its concurrent edits stabilized: **32/32 suites**, production/test compilation, package, manifest check, and final source-stability check (`source_stable=true`). This verifies the root working tree separately from the fixed-copy release verification above. The dirty-worktree JAR SHA-256 is `615f93db370af6d69417a5506ebe7495319f0bcd4215a796fb87f0457bf7f070`.

Command:

```powershell
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot' --output-root Intra-Paper-System/build/stage1-full-20261116-final2
```

See [the retained run summary](../build/stage1-full-20261116-final2/logs/summary.json). Hosted CI was not run; all external-permission, account, paper-operation and live-pilot gates remain open.