# B04 — Algorithmic Trading: Research for the Nifty System

**Author:** Ernest P. Chan  
**Edition / publisher:** 2013, Wiley Trading  
**ISBN:** 978-1-118-74691-2  
**Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset  
**Research date:** 1 November 2026 (Asia/Kolkata)  
**Research status:** Publisher metadata, contents, and legal Chapter 1 preview reviewed; full book not accessed or read.

> **Purpose and evidence boundary.** This note turns the publisher’s public description, chapter list, and opening Chapter 1 preview into a pre-build research plan. The available preview verifies the book’s focus and the opening discussion of backtesting, statistical significance, and regime change. It does not expose the complete chapters or their examples. Recommendations below distinguish those book topics from original system-design synthesis.

## 1. Book scope and its fit

Wiley describes Chan’s book as an applied guide centered on backtesting and automated execution, mean reversion, momentum, and risk management. Its contents include stock/ETF, currency, and futures examples; intraday and interday momentum; pairs and spreads; and a risk-management chapter. The legal preview’s opening passage stresses that a backtest can be useless or misleading if common pitfalls are ignored, that statistical significance should be examined, and that even a statistically sound backtest may not predict future returns after a regime shift. [1]

This makes B04 a practical strategy-research companion to B03’s market/execution framework. Its central pre-build value is the research loop: define a testable idea, verify the data, test it with adequate statistical care, model execution, and monitor whether results persist. The book does not establish that a particular Nifty strategy will work today.

## 2. Strategy families in the contents

| Book topic | What it can contribute | Nifty-first constraint |
|---|---|---|
| Backtesting and automated execution | Test design, statistical significance, platform selection, implementation considerations | Backtest logic and broker execution must be separate, auditable modules. |
| Mean reversion | Stationarity, cointegration, pairs and spreads, bands, dynamic regression, stock/ETF examples | A spread reverting historically may not be stationary; index options add expiry, carry, and volatility effects. |
| Futures and FX mean reversion | Calendar spreads and intermarket relationships | Contract rolls, basis, and expiry handling must be explicit. |
| Interday/intraday momentum | Time-series, cross-sectional, opening-gap, news, and high-frequency examples | Signals depend on data timing and slippage; US examples need NSE-specific retesting. |
| Risk management | Leverage, stops, risk indicators | Position sizing controls loss exposure; it cannot create signal quality. |

The table of contents includes a section explicitly called “The Danger of Data Errors” within the mean-reversion implementation material. That is a useful reminder that a clever strategy cannot repair bad timestamps, incorrect instrument joins, duplicate prices, or a wrong contract history. [1]

## 3. Pre-build lessons from the backtesting chapter

### 3.1 Write the hypothesis before searching parameters

Turn each idea into a short specification before coding: the input available at decision time, the forecast horizon, the precise entry/exit rule, the instrument and session, the intended order type, and the benchmark. Keep a record of how many variants were tried. Otherwise the “best” parameter may only be the winner of a large unreported search.

The book’s preview explicitly points to hypothesis testing and Monte Carlo methods for estimating statistical significance. In our system, do not equate a large number of trades with a large number of independent observations: clustered positions and common market regimes can make results dependent. Report the sample period, number of sessions, trades, and overlapping exposure separately. [1]

### 3.2 A good historical fit is not a future guarantee

The preview warns that a correctly performed, statistically significant backtest can still stop working after a regime shift. Therefore, reserve later chronological periods for validation; examine performance by volatility, trend, liquidity, and time-of-day state; and define conditions that pause a strategy if live/paper diagnostics depart from its tested range. These are implementation recommendations derived from the warning, not claims about a specific method in an unread chapter.

Use a baseline and an out-of-sample period before optimizing. Track the spread and fees assumed in each run. If historical quote data are unavailable, label the result as a signal study, not execution evidence. A bar-level close cannot prove that an intraday limit order filled at an intrabar price.

### 3.3 Reproducibility and data checks

Give each run a versioned dataset ID, code/config version, instrument master version, date range, parameter set, and output file. Before the strategy calculation, validate monotonic timestamps, missing bars/quotes, duplicate records, valid prices and sizes, market-hours/calendar joins, and symbol/expiry identity. Keep a rejected-data report rather than silently deleting suspicious observations.

For futures, document roll/expiry transitions and basis. For options, use a versioned contract master and record which quote was used for the signal. If the API provides sparse snapshots rather than event data, do not infer queue position or precise passive fills. These controls extend Chan’s inclusion of data-error risk into the project’s own data pipeline.

### 3.4 Make automated execution a separate experiment

A strategy’s paper order must be generated from its decision-time state, then passed through an order simulator or broker sandbox with its own state machine. Log send, acknowledgement, reject, partial-fill, cancel, and final-fill events. Reconcile positions after every order cycle. Do not substitute the next bar’s close or a midpoint for an actual fill without labeling that approximation.

Report at least two result layers:

- **Forecast result:** return/risk outcome at the target horizon before execution assumptions.
- **Tradable result:** fills, unfilled quantity, slippage, spread, broker charges/taxes, and position risk after applying a documented execution model.

The current NSE retail algo/API page routes Client Direct API applications through exchange circular NSE/INVG/73992 dated 30 April 2026. Confirm the selected broker’s classification, tagging, permissions, allowed order types, and operational limits before implementing live order routing. [2]

## 4. How the strategy chapters might guide Nifty research

### 4.1 Mean reversion requires a defensible reference value

The contents move from stationarity and cointegration to implementing spread, log-spread, ratio, band, and dynamic-regression ideas. For a Nifty project, do not assume that the index level itself is mean reverting simply because a recent chart looks range-bound. Define the object expected to revert: a normalized return, a relationship between two instruments, a futures basis, or an option residual relative to a model. Test that property using only the training window, then measure whether it survives new sessions and realistic trading costs. [1]

Pairs and ETF arbitrage add synchronization and hedge-leg execution risk. Keep them out of the first single-instrument Nifty milestone. If later explored, record the signal timestamp for both legs, hedge ratio, contract identity, partial-leg exposure, and the cost of closing the pair.

### 4.2 Momentum claims need a horizon and event model

The contents distinguish interday from intraday momentum and include opening gaps, news-driven moves, and high-frequency strategies. Those are different hypotheses. An opening-gap feature needs a rule for overnight information and the opening auction; a news-driven feature needs reliable event timestamps and a defensible data source; a very short horizon needs quotes and execution timing fine enough to resolve the forecast. [1]

With no paid tick feed at the outset, begin with slower data and label conclusions accordingly. A minute-bar study can screen a slower hypothesis; it cannot establish millisecond queue advantages or high-frequency fills. Compare momentum with a simple passive/marketable-limit execution baseline and include missed fills.

### 4.3 Risk management changes the distribution, not the edge

The book’s contents include leverage, constant-proportion portfolio insurance, stop losses, and risk indicators. In system design, sizing and stops must be evaluated separately from the entry signal. A sizing rule can amplify a valid edge or magnify model error and drawdown; it is not a method for turning negative expectancy into positive expectancy.

Predefine per-trade and session exposure limits, maximum open positions, maximum loss under a gap or fast move, and behavior on stale data or API failure. Test exposure in currency and contract units, not only percentages. Use conservative size caps until the return distribution and fill behavior have been measured; do not apply leverage formulas to a point estimate without uncertainty analysis.

## 5. Proposed workflow for B04’s material

1. **Research card:** write the mechanism, feature, horizon, trading instrument, and reason it might persist.
2. **Data audit:** check identity, timestamps, missingness, bad values, sessions, rolls, and contract changes.
3. **Simple benchmark:** compare against buy/hold or a no-signal execution policy appropriate to the hypothesis.
4. **Statistical check:** estimate uncertainty and dependence; track all tested variations; validate on chronological holdouts.
5. **Execution layer:** add spread, fees, slippage, non-fills, partial fills, and order latency. Do not infer precise fills from coarse bars.
6. **Risk limits:** cap position, daily loss, number of simultaneous exposures, and behavior during data/API faults.
7. **Paper monitoring:** compare the new observations with the tested regime and pause when inputs or execution leave the validated range.

This sequence is project synthesis. The publisher preview supports the importance of backtesting, hypothesis testing, regime change, and platform choice, but the exact controls above are not attributed to unread pages.

## 6. Evidence and limitations

- Publisher metadata verifies the 2013 edition, ISBN, and chapter scope.
- A legal Chapter 1 preview is publicly displayed on O’Reilly’s book page; it provides only an opening excerpt.
- The rest of the book was not accessed. The report does not claim to reproduce or fully assess the mean-reversion, momentum, or risk-management chapters.
- US equities, ETFs, FX, and futures examples require fresh validation for NSE products and the selected broker API.
- Keep B04 “in progress” until authorized full-text reading is completed.

## Sources

1. [O’Reilly/Wiley — *Algorithmic Trading: Winning Strategies and Their Rationale*](https://www.oreilly.com/library/view/algorithmic-trading-winning/9781118746912/) — publication data, table of contents, and legal preview of Chapter 1.
2. [NSE — Decision Support Tools / Algorithms trading](https://www.nseindia.in/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — current Client Direct API / retail-algo process reference.

---
*B04 research note • Public-source synthesis and Nifty system translation. Full-text reading remains open.*
