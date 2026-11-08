# G0 validation record — version 1

**Local software validation PASS. Actual G0 remains INCOMPLETE.** All 14 selected
source suites and 13 build/packaged/manifest checks passed at clean implementation
commit `efc788cff30dc7acf607a0766cddddf35cc36738`. This record's later documentation
revision does not change Java, runners, normative field headers or fixture bytes. Its final build
identity and hosted outcome are reported separately in the completion response.
No hosted success is asserted by this pre-publication record.

Base: freshly fetched `origin/main` `ff4874167e889a26990e1f4d9d2b1ffa4acbefe6`.
Branch: `codex/g0-charter-20261108`. Implementation lineage:
`c6263b6d6984d9f98bbf7743adf9dd7661e3f21a` (package/research),
`4dbdeac65a7bfeb193f82410abef35743c8d5699` (unknowns/row blockers),
`efc788cff30dc7acf607a0766cddddf35cc36738` (Windows argument files/output isolation,
placeholder checks). A fresh publication-preparation fetch still returned the base;
`git merge-base --is-ancestor origin/main HEAD` succeeded. Unrelated worktrees
were neither overwritten nor cherry-picked; initial states are in DECISIONS.md.

Pinned environment: Microsoft JDK `21.0.13.0.2+1-LTS`, implementor Microsoft,
implementor version `Microsoft-15780113`, installed directory label
`jdk-21.0.13.21-hotspot`. The directory label is not the runtime version.
Executable Java: `21.0.13.0.2`; javac: `21.0.13.0.2`; Windows 11 amd64/NTFS;
PowerShell `7.6.9`. The local run used the installed JDK, verified its release and
executable identities, and did not reinstall/download it. Hosted provisioning uses
the existing pinned archive installer; that provisioning is not a local-run claim.

Commands from the isolated repository root (temporary paths rendered through
`$env:TEMP`, rather than publishing the OS user's absolute directory):

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
.\.github\scripts\test-offline-ci.ps1 `
  -OutputRoot (Join-Path $env:TEMP 'g0-clean-ci-20261108-efc788c') `
  -ExpectedCommit efc788cff30dc7acf607a0766cddddf35cc36738
git diff ff4874167e889a26990e1f4d9d2b1ffa4acbefe6 HEAD --check
git status --porcelain
```

Runner exit **0**, `VALIDATION_STATUS=PASS`, every result below PASS. Retained
environment/summary timestamps: **2026-11-08 03:50:38–04:02:57 UTC** (12m19s).
Artifacts: `$env:TEMP\g0-clean-ci-20261108-efc788c\`; exact per-run commands,
entrypoints, stdout/stderr, exit codes and identities are in `logs/`.

| Selected source suite | Exact emitted count / coverage |
|---|---|
| risk-gateway | 447 checks, 13 groups |
| risk-gateway-process | 728 checks, 5 groups; 308 child JVMs, 24 forced terminations |
| portfolio-replay-engine | Replay, cost, artifact verifier and settlement entrypoints; no numeric count emitted |
| portfolio-risk-replay | 84 checks plus the shared replay/cost/artifact/settlement entrypoints |
| portfolio-accounting | Accounting entrypoint; no numeric count emitted |
| portfolio-risk-audit | 79 audit and 274 settlement-risk checks |
| portfolio-settlement-audit | 88 checks |
| portfolio-trial-evaluation | 165 evaluator, 30,019 Sharpe HAC checks plus shared replay/cost/artifact entrypoints |
| daily-ops-evidence | 455 assertions, 5 synthetic groups |
| dataset-intended-use-preflight | 300 checks |
| data-permission-coverage | 102 scenarios, 1,648 assertions |
| incident-drill | 198 checks and nine synthetic scenarios |
| feed-completeness-audit | 507 checks |
| g0-charter | 551 checks; 3 child-JVM runs and 3 exact verifications |

| Build / packaged check | Exact emitted count / coverage |
|---|---|
| production-build | JAR produced from clean exact commit |
| g0-packaged | 551 checks; 3 child-JVM runs and 3 exact verifications |
| daily-ops-packaged | 455 assertions, 5 groups |
| daily-ops-drill | Nine scenarios with expected PASS/FAIL/INCOMPLETE outcomes |
| dataset-intended-use-packaged | 300 checks |
| dataset-intended-use-drill | Expected synthetic permission outcomes |
| data-permission-coverage-standalone-build | Separate JAR produced |
| data-permission-coverage-standalone-test | 102 scenarios, 1,648 assertions |
| data-permission-coverage-packaged | 102 scenarios, 1,648 assertions |
| incident-drill-packaged | 198 checks and nine scenarios |
| feed-audit-packaged | 507 checks |
| feed-audit-drill | 557 checks |
| manifest-check | Exactly one Build-Commit equals expected/source commit; SHA-256 retained |

These counts deliberately include repeated shared regressions; they are not a
count of unique repository tests. No broader repository coverage is claimed.

G0 artifacts relative to the retained root:

- Standalone checks: `g0-source/17f250849fcf4524852029aa4e69a12a/`.
- Production-JAR checks: `g0-packaged/07ae8ddfb07d474da33dfba89d33d1c7/`.
- Production JAR: `package/intra-paper-system.jar`, SHA-256
  `36edbcca0527b9fe47e23a8d9ec6e05ea749e087611d61cfc748aba4663bff08`.
- Standalone G0 JAR SHA-256:
  `8df44143fe2d20846a0fa9d1aadadcd56211a5f3742b300ea302a667e82b8f5e`.
- English/UTC, Turkish/Apia and Japanese/Kolkata report bytes matched within each
  package run. Standalone repeat digest:
  `6214bf18ed6458089144f0d39ffac73eebc0a018a5ed61f533035e234c676940`;
  production repeat digest:
  `886a0796a867ca049d4c1e69c453d3c0a27d9b595d9d672e5becd9ac30bc77c1`.
- Dependency inspection is retained in each G0 run's `dependencies.txt`; no
  simulator/strategy/risk/shared-CLI/broker/network dependency was introduced.

Additional entrypoint smoke artifacts:
`$env:TEMP\g0-cli-smoke-efc788c-20261108\smoke.log`. Both `java -jar` and
`g0-charter.ps1 -JarPath <standalone-jar>` ran `run examples/g0-charter <new-dir>`
with exit **2 / INCOMPLETE**, then `verify examples/g0-charter <report> <retained-sha>`
with exit **0**. Both reports had SHA-256
`0dce4db318553a6adda7695d3b7f435bbbf53452b2041ada1f0a95afb68b1e36`.
Successful verification preserves the incomplete gate state.

Failures corrected before the passing run:

- The first full attempt at `4dbdeac...` passed 7/14 suites (G0: 547 checks) but
  seven existing all-source runners exceeded Windows' command-line length.
  Production checks were correctly NOT_RUN. Retained failed logs:
  `$env:TEMP\g0-clean-ci-20261108-4dbdeac\logs\`. Runner repairs began only after
  those failed runners had completed; the failed run is not clean-release evidence.
  Seven runners now use UTF-8 argument files; two formerly generated ignored output
  inside the checkout and now use CI's redirected LOCALAPPDATA.
- Initial Git whitespace checking flagged meaningful terminal tabs in two pending
  TSV registers. Narrow attributes preserve their empty cells; strict parsing still
  rejects surrounding cell whitespace. Explicit staged/base diff checks passed
  after that correction. Initial feature-commit checking was not a passing check.

The first sandbox attempt compiled G0 but failed Java canonical-path/JAR inspection
with AccessDeniedException in temporary output. It was rerun with approved normal
filesystem access; no path-security requirement was weakened to accommodate sandboxing.

Limits: all test data and review assertions are synthetic; no private owner inputs,
broker credentials, accounts, feeds or orders were used. No legal interpretation,
data-rights grant, broker authorization, reviewer identity/signature verification,
power-loss/hostile-filesystem/remote-storage guarantee or live readiness is proved.
All 51 actual owner/assumption decisions, account-specific applicability/rights and
independent human review remain pending. G0 is not closed. Hosted Actions success
requires an actually completed successful run at the published revision.
