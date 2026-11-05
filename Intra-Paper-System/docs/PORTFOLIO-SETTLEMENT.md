# Caller-sourced portfolio expiry and settlement

The portfolio replay supports explicit final-session cash settlement of futures and options. Each selected instrument settles its entire remaining signed position after executions and partial exits. It does not calculate option intrinsic value, select an expiry or settlement date, infer exercise from a positive payoff, or supply tariffs. All results remain `REPLAY_DIAGNOSTIC_ONLY` and performance-ineligible.

## Configuration and timing

Add these keys to the existing portfolio settings:

```properties
portfolio.settlement_policy=EXPLICIT_FINAL_SESSION
portfolio.settlement_instruments=F,O
portfolio.settlement_events_csv=settlement-events.csv
portfolio.settlement_cost_schedule_csv=settlement-costs.csv
```

Paths resolve relative to the portfolio properties file. Instrument IDs must be unique, trimmed, comma-separated, and present in the input manifest. Exactly one event is required per selected instrument, including an instrument that finishes flat. Unselected events, unknown instruments, duplicate event IDs, and repeated instruments fail. An identical repeated event is also an error; settlement does not inherit execution-ID idempotency.

Omitting the new settings retains `NONE`: no settlement occurs. With `NONE`, instrument declarations and enabled settlement files are rejected. With `EXPLICIT_FINAL_SESSION`, instruments and the event file are mandatory. Omitting the cost file or using `NONE` records `NOT_MODELED`, not a verified zero-cost tariff. No event is synthesized at the end of available bars.

Every portfolio member must have a complete final session when settlement is enabled. Each event date must equal that shared final session and must not be after the supplied instrument expiry. A caller-specified earlier session is permitted; no holiday or weekday rule is inferred. The offset-bearing event timestamp must resolve to that date in the dataset timezone and must be at or after the final bar close. Data reaching a supplied expiry date requires that instrument to be explicitly selected with an event, even when flat.

The engine preserves the final `BAR_CLOSE` snapshot, then applies events ordered by timestamp, instrument ID, and event ID. Events at the same timestamp share a `SETTLEMENT` snapshot. Untouched positions retain their last observed close marks; these are not newly observed prices at the settlement timestamp. Final `REQUIRE_FLAT` validation runs after settlement. Earlier session-boundary checks remain in force. `ALLOW_OVERNIGHT` may retain unselected, unexpired positions.

## Settlement event CSV

UTF-8 CSV headers must be exactly these columns in this order:

```csv
event_id,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,settlement_date,settlement_timestamp,settlement_value_per_unit_inr,option_exercise,charge_turnover_per_unit_inr,charge_turnover_source_reference,source_reference
```

All identity fields must match the final dated bar/reference exactly; decimal strike and tick values match numerically. `contract_kind` is `FUTURE` or `OPTION`. Futures require blank strike/right; options require a positive strike and `CALL` or `PUT`. Lot/tick values are explicit and positive. No wildcard or symbol/token fallback is accepted.

Dates use ISO `YYYY-MM-DD`. Timestamps require a UTC offset, such as `2030-05-06T09:19:00+05:30` or `2030-05-06T03:49:00Z`. Strings must be trimmed; required fields cannot be blank. IDs use the existing portfolio ASCII identifier syntax. Source references are nonempty single-line caller references, which may identify a controlled document, record, or assumption; the application does not retrieve them.

`settlement_value_per_unit_inr` is the supplied final value per underlying unit. Futures accept signed decimal values; option values are nonnegative, including zero. It is not rounded to the execution tick. Quantity is taken from the ledger, never the strategy target or an event quantity field.

`option_exercise` must be exactly `true` or `false`, and must be false for futures. For options it is the caller's declaration that exercise/assignment charge components apply to the settled position. It does not instruct an exchange to exercise, impose long-only exercise rules, or derive applicability from value/strike. Short positions use SHORT tariff rows.

`charge_turnover_per_unit_inr` is an optional nonnegative sourced calculation basis distinct from the settlement payoff. Its source reference must be supplied with it. Both fields must be blank together when absent. A cost line using caller turnover requires them; underlying notional is never substituted automatically.

## Effective-dated settlement cost CSV

Headers must be exactly:

```csv
schedule_id,entry_id,effective_from,effective_until,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,position_side,charge_category,component_code,basis,rate,charge_rounding_scale,charge_rounding_mode,gst_bps,gst_on_charge,gst_rounding_scale,gst_rounding_mode,source_reference
```

One schedule ID covers the file; entry IDs are unique. Every row includes full dated identity and source attribution. `position_side` is `LONG` or `SHORT`. `charge_category` is `EXPIRY_SETTLEMENT` or `OPTION_EXERCISE`; exercise categories require an option identity. Component codes are caller identifiers, not built-in tax names.

Date coverage is `[effective_from,effective_until)`: from is inclusive, until is exclusive, and blank until is unbounded. Overlapping ranges for the same identity, side, category, and component fail. For a non-flat event, exactly one dated row must cover every component declared for its matching identity/side/category. At least one expiry component is required; an exercised option additionally requires exercise coverage. Use explicit zero-rate rows for modeled components intended to charge zero. A flat event creates no cost lines and requires no side-specific coverage.

Let `n = abs(signed units)`, `v = supplied settlement value`, and `t = sourced turnover per unit`:

| Basis | Calculation base | Rate unit |
|---|---|---|
| `FIXED_PER_SETTLEMENT` | 1 for a non-flat settlement | INR |
| `PER_SETTLED_UNIT` | n | INR per unit |
| `PER_EXERCISED_OPTION_UNIT` | n, only in an applicable exercise category | INR per unit |
| `BPS_OF_SETTLEMENT_VALUE` | abs(n × v) | basis points |
| `BPS_OF_CALLER_TURNOVER` | n × t | basis points |

Rates and GST basis points must be nonnegative. Fixed/unit charges are base × rate; basis-point charges divide that product by 10,000. Arithmetic uses exact `BigDecimal` operations, with no preliminary 12-place division rounding. Each charge is rounded once using its declared scale and mode. If `gst_on_charge=true`, GST uses only that rounded charge as taxable base; otherwise its base is zero. GST is base × gst_bps / 10,000, rounded using its independent declared scale and mode. Line total is charge + GST; the taxable base is not added again.

Scales are integers from 0 through 18. Modes are Java `RoundingMode` names: `UP`, `DOWN`, `CEILING`, `FLOOR`, `HALF_UP`, `HALF_DOWN`, `HALF_EVEN`, or `UNNECESSARY`. `UNNECESSARY` fails if rounding would be needed. Charge and GST fields are mandatory even when their rates are zero. These are caller modeling choices, not prescribed legal rounding rules.

## Position reconciliation

The ledger maintains unsigned exact remaining entry basis `B` separately from the displayed average price. For signed residual units `q`:

```text
signed proceeds             = q × v
signed released basis       = sign(q) × B
settlement realized P&L     = signed proceeds − signed released basis
portfolio net P&L           = accumulated realized + remaining unrealized − execution fees − settlement fees
```

Settlement releases the entire residual basis, adds realized P&L, and clears the position. It never multiplies a rounded average-price snapshot back into a basis. Futures and option premiums use the existing portfolio P&L convention; proceeds are an audit calculation, not an account cash/margin balance or daily futures variation-margin ledger.

Synthetic examples, excluding charges: a future with q=25, B=2,500 and v=110 realizes +250; q=-25 with the same basis/value realizes -250. An option with q=25, B=250 and v=0 realizes -250; the short realizes +250. After partial exits, only remaining units and basis settle. A flat event reports zero units, proceeds, P&L, and charges.

All event and cost calculations are staged before the terminal ledger batch commits. A bad later event or rounding failure leaves every position unchanged, allowing a corrected retry. Successful final settlement forbids further frame opens or repeated settlement.

## API, artifacts, and compatibility

`PortfolioSettlementEvents.load` and `PortfolioSettlementCostSchedule.load` freeze canonical immutable rows plus source-file hashes. The extended `PortfolioReplayEngine.Config` carries policy and selected instruments; the extended engine constructor accepts optional events and costs. Existing engine/config constructors retain disabled settlement. Settlement-enabled runs require `ValidatedPortfolioDataset`; raw-frame runs cannot bypass dated/session validation. The portfolio loader retains the single-contract data checks but uses its dedicated settlement context.

Portfolio input properties must not configure legacy `settlement.file` or `settlement.cost_schedule_file`. Migrate those inputs to the new portfolio files; legacy option underlying-close inputs are not convertible by inference. The single-contract loader and runner retain their established input and accounting behavior.

Artifact schema and replay fingerprint are v3. Older artifacts require their original build; files are not migrated or rewritten. The fixed output set includes `datasets.csv`, `decisions.csv`, `orders.csv`, `executions.csv`, `execution-fees.csv`, `equity.csv`, `settlements.csv`, and `settlement-cost-lines.csv`. Settlement files retain headers when empty.

`settlements.csv` contains the supplied event, event-file hash, signed residual units, exact released basis, signed proceeds, realized P&L, cost-model status, distinct expiry/exercise fee totals, and total fees. `settlement-cost-lines.csv` contains event linkage, complete resolved tariff rows, schedule hash, calculation base, charge, GST base, GST, and total. `datasets.csv` records final dated identity, session date, close timestamp, and completeness. Equity includes the settlement phases and all fees.

The manifest/run identity bind policy, sorted targets, event bytes, cost-schedule identity/bytes, and existing inputs/models/build. Reordering a physical input file changes its hash and run ID even when canonical calculations are unchanged. Publication remains atomic and rejects overwrites.

Artifact verification checks schemas, counts, membership, dates, identity, provenance references, replay fingerprint, and output hashes. It independently reconstructs execution basis/quantities, recomputes settlement and charge arithmetic, and reconciles equity. Deterministic verification additionally reloads original inputs and byte-compares all regenerated outputs. Neither authenticates the manifest, tariff/source truth, or legal completeness. Missing components whose declarations exist only in external source files are checked by replay, not established by output-only verification.

## Runnable example and checks

`examples/portfolio-settlement/` contains a four-bar future/call fixture with explicitly synthetic rules, values, and tariffs. Run from the Java project with a built JAR:

```powershell
& $java -jar $jar portfolio-run .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\portfolio-runs
& $java -jar $jar portfolio-replay-verify .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\portfolio-runs\<run-id>
.\test-portfolio-accounting.ps1
.\test-portfolio-replay-engine.ps1
```

The sample buys one future at 103 and one option at 13, then settles at 110 and 5: gross P&L -1. Synthetic execution fees total 2. Settlement fees are 1.19 for the future and 0.64 for the option, including the declared rounding/GST; net P&L is -4.83. These numbers test plumbing only.

The workflow does not implement independently expiring instrument universes, sliced settlement quantities, physical delivery, daily variation margin, collateral, broker account reconciliation, early-exercise decisions, price discovery, or exchange matching. No network or broker order route is added.
