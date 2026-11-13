# Proposed G7 implementation validation

G7 remains a proposal outside the original G0–G6 roadmap. Owner adoption is
absent. No G0–G6 gate is closed or repaired by this work. No real operational
evidence or authenticated owner/independent review was supplied.
`g7_closed=false`, `operation_authorized=false`, `scale_authorized=false`.

Branch: `codex/g7-operations-governance`. Starting commit:
`7d6f6c56424b4e50903121eaf8ee491a9ce15607`. The existing isolated worktree was
preserved; it was not reset, cleaned or recreated. The original seven Java files
and two scripts were inspected and their original bytes/hashes retained under
`build/g7-validation/draft-baseline/`. Earlier build output was not used as current
validation evidence. This is automated software review, not qualified independent
human review.

## Preserved-checkout continuation (2026-11-13)

The continuation started at `099a3cec7f227275a318e859763e524cc42f626f`
on the same branch and worktree. Direct inspection found ten unstaged G7 files,
no staged changes and no follow-up commit. The source, wrapper, test, evidence
contract, both trackers, proposal and operating-guide diffs were reviewed in full.
No repository `AGENTS.md` was found; the supplied `RTK.md` and repository
`SESSION_PROTOCOL.md` were read. The user's isolated-branch/local-only instruction
overrides the protocol's generic main/push instruction. All existing changes and
validation generations were preserved. The original implementation session is
historical; this continuation has a
[separate session record](../../research/sessions/38309891-48d8-4e6a-82ad-2ddf6cf2c37a.md).

The follow-up checks exact case-sensitive wrapper arguments inside the redacted
error boundary, including original `pwsh -File` tokens because that host normalizes
`-Name:value` in `$args`. Launcher failures are captured before releasing diagnostic
JSON. The wrapper verifies the pinned JDK vendor/version. Role-document age uses
the explicit `review_freshness_seconds` policy, including earlier-gate signer
evidence. Artifact events bind the review scope and declared artifact period.
Normalized corrections preserve order/instrument IDs and reject repeated correction
of the same prior record/field. These checks do not authenticate roles or implement
broker correction accounting. Cross-JVM checks also cover explain and append bytes.

The retained `build/g7-validation/precommit-4463c1526d354957ac78db8d14d98123/`
was inspected directly. Every entry in its `hashes.json` still matches: all nine
G7 main/test files, three scripts, the JDK release/java/javac, standalone JAR and
production JAR. Its source run passed **1,904 checks**; its production-JAR run
passed **1,905 checks** (one extra expected-Build-Commit assertion). Each ran
198 synthetic scenarios, 67 child processes and 34 independent schema checks.
All 12 selected regression runners passed. Standalone builds matched exactly,
with SHA-256 `de1737f7cdc3d81f515ed1d85d126eda1d33ebc9ea4f24374a7a82c9d972937c`.
The production JAR SHA-256 was
`139bc95cd4663ba0f5b3dcf77f2e2ef7c1577846e75bbd54d7abdb4da486f518`.
Its manifest is explicitly **`Build-Commit: unknown-dirty`**, with tested HEAD
`099a3cec7f227275a318e859763e524cc42f626f`; this is precommit validation of
the modified bytes, not validation of a committed follow-up revision.

Exact retained run command, from `Intra-Paper-System`:

```powershell
rtk proxy pwsh -NoProfile -NonInteractive -File .\build\g7-validation\finish-g7.ps1 -Phase precommit
```

The sandbox account again received `Access denied` reading the pinned JDK
`release`. An approved run under the normal Windows identity read the same file
and verified its original hashes, `IMPLEMENTOR="Microsoft"` and
`JAVA_VERSION="21.0.13.0.2"`. No ACL, JDK or verification rule was changed.
PowerShell 7 startup was also unavailable in the sandbox; the read-only audit ran
under the normal identity. This tool-access identity is not authenticated evidence
of an operational owner, reviewer or supplier.

The local continuation driver is retained at
`build/g7-validation/resume-validate.ps1`. It uses a new output generation each
time, fresh Java 21 classes, all 12 regressions, two repeatable standalone builds,
standalone explain/evaluate/verify exits 2/3/0, a fresh production build, packaged
G7 tests with the expected manifest commit, both diff whitespace checks and
before/after tracked-input hashes. It rejects dirty postcommit validation.
The source-test JAR deliberately retains `unknown-dirty` even in a clean checkout;
only the production/standalone package can carry the tested committed revision.
Exact commands, from `Intra-Paper-System`, are:

```powershell
rtk proxy pwsh -NoProfile -NonInteractive -File .\build\g7-validation\resume-validate.ps1 -Phase precommit
rtk proxy pwsh -NoProfile -NonInteractive -File .\build\g7-validation\resume-validate.ps1 -Phase postcommit
```

Fresh completion results are pending at this documentation snapshot. A run is
successful only when its final `validation.json` reports `status=PASS`; absence
of that record is not success. Local postcommit identity will be recorded separately
after the reviewed source commit. Hosted CI has not run for this continuation.

## Delivered scope and original audit corrections

The seven governance classes implement strict schemas, a bounded UTF-8 parser,
local snapshots/publication, immutable hash-chain generations, fixed offline
verifier adapters, evidence reduction and the evaluate/verify/explain/append CLI.
The builder, wrapper and test runner accompany nineteen exported schemas, three
explicitly synthetic static dossiers (172 files), generated adversarial fixtures,
the [evidence contract](G7-OPERATIONS-EVIDENCE.md),
[proposal](../../research/systems/G7-OPERATIONS-PROPOSAL.md), tracker entries and
[session record](../../research/sessions/c90a2391-d0fd-4b82-b855-9c9f19c36215.md).

The audit corrected compilation, exact size/path/hash and record-history bindings,
decision target/expiry/revocation handling, unsafe legacy dependency paths,
stale/missing coverage handling, code-origin/build-identity checks, canonical-schema
regex/integer bounds, scoped severity handling and publication mutation checks.
Windows Java's null file keys are preserved as unknown; snapshots use separate
metadata, locks, exact bytes and alias checks. Reviewed severity remains null
without authenticated policy review; declared labels are private hashed references.
The existing daily verifier caught two missing records in the synthetic daily
fixture's extended input window; the fixture was corrected without weakening the
verifier.

No external-account ledger verifier or provisioned G0–G6/source/owner/reviewer
authentication adapter exists in this checkout. Exact normalized comparison does
not substitute for either. `ELIGIBLE_FOR_HUMAN_REVIEW` is unavailable in this
revision, including for matching synthetic evidence, AI review and valid signatures
under supplied keys.

## Historical local validation (original implementation)

Environment: Microsoft OpenJDK **21.0.13.0.2**, runtime
**21.0.13.0.2+1-LTS**, Microsoft build **15780113**, 64-bit Windows;
PowerShell **7.6.9**. No dependencies or market data were downloaded.

The final source run used fresh Java 21 classes and retained its evidence at
`build/g7-validation/focused/f759377a31274d439c9988ca6daf4c96/`:

| Check | Observed result |
| --- | --- |
| G7 source compilation and focused Java checks | **1,464 checks passed**, 143 synthetic scenarios, 11 child processes. |
| Independent PowerShell JSON Schema validation | **34 checks passed**, including unsupported fields/versions, absent explicit-null fields and trailing-newline IDs. |
| Published schema identity | All 19 exports match the executable definitions byte for byte; checked by the Java suite. |
| Static synthetic dossiers | All three evaluate to `BLOCKED`; their published diagnostic bytes verify with exit 0 and false authority flags. |
| JVM/locale/timezone reproducibility | Exact report and metadata bytes across English/US/UTC, Turkish/TR/Pacific-Auckland and Hindi/IN/Asia-Kolkata child JVMs. |
| Existing regression suites | **All 12 selected runners passed**, with native exit codes checked by the G7 runner. |
| Standalone package | Two fresh builds were byte-identical; direct `java -jar` explain/evaluate/verify exits were 2/3/0, with a blocked diagnostic and all authority flags false. |

The source-test JAR is deliberately **`Build-Commit: unknown-dirty`**. Its SHA-256
is `f04aa88ddf987089473b9018c931c73dff4bedac5a12c6889821fe64a59d428c`.
It is not represented as a commit-identified production artifact.

Standalone precommit JAR SHA-256:
`513cf6fd0e1aee2f082af0410d5aa9c5d6c209deb2d2d9182a826f1307f5506e`.
Its manifest also deliberately says `unknown-dirty`. The two standalone builds
are retained under `build/g7-validation/standalone-precommit/` and
`build/g7-validation/standalone-repeat-precommit/`; the entry-point checks are in
`build/g7-validation/standalone-smoke-precommit/checks.json`.

| Existing runner | Reported checks, all passed |
| --- | --- |
| daily-ops-evidence | 455 assertions, 5 synthetic groups |
| feed-completeness-audit | 507 |
| portfolio-replay-engine | Engine, cost schedule, artifact v3 and signed settlement checks; no numeric total emitted |
| portfolio-accounting | Ledger checks; no numeric total emitted |
| portfolio-risk-audit | 79 audit + 274 settlement/risk |
| portfolio-settlement-audit | 88 |
| risk-gateway | 447, 13 groups |
| risk-gateway-process | 728, 5 groups, 308 child JVMs, 24 controlled terminations |
| portfolio-risk-replay | 84 plus the runner's replay regressions |
| incident-drill | 198 acceptance checks plus its synthetic CLI scenarios |
| g2-contracts | 365 |
| portfolio-trial-evaluation | 165 evaluation + 30,019 Sharpe HAC, plus replay regressions |

These runner totals overlap where a runner intentionally invokes another suite;
they are not summed into an inflated unique-test count. Exact logs are retained
in the source run's `regressions/` directory. This was the selected local matrix,
not the entire hosted workflow.

Exact source-run command, from `Intra-Paper-System`:

```powershell
rtk proxy pwsh -NoProfile -NonInteractive -File .\test-g7-operations.ps1 -RunRegressionSuites
rtk proxy pwsh -NoProfile -NonInteractive -File .\build-g7-operations.ps1 -OutputRoot build/g7-validation/standalone-precommit
rtk proxy pwsh -NoProfile -NonInteractive -File .\build-g7-operations.ps1 -OutputRoot build/g7-validation/standalone-repeat-precommit
```

The runner compiles with `javac --release 21 -encoding UTF-8 -proc:none`, packages
the main code, compiles the focused tests and runs them with `java -ea`. Its
regression matrix invokes the existing `test-<suite>.ps1` scripts for daily-ops-
evidence, feed-completeness-audit, portfolio-replay-engine, portfolio-accounting,
portfolio-risk-audit, portfolio-settlement-audit, risk-gateway, risk-gateway-process,
portfolio-risk-replay, incident-drill, g2-contracts and portfolio-trial-evaluation.
Generated work stays in fresh local validation directories.

The commit-identified production JAR must be built and checked after the local
source commit; no dirty precommit artifact is presented as that build. Its exact
commit/hash and packaged test result are recorded in the post-commit validation
update. No hosted result is implied.

## Boundaries and remaining human work

These are synthetic software checks only. Hashes establish byte identity, not
supplier identity, historical viewing/custody, reviewer qualifications or
independence, rights or completeness against omitted evidence. Verification of an
old report is historical byte verification under its explicit as-of, not current
permission to operate. Fresh evidence requires a new review as documented.

The tested file-safety boundary is ordinary local Windows/NTFS with exclusive
control of source/output parents. It includes malformed UTF-8/schema rejection,
traversal, symlink and junction escape, hard-link aliases, mutation detection,
output collisions, competing publications and injected pre-publication failure.
It does not establish trusted storage, power-loss durability, global prevention
of forks/full-history forgery, or resistance to privileged/noncooperating mount,
ancestor or metadata replacement. Generic schema validation does not replace the
runtime's semantic/chronology/canonical checks.

The code cannot authenticate an observed running environment or account, close an
incident, release a halt, resume operation, modify settings/limits or raise
capacity. Those actions require separately controlled human procedures and
authorization. Statistical estimates and capacity claims remain separate.

Hosted CI has **not run for this G7 revision**. Local results do not inherit
historical hosted results from other commits. No push or merge is authorized or
performed by this task.
