# Initial cost wall — G1-COST/v1

Status: **PENDING_SOURCE_REVIEW**, 2026-11-08. The machine-readable [component register](g1-foundations-2026-11-08/cost-model-v1.csv) contains dated rate references and blank actual amounts. It is an initial comparison model, **not** an executable cost schedule. No candidate has demonstrated that it clears the wall. Source IDs below resolve to title, publisher, direct URL, dates and pinpoint in the [source register](g1-foundations-2026-11-08/source-register-v1.md).

NSE/FA/73061 separates transaction fees from IPFT, effective 2026-03-01 [S04]. NSE's levy table supplies STT from 2026-04-01, buyer stamp rates, turnover fee and GST rate [S05]. These dates cannot be back-projected. GST's taxable base needs invoice/pure-agent review [S06]. Broker charges, separate clearing pass-through, exercise turnover basis, rounding, minimums and exemptions remain unknown. Reference rates are recorded for desk comparison; all actual charge amounts remain blank. No illustrative plan budget enters configuration.

For each actual execution j, q_j is units (lots × the dated lot size), p_j is INR per unit, and V_j = q_j p_j is executed futures value or **option premium** value. N_j is order count only where the tariff charges per order; executions split across fills must not create duplicate per-order brokerage. A crore is 10,000,000 INR and one basis point is 1/10,000. Rates quoted as percent must first be divided by 100. A fee per crore is multiplied by V_j/10,000,000. Record per-fill/order/day rounding explicitly before economic evaluation.

```text
B_j = tariff(side, contract, order_id, executions, account plan)                  UNKNOWN
E_j = exchange_rate(date,kind) × V_j / 10,000,000
I_j = IPFT_rate(date,kind) × V_j / 10,000,000
Cl_j = clearing_pass_through(date,kind,basis)                                   UNKNOWN
R_j = SEBI_rate(date,kind,basis) × applicable_turnover_j / 10,000,000
T_j = seller_STT_rate(date,kind) × V_j          (actual sales only)
D_j = buyer_stamp_rate(date,kind) × V_j        (actual purchases only)
G_j = GST_rate(date) × reviewed_taxable_service_base_j                          BASE UNKNOWN
F_trade = sum_j(B_j + E_j + I_j + Cl_j + R_j + T_j + D_j + G_j)
F_expiry = exercise_STT × positive_intrinsic_value × exercised_units
           + sourced_exercise_turnover_fee + sourced_settlement/broker_charges
```

This is an itemization, not a claim all components apply to every event. Expiry/exercise fees are distinct from premium-turnover fees; there is no synthetic closing trade at an expiry mark. Futures variation-margin transfers and option premium principal are settlement cash flows, not additional fees. Do not double-count them as losses and costs. Unreviewed applicability is `UNKNOWN`, not zero. Source-specific verified non-applicability would need a separate dated row.

To compare an assumed full round trip relative to decision mid prices, let s_entry and s_exit be observed spreads in INR/unit. The crossing term is q(s_entry/2 + s_exit/2). This mid-based decomposition is an **assumption** about marketable execution. If gross P&L already uses actual ask buys/bid sells, crossing is already embedded and must not be subtracted again. Let L, J, M be incremental latency, residual slippage and impact in INR; define them against a common reference so they do not overlap. Post-fill adverse markout is a diagnostic of conditional selection, not an automatic extra debit if the exit P&L already includes that movement.

```text
implementation_shortfall = executed-price displacement from decision mid
                          + residual-opportunity shortfall at declared deadline
economic_net = gross_decision_mid_pnl - crossing - L - J - M - F_trade - F_expiry
               - financing - allocated_fixed_cash - allocated_time - capital_hurdle
```

Report unfilled and partially filled outcomes on the **original intended quantity** as well as per filled unit. Missed fills cannot be omitted from the event sample. Use q_filled and matched actual sides for fees; unmatched exposure, forced completion and residual opportunity costs have separate diagnostic lines. A partial entry followed by a failed exit is not a completed round trip. Snapshot sizes do not prove future capacity or queue rank.

For capital K tied up for d calendar days, a declared simple annual hurdle h gives K h d / Y, with Y and day count frozen explicitly. Interest actually paid on borrowed funds is separate; avoid charging the same capital twice by declaring the funding/hurdle basis. Margin is a collateral/feasibility requirement, not a fee or a safe loss limit. K, h, borrowing rate, broker margin add-ons and margin archive are unknown/private caller inputs. Annual fixed cash O and time hours H at private opportunity rate w yield O + Hw; allocation denominator is intended programme turnover/event count and is unknown. Retain annual costs even when no orders fill. Daily loss, recurring spend and live-loss ceilings remain separate A15 controls.

Under scenario k and chosen denominator N (INR notional, or premium turnover, never a mixture):

```text
wall_INR(k) = F_trade(k)+F_expiry(k)+crossing(k)+L(k)+J(k)+M(k)
              +financing(k)+fixed(k)+time(k)+capital_hurdle(k)
wall_bps(k) = 10,000 × wall_INR(k) / N
required_gross_INR(k) > wall_INR(k)                       for positive economic net
G3 design hurdle = max(wall_INR(k), 3 × declared_round_trip_trading_cost_INR(k))
```

The plan's three-times rule is a later acceptance policy, not a G1 estimate [Final Plan §§2.2,4,5.1]. At G3 the definition of “trading cost” and the uncertainty/capacity criterion must be reviewed and frozen; this model treats fees plus crossing as its proposed trading-cost basis and keeps incremental execution sensitivity explicit. Gross effect **UNKNOWN** and unfilled inputs prevent a numeric wall or hurdle for every traded candidate.

| Scenario | Explicit assumed treatment | Wall result / missing evidence |
| --- | --- | --- |
| K0 documentary fees | Actual executed turnover and dated fee/tax applicability; excludes execution/friction estimates | Partial reference formula only; broker/clearing/GST base/rounding unknown. Cannot be called break-even. |
| K1 marketable full-fill sensitivity | Both-side crossing, execution after measured receive/decision delay, full intended quantity | All spread/delay/slippage/impact amounts unknown; optimistic full fill is sensitivity only. |
| K2 partial/missed sensitivity | Whole-lot displayed-size cap at delayed quote; IOC remainder canceled; failed exits/residuals retained | Future depth/size and completion evidence missing. No queue estimate from snapshots. |
| K3 stressed economics | Wider spread, longer delay, impact and adverse selection stressed jointly; expiry charges where applicable; annual fixed and capital/time allocation included | Stress magnitudes **caller-supplied**, not fitted to validation. No numeric result asserted. |

Index signal scores are not trading economics. Applying futures fees to an index markout would create an invalid net figure. An option's bps of premium and a future's bps of notional have different denominators; compare INR economics, risk/capital and feasible sizes before comparing percent returns. No market observations, account amounts or profit calculations were generated for these scenarios.
