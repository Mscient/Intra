# G2 offline increment validation — 2026-11-08

**G2 INCOMPLETE.** [Requirement inventory, contracts and readiness](../../Intra-Paper-System/docs/G2-READINESS-INVENTORY.md) and [source/use map](../../Intra-Paper-System/docs/G2-SOURCE-USE-EVIDENCE.csv) are authoritative for scope. No real market/account data or source grants were supplied or acquired. Code and synthetic checks cannot establish data completeness, rights, issuer/market correctness, strategy profitability, independent review or live readiness.

Started after inspecting original dirty `main` at `2b7fe0a`, branches/worktrees/status, then fetching origin. Base `ff4874167e889a26990e1f4d9d2b1ffa4acbefe6`; isolated `codex/g2-offline-core-20261108` worktree. Original unrelated files preserved. Base hosted [Actions 42438198778](https://github.com/Mscient/Intra/actions/runs/42438198778) completed with success; branch hosted validation is not yet claimed.

Development verification found and fixed two pre-existing Windows failures: accounting javac source arguments exceeded the native command limit, and raw resume read through a second handle while its exclusive lock denied access. Corresponding quote/lifecycle resume paths were fixed through the same locked handle. The development package passed 365 synthetic G2 checks and compared 42 complete artifacts plus a normalized journal across separate UTC/English and Pacific/Apia/Turkish JVMs. That dirty development package is not a clean-release Build-Commit claim. The clean validation below includes the subsequent quality-record addition and passed before publication.

## Clean revision validation

**PASS / SYNTHETIC_OFFLINE_ONLY** at clean tested revision `819a53c1c6dc58c40f91cd676a10b2583af33ec4`, containing implementation `c004173d3db59b3b35401a02a31b4b112625e20a` and the explicit synthetic raw fixture commit. Microsoft OpenJDK `21.0.13.0.2+1-LTS`, vendor build `Microsoft-15780113`, javac `21.0.13.0.2`; PowerShell `7.6.9`; Windows 11 amd64 / NTFS. Exact environment metadata and concise results are retained in [the evidence folder](g2-offline-validation-2026-11-08/summary.log).

Exact clean command from the isolated repository root:

```powershell
.\.github\scripts\test-offline-ci.ps1 `
  -OutputRoot C:/Users/prash/AppData/Local/Temp/codex-intra-origin-20261103/g2-ci-819a53c `
  -ExpectedCommit 819a53c1c6dc58c40f91cd676a10b2583af33ec4
```

The runner required a clean checkout, ran each suite in a separate noninteractive PowerShell process, built the production JAR, ran all packaged/drill checks and verified Build-Commit. Local observed start/end: `2026-11-08T03:22:48.2378689Z` / `2026-11-08T03:50:38.6811293Z`, 1670.44 seconds (27m50s). This observation prompted a workflow deadline change from 20 to 45 minutes for the expanded suite; it is not a latency or operational SLA claim. There were no broker/data/dependency downloads; hosted setup uses only pinned JDK and Actions tooling.

| Selected validation | Actual result |
| --- | --- |
| Risk gateway | PASS, 447 checks / 13 groups |
| Risk gateway subprocess recovery | PASS, 728 checks / 5 groups, 308 child JVMs, 24 forced terminations |
| Portfolio replay engine / dated costs / artifact verifier / settlement | PASS, all four entrypoints; explicit golden fee arithmetic and date/rounding/atomicity cases |
| Portfolio risk replay | PASS, 84 checks plus shared replay/cost/artifact/settlement regressions |
| Portfolio accounting | PASS |
| Portfolio risk audit / settlement risk | PASS, 79 / 274 checks |
| Portfolio settlement audit | PASS, 88 checks |
| Trial evaluator regressions / existing fixed synthetic HAC qualification | PASS, 165 / 30019 checks; methods unchanged, no holdout financial data inspected |
| Daily operations evidence | PASS, 455 assertions in both source and production-JAR modes; nine expected-status synthetic drills |
| Intended-use preflight | PASS, 300 checks in source and production-JAR modes; ten expected-status CLI checks |
| Permission coverage | PASS, 102 scenarios / 1648 assertions in source, standalone JAR and production JAR modes |
| Incident drills | PASS, 198 checks in standalone and production JAR modes, nine expected-status scenarios |
| Feed audit | PASS, 507 checks in source and production JAR modes; packaged synthetic drill 557 checks |
| Dated mapping registry | PASS |
| Instrument sequence / acknowledgement coverage | PASS, both entrypoints |
| Synchronized bar frames | PASS |
| K0–K3 recommendation policy | PASS |
| New G2 contracts | PASS, 365 checks each in source and production-JAR modes |
| New G2 separate-process reproduction | PASS, 42 complete artifact files, normalized quote journal and three quality records byte-identical across UTC/en-US and Pacific/Apia/tr-TR JVMs; all six runs reconstructed and OMS-recovered |
| Production build / Build-Commit / diff | PASS; exact tested revision in JAR; git diff --check and clean source checks passed |

Production JAR: `g2-ci-819a53c/package/intra-paper-system.jar`, SHA-256 `b9a604ab107af25034759dc6ba9e4271c39ab8a3a40e730ef1307a6f9a07607f`; Build-Commit `819a53c1c6dc58c40f91cd676a10b2583af33ec4`. The later validation/index/workflow-deadline commit changes no Java, simulator schema, tests or CLI scripts; the retained production artifact remains the exact fully tested revision.

| Reproduced artifact | SHA-256 |
| --- | --- |
| BAR_NEXT_OPEN run manifest | `5aaac6668943d70c33514dde3c2d37f52d36d3d057dfdbcba754c850acc64459` |
| L1_FULL_SIZE_IOC run manifest | `7d7cf3b8db0474641cb7e99a6750dc4d5b73f7b9d1784ebca87c5515b2cf89a4` |
| L1_PARTIAL_IOC run manifest | `1ecd60fc0dcfbc785c1a992fa74f298502cc7fe5ba035cd40ced998d613ef526` |
| Normalized quote journal | `5eaa06915a385e949aebdc32867ff4f29600536cdc4f826129670a3e0e9e0c4b` |

Exact input and all artifact hashes and quality records are retained in `g2-ci-819a53c/g2-reproducibility/15e19a87be474156afea5eee56bb14e1`. [Concise retained copies](g2-offline-validation-2026-11-08/artifact-sha256.csv) use UTF-8/LF; a separate retained-file hash ledger identifies those copies. Original complete logs, JDK hashes, all frozen inputs, failure fixtures and packages remain in the outside-checkout local evidence root. This is local CI evidence; no hosted success is claimed for this branch. The workflow triggers main pushes/PRs/manual runs; a branch push by itself does not trigger it. Base hosted Actions remains the separately identified successful main run above.

Evidence files include logs/environment.log, logs/summary.log, logs/manifest-check.log, G2 fixtures/checks.log, frozen input-sha256.csv, artifact-sha256.csv, quality-*.json, raw/lifecycle/quote journals, complete reconstructed runs and result.txt. Large generated evidence and executable packages remain local outside Git; concise exact logs/hashes will be retained with this report. Golden opaque raw fixture SHA-256: `3a968c3d92361a75554a0f927dbd0033f35753b51026cd0ce2fda19721f2197f`.

## Evidence limitations

Separate JVM processes/directories/default locale/timezone settings are exercised on one Windows host and the same pinned JDK/build, not separate OS/hardware environments. Existing subprocess risk-kill fixtures test Windows termination, not OS power loss, independent risk failure domains or broker recovery. New raw/quote/lifecycle resume tests verify locked-handle ownership and damaged-tail rejection, not adversarial path aliases, noncooperating writers, source authentication or completeness. Synthetic quality zeros are generated-fixture/configured-validator facts, not market measurements or a universal data-quality checker. Source/capture identity and complete generic event schema, dated phases/bands, all-in costs and universal trusted SAFE/build/head/tail/reconciliation/release remain partial. Margin values remain caller proxies; queue/impact/latency calibration, owner budgets and independent reviewer decisions remain absent. G0/G2/G4/G5 remain open; order egress stays disabled.
