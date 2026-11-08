# G3 validation and publication record

This packet changes documentation and retained documentary/synthetic evidence only.
No Java, strategy, statistics, fill/cost/risk configuration or custody implementation
changed. Full selected Java 21 validation is recorded below after completion;
candidate market analyses remain NOT TESTABLE / INCOMPLETE independently of tests.

## Inspection and attempts

- Original checkout: dirty `main` at `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`;
  branches/worktrees/remotes/status inspected before edits and preserved.
- `git fetch origin main` initially failed on sandbox `.git/FETCH_HEAD` access;
  approved retry succeeded. Isolated branch/worktree started at freshly fetched
  `0ae90aadc58fd86a4835917ffbbf992a7870bcf2` (main advanced to G0 during setup).
- Initial CI attempt at that clean base failed before suites because the sandbox
  denied reading the configured pinned JDK `release`. It generated no market
  outcomes. Approved retry uses a new evidence root, not overwritten logs.
- `rg` and `gh` were absent. Public Actions API read initially failed on sandbox
  socket permissions; approved retry verified base run
  [42447304687](https://github.com/Mscient/Intra/actions/runs/42447304687), exact
  base SHA, status completed, conclusion success. This is base hosted evidence,
  not branch hosted success.

Commands from the isolated repository root:

```powershell
git status --short --branch
git branch -vv
git worktree list --porcelain
git fetch origin main
git worktree add -b codex/g3-strategy-evidence-20261108 g3-worktree origin/main
.\.github\scripts\test-offline-ci.ps1 -OutputRoot C:/Users/prash/AppData/Local/Temp/codex-intra-origin-20261103/g3-baseline-ci-retry -ExpectedCommit 0ae90aadc58fd86a4835917ffbbf992a7870bcf2
```

The matrix is the checked-in Windows Java 21 runner: 14 selected suites, production
build and 13 build/package/drill/manifest checks. G1/G2 unintegrated tests and the
uncommitted holdout suite are not presented as main's selected matrix.

## Documentary verification

Observed PASS: 54 upstream source identity rows; 13 exact retained snapshots;
three copied unapproved cards with nine matching cost/variant/source attachments;
two required candidate slots with null bindings/financial values and INCOMPLETE
decisions; no gate/operation/performance authorization. The packet SHA manifest
was regenerated twice from frozen files and compared byte-for-byte.

Four documentary negative checks on separate throwaway copies passed: changed
decision bytes differ from the expected hash; missing card fails file hashing;
changed cost bytes fail the card attachment; refreshing the manifest after
tampering differs from the retained original manifest digest. This is local byte
integrity, not independent custody, source authentication or a general ingestion
validator. The expected digest is local and remains UNVERIFIED as an independent
anchor. The operator recipe assumes reviewed ordinary packet files/layout.

SHA256.csv excludes itself, this validation narrative and `validation/` logs.
Their identities are recorded separately in validation/EVIDENCE-SHA256.csv after
checks finish. Snapshots retain exact upstream bytes, including original link
context; their links were not rewritten to alter their source identity.

## Java/build results

**PASS:** all 14 selected source suites and 13 build/packaged/drill/manifest checks
completed on clean source revision `0ae90aadc58fd86a4835917ffbbf992a7870bcf2`.
UTF-8/LF copies of the [summary](validation/summary.log),
[environment](validation/environment.log) and [manifest check](validation/manifest-check.log)
are retained. [Original log identities](validation/original-log-identities.csv)
record exact original byte hashes before line-ending normalization; raw logs remain
unchanged in the outside-checkout evidence root. No snapshot bytes are normalized.
The observed
local log creation times were 04:38:54–04:54:55 UTC; these are filesystem
observations, not independent timestamps or an execution SLA.

Microsoft OpenJDK `21.0.13.0.2+1-LTS`, vendor `Microsoft-15780113`, javac
`21.0.13.0.2`, PowerShell `7.6.9`, Windows/x86_64. Production JAR remains local at
`g3-baseline-ci-retry/package/intra-paper-system.jar`, SHA-256
`89e557af6ff5b25bf118a756585aa4d5e8ec3e320e64cf5cef67d31b4263b32a`.
Exactly one Build-Commit matches the tested source SHA above. No JAR is claimed to
have been built at a later documentary commit.

The Java production source-tree Git object is
`692519f90c3d26b576ecbcac1c60c6d7cdf6d4ac`. The packet changes no Java, tests,
runtime settings, build or CI script; that exact source tree and executable
validation inputs remain unchanged. The full matrix tests the reused software at
the base revision; documentary identity/null/status/links/diff checks validate this
new packet separately. No repeated matrix for a documentation-only change is
presented as new strategy evidence.

| Selected suite | Observed result |
| --- | --- |
| Risk gateway | PASS, 447 checks / 13 groups |
| Risk gateway process recovery | PASS, 728 checks / 5 groups; 308 child JVMs, 24 controlled terminations |
| Portfolio replay/cost/artifact/settlement | PASS, all four entrypoints; deterministic reconstruction and tamper/signed-partial/date/UTF-8/charge checks |
| Portfolio risk replay | PASS, 84 checks plus shared replay/cost/artifact/settlement regressions |
| Portfolio accounting | PASS; existing runner reports no numeric assertion count |
| Portfolio risk/settlement risk audit | PASS, 79 / 274 focused checks |
| Portfolio settlement audit | PASS, 88 focused checks |
| Registered trial evaluator / HAC | PASS, 165 / 30,019 checks; existing DSR/PBO unchanged, synthetic sealed-string invariance only |
| Daily operations evidence | PASS, 455 assertions; source and production JAR; nine synthetic drills |
| Intended-use preflight | PASS, 300 checks; source/JAR plus expected-status synthetic drill |
| Permission coverage | PASS, 102 scenarios / 1,648 assertions; source/standalone/production JAR |
| Incident drill | PASS, 198 checks; standalone/production JAR and expected-status scenarios |
| Feed audit | PASS, 507 checks in source and production JAR; packaged synthetic drill PASS |
| G0 charter | PASS, 551 checks in standalone/production JAR, three separate-JVM locale/timezone reproductions/verification per mode |

Counts are the existing runners' outputs, not a summed independent-sample count.
The [evaluator log](validation/portfolio-trial-evaluation.log) records frozen
synthetic HAC ratios at largest declared bandwidth: IID 0.9650831061571195,
AR1 0.9415661262572579, VOL 0.9713234633127813. They reproduce the protocol's
software qualification only, not market profitability or general coverage.

The existing evaluator suite checks repeat bytes, locale/timezone/order invariance,
complete-family and mismatch rejection, rehashed artifact/ledger tampering and
synthetic holdout-field exclusion. Replay verifier checks frozen input/config/build
identity and regenerated outputs. The actual uncommitted one-shot controller suite
was not part of this main revision and was not executed by this session.

## Git and hosted scope

The G3 packet, session and trackers are committed after packet verification and
`git diff --check`. Commit/push observations are appended after publication.
Base hosted Actions run 42447304687 completed successfully at the exact base SHA.
No G3-branch hosted success is claimed: the workflow triggers main pushes, PRs or
manual dispatch; a codex branch push alone does not trigger it. No merge or live
release is authorized by this deliverable.

Scope limits: synthetic fixtures only; one Windows host and pinned JDK. No
independent market/source/account validation, power-loss durability, operational
paper sessions, general statistical coverage or strategy-performance claim.
