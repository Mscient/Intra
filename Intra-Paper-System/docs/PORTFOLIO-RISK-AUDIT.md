# Offline portfolio risk audit

Version 2 adds an isolated [settlement audit contract](PORTFOLIO-RISK-AUDIT-SETTLEMENT.md): separate pre-settlement positions, close-to-settlement adjustments, charge components and settled portfolio exposure. Omitted settlement charges produce INCOMPLETE with blank covered net P&L, while explicitly labeled modeled values remain available for replay reconciliation. This extension is independent of trial evaluation.

`com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditCli` checks a completed portfolio replay against separate caller-supplied limits and margin proxies. It verifies the saved artifact, runs `PortfolioReplayDeterminismVerifier`, reconstructs with `PortfolioReplayCsvRunner`, and compares the exact reconstructed output hashes before auditing the in-memory frames and executions. Original manifests, settings and their referenced files are required. Replay build identity must match; the established verifier intentionally rejects a run that cannot be reproduced by the current build.

This is an offline diagnostic command, not an independent live risk service. It has no broker/account connection, order gate or execution effect. All margin amounts are **caller-supplied proxies**. No exchange-margin calculation or adequacy certification is provided. It does not modify replay behavior or `IntraPaperCli.java`.

## Build and commands

Use JDK 21. Set a dedicated build directory to keep concurrent tasks' JARs separate:

```powershell
$env:INTRA_PAPER_BUILD_DIR = Join-Path $env:TEMP 'intra-portfolio-risk-audit-build'
.\build.ps1
$auditJar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'

.\portfolio-risk-audit.ps1 -JarPath $auditJar audit C:\runs\<run-id> C:\inputs\portfolio-inputs.csv C:\inputs\portfolio.properties C:\inputs\risk C:\audits
.\portfolio-risk-audit.ps1 -JarPath $auditJar verify C:\audits\<audit-id> C:\runs\<run-id> C:\inputs\portfolio-inputs.csv C:\inputs\portfolio.properties C:\inputs\risk
.\portfolio-risk-audit.ps1 -JarPath $auditJar verify-artifact C:\audits\<audit-id>
.\test-portfolio-risk-audit.ps1
```

The equivalent platform-independent invocation is `java -cp <jar> com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditCli <command> <arguments>`.

`verify` reconstructs and compares every report byte. `verify-artifact` only checks the fixed file layout, canonical manifest, folder identity and all content hashes. Hashes are integrity evidence, not signatures or proof of source authenticity. Exit codes for every command: `0` PASS, `1` rejected inputs/artifact or invocation failure, `2` BREACH, `3` INCOMPLETE. An incomplete report may also contain known breaches. Verification preserves the audited status in its exit code.

## Strict risk directory contract

Provide these four UTF-8 CSV files. Headers must match exactly and in this order. Blank rows/cells, extra columns, overlapping or duplicate intervals, unknown instrument/source IDs, negative values, exponents, non-finite values, malformed dates and invalid UTF-8 are rejected. Each file must contain at least one row. Decimals allow at most 18 integer and 12 fractional digits; integer fields require plain nonnegative integers. Zero is an explicit caller choice, never an inferred default.

```csv
instrument_id,effective_from,effective_to,max_abs_units,max_gross_notional_inr,source_id
```

`limits.csv`: one applicable row per replay instrument and session date. Limits are absolute underlying **units**, not lots. A position exactly at its limit passes.

```csv
instrument_id,effective_from,effective_to,long_proxy_inr_per_unit,short_proxy_inr_per_unit,source_id
```

`margin-proxies.csv`: separate caller-supplied long and short proxy amounts per underlying unit. Multiply by absolute signed units, then sum across instruments without netting or offsets. Even a flat instrument requires explicit coverage; a missing proxy does not become zero.

```csv
source_id,effective_from,effective_to,observed_date,reference,reference_sha256
```

`sources.csv`: effective-dated references for both risk schedules and portfolio budgets. `observed_date` must be on or before `effective_from`. `reference_sha256` is a caller-declared lowercase SHA-256 of the referenced evidence; the command neither downloads nor independently verifies that evidence. The source CSV's actual byte hash is recorded separately. At each session date, the source interval must apply and its age in calendar days must be no greater than the applicable budget's `max_source_age_days`.

```csv
effective_from,effective_to,max_gross_notional_inr,max_session_loss_inr,max_caller_supplied_margin_proxy_inr,max_mark_age_seconds,max_source_age_days,source_id
```

`budgets.csv`: explicit portfolio budgets and freshness thresholds. Only one budget interval may apply on any date. All effective intervals use inclusive **session dates** (including the close checkpoint); dates are not machine-clock expiry checks. A source at its age threshold passes. Effective schedules can change at session boundaries. A missing/expired applicable schedule or source, or stale source, makes the audit INCOMPLETE with a nonzero exit, while malformed files are rejected before publication.

Runnable input templates are in `examples/portfolio-risk-audit/`. They are synthetic examples for the two instrument IDs named there, not recommended risk limits or market margin evidence. Replace IDs, dates, values and source references with the actual caller inputs.

## Calculations and coverage

The audit starts flat, as the portfolio replay does. It independently accumulates BUY quantities positively and SELL quantities negatively, with exact integer overflow checks and `BigDecimal` arithmetic:

- Signed exposure = signed units × contemporaneous bar mark.
- Gross notional = sum of absolute instrument exposures. Options use premium notional, not underlying-equivalent or delta-adjusted notional.
- Cumulative net P&L = sum of negative signed execution consideration + signed settlement proceeds + remaining signed marked values − execution fees − settlement fees. It includes realized and unrealized P&L and does not use the replay ledger to perform this calculation.
- Session P&L = cumulative net P&L − preceding session's final checkpoint net P&L; the first session starts at zero. Overnight movement and current execution/settlement fees belong to the incoming session. The session-loss budget applies to `max(0, -session P&L)`, not peak-to-trough drawdown.

Positions, per-instrument net P&L and fees, and aggregate net P&L and fees must reconcile exactly with BAR_OPEN, BAR_CLOSE, and terminal SETTLEMENT replay snapshots. A mismatch rejects publication. Every settlement's signed quantity and full dated identity must match the outstanding position; cash consideration is independently recalculated as signed units times the supplied per-unit value. Positions become flat and settlement charges enter cumulative fees exactly once. Original execution and settlement fees are consumed from the fully verified replay; audit proxy amounts do not affect replay P&L. Same-time settlement events are audited as one published checkpoint, and events at different timestamps produce separate checks on the final frame index.

Each frame is checked after its opening executions and at its close. There is no claim about unobserved intrabar highs/lows, intermediate execution sequencing, continuous risk, open-order reservations or capital adequacy. BAR_START open prices are available at the frame timestamp; close prices are marked at timestamp + interval. These exact phase marks have zero age. The age check rejects missing/future/too-old marks; no close is brought forward to the open, and no absent frame is forward-filled. It does not prove feed receive-time freshness. The original validator rejects unaligned/missing instrument marks or missing session boundaries; replay-permitted intrasession gaps produce `MISSING_FRAME_MARKS` coverage issues and INCOMPLETE status. At a gap, only existing checkpoints are valued; absent checkpoint exposures remain unknown.

Unknown margin totals and values whose mark freshness cannot be established are empty CSV cells, not zero. Missing applicable limits remain empty and checks needing them are not treated as passed. Available checkpoints and known breaches are retained in an incomplete report.

At settlement, a still-open instrument retains the final bar close price **and its original observation timestamp**. A later settlement timestamp does not refresh that quote; if it exceeds `max_mark_age_seconds`, affected exposure/P&L checks remain unknown and the report is INCOMPLETE. A flat instrument requires no market quote: it records `NOT_REQUIRED_FLAT`, an empty mark price, and zero exposure. It still requires explicit risk/proxy/source coverage. Its zero unit-based margin proxy is not a claim about release of actual account collateral. Final realized losses and settlement charges are checked even if all positions are flat. No new market marks, rates, margin formulas, or exchange rules are inferred.

## Published artifact

Reports use deterministic UTF-8/LF output, canonical ordering, no wall-clock timestamps and no absolute output paths in their identity. The folder name is the SHA-256 of `audit-manifest.properties`, which binds all files, the classification and status. Publication uses a same-volume atomic directory rename under a per-identity lock; unsupported atomic moves fail. Repeating an identical audit verifies and reuses the existing report. A pre-existing corrupt report is rejected.

| File | Contents |
| --- | --- |
| `inputs.csv` | Original manifest/settings byte hashes, verified run manifest/output hashes, replay/frame identity, every validated dataset's bars/config/calendar/master/rule/additional hashes, execution-cost and settlement event/cost hashes when enabled, risk-file hashes |
| `risk-*.csv` | Exact frozen bytes of the four risk files, retaining all effective dates, limits, proxy rates and source references |
| `exposures.csv` | Every frame/phase/instrument's signed position, mark, exposure, gross notional, caller-supplied margin proxy, resolved caps/source IDs and coverage |
| `checkpoints.csv` | Portfolio totals, session baseline/P&L, budgets and budget coverage at every checkpoint |
| `coverage.csv` | Missing/expired/stale schedules, source and mark coverage issues |
| `breaches.csv` | Checkpoint, instrument or portfolio, limit name, actual amount and cap |
| `reconciliation.csv` | Exact position, per-instrument P&L, aggregate fee and net P&L comparison results |
| `settlement-reconciliation.csv` | Per-event historical pre-settlement position/value, residual basis, caller settlement value/proceeds, adjustment, modeled/covered charges, post-settlement exposure and provenance |
| `settlement-charges.csv` | Independently reconciled caller charge components, bases, charge/GST rounding, amounts and references |
| `summary.properties` | Classification, status, reconciliation, issue/breach/frame/execution counts, settlement event/checkpoint counts, total checkpoint count, and audit build identity |

The focused checks create their own synthetic two-instrument validated replay and isolated output. They cover signed/reversed positions, partial closes, fee-inclusive and overnight P&L, cap equality/excess, missing coverage, effective expiry/source freshness, malformed risk data, missing/stale marks, deterministic reproduction, manifest/output/input hash tampering and rehashed replay mismatch. Existing portfolio replay and accounting checks remain separate.

The same command also runs the [settlement fixture matrix](../examples/portfolio-risk-audit/SETTLEMENT-FIXTURES.md). Use `-FixtureOutput <new-directory>` to retain the synthetic scenario tables and expected totals for inspection. Version 1 artifacts retain integrity-only verification support; new audits use version 2.
