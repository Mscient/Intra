# Offline integration and checkout reconciliation — 2026-11-14

## Checkout and scope

Continued the existing integrated Intra-Paper-System. Inspected HEAD `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090` on local main, ahead 4 / behind 56 versus cached origin/main; no fetch. Substantial modified/untracked implementation was already present. Additional local paper-runtime source appeared during verification. Source-change guards prevented presenting a moving checkout as a verified release. Preserve the exact tested JAR and hash inventory, not HEAD alone. Working builds correctly identify themselves as `unknown-dirty`.

Read the project README/tracker, operating guide, G0 open items, roadmap/design, session protocol, RTK instructions, build/CI scripts, recent history and adjacent implementation/tests. Earlier Python, authentication/capture and other-revision CI claims in research documents are historical context, not verification or authorization for this Java checkout.

## Reused implementation

- Java 21 dependency-free CLI; strict CSV/JSON; dated futures/options identity, expiry, lot/tick sizes, strike/right; explicit calendars and effective-dated rules.
- Local bar/L1 replay, raw/quote/lifecycle journals, source hashes, quality/sequence/coverage diagnostics, completed-bar SMA harness and next-eligible-open execution.
- Independent risk decisions, configured position/notional/loss/order-frequency/session controls, full and whole-lot partial IOC, local OMS transitions and idempotent executions.
- Dated costs, supplied margin inputs, explicit supported settlement, independent ledger/risk/cost reconciliation, deterministic artifacts and replay.
- Signed portfolio accounting, risk/settlement sidecars, chronological registered evaluation, outcome history, one-shot holdout controller, offline stops/drills/daily evidence and external-ledger diagnostics.
- BSM/Black-76 numerical validation and dated diagnostic option valuation; model prices do not replace fills or accounting marks.

These are components of this system. Synthetic acceptance validates plumbing, not profitability, legal rights, external rules or production custody.

## Changes and integration

Paths below are relative to `Intra-Paper-System/` unless stated otherwise; Java production package root is `src/main/java/com/mscient/intrapaper/`.

| Files | Purpose |
|---|---|
| `test-offline.mjs`, `build.ps1`, repository `.github/scripts/test-offline-ci.ps1` and `.github/workflows/java21-build.yml` | Shared offline compilation/tests/packaging, resources in JAR, actual manifest verification, source/JAR hashes, clean-commit CI enforcement, publication only after all suites pass. Focused runs never publish a release JAR. |
| `simulation/BacktestCheckpointStore.java` | Correct serialization allowlist for concrete Instant/LocalDate values restored through Ser.readResolve. |
| `marketdata/{Quote,RawCapture,FeedLifecycle}Journal{Reader,Writer}.java`, `JournalChannelInput.java` | Verify using the held channel, compare retained summaries, reject ambiguous tails/rollback and disable uncertain writers. Unanchored verification cannot detect whole-file rollback. |
| `permission/PermissionAdmission.java`, `provenance/RunConfig.java`, `data/ValidatedDataset.java` | Complete existing permission admission, exact scope/hashes and storage containment for governed sidecars. Declarations remain unverified legal assertions; multi-source quote permission binding remains unsupported. |
| `simulation/RunIdentity.java`, `RunArtifactWriter.java`, `RunArtifactVerifier.java`, `OmsRunRecovery.java` | Bind omitted governed input hashes; expose quote ages/spread/latency and unverified assumption hash; version manifests as 15 and identities as V14/V15_TRIAL; accurately report partial fills; strictly parse manifests; restore exact legacy and extended fill schemas. |
| `simulation/BacktestEngine.java`, `QuoteExecutionBook.java`, `simulation/portfolio/PortfolioReplayEngine.java` | Reject incomplete simulation coverage; preserve historical behavior and review newly present incremental local paper-core integration against regression tests. |
| Tests `simulation/OfflineCorePlumbingTest.java`, `marketdata/JournalRecoveryTest.java`, `permission/PermissionAdmissionTest.java` | Input/session/identity/risk/accounting, checkpoint, corruption, deterministic replay, quote-audit and admission regressions. |
| Test `riskgateway/RiskGatewayProcessTest.java` | Child startup/thread diagnostics on timeout; preserve assertions and time limits; terminate only harness-owned children. |
| Test `evaluation/portfolio/PortfolioHoldoutReleaseTest.java`, runner and `.gitignore` | Require fresh isolated fixture output; avoid source-directory fixtures. Existing legacy scratch is ignored, not broadly deleted. |
| Tests `paper/PaperRuntimeIntegrationTest.java`, `paper/PaperRuntimeTest.java` | Local provider/shared-core equivalence, restart, stop, malformed/stale events, deduplication, changed inputs and journal/report corruption. |
| README, tracker, operating guide, G0 document and research roadmap/design | Reconcile Java implementation, partial fills/recovery, historical evidence, one integrated project and evidence gates. |

The paper package and admission/holdout/ops/audit packages include work already present or added elsewhere in the shared checkout. They were preserved and inspected; this report does not claim sole authorship. No external adapter, SDK, credential handling or broker order route was added by this work.

## Verification

**Final result: PASS, exit 0. All 28 suites passed against the same production JAR.** Production compilation, test compilation, packaging, extraction/verification of the actual manifest, and the source-mutation guard passed. Microsoft JDK: `javac 21.0.13.0.2` on Windows 11/NTFS.

Exact command from the repository root:

```text
rtk proxy node Intra-Paper-System/test-offline.mjs --java-home C:\PROGRA~1\Microsoft\jdk-21.0.13.21-hotspot --output-root Intra-Paper-System/build/offline-20261114-complete-8e62
```

Published JAR: `build/offline-20261114-complete-8e62/intra-paper-system.jar`. SHA-256: `95681971be1721b50b1160ed82d05ff702da0ce7eac4bf1c7ef17f4b648ca4f2`. Manifest: `Build-Commit: unknown-dirty`; source HEAD: `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`. The final `logs/summary.json` records all 311 source/test/resource hashes, the runner hash and individual outcomes. A post-run read confirmed no hashed sources or runner had changed and the published JAR hash matched.

| Suite / coverage | Final outcome |
|---|---|
| Local paper integration; paper fault/recovery suite | PASS: 60 and 135 checks |
| Core input/replay/risk/accounting/manifest regression | PASS: 961 checks |
| Anchored journal recovery | PASS: 732 checks |
| Permission admission; intended-use preflight | PASS: 21 and 300 checks |
| Risk durability; subprocess recovery | PASS: 447 and 728 checks; 308 child JVMs, 24 forced terminations |
| Portfolio risk replay/audit/settlement fixtures/settlement audit | PASS: 84 / 79 / 274 / 88 checks |
| Registered evaluation; holdout release | PASS: 161 / 158 checks |
| Incident drills; daily evidence; external-ledger audit | PASS: 198 / 455 / 262 checks |
| Frame/mapping/sequence/coverage/kill-policy and portfolio accounting/replay/cost/artifact/settlement suites | All PASS; these suites do not expose a uniform assertion count |
| Option pricing reference/boundary/no-arbitrage | PASS: 22 / 16 / 5,805 cases |

Earlier runs below document regressions and moving-checkout failures; none supersedes this final result.

- `build/offline-20261114-b`: compilation/package PASS, 24/26 suites PASS; OMS schema and process timeout failures.
- `build/offline-20261114-c`: sandbox test compilation failed with inaccessible generated classes (2,310 symbol errors); no publication.
- `build/offline-20261114-d`: compilation/package/manifest PASS, 26/26 suites PASS, exit 0; published JAR for that hashed pre-paper snapshot.
- `build/offline-20261114-final`: existing output directory rejected, exit 1; previous evidence preserved.
- `build/offline-20261114-e-5f1c`: compilation/package and 26/26 suites PASS, including 961 core checks; final source-set mutation guard rejected publication, exit 1.
- `build/paper-focus-20261114-7b12`: compilation/package PASS; paper check failed on BAR_NEXT_OPEN freshness configuration (`maximumAge must be positive`); no publication.
- `build/paper-focus-20261114-7b13`: failed because checkpointing opened a second reader while the journal writer held a Windows file lock.
- `build/paper-focus-20261114-7b14`: failed because a risk audit event was interpreted as an OMS transition.
- `build/paper-focus-20261114-7b15`: 60 paper integration checks PASS, exit 0, status PASS_FOCUSED; no release JAR published.
- `build/offline-20261114-complete-8e61`: all 28 suites PASS, but BacktestEngine and PaperRuntimeTest changed during verification; final guard rejected publication, exit 1.

`rtk proxy git diff --check` completed with exit 0 (only Git LF/CRLF notices). The five updated primary Markdown documents had resolving local links at that check; later missing helper/evidence files are listed below.

All preceding full runs used the same command structure with the listed `--output-root`. The paper-focus runs additionally used `--suite paper.PaperRuntimeIntegrationTest`. Fresh-output rejection did not overwrite evidence; failed or focused runs did not publish a release JAR.

Each run retains logs and `logs/summary.json`. Hosted CI and a clean-commit build were not run. The PowerShell launcher/CI wrapper and external QuantLib comparison were not executed in this local pass; Java R11 validation was executed. No dependency download occurred.

## Remaining dependencies and gates

| Gate | Status and required evidence |
|---|---|
| G0 | OPEN: named operator/reviewer and stop authority, current read-only route/account eligibility and rules, source-specific use/retention/sharing rights, approved private risk limits and operating requirements. Typed references/public APIs are insufficient. |
| G1 | OPEN: reviewed/frozen hypothesis, dated instruments, benchmark, costs/fills, parameters/variants and rejection criteria. SMA remains a harness. |
| G2 | OPEN: authorized multi-expiry data with complete sessions/transformations; authenticated dated instruments/calendars/rules/costs/margins/settlements; independently reconciled evidence and calibrated execution assumptions. |
| G3 | OPEN: qualified chronological development/validation and sealed holdout evaluation, independent review, recorded trials and data-supported walk-forward/sensitivity results. No market-derived evaluation occurred. Index markouts remain separate from contract P&L. |
| G4 | OPEN: reviewed read-only ingestion and production paper integration after G0, trusted checkpoints, actual pre-open/stop/reconnect/reconciliation evidence, 60 complete sessions, 20 consecutive passing pre-open checks, successful drills and no unresolved severe incident. Frozen-event rehearsal earns no session credit. |
| G5 | OUT OF SCOPE: optional live pilot requires a separate human decision and independent review. No order route. |
| G6 | NOT STARTED: later capacity evidence, including at least six months of live evidence if a G5 pilot ever occurs. |

Unsupported/unverified: exchange margin, daily variation margin, physical delivery, passive queues, capacity/market impact, long-lived streaming resource bounds, authenticated cross-journal/remote recovery, fee/tax authenticity and settlement rules. Model inputs/proxies remain labeled; missing liquidation/settlement prices are not invented. Options reports retain dated-contract identity; a consolidated expiry/right dashboard and qualified walk-forward research remain integration/evidence work. Unsupported statistics stay null.

At the final handoff, the 311 hashed Java/test/resource files, shared Node runner and published JAR still matched the passing build. Additional tracked files were missing in the shared checkout: six example files (four risk-audit CSVs plus trial-evaluation catalog/split), four portfolio command wrappers and seven older focused test wrappers. Deletion provenance is unknown; these deletions were not performed by this session. The shared `test-offline.mjs` remains available, but the missing wrapper/example commands are not certified by the Java pass. Unrelated book/research documents, a plan PDF and older risk-evidence files were also missing and were preserved as found. No broad reset/cleanup, stage-all, commit or push was performed; divergence and unrelated local work remain visible.

## Access confirmation

No account access, credential request/use, market-data capture, broker connection or broker order placement/modification/cancellation occurred. Tests used local synthetic/reference fixtures. G0 stayed open and performance eligibility remained false.
