# G1 local validation evidence

Prepared 2026-11-08. **Software/synthetic evidence only. G0 OPEN; G1 INCOMPLETE.**
The complete selected Windows Java 21 matrix was tested on clean implementation commit
`9accd8296c12eb901cab3f64917bc798c66729c8`, branch
`codex/g1-foundations-20261108`, based on freshly fetched `origin/main`
`ff4874167e889a26990e1f4d9d2b1ffa4acbefe6`. A publication-time fetch confirmed
main still has the same G0 open-items record. The original dirty checkout and
unrelated worktrees were preserved.

The evidence/documentation commit that follows records this tested implementation
revision; it does not claim that its own different commit ran the entire matrix.
A final clean-commit production build/manifest check is recorded in the delivery
report. No hosted GitHub Actions result is claimed. The workflow triggers main
pushes, main-targeting PRs and manual dispatch; this task publishes a branch only.

## Environment and exact commands

Microsoft OpenJDK **21.0.13.0.2+1-LTS**, `JAVA_VERSION=21.0.13.0.2`,
`IMPLEMENTOR_VERSION=Microsoft-15780113`, PowerShell **7.6.9**,
Windows 11 x64, NTFS. Installation:
`C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot`.
The runner verifies java, javac, vendor/version, expected commit and clean checkout.
All runs are offline; outside-sandbox execution approval was needed because the
sandbox denied Java classpath access. No dependencies, market data or credentials
were acquired.

From the repository root:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot'
./.github/scripts/test-offline-ci.ps1 -OutputRoot 'C:/Users/prash/AppData/Local/Temp/codex-g1-ci-fixed-20261108' -ExpectedCommit 9accd8296c12eb901cab3f64917bc798c66729c8
```

The matrix invokes 14 source runners and every selected build/package/drill/
standalone/manifest check. Their exact invocations, results and counts are in
the retained logs. It is a selected matrix, not exhaustive verification of every
project behavior. Repeated groups are not added into an artificial aggregate
assertion count.

## Complete matrix outcome and package

**PASS: 14 source suites + 13 selected build/package/drill/standalone/manifest stages
(27 stages, excluding the overall status header).** All reported native exits were
zero at the runner level. Expected negative fixture verdicts/exits are checked by
their runners; they are not test-suite failures.

| Remaining matrix stage | Result/count actually emitted |
| --- | --- |
| production-build | PASS |
| daily-ops-packaged | PASS, 455 assertions / 5 groups |
| daily-ops-drill | PASS, 9 synthetic scenarios |
| dataset-intended-use-packaged | PASS, 300 checks |
| dataset-intended-use-drill | PASS, 10 synthetic CLI checks |
| data-permission-coverage-standalone-build | PASS |
| data-permission-coverage-standalone-test | PASS, 102 scenarios / 1,648 assertions |
| data-permission-coverage-packaged | PASS, 102 scenarios / 1,648 assertions |
| incident-drill-packaged | PASS, 198 checks + 9 synthetic scenarios |
| feed-audit-packaged | PASS, 507 checks |
| feed-audit-drill | PASS, 557 checks |
| hypothesis-card-packaged | PASS, 168 checks |
| manifest-check | PASS, exactly one matching Build-Commit |

Production package retained at
`C:/Users/prash/AppData/Local/Temp/codex-g1-ci-fixed-20261108/package/intra-paper-system.jar`.
Main-Class `com.mscient.intrapaper.cli.IntraPaperCli`,
Implementation-Version `1.0.0`, Created-By `21.0.13.0.2 (Microsoft)`,
Build-Commit `9accd8296c12eb901cab3f64917bc798c66729c8`.
JAR SHA-256: `491f24bb5226562e7a4ebb343d03d6fb7b3205220c9d67b4f5186a89b3e8df4f`.

Retained [summary](fixed/summary.log), [environment](fixed/environment.log),
[manifest check](fixed/manifest-check.log), all fixed-run logs, and selected
[first-run failure evidence](first/summary.log) accompany this report.
These are **LF-normalized UTF-8 transcript copies**, not market evidence.
[Log hash inventory](log-hashes.csv) records both original and copied SHA-256
values. Original raw logs/generated fixtures remain in the two absolute output
roots above; no market rows or private owner records are included.

## Counts actually reported

| Source suite | Actual result/count |
| --- | --- |
| risk-gateway | PASS, 447 checks / 13 groups |
| risk-gateway-process | PASS, 728 checks / 5 groups, 308 child JVMs / 24 controlled terminations |
| portfolio-replay-engine | PASS, replay, execution-cost, artifact-verifier and signed-settlement groups; runner emits no assertion count |
| portfolio-risk-replay | PASS, 84 checks plus repeated replay/cost/artifact/settlement groups |
| portfolio-accounting | PASS; runner emits no assertion count |
| portfolio-risk-audit | PASS, 79 audit + 274 settlement-risk checks |
| portfolio-settlement-audit | PASS, 88 checks |
| portfolio-trial-evaluation | PASS, 165 evaluator + 30,019 HAC checks, plus repeated replay/cost/artifact groups |
| daily-ops-evidence | PASS, 455 assertions / 5 synthetic groups |
| dataset-intended-use-preflight | PASS, 300 synthetic checks |
| data-permission-coverage | PASS, 102 scenarios / 1,648 assertions |
| incident-drill | PASS, 198 checks and 9 synthetic scenarios with expected exits |
| feed-completeness-audit | PASS, 507 checks |
| hypothesis-card | PASS, 168 checks |

## Earlier failure and fix

The first full matrix used clean `ebd1a0290a0e2eeb03f5dd4326edb7ec1d8516fa`:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot'
./.github/scripts/test-offline-ci.ps1 -OutputRoot 'C:/Users/prash/AppData/Local/Temp/codex-g1-ci-20261108' -ExpectedCommit ebd1a0290a0e2eeb03f5dd4326edb7ec1d8516fa
```

Seven legacy runners could not launch javac because direct source filename lists
exceeded the Windows command-length limit in the long worktree path. Replay,
risk-replay, accounting, risk-audit, settlement-audit, trial-evaluation and
daily-ops failed; the other seven source suites passed. Production build and all
package/manifest stages were correctly NOT_RUN. No first-run success is claimed.
Commit `9accd82` changes those seven source lists to UTF-8 javac argument files
with unchanged source/test sets and Java 21 options. The complete matrix was
rerun from a fresh output root.

Initial focused packaged tests passed 163 checks; strengthened false-approval/
permission/gate/placeholder coverage increased the final source and packaged
suite to 168. Initial diagnostic test-compilation/classpath failures were corrected
and are not represented as passing runs. Final focused source evidence is retained
at `C:/Users/prash/AppData/Local/Temp/codex-g1-focused-20261108/final-source-approved/42bdc4682cc74d2bba1e6a74ca6596e6`.

## Independent existing pricer validation

From `Intra-Paper-System/`, on clean `ebd1a0290a0e2eeb03f5dd4326edb7ec1d8516fa`:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot'
$env:LOCALAPPDATA = 'C:/Users/prash/AppData/Local/Temp/codex-g1-pricers-20261108/local'
./validate-option-pricers.ps1
```

[Retained pricer log](pricers.log). PASS: R11 **22** cases (12 BSM / 10 Black-76), boundary **16**, no-arbitrage
**5,805**. QuantLib cross-check is separate and **was not rerun**.
The pricer code/reference fixtures were unchanged by the later argument-file fix.
Its production JAR manifest had `Build-Commit: ebd1a0290a0e2eeb03f5dd4326edb7ec1d8516fa`,
`Main-Class: com.mscient.intrapaper.cli.IntraPaperCli`,
`Implementation-Version: 1.0.0`, Microsoft Created-By 21.0.13.0.2.
JAR SHA-256: `b8a7dfb3673030145c34d67a58c046c443cd290891ba6a78018fff2af7dfb011`.

## Artifact/privacy checks and limits

- Ten changed PowerShell runners/wrappers parse without diagnostics.
- Initial nine new Markdown files have 25 resolving local links; final publication
  documentation audit passed for 11 Markdown files and 39 local links.
- Cost register: 24 rows, 13 fields, zero nonblank actual amounts. Every unknown
  remains blank/UNKNOWN/PENDING_SOURCE_REVIEW/PENDING_OWNER_INPUT.
- Three authored cards have null freeze times, 15 pending review records and
  UNRESOLVED market rights; all nine companion bindings match exact SHA-256 bytes.
- Credential-shaped assignment scan across 79 changed/new files found zero matches; manual review
  found no actual owner amounts, account inputs or credentials. A pattern scan is
  not a general secret-detection guarantee.
- `git diff --check ff48741 HEAD` and final `git diff --check` pass.
- Stable original-byte card identities, strict parsing, duplicate/unknown fields,
  unresolved values, schema equality, changed-version/predecessor behavior,
  immutable frozen snapshots, false review/permission/gate claims, proposed/
  synthetic trial refusal and toy FIFO/partial/missed-IOC mechanics are tested.
- No market experiment, source acquisition, provider contact, broker/network route,
  private-owner record, holdout release or formula change occurred. Test freezes,
  review hashes and economics are fictional plumbing fixtures.
- Local receipts/timestamps do not authenticate independent preregistration;
  all CLI approval/permission/eligibility/gate/market-evidence flags remain false.
  Process kills do not prove power-loss durability. Synthetic HAC processes and
  numerical pricer agreement do not establish market accuracy, profitability,
  trading readiness or legal compliance.

See the [G1 packet and exact B00–B11 blockers](../G1-FOUNDATIONS-2026-11-08.md)
and [private owner/reviewer checklist](../G1-OWNER-REVIEW-CHECKLIST.md).
