# Task 1 completion report: portfolio expiry and settlement

Date: 2026-11-05 (Asia/Kolkata)

Status: implemented, locally verified, and published to `main`. Results remain diagnostic-only.

## Delivery

- Core implementation: [`79d68cf`](https://github.com/Mscient/Intra/commit/79d68cfd49aa084c2accb59d9f32a579c2218acd).
- Final integrated source and verified JAR build identity: [`0ecd4cb`](https://github.com/Mscient/Intra/commit/0ecd4cbfad5fbf5dba19e380cd2b701c22ebc5f5).
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

## Acceptance evidence

| Requirement | Result and evidence |
| --- | --- |
| JDK 21 build | Passed clean compile/package with Microsoft OpenJDK `21.0.13.0.2`; JAR manifest matches final integrated source `0ecd4cb`. |
| Signed futures/options and partial positions | Expanded settlement suite passed long/short futures, calls/puts, zero/negative supported values, partial exits, fractional remaining basis, selected subsets, flat events, and atomic failure/retry. |
| Strict dates, identity, and event coverage | Checks passed date/timezone/identity matching and rejection of missing, duplicate, unexpected, stale, or mismatched events. |
| Charges and rounding | Checks passed dated coverage/overlap, calculation bases, separate caller turnover, expiry/exercise applicability, charge rounding, and GST-on-rounded-charge calculations. |
| Accounting and artifacts | Accounting and replay checks passed. V3 artifact checks reject changed quantity, basis, P&L, fees, provenance, and equity, including rehashed tampering; missing/extra artifacts and changed frozen inputs are covered. |
| Deterministic replay | Packaged CLI regenerated the final example and verified all eight output files byte-for-byte. |
| Concurrent evaluator integration | All 114 evaluator checks passed, including holdout exclusion; replay, cost, and v3 artifact checks in its runner also passed. Settlement-enabled trial evaluation is explicitly rejected. |
| Concurrent settlement sidecar integration | All 88 checks passed after adapting its basis to the verified final bar before integrated settlement; added checks establish explicit basis labeling and no double-counted proceeds/charges. |
| Concurrent risk audit | All 55 checks passed at the earlier risk integration checkpoint. Settlement checkpoints remain unsupported by that separate audit. |

Focused runners used: `test-portfolio-accounting.ps1`, `test-portfolio-replay-engine.ps1`, `test-portfolio-trial-evaluation.ps1`, and `test-portfolio-settlement-audit.ps1`. Earlier risk-integration validation is recorded in the linked session record. Tests use synthetic fixtures. No hosted CI success is claimed.

## Final packaged example

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

The separate trial evaluator uses a limited integrity check before its split-aware reader so holdout P&L remains unread; this API does not replace full accounting verification. Trial evaluation and risk auditing of integrated settlement checkpoints remain explicitly unsupported. The independent settlement sidecar starts from the verified pre-settlement bar balance and reports the actual integrated final balance separately. Its own supplied values/charges may differ; sidecar results are not added to the replay balance.

The delivered scope is final-session settlement of each selected instrument's entire residual position. Independently expiring universes, sliced settlements, physical delivery, daily futures variation margin, cash/collateral balances, broker/account reconciliation, source authentication, and legal tariff completeness remain outside scope. Existing single-contract settlement remains supported through its original loader; portfolio inputs use the new dedicated files. Older replay artifacts require their original build.

No broker connection, live order route, real-market performance claim, or exchange-specific settlement assumption was introduced.
