# Task 4 completion report: settlement-aware portfolio risk audit

Date: 2026-11-05. Status: implemented and validated locally. The user subsequently authorized including this extension in the reviewed local settlement/evaluation integration commit; see [the commit review](../sessions/cb3ba092-6af2-4fde-95e9-d9a0b149ac9f.md). No push was requested.

The portfolio risk audit now reconciles integrated final-session settlement events and the settled portfolio state. It preserves the final BAR_CLOSE checkpoint, adds terminal SETTLEMENT checkpoints, and separates pre-settlement positions, settlement adjustments, charges and remaining portfolio exposure. This is an isolated audit extension with no dependency on Task 3 trial evaluation.

## Deliverables

| Deliverable | Location |
| --- | --- |
| Separate version 2 audit contract | [PORTFOLIO-RISK-AUDIT-SETTLEMENT.md](../../Intra-Paper-System/docs/PORTFOLIO-RISK-AUDIT-SETTLEMENT.md) |
| Updated audit guide | [PORTFOLIO-RISK-AUDIT.md](../../Intra-Paper-System/docs/PORTFOLIO-RISK-AUDIT.md) |
| Independent settlement reconciliation | [PortfolioRiskSettlements.java](../../Intra-Paper-System/src/main/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskSettlements.java) |
| Position, basis, P&L and coverage implementation | [PortfolioRiskCalculator.java](../../Intra-Paper-System/src/main/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskCalculator.java) |
| Provenance and versioned artifact integration | [PortfolioRiskAudit.java](../../Intra-Paper-System/src/main/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskAudit.java), [PortfolioRiskAuditArtifacts.java](../../Intra-Paper-System/src/main/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskAuditArtifacts.java) |
| Reproducible synthetic fixtures | [Fixture guide](../../Intra-Paper-System/examples/portfolio-risk-audit/SETTLEMENT-FIXTURES.md), [fixture generator](../../Intra-Paper-System/src/test/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskSettlementFixtures.java) |
| Public workflow and regression checks | [PortfolioRiskAuditTest.java](../../Intra-Paper-System/src/test/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskAuditTest.java), [test runner](../../Intra-Paper-System/test-portfolio-risk-audit.ps1) |

## Resulting behavior

The new `settlement-reconciliation.csv` records each event's signed residual position, historical final-close mark and exposure, exact released basis, caller settlement value, signed proceeds, gross realized P&L, adjustment from the final close, modeled/covered charges and post-event exposure. `settlement-charges.csv` records and independently checks each modeled charge component, including its basis, dated identity, rounding, GST and provenance.

For signed units `q`, final close `m`, settlement value `v` and modeled charges `C`, the change to already marked checkpoint P&L is:

`net adjustment = q × (v − m) − C`

The audit adds settlement proceeds once, consumes charges once and removes the residual marked value once. It does not add full realized settlement P&L to an already marked checkpoint. Same-time events share one terminal checkpoint; different timestamps retain separate checkpoints.

The integrated CSV example demonstrates this distinction: pre-settlement net P&L is −2 INR; futures and option mark adjustments are +7 and −8; settlement charges total 1.83. Final net P&L is therefore **−4.83 INR**. A session-loss cap of 4 produces a settlement-only breach.

Missing nonflat settlement charges produce **INCOMPLETE**, including through full CLI verification with exit code 3. Covered net P&L and covered event charges/adjustments remain blank. Explicitly labeled modeled amounts remain available for replay reconciliation. Insufficient charge categories also remain incomplete, even if the omitted components would numerically be zero. Known losses remain visible as lower-bound breaches.

Flat positions require no new quote, but still require explicit risk/proxy/source coverage. Open positions awaiting another event retain the final close's original observation timestamp; settlement does not refresh that mark. Stale coverage remains INCOMPLETE. With settlement policy NONE, the audit reports NOT_REQUESTED and does not infer settlement.

## Acceptance evidence

| Acceptance item | Verified result |
| --- | --- |
| Signed futures and options | Independently long/short futures, calls and puts; positive and negative futures values |
| Zero option payoff | Explicit zero proceeds accepted and reconciled |
| Partial residual positions | Exact residual basis retained; fixture checks 200.166666666666666667 rather than a rounded average-cost reconstruction |
| Flat positions | Zero event exposure/charges under the position model; missing proxy coverage still incomplete |
| Omitted or insufficient charges | Covered amounts blank; modeled values labeled; INCOMPLETE preserved |
| Malformed/unmatched events | Invalid values, headers, duplicate IDs, missing targets, incorrect dated identity and early timestamps rejected |
| Reconciliation mismatches | Wrong units/basis, missing settled snapshots, offsetting realized/unrealized errors and incorrect/duplicate charges rejected |
| No double-counting | Pre-settlement net plus event adjustments equals final modeled net; same-time and delayed events checked |
| Provenance and integrity | Event/schedule hashes bound; changed inputs and output tampering rejected; deterministic report bytes verified |
| Input classification | CALLER_SUPPLIED_UNAUTHENTICATED and CALLER_SUPPLIED_PROXIES retained |

## Validation and reproduction

The completed JDK 21.0.13 validation run passed **79 audit checks and 274 settlement checks: 353 combined**. Portfolio replay, execution-cost schedule, artifact-verifier v3, settlement and accounting regressions also passed. `git diff --check` passed.

Run from `Intra-Paper-System` with `JAVA_HOME` pointing to JDK 21:

```powershell
.\test-portfolio-risk-audit.ps1
.\test-portfolio-replay-engine.ps1
.\test-portfolio-accounting.ps1
```

To retain the fixture tables, supply a new directory whose parent exists:

```powershell
.\test-portfolio-risk-audit.ps1 -FixtureOutput .\output\risk-settlement-fixtures
```

The completed export contains **23 scenarios** and [expected totals](../../Intra-Paper-System/output/task4-risk-fixtures-final/expected.csv) under ignored `output/task4-risk-fixtures-final`. These are reproducible arithmetic tables; the separate public-workflow tests exercise complete replay artifacts. The results above are from the completed implementation validation, not a new test run performed while writing this report.

## Scope and limitations

New reports use audit schema version 2. Version 1 artifacts retain integrity-only verification; regeneration is required for the new audit coverage.

This remains an offline diagnostic. Caller settlement values, exercise/turnover declarations, tariff completeness and source authenticity are not independently verified. Margin amounts are proxies, and zero position exposure does not certify release of actual collateral. No broker connection, live-risk certification, strategy-performance conclusion or hosted CI result is claimed.

Task 4 changes remain local. Existing evaluation, settlement and concurrent risk-gateway work was preserved. Session record: [034ff2e1-e7d6-4a04-a6a6-6cd1e5423faf](../sessions/034ff2e1-e7d6-4a04-a6a6-6cd1e5423faf.md).
