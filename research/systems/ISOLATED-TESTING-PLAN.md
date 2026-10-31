# Isolated Testing Plan for the Ten Trading Systems

**Purpose:** Evaluate ten strategy and execution archetypes as independent research experiments while keeping all test code and runs outside the complete HFT 2 application and broker path.
**Project:** Nifty intraday paper-trading research; India; Python; own account; retail broker API; no paid tick feed at the outset.
**Status:** Plan only. No test harness, broker connection, or strategy backtest was created by this plan.

## 1. Isolation boundary

### Separate lab repository

Run all experiments in a separate repository named `Intra-Systems-Lab`, outside the HFT 2 application repository. Do not place experiment code inside the app package or import application modules. The HFT 2 repository should contain this plan, the system tracker, and reviewed result summaries only. No experiment executable code is promoted into the application under this plan.

### Separate environment and data flow

- Use a separate Python environment and one run directory per system and experiment. Pin package versions and record the lab commit hash.
- Keep the broker SDK, API credentials, live order-routing code, production configuration, and application database out of the lab. Runs must have no route to submit orders.
- Stage legally usable market-data snapshots into read-only lab input directories. Store dataset manifests and hashes with each run. Keep licensed raw data outside Git unless its license explicitly permits committing it.
- Disable network access during backtests and replays after data staging. Save outputs only to that system's own run directory.
- Use a shared lab-only data schema and metric implementation for comparability. Each system has its own code, config, parameters, and output directory; systems cannot read one another's signals, fitted parameters, or results.
- Return only a reviewed, non-executable summary and run manifest to the HFT 2 repository. Do not copy code, secrets, raw restricted data, or broker-state files back.

This gives us a one-way boundary:

`HFT 2 research brief -> frozen lab inputs -> isolated system run -> reviewed summary -> HFT 2 research record`

There is no return path from the lab into application runtime or order routing.

## 2. Experiment record and common rules

Every run gets a unique ID and records: system ID, lab commit, data-set ID/hash, instrument/contract definitions, time zone, interval, run dates, rule/config version, cost model version, random seed if applicable, baseline, metrics, and output file hashes. Save the signal stream and simulated order/fill ledger separately.

Before looking at results, write down the hypothesis, signal formula, entry and exit, risk controls, cost assumptions, variants to test, baseline, and decision thresholds. Log every tested variant. Do not change thresholds after reviewing the final holdout.

Use chronological development, validation, and final holdout periods. Freeze the final holdout until rules and variants are selected; do not use random shuffles for time-series evaluation. Use gaps or purging when overlapping positions or labels could leak information across splits. Keep a record of all trials to control selection bias and multiple testing.

### Common fill and cost policy

- Prefer timestamped executable bid/ask quotes and order events. Apply the appropriate side of the market to marketable orders.
- If only bars exist, use a predeclared conservative next-bar/next-quote rule and disclose its limits. Do not fill at the same close that generated the signal.
- A limit order does not fill merely because a candle touched its price. Model queue/fill uncertainty or report a conservative no-fill sensitivity.
- Include brokerage, exchange and statutory charges, spread, slippage, and partial/non-fills using a dated cost-model version.
- If data cannot support a key execution assumption, label that system `not testable with current data`; do not invent missing event or queue data.

### Shared evaluation outputs

Report gross and net expectancy, trade count, turnover, exposure, drawdown, tail losses, fill and completion ratios, slippage/shortfall, rejections/cancellations, and results by time of day and market regime. Compare each system to a simple, frozen benchmark. Record sensitivity to costs, fill assumptions, and small parameter changes. A statistical signal is not promoted when the result depends on optimistic fills or a narrow parameter choice.

## 3. System-specific test cards

### S01 Opening-range breakout

- **Inputs:** Nifty price bars, session calendar, instrument metadata, and quotes for fill estimates where available.
- **Test:** Preselect a small set of opening-window lengths; define close/quote confirmation, stop, last entry time, and forced exit. Compare with a no-signal intraday baseline.
- **Report:** Breakout count, false-break rate, net expectancy, drawdown, time-of-day and window sensitivity. Keep the opening window fixed before final holdout.

### S02 Intraday time-series momentum

- **Inputs:** Timestamped Nifty returns, volatility estimates, and executable prices/costs.
- **Test:** Predefine a few lookback windows and volatility-scaled thresholds. Compare directional performance with a flat/no-trade benchmark and a simple unscaled rule.
- **Report:** Net expectancy by horizon, signal persistence, turnover, exposure, and performance by volatility regime. Do not optimize an unrestricted grid.

### S03 Opening reversal or gap fade

- **Inputs:** Prior close, opening prices, first-session bars/quotes, and calendar/event labels if lawfully available.
- **Test:** Define the gap or opening-move threshold and a non-lookahead confirmation that continuation has stalled. Compare with taking the initial move and with no trade.
- **Report:** Results by gap direction/size, confirmation delay, costs, and continuation/reversal regime. Use conservative fills because reversals are vulnerable to spread effects.

### S04 VWAP-deviation mean reversion

- **Inputs:** Reliable intraday volume and prices, session boundaries, quotes, and a declared trend/regime measure.
- **Test:** Fix a volatility-normalized VWAP deviation threshold, regime gate, entry, target, and time stop. Compare with the same rule without the regime gate.
- **Report:** VWAP convergence rate, net expectancy, time-to-convergence, adverse excursion, and trending versus range-bound sessions. If volume is incomplete or misaligned, stop and mark `not testable`.

### S05 Nifty spot-futures lead-lag and basis

- **Inputs:** Synchronized spot proxy and futures quotes, contract expiry/carry inputs, and both-leg cost/fill records.
- **Test:** Specify a fair-basis calculation before measuring deviations. Test a paired trade only when both executable legs can be modeled; otherwise report predictive lead-lag separately from arbitrage P&L.
- **Report:** Basis distribution, convergence time, both-leg net P&L, legging exposure, partial fills, and results by expiry/time. Never label a lead-lag result risk-free.

### S06 VWAP/TWAP execution

- **Inputs:** A fixed set of parent orders, decision timestamps/prices, historical volume profile for VWAP, and executable quote/order events.
- **Test:** Keep the intended parent order identical across baseline, TWAP, and VWAP schedules. Run in a local order simulator, not through the broker.
- **Report:** Implementation shortfall from decision price, fill/completion ratio, residual quantity at deadline, adverse movement while waiting, and fees. Do not mix execution score with directional alpha.

### S07 Pairs / relative-value trading

- **Inputs:** A pair universe selected using training data only, synchronized prices/quotes, hedge-ratio inputs, and both-leg costs.
- **Test:** Freeze pair selection and spread rule before validation; model both legs and the possibility that only one leg fills. Include a relationship-break exit.
- **Report:** Net spread P&L, hedge-ratio drift, legging exposure, turnover, and out-of-sample stability. Do not reuse pairs selected on the final holdout.

### S08 Order-flow imbalance

- **Inputs:** Timestamped best-bid/ask prices and sizes, trades, cancellations/updates if available, quote freshness, and future mid-price targets at declared horizons.
- **Test:** Do not reconstruct event flow from one-minute bars. Measure a simple imbalance feature against a mid-price baseline, then test whether any rule survives costs and delay assumptions.
- **Report:** Predictive value by horizon, spread/depth state, stale/missing quote rate, latency sensitivity, and executable net result. If event data are unavailable, stop at the data-readiness gate.

### S09 Limit-order market making

- **Inputs:** Event-level order book, trade and quote updates, queue/fill model, cancel/replace latency, fees, and position limits.
- **Test:** Use a local event simulator with inventory controls and adverse-selection markouts. Run separate queue-model sensitivities; do not assume every displayed quote would fill.
- **Report:** Net spread capture, inventory P&L, post-fill markouts, fill probability, adverse selection, cancellations, and worst inventory drawdown. Without queue-quality data, record `not testable` rather than extrapolating from bars.

### S10 Delta-hedged options volatility

- **Inputs:** Synchronized option quotes and contract metadata, underlying futures quotes, implied-volatility surface, realized-volatility forecast, Greeks, and dated hedge costs.
- **Test:** Define the implied-versus-realized volatility rule and hedge trigger before the run. Simulate option and futures legs with executable bid/ask prices, expiry handling, and jump/stress cases.
- **Report:** Net P&L and delta/gamma/theta/vega attribution, hedge turnover, spread costs, slippage, tail loss, and sensitivity to volatility forecasts. Mid-price option fills alone are not an acceptable result.

## 4. Gates and decision states

1. **Isolation gate:** verify separate repository/environment, no imports from HFT 2, no broker package or credentials, no network/order route, and no shared writable state.
2. **Data gate:** record source, permitted use, fields, timestamps, missingness, contract metadata, and data hash. If a system's required inputs are absent, mark it not testable and stop that system's test.
3. **Rule gate:** freeze the hypothesis, variants, baseline, costs, fill model, and decision thresholds. Check signal timing and data access for look-ahead.
4. **Development gate:** implement only the isolated test model, compare against its baseline, log all variants, and report gross and net results.
5. **Validation gate:** run chronological walk-forward/holdout tests, cost and fill sensitivities, parameter stability, and regime breakdowns. The holdout is opened once for a recorded final evaluation.
6. **Review gate:** independently review data lineage, code version, run manifest, results, and failure cases. Record one decision state per system:
   - `not testable with current data`
   - `rejected under the predeclared criteria`
   - `requires better data/model`
   - `candidate for another isolated simulation`
7. **Summary gate:** copy only the review summary, assumptions, limitations, and manifest hash into HFT 2 research records. Keep the lab repo and all run code separate.

A `candidate for another isolated simulation` result is not approval for live use or integration. Any later broker-connected paper test or application integration requires a separate design and authorization step. This plan keeps all ten systems as independent experiments and leaves the complete HFT 2 project untouched by their code or state.

## 5. Execution order

- **Wave A - bar-based signals:** S01-S04, each in its own run space with shared frozen input manifests and the common cost policy.
- **Wave B - paired instruments and order handling:** S05-S07 after synchronized instrument data and order-fill simulation are available.
- **Wave C - event-level and options models:** S08-S10 only after the required quote-event, queue, or options data pass the data gate.
- **After every wave:** publish a brief result summary and update `research/systems/SYSTEM_TRACKER.md`. Do not merge experiment code into HFT 2.

The waves organize effort; each system retains independent data requirements, rules, run records, and decisions. Failure or success of one system does not change another system's configuration or status.
