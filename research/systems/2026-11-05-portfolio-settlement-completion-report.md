# Task 1 completion report: portfolio expiry and settlement

Date: 2026-11-05 (Asia/Kolkata)

Status: settlement workflow, strict UTF-8 inputs, settlement-aware risk auditing, and registered trial evaluation are implemented and verified. The user subsequently authorized a local commit on `main` of this integration and the README/tracker updates, based on `9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c`; see [the commit review](../sessions/cb3ba092-6af2-4fde-95e9-d9a0b149ac9f.md). The build and checkout-state evidence below records the pre-commit checkpoint. Published baseline evidence is historical. No push was requested; results remain diagnostic-only.

## Local settlement-aware integration — 2026-11-05

The risk audit now consumes terminal SETTLEMENT checkpoints, groups same-time events, independently reconciles signed residual units and proceeds, clears settled positions, and includes settlement charges in session-loss checks exactly once. Event/cost input hashes enter risk provenance. Still-open instruments retain the final close's original observation timestamp; delayed settlement cannot refresh a stale quote. Stale remaining marks produce INCOMPLETE. Flat positions use `NOT_REQUIRED_FLAT`, an empty mark price, and zero exposure while retaining explicit risk/proxy/source coverage requirements.

Registered evaluation supports both catalog scopes. `ALL_SPLIT_SESSIONS` remains the backward-compatible default: terminal settlements fall in the final holdout session and are excluded from evaluated returns. `DEVELOPMENT_VALIDATION_ONLY` requires the entire development/validation prefix and a frozen split with future holdout dates; it forbids holdout artifact rows and includes terminal validation settlement P&L and charges. Each session uses its last checkpoint, with fees counted once. Settlement-enabled registrations bind policy, targets, event bytes, schedule identity, and cost bytes through parameter identity V2; settlement-disabled registrations retain V1. Evaluation reports use `INTRA_PORTFOLIO_TRIAL_EVALUATION_V2`, recording scope and included/excluded settlement counts.

Prefix evaluation performs full artifact accounting verification after establishing that no holdout rows are present. Full-split evaluation inspects holdout timestamps, identities, quantities, and event/charge links without numerically interpreting held-out equity, settlement values/P&L, or charge fields. Regression fixtures replace those financial payloads with `SEALED_HOLDOUT` and require unchanged statistics.

The final review also reproduced an independent metadata gap: moving one dataset's `final_bar_close_timestamp_utc` backward by one second was accepted after refreshing output hashes and the catalog's manifest pin. `PortfolioSessionReturns.read` now compares every dataset's metadata with the actual last BAR_CLOSE before processing settlement groups. This rejects the mismatch while preserving sealed holdout financial fields. The previously failing regression now passes in the 161-check evaluator suite.

The [synthetic settlement example](../../Intra-Paper-System/examples/portfolio-settlement/README.md) now includes explicit risk inputs. Its session-loss cap of INR 4 deliberately produces exactly one SETTLEMENT loss breach at INR 4.83. Both audit and semantic verification must exit 2 with BREACH status; this is the expected diagnostic result.

## Pre-commit verification and packaged evidence

The final checks and build used one unchanged snapshot of 190 production Java sources. Other ongoing work in the shared checkout added risk, gateway, and replay changes; those edits were preserved. The risk suite had previously passed 67 checks; its final expanded version produced the counts below. This report records the settlement follow-up and observed verification results, without attributing the other work to this continuation.

| Check | Final observed result |
| --- | --- |
| `test-portfolio-trial-evaluation.ps1` | 161 evaluator checks passed, including the final-close regression; its replay, execution-cost, and v3 artifact/tamper/determinism checks also passed. |
| `test-portfolio-risk-audit.ps1` | 77 risk-audit checks and 274 settlement-risk fixture checks passed. |
| `test-portfolio-replay-engine.ps1` | Replay, execution costs, v3 artifact/tamper/determinism, signed settlement accounting, strict inputs, UTF-8, dates, charges, and atomicity passed. |
| `build.ps1` | Microsoft OpenJDK `21.0.13.0.2` compiled and packaged successfully; actual JAR manifest reports `Build-Commit: unknown-dirty`. |
| Packaged `portfolio-run` / `portfolio-replay-verify` | Both exited 0; all eight output files reproduced byte-for-byte. |
| Packaged risk `audit` / semantic `verify` | Both exited 2 with BREACH; exactly one `SESSION_LOSS_INR` breach at SETTLEMENT, actual INR 4.83 versus cap INR 4. All 13 report payload files reproduced byte-for-byte, and the manifest matched. |
| `test-portfolio-settlement-audit.ps1 -JarPath .\build\settlement-followup\intra-paper-system.jar` | All 88 independent settlement-sidecar checks passed against the final JAR. |

The final example records two settlements, three charge lines, and two flat positions. Gross realized P&L is INR -1, ordinary execution fees INR 2, settlement fees INR 1.83, and net P&L INR -4.83. The risk report records four frames, nine checkpoints including one grouped settlement checkpoint, zero coverage issues, zero final exposure/margin proxy, and both replay and settlement reconciliation PASS. Its schema is 2; the replay remains schema 3 and diagnostic-only.

```text
JAVA_HOME: C:\Users\prash\AppData\Local\Programs\Microsoft\OpenJDK21\jdk-21.0.13.0.2+1
JAR: Intra-Paper-System/build/settlement-followup/intra-paper-system.jar
SHA-256: fb5b8962c44c575af01bf0a598819aa60a11a2b059c20904439411a2523d1bb7
Build-Commit: unknown-dirty
Replay ID: 287d1de95a4b084905c541d2d7d9b38a7b16e20e1598a0f8f207605d6fe24be1
Risk audit ID: 96c927542eb8021724a1890929731ea233abe8bbe14c88ed6861bca3d33ecc0a
Artifact root: Intra-Paper-System/output/settlement-followup-smoke-20261105-7c4d6d72
```

Under that artifact root, `replay/<replay-id>/` and `risk/<audit-id>/` retain the verified outputs. [`verification.json`](../../Intra-Paper-System/output/settlement-followup-smoke-20261105-7c4d6d72/verification.json) records the final JAR hash, output hashes, counts, and exit statuses; [`build-source-sha256.csv`](../../Intra-Paper-System/output/settlement-followup-smoke-20261105-7c4d6d72/build-source-sha256.csv) records the source snapshot. Packaged command logs are beside them, and focused runner logs are under `Intra-Paper-System/build/*.settlement-followup.log`. These ignored build/output files are local evidence, not committed artifacts.

Both this build and the earlier UTF-8-only build use the label `unknown-dirty`, and the example retains the same replay ID. That label/run ID does not identify a unique local binary; preserve the exact final JAR hash, source snapshot, and frozen inputs. The earlier `settlement-utf8` JAR and the intermediate packaged check are superseded by the final JAR above.

The sandbox denied access to the configured JDK, so successful final checks used the approved elevated invocation of that installation. At this verification checkpoint, no commit, push, reset, discard, or agent spawning had occurred. The later instruction authorizes committing the reviewed integration locally; the earlier no-push constraint remains in force.

Final checkout review passed `git diff --check` and found no staged changes. `main` remained at `9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c`; all 190 production source hashes still matched the final build snapshot, and the JAR hash was unchanged. Current guides and completion records were checked for stale claims that settlement-aware risk auditing/evaluation are unsupported; historical limitations are labeled as superseded.

## Earlier local UTF-8-only checkpoint — 2026-11-05

The review reproduced a strict-input defect: the shared `CsvTable` reader used the UTF-8 charset's default replacement behavior. Malformed bytes inside an otherwise valid settlement source reference were accepted as replacement characters. The correction configures the decoder to report malformed/unmappable input, causing event and cost loading to fail rather than silently change provenance text. Valid input bytes and financial calculations are unaffected.

Regression checks first failed against the old reader with `Malformed UTF-8 accepted in settlement-events.csv: c328`. After the fix, both event and cost loaders reject six malformed encodings each: bad/stray continuations, overlong encodings, encoded surrogates, out-of-range code points, and a truncated sequence at EOF. Valid Devanagari, currency symbols, emoji, and a correctly encoded literal U+FFFD remain supported. BOM/CRLF variants preserve the exact source references and hash their original bytes.

Verification at the earlier UTF-8-only checkpoint passed:

- Expanded portfolio replay, execution-cost, settlement, UTF-8, and artifact/tamper/determinism checks.
- All 114 portfolio trial-evaluator checks, retaining holdout exclusion.
- All 55 portfolio risk-audit checks.
- All 88 settlement-sidecar checks against the corrected packaged JAR.
- JDK `21.0.13.0.2` compile/package and packaged deterministic replay of all eight output files. Example final net P&L remains INR -4.83, with both positions flat.

Working checkout: `C:\Users\prash\AppData\Local\Temp\codex-intra-origin-20261103`. The user authorized continuing in this verified checkout after the selected HFT folder still appeared empty. Changes are unstaged and uncommitted; no follow-up commit or push was made. The JAR correctly reports `Build-Commit: unknown-dirty`, so retain the exact JAR/source changes for these local diagnostics rather than treating its build label as a commit identity.

```text
JAR: Intra-Paper-System/build/settlement-utf8/intra-paper-system.jar
SHA-256: 14d655fac459524ec6bb811a082e9546a0ccecbbecc475e844feddf29d7a264a
Run: 287d1de95a4b084905c541d2d7d9b38a7b16e20e1598a0f8f207605d6fe24be1
Artifacts: Intra-Paper-System/output/settlement-utf8-smoke/<run-id>/
```

This `settlement-utf8` JAR predates settlement-aware risk/evaluation integration and is not the final follow-up build. The published-baseline sections below preserve their original verification evidence.

## Published baseline delivery

- Core implementation: [`79d68cf`](https://github.com/Mscient/Intra/commit/79d68cfd49aa084c2accb59d9f32a579c2218acd).
- Published integrated source and baseline JAR build identity: [`0ecd4cb`](https://github.com/Mscient/Intra/commit/0ecd4cbfad5fbf5dba19e380cd2b701c22ebc5f5).
- Remote `main` was confirmed at `0ecd4cbfad5fbf5dba19e380cd2b701c22ebc5f5` after publication; subsequent completion-report commits contain documentation only.
- [Settlement schema, calculations, and operating examples](../../Intra-Paper-System/docs/PORTFOLIO-SETTLEMENT.md).
- [Runnable synthetic futures/options example](../../Intra-Paper-System/examples/portfolio-settlement/).
- [Detailed session and integration evidence](../sessions/2bb34fb9-7179-4c9b-99ad-ec97cc8f6eb3.md).

## Implemented behavior

The caller explicitly selects instruments and supplies exactly one sourced settlement event for each, including selected flat positions. Settlement closes the entire actual signed residual quantity after the shared final complete session. Partial fills and earlier exits affect that residual. Dates, timestamps, full dated instrument identity, values, and exercise-charge applicability are validated; prices, expiry calendars, exchange rules, and tariffs are never inferred. Missing, duplicate, stale, extra, or mismatched events are rejected. Data reaching expiry requires explicit event coverage. No automatic end-of-data settlement is performed.

The implementation reuses the established remaining-basis accounting convention:

```text
signed proceeds = signed residual quantity × caller per-unit settlement value
realized settlement P&L = signed proceeds − sign(quantity) × exact remaining basis
net P&L = realized P&L + remaining unrealized P&L − execution fees − settlement fees
```

Signed futures and option positions are supported, including zero option payoffs and negative futures settlement values. Settlement releases the remaining basis, clears the position, and records its realized P&L. Flat events record zero quantity, proceeds, P&L, and charges. The whole terminal batch is validated before state changes; a failed event or charge calculation leaves positions unchanged.

Dated settlement charges carry full contract identity, source references, component names, calculation bases, and caller-declared charge/GST rounding. Expiry settlement and option exercise charges are separate from ordinary execution fees. Supported bases include fixed amounts, settled/exercised units, absolute settlement value, and separately sourced caller turnover. An omitted schedule is `NOT_MODELED`, not evidence of zero actual charges. No tariff rate or exchange-specific behavior was added.

## Historical published baseline acceptance evidence

| Requirement | Result and evidence |
| --- | --- |
| JDK 21 build | Passed clean compile/package with Microsoft OpenJDK `21.0.13.0.2`; JAR manifest matches final integrated source `0ecd4cb`. |
| Signed futures/options and partial positions | Expanded settlement suite passed long/short futures, calls/puts, zero/negative supported values, partial exits, fractional remaining basis, selected subsets, flat events, and atomic failure/retry. |
| Strict dates, identity, and event coverage | Checks passed date/timezone/identity matching and rejection of missing, duplicate, unexpected, stale, or mismatched events. |
| Charges and rounding | Checks passed dated coverage/overlap, calculation bases, separate caller turnover, expiry/exercise applicability, charge rounding, and GST-on-rounded-charge calculations. |
| Accounting and artifacts | Accounting and replay checks passed. V3 artifact checks reject changed quantity, basis, P&L, fees, provenance, and equity, including rehashed tampering; missing/extra artifacts and changed frozen inputs are covered. |
| Deterministic replay | Packaged CLI regenerated the final example and verified all eight output files byte-for-byte. |
| Concurrent evaluator integration | All 114 evaluator checks passed, including holdout exclusion; replay, cost, and v3 artifact checks in its runner also passed. That baseline rejected settlement-enabled trial evaluation; the local follow-up above removes this limitation. |
| Concurrent settlement sidecar integration | All 88 checks passed after adapting its basis to the verified final bar before integrated settlement; added checks establish explicit basis labeling and no double-counted proceeds/charges. |
| Concurrent risk audit | All 55 checks passed at the earlier risk integration checkpoint. That baseline did not support settlement checkpoints; the local follow-up above adds them. |

Focused runners used: `test-portfolio-accounting.ps1`, `test-portfolio-replay-engine.ps1`, `test-portfolio-trial-evaluation.ps1`, and `test-portfolio-settlement-audit.ps1`. Earlier risk-integration validation is recorded in the linked session record. Tests use synthetic fixtures. No hosted CI success is claimed.

## Published baseline packaged example

Run ID:

```text
92b38661bcf7af53a850a7c38f45ae652d7b743ddc2b384c1dac685bcbda50f7
```

| Item | Verified result |
| --- | ---: |
| Settlements | 2 |
| Settlement charge lines | 3 |
| Final futures/options quantities | Both 0 |
| Gross realized P&L | INR -1.00 |
| Ordinary execution fees | INR 2.00 |
| Settlement fees | INR 1.83 |
| Net P&L | INR -4.83 |
| Verified output files | 8 |

The manifest records schema 3, `EXPLICIT_FINAL_SESSION`, `REPLAY_DIAGNOSTIC_ONLY`, and `performance_claim_eligible=false`.

Local verified JAR:

```text
C:\Users\prash\AppData\Local\Temp\codex-portfolio-settlement-combined-final-build\intra-paper-system.jar
SHA-256: bb555c2f53f505892faae455f062cc1e61a1f05d22562f8a6dd71fae9ed70446
```

Local example artifacts:

```text
C:\Users\prash\AppData\Local\Temp\codex-portfolio-settlement-final-smoke-0ecd4cb\92b38661bcf7af53a850a7c38f45ae652d7b743ddc2b384c1dac685bcbda50f7
```

Those local build/run files are temporary artifacts; preserve them with their exact source inputs when reproducibility is required. The committed example can generate a new build-bound run.

## Provenance and remaining limits

Input bytes, settlement policy, selected instruments, and schedule identity are bound into v3 run identity. The eight output files are `datasets.csv`, `decisions.csv`, `orders.csv`, `executions.csv`, `execution-fees.csv`, `equity.csv`, `settlements.csv`, and `settlement-cost-lines.csv`, alongside the manifest. Full artifact verification independently reconstructs settlement accounting; deterministic verification additionally reloads the original inputs. Hashes establish byte identity, not source authenticity or tariff completeness.

Settlement-aware risk auditing and registered evaluation are supported by the local follow-up. The evaluator uses integrity and structural checks to keep full-split holdout financial payloads unread; those checks do not certify held-out accounting. Prefix mode performs full artifact accounting verification after excluding holdout rows. The independent settlement sidecar starts from the verified pre-settlement bar balance and reports the actual integrated final balance separately. Its own supplied values/charges may differ; sidecar results are not added to the replay balance.

The delivered scope is final-session settlement of each selected instrument's entire residual position. Independently expiring universes, sliced settlements, physical delivery, daily futures variation margin, cash/collateral balances, broker/account reconciliation, source authentication, and legal tariff completeness remain outside scope. Existing single-contract settlement remains supported through its original loader; portfolio inputs use the new dedicated files. Older replay artifacts require their original build.

No broker connection, live order route, real-market performance claim, or exchange-specific settlement assumption was introduced.
