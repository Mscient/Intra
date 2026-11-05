# Settlement extension to the offline portfolio risk audit

Task 4 contract, audit schema version 2. This extends [the risk audit](PORTFOLIO-RISK-AUDIT.md) for the integrated [portfolio settlement contract](PORTFOLIO-SETTLEMENT.md). It does not depend on trial evaluation or the standalone cash-settlement sidecar.

## Scope and trust

The audit verifies and deterministically reconstructs the original replay, including its settlement events, dated charge schedule and settled snapshots. It then independently reconstructs execution cash flows and residual cost basis and reconciles the settlement accounting. It consumes no new settlement estimates and changes no replay behavior.

Settlement values, exercise declarations, turnover, tariff references, risk limits and source hashes remain **CALLER_SUPPLIED_UNAUTHENTICATED**. Margin inputs remain **CALLER_SUPPLIED_PROXIES** with no offsets or exchange margin calculation. Hashes bind the supplied bytes; they do not authenticate an exchange, broker, tariff, payoff or account. A complete caller schedule does not establish complete actual charges. A zero position-based proxy does not certify collateral release or account capital adequacy.

## Checkpoint and event contract

All existing BAR_OPEN and BAR_CLOSE checkpoints are retained. The final close is the pre-settlement checkpoint even when it shares a timestamp with settlement. Each distinct terminal event timestamp adds one SETTLEMENT checkpoint after all events at that timestamp. Events must be in canonical timestamp/instrument order, have unique event IDs and instruments, and match exactly the replay's explicitly selected settlement targets. Each event must match the final frame's full dated contract identity and session date and occur no earlier than the final close. The original dataset validator also enforces the session timezone and complete final-session requirements.

Every event's signed residual units must match the reconstructed position. Settlement flattens only that instrument. Unselected or not-yet-settled positions remain in the portfolio and retain their final-close mark and its original observation timestamp. Stale marks produce INCOMPLETE coverage and blank affected exposure/P&L totals. A flat settled instrument has zero exposure, an empty quote and `NOT_REQUIRED_FLAT` mark coverage; explicit risk, source and margin-proxy coverage is still required.

No settlement is inferred when the replay policy is NONE. The summary records `settlement_scope=NONE` and `settlement_reconciliation=NOT_REQUESTED`, and both settlement tables contain headers only. This is a bar-checkpoint audit, not evidence that an outstanding position has settled. A selected target without its event is a malformed replay and is rejected, not silently valued or flattened.

## Independent reconciliation

For signed residual units `q`, unsigned remaining cost basis `B`, last close `m`, caller settlement value `v`, and modeled event charges `C`:

| Quantity | Calculation |
| --- | --- |
| Pre-settlement signed marked exposure | `q × m` |
| Pre-settlement gross notional | `abs(q × m)` |
| Signed settlement proceeds | `q × v` |
| Gross P&L realized by extinguishing the residual | `q × v − sign(q) × B` |
| Settlement adjustment from the final close | `q × (v − m)` |
| Modeled net adjustment from the final close | `q × (v − m) − C` |
| Post-settlement units and marked exposure for the event instrument | `0` |

Residual basis is reconstructed from executions. Partial closes allocate basis at 18 decimal places with HALF_UP rounding, retaining the exact remainder. A full close releases that remainder. Snapshot average cost is compared at the replay contract's 12-place HALF_UP precision; rounded average cost is never used to reconstruct settlement basis.

The audit adds signed proceeds once to execution cash flows, consumes each modeled charge once, and removes the marked residual once. Gross settlement realized P&L is **not** added to a checkpoint that already includes its unrealized P&L. Equivalently, the next modeled checkpoint net equals the preceding modeled checkpoint net plus the sum of that timestamp's modeled net adjustments. Session loss uses the existing preceding-session baseline, so terminal settlement adjustments and charges belong to the final session.

Positions, residual basis, signed proceeds, per-instrument average cost, realized/unrealized P&L, fees, and portfolio realized/unrealized/net P&L must reconcile exactly. Charge lines are independently checked for schedule/hash, event/contract, side, date, category and component uniqueness, calculation base, charge rounding, GST base and GST rounding. Any numeric or identity mismatch rejects publication. The full replay verifier additionally detects removal or alteration of declared charge components, even if saved files are rehashed.

## Missing charges and status

| Event condition | Charge coverage and result |
| --- | --- |
| Nonflat, dated schedule with applicable settlement and (when declared) exercise categories | `COMPLETE_CALLER_SCHEDULE`; explicit zero-rate/zero-base amounts are retained |
| Nonflat, NOT_MODELED or insufficient category coverage | `MISSING_SETTLEMENT_CHARGES`; INCOMPLETE |
| Already flat, no charge lines | `NOT_REQUIRED_FLAT`; zero event charges under this unit-position model |
| Contradictory cost provenance, duplicate/malformed lines or arithmetic mismatch | Rejected before publication |

From the first event with missing charge coverage onward, `checkpoints.csv` leaves `net_pnl_inr` and `session_pnl_inr` blank. It retains `modeled_net_pnl_inr`, `modeled_session_pnl_inr` and `modeled_fees_inr` for comparison with the replay, subject to mark coverage. `settlement_charge_coverage` identifies the gap; later events do not clear it. Per-event `settlement_charges_inr` and `net_adjustment_inr` are blank for the affected event, while their `modeled_*` counterparts retain exactly what the replay modeled. Missing amounts never become zero estimates.

Exposure and covered proxy checks remain available independently of charge completeness. Known breaches remain in an INCOMPLETE report. Because settlement charges are nonnegative in this contract, an already excessive modeled loss is emitted as `MODELED_SESSION_LOSS_LOWER_BOUND_INR`; it cannot establish a pass. Earlier valid checkpoints retain their values and breach records. Missing risk, budget, source or quote coverage retains the original INCOMPLETE semantics.

Status precedence and CLI exits remain: rejected invocation/input/artifact `1` (no new report), INCOMPLETE `3`, BREACH `2`, PASS `0`. PASS concerns only the supplied inputs and observed checkpoints.

## Version 2 outputs

| File | Added information |
| --- | --- |
| `settlement-reconciliation.csv` | One row per event: event/contract kind, pre-settlement signed position, historical close/time/exposure, released basis, caller value and provenance, signed proceeds, realized gross P&L, close-to-settlement adjustment, modeled/covered charges and net adjustment, zero post-settlement exposure, coverage/classification and reconciliation result |
| `settlement-charges.csv` | One row per modeled component: event/instrument/schedule/entry/category/side, basis and rate, charge/GST rounding and amounts, source reference/hash |
| `checkpoints.csv` | Terminal portfolio states plus modeled P&L/fee fields and cumulative applied-event charge coverage |
| `inputs.csv` | Binds integrated event/schedule source hashes and all replay output hashes |
| `summary.properties` | Version 2, unauthenticated-input/proxy classifications, settlement scope/reconciliation, event count, distinct settlement checkpoint count and total checkpoint count |

The event table's `pre_close_*` fields are historical reconciliation evidence, not newly observed settlement-time quotes. The existing `reconciliation.csv` compares modeled replay arithmetic even when charge or mark coverage is insufficient for a risk conclusion. Full dated event identity and original charge schedules remain bound through the original verified replay and input hashes.

New reports use the version 2 fixed layout and content-addressed identity. `verify-artifact` still accepts canonical version 1 artifacts as integrity checks only; it does not upgrade their audit coverage. To obtain this extension, regenerate the audit using the original replay inputs. Full `verify` compares the current version 2 report bytes and requires the current replay build's reproducibility contract.

## Reproduction and acceptance

Use JDK 21 from `Intra-Paper-System`:

```powershell
.\test-portfolio-risk-audit.ps1
# Optional: export each synthetic scenario's report tables and expected totals.
# The parent directory must exist and the output directory must be new.
.\test-portfolio-risk-audit.ps1 -FixtureOutput .\output\risk-settlement-fixtures
```

The [fixture guide](../examples/portfolio-risk-audit/SETTLEMENT-FIXTURES.md) describes the exact cash-flow cases. Tests cover signed futures, calls and puts, both positive and zero option payoffs, negative futures values, partial residual basis, explicit zero charges, flat events, omitted charge coverage, missing flat-position proxies, settlement-only loss breaches, same-time and delayed events, stale remaining marks, malformed/unmatched events, duplicate charges/events, accounting mismatches, artifact tampering and deterministic reproduction. These are synthetic software checks, not market validation or trading-performance evidence.
