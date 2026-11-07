# Local project consolidation and validation

- **Session UUID:** `f942df04-f946-4308-93f3-83b9289bd66d`
- **Date:** 2026-11-07, Asia/Kolkata.
- **Branch:** `codex/latest-build-consolidation-20261107`.
- **Validated source commit:** `bf0644a8fe3c632036b7369d2d857b1303a401a0`.
- **Base:** fetched `origin/main` `fe552d268f9826404610b722acd4c648501dc95f` plus reviewed integration and task commits.
- **Original checkout:** preserved with all concurrent working changes; no reset, cleanup, or direct commit from that checkout.

## Consolidated work

The branch includes the reviewed settlement/risk/evaluation integration, the Java 21 CI commit `138a84de5cf06c78b2b1bfc4cba6e0b9c28284ba`, the Sharpe HAC implementation (`a3f3616` plus canonical protocol-hash follow-up `55ad3cb`), the offline daily-operations checker and risk-gateway process-recovery harness (`814894a`), and the G2 source-research package (`e816a0c`). CI now runs nine offline suites, including process recovery and daily-operations source checks, then tests the daily-operations checker and drill against the built JAR before accepting the manifest identity.

The research pack contains 12 reports and 42 candidate rows. They remain pending independent source/rights/applicability review and production-ineligible; G2 remains open. Daily-operations results remain caller-supplied diagnostic evidence; G4 remains open. HAC remains standard-error-only and synthetic-qualified only for its three frozen processes; it does not alter DSR/PBO or support a strategy-performance claim.

## Full local CI run

The committed CI runner completed with **PASS** on Microsoft JDK **21.0.13.0.2**, Windows 11 / amd64. All nine suites, production build, packaged daily-operations checks, synthetic drill, and manifest verification passed:

| Suite / check | Result |
|---|---|
| Risk gateway | PASS — 447 checks |
| Risk gateway process recovery | PASS — 728 checks, 308 child JVMs, 24 forced terminations |
| Portfolio replay engine | PASS |
| Portfolio risk replay | PASS — 84 checks plus replay/cost/artifact/settlement regressions |
| Portfolio accounting | PASS |
| Portfolio risk audit | PASS — 79 audit + 274 settlement checks |
| Portfolio settlement audit | PASS — 88 checks |
| Portfolio trial evaluation | PASS — 165 evaluator checks + 30,019 HAC checks and regressions |
| Daily-operations evidence source suite | PASS — 455 assertions |
| Production JAR build | PASS |
| Packaged daily-operations evidence | PASS — 455 assertions |
| Packaged nine-case synthetic drill | PASS; expected PASS/FAIL/INCOMPLETE classifications reproduced |
| JAR manifest | PASS — `Build-Commit` exactly matches validated source commit |

HAC synthetic largest-bandwidth RMS-SE / empirical-Sharpe-SD ratios were IID `0.965083`, AR(1) `0.941566`, and volatility-clustering `0.971323`, each inside the predeclared `[0.80, 1.20]` range. The scope is only those frozen synthetic processes. Process termination does not establish power-loss durability.

The validation log root is `C:\Users\prash\AppData\Local\Temp\codex-latest-offline-ci-run\logs`; the tested JAR is `C:\Users\prash\AppData\Local\Temp\codex-latest-offline-ci-run\package\intra-paper-system.jar` with SHA-256 `a2857bddd02b305a2da08c21c9746935e948ba785109aa0323f36bcc35498c81`. A final documentation/index commit and clean build follow this source validation. Hosted GitHub Actions is not claimed by this local report.

## Publication scope and limits

The user requested consolidating current systems, commits, and GitHub. This branch is a fast-forward descendant of the fetched `origin/main`. The original local `main` has unrelated concurrent history/worktree changes, which remain preserved and are not used as the publication base; reviewed equivalents and the specified current research/checker work are integrated here. No market data, broker/account credentials, live connection, or order route was used. G0/G2/G3/G4 and other evidence gates remain open.

## GitHub publication

- **Published `main`:** `2fb5ce9962f31a0a4790f917a5e85db93976f5ff` (fast-forward from `fe552d268f9826404610b722acd4c648501dc95f`).
- **Hosted validation:** GitHub Actions run [42338310759](https://github.com/Mscient/Intra/actions/runs/42338310759) completed successfully on the published commit. This ran the committed Windows Java 21 offline-validation workflow. A following documentation-only commit records this result; the run validates identical production and test sources.