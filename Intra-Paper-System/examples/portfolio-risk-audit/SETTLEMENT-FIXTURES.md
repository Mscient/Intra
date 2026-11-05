# Synthetic settlement risk fixtures

Run `test-portfolio-risk-audit.ps1` with JDK 21. An optional `-FixtureOutput <new-directory>` exports the seven calculator CSV tables for each scenario, the synthetic risk inputs, and `expected.csv`. The parent directory must already exist. The generator refuses to overwrite an existing export.

The executable fixture source is [PortfolioRiskSettlementFixtures.java](../../src/test/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskSettlementFixtures.java). Fixtures use synthetic ledger snapshots to exercise the independent audit calculator and hard-coded cash-flow expectations. These arithmetic fixture tables are not complete replay artifacts. [PortfolioRiskAuditTest.java](../../src/test/java/com/mscient/intrapaper/audit/portfolio/PortfolioRiskAuditTest.java) separately exercises the public CLI/facade, deterministic reconstruction and publication using the [integrated CSV example](../portfolio-settlement/README.md).

Each signed case has three one-minute bars on 2030-05-06 starting at 03:45 UTC. Buy/sell one unit, add two, then close one, leaving two signed units for settlement at 03:48 UTC. Futures execution/close prices are 100.05, 100.10, 110; option prices are 10.05, 10.10, 12. Each execution costs 1 INR (six executions). The futures settlement charge is 1.005 rounded HALF_UP to 1.01 plus GST of 0.18, totaling 1.19. Option charge categories have explicitly declared zero rates. All source labels/hashes are synthetic caller declarations.

Let `f` and `o` be independently +1 (long) or -1 (short), and `p` the option payoff:

| Scenario | Expected final modeled net P&L (INR) |
| --- | --- |
| Futures value 105; CALL or PUT; `p` = 0 or 5 (16 cases) | `19.75 × f + (2 × p − 18.25) × o − 7.19` |
| Futures value -2, zero call payoff, both positions sign `s` (2 cases) | `−212.5 × s − 7.19` |
| Both long, futures value 105, call payoff 5, omitted schedule | `5.5` modeled; covered net blank; INCOMPLETE |
| Both long, futures value 105, call payoff 5, absent zero-valued option category lines | `4.31` modeled; covered net blank; INCOMPLETE |
| No executions, flat futures/put, omitted schedule | `0`, PASS with covered proxies |
| Same flat case, missing option proxy row | `0` modeled; proxy total blank; INCOMPLETE |
| Both long, futures value -2, call payoff 0, omitted schedule and loss cap 1 | `−218.5` modeled; covered net blank; known lower-bound breach retained; INCOMPLETE |

Each event verifies `pre net + sum(modeled net adjustments) = final modeled net`. The residual future basis is exactly 200.166666666666666667, which detects the error of using rounded average cost. Gross settlement realized P&L and the adjustment from the last close are separately asserted; the latter alone bridges the already marked checkpoint.

Additional rejection cases mutate valid results or event files: wrong residual basis/units, duplicate or out-of-order event IDs, absent target events/snapshots, offsetting realized/unrealized totals, incorrect/duplicate charges, negative option value, NaN, malformed header, unmatched dated token, and settlement before final close. The end-to-end suite also checks omitted-charge publication/exit status, hash binding, same-time aggregation, delayed settlement with stale remaining marks, and loss budgets.

See the [audit extension contract](../../docs/PORTFOLIO-RISK-AUDIT-SETTLEMENT.md) for coverage and trust limits.
