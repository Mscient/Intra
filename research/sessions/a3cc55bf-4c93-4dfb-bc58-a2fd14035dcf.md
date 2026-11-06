# Reviewed local-only integration

- Session UUID: a3cc55bf-4c93-4dfb-bc58-a2fd14035dcf
- Date: 2026-11-06, Asia/Kolkata
- Objective: review the four local-only commits against current GitHub main and prepare a clean, validated integration branch without pushing or modifying the original checkout.
- Original checkout: `C:/Users/prash/AppData/Local/Temp/codex-intra-origin-20261103`, `main` at `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`.
- Integration worktree: `C:/Users/prash/AppData/Local/Temp/codex-local-integration-review-20261106`.
- Integration branch: `codex/local-integration-reviewed-20261106`.
- Base: `fe552d268f9826404610b722acd4c648501dc95f`. Fetched before branching and again after validation; GitHub main remained at this revision.

## Review and resulting commits

Read the repository session protocol, README, book tracker, implementation trackers, four local commits, divergent published history, working-tree diff, Java implementations, focused tests, contracts and examples. No external market sources or book research were used; book/access statuses did not change. The user's explicit local-only instruction supersedes the session protocol's main/push step.

| Commit | Reviewed integration |
| --- | --- |
| `2b762e5481d060118f324a5dc59a392c6cce26a0` | Durability fix and its records from `759d5ba` / `49e584c`; isolated from settlement/evaluation. |
| `b25426f933fa77d398b9f53b0027b1eb75aff76a` | Missing settlement-aware evaluation, strict UTF-8 decoding, examples and records from `c1ed5c6` / `2b7fe0a`; 29 reviewed files. |

The ten risk-audit implementation/fixture/runner/contract files from the local settlement commit were already identical to published `722f855` on origin/main. They were retained without duplicate implementation changes. Published R14, risk-replay and settlement-review session records and tracker evidence were preserved. Conflicting local Task 4 record changes were resolved by retaining published history and adding the local follow-up reference.

The session index on origin/main had the end of session `034ff2e1` joined to the beginning of `9296420b`; the missing newline was repaired while preserving both records. Only the local durability and settlement commit-index rows were added. The local commit's dangling Task 5 G2 README link was omitted, together with all uncommitted G2 and concurrent subprocess/CI changes.

The evaluator retains settlement-disabled parameter identity V1, binds enabled settlement policy/targets/event/cost bytes through V2, supports explicit full-split or development/validation-prefix coverage, and includes terminal validation settlement returns exactly once. Holdout accounting remains structurally checked without financial interpretation in full-split mode.

## JDK 21 validation

Microsoft OpenJDK `21.0.13.0.2+1-LTS`, vendor `Microsoft-15780113`, Windows 11 amd64. JDK path: `C:/Users/prash/AppData/Local/Programs/Microsoft/OpenJDK21/jdk-21.0.13.0.2+1`.

| Check | Result on integrated source |
| --- | --- |
| `test-risk-gateway.ps1` | 447 checks in 13 groups passed against its dedicated packaged risk classes. |
| `test-portfolio-trial-evaluation.ps1` | 162 evaluator checks passed, plus replay, execution-cost and v3 artifact-verifier regressions. |
| `test-portfolio-risk-audit.ps1` | 79 audit checks and 274 settlement-risk fixture checks passed. |
| `test-portfolio-replay-engine.ps1` | Replay, execution-cost schedule, v3 publication/tamper/signed-partial/determinism, signed settlement, strict UTF-8, dates, charges and atomicity passed. |
| `test-portfolio-accounting.ps1` | Portfolio accounting regressions passed. |
| `test-portfolio-settlement-audit.ps1` | 88 sidecar checks passed from source, then 88 passed against the clean integration JAR. |
| `PortfolioRiskReplayTest` | 84 checks passed from source using a javac argument file; the packaged risk-replay runner also passed 84 plus its replay/cost/artifact/settlement regressions. |
| `build.ps1` | Clean source build at `b25426f`; embedded JAR manifest inspected and matched that full commit hash. |
| Packaged settlement example | Replay and deterministic verification exited 0; all eight replay output files reproduced byte-for-byte. |
| Packaged risk example | Audit and semantic verification both returned expected BREACH/exit 2: exactly one `SESSION_LOSS_INR` breach at SETTLEMENT, actual 4.83 versus cap 4; zero final gross exposure/margin proxy and zero coverage issues; both reconciliations PASS. |
| Whitespace and scope | Working-tree/staged and base-to-integration `git diff --check` passed; staged lists reviewed; excluded G2 files and row absent. |

The original evaluator regression was strengthened: shifting one dataset final-close timestamp backward by one second is rejected before rehashing, then is rejected specifically with `Identity mismatch: portfolio final bar close timestamp for <instrument>` after output hashes and catalog manifest pins are refreshed. This occurs in the sealed full-split fixture. Its equity, settlement values/P&L/fees, and charge rates/amounts are replaced with nonnumeric `SEALED_HOLDOUT`; evaluation succeeds with unchanged statistics/session returns before the structural timestamp mutation. Thus rejection is tied to actual final BAR_CLOSE metadata, while holdout financial fields remain numerically unread.

## Packaged identity and retained local evidence

- JAR: [intra-paper-system.jar](../../Intra-Paper-System/build/integration-verified/intra-paper-system.jar).
- SHA-256: `ebcdc3a5750a97f33aa6753410a57607480e38f45de3ed6d9fe43de775093e64`.
- Embedded `Build-Commit`: `b25426f933fa77d398b9f53b0027b1eb75aff76a`; `Created-By: 21.0.13.0.2 (Microsoft)`.
- Later review/index commits contain documentation only. The tested JAR remains identified with its clean implementation commit, rather than relabeled as a build of later documentation commits.
- [Packaged example evidence](../../Intra-Paper-System/build/integration-verified/example-evidence.json) records replay ID `c896baa3a1d7dcce5292b3592eb4fb088c93282874d16a878022e745d6b037ee` and risk audit ID `50e169ddfdb314a4aafb999db0656f8c147813ee74f3803794a82e846beb2863`.
- Example totals: two settlements, three settlement-cost lines, final gross P&L -1, ordinary fees 2, settlement fees 1.83, net P&L -4.83.
- Risk durability evidence is retained in ignored `Intra-Paper-System/build/risk-gateway-verification/3738d9a812c5402ba9f8a5426fdf1d98`; its tested package SHA-256 is `8efa583465d0f4a9b80627d5786900afdc80f64d73adad653c3ebf66ebadafc0`.
- The reviewed implementation file list is retained in ignored `Intra-Paper-System/build/integration-verified/reviewed-file-list.txt`; Git history is the committed file-list authority.

## Original-checkout preservation and concurrent work

No reset, clean, rebase, staging, commit, or working-tree write was performed in the original checkout. Fetch/worktree/branch operations wrote only the necessary Git metadata. Original main remained at `2b7fe0a`, 4 ahead and 9 behind the confirmed origin/main.

At the start, the original checkout contained the modified G2 session index, untracked session `f014bb72-4737-46e7-ad0c-cde38e489c55.md`, and `g2-source-research-2026-11-05/`. A SHA-256/length snapshot covered 22 files. At final verification, all 21 G2 session/package files remained byte-for-byte unchanged. The original index's initial 30,317 bytes also remained byte-for-byte unchanged; concurrent work appended a 470-byte row for `3f008e30-adf6-43e1-aeae-ddc231806ed9`, producing a 30,787-byte file. The changed whole-file hash is therefore expected and must not be reported as an unchanged original index.

Additional uncommitted subprocess-verification/CI work appeared during this review. It was preserved and excluded. The observed original status is recorded below; this review did not validate or adopt those new files or their reported test claims.

```text
 M .github/workflows/java21-build.yml
 M Intra-Paper-System/IMPLEMENTATION-TRACKER.md
 M Intra-Paper-System/README.md
 M Intra-Paper-System/docs/INDEPENDENT-RISK-GATEWAY.md
 M README.md
 M research/SESSION_INDEX.csv
 M research/systems/IMPLEMENTATION-TRACKER.md
?? .github/scripts/
?? Intra-Paper-System/docs/WINDOWS-JAVA21-CI.md
?? Intra-Paper-System/src/test/java/com/mscient/intrapaper/riskgateway/RiskGatewayProcessTest.java
?? Intra-Paper-System/test-risk-gateway-process.ps1
?? research/sessions/3f008e30-adf6-43e1-aeae-ddc231806ed9.md
?? research/sessions/f014bb72-4737-46e7-ad0c-cde38e489c55.md
?? research/systems/2026-11-06-risk-gateway-process-recovery-verification.md
?? research/systems/g2-source-research-2026-11-05/
?? research/systems/risk-gateway-process-recovery-2026-11-06/
```

## Limits and next actions

No push or GitHub merge was performed. All evidence is focused offline/synthetic validation; no hosted CI or trading-readiness claim is made. Settlement values, source authenticity, rights and tariff completeness remain caller assertions; margin remains a proxy and does not implement exchange SPAN/ELM or certify collateral release. G0/G2 remain open. Durability evidence here covers same-JVM concurrency and injected failures; trusted checkpoint storage, power-loss/full-disk and process isolation are not established by this integration. The concurrent subprocess package was explicitly excluded.

The existing source risk-replay runner exceeds Windows' command-line length limit at this worktree path because it passes all production/test paths to javac in one invocation and selects Java from PATH. Its runner source was left unchanged. An argument-file source compilation with the same JDK passed all 84 risk-adapter checks; packaged mode also passed after placing the exact JDK on PATH. Retained argument file/classes: ignored `Intra-Paper-System/build/integration-risk-replay-source/`.

Review and integrate this branch under a separate explicit publication instruction. Preserve the original G2 and concurrent subprocess/CI work for their own tasks.
