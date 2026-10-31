# Ten Trading Systems to Study for a Nifty Intraday Paper System

**Project fit:** India; own account; Nifty first; Python research; retail broker API; no paid tick feed at the outset; 90-day paper-system target
**Research date:** 1 November 2026 (Asia/Kolkata)
**Research status:** Public-source review of ten strategy and execution archetypes. No strategy has been tested on current Nifty data or validated through the project's broker route.

> **Purpose and evidence boundary.** This note maps how ten systematic trading approaches work, what published research has examined, and what data and execution assumptions they require. The ranking is for learning value and project fit, not profitability. Several cited studies use U.S., Taiwanese, or older Indian market samples. Those findings are hypotheses for local research, not evidence of a current Nifty trading edge.

## 1. Summary

A complete trading system has more than a signal. It receives time-stamped data, decides whether conditions allow a trade, sizes and submits an order, handles broker acknowledgements and partial fills, manages the open position, exits, reconciles fills, and calculates net P&L after costs.

The most useful first models for learning this loop are opening-range breakout, intraday momentum, opening reversal, and VWAP-deviation reversion. VWAP/TWAP is an execution method and helps teach order handling; it does not provide directional alpha by itself. Spot-futures relative value is market-specific but needs synchronized two-leg data. Order-flow imbalance, market making, and options volatility systems require data or risk controls beyond a first bar-based paper system.

The evidence reviewed here does not demonstrate that any of the ten systems is profitable on current NSE Nifty instruments using a retail broker API.

## 2. System profiles

### 2.1 Opening-range breakout

**Mechanism.** Measure the high and low during a fixed opening interval. Trade only after a defined break or close beyond the range. Specify confirmation, entry timing, invalidation, last entry time, position sizing, and end-of-day exit.

**Research evidence.** Tsai et al. tested a time-aware opening-range strategy on one-minute data for DJIA, S&P 500, NASDAQ, Hang Seng, and TAIEX futures from 2003 to 2013 [1]. The study illustrates that the opening interval is a model choice; it does not test Nifty.

**Project implications.** One-minute bars can support a first signal prototype. Fills still need conservative assumptions or bid/ask observations. A candle crossing a trigger does not prove our order could fill there. Compare a close-confirmed rule with a false-break filter and a time exit.

**Assessment.** High learning value. This is a clear way to study entry, stop, time-of-day, and order-state logic without introducing option Greeks immediately.

### 2.2 Intraday time-series momentum

**Mechanism.** Measure the recent direction and strength of one instrument over a preselected intraday window. Trade continuation only when the move clears a preset, volatility-scaled threshold. Define a time exit or a rule for weakening momentum.

**Research evidence.** Baltussen et al. examined intraday returns across more than 60 futures contracts in equity indices, bonds, commodities, and currencies. They report broad intraday momentum and discuss hedging demand as a possible driver [2]. This supports studying the effect across markets; it does not validate Nifty or a retail fill model.

**Project implications.** Predeclare a small set of horizons and normalizations. Use chronological out-of-sample periods and next-quote or conservative fills. Do not use the signal bar close as the trade fill when the close also determines the signal.

**Assessment.** High learning value and a useful comparator to reversal models. It teaches signal horizon, volatility scaling, and regime dependence.

### 2.3 Opening reversal or gap fade

**Mechanism.** After an unusually large opening move, wait for evidence that continuation has weakened before testing a trade in the opposite direction. Do not fade every gap; news-driven moves may continue. Define a hard invalidation level and daily loss limit.

**Research evidence.** Grant, Wolf, and Yu studied S&P 500 futures from 1987 to 2002. They found reversal patterns after large opening moves, but the apparent result weakened sharply when a bid-ask transaction-cost proxy was applied [3]. This is a direct reminder that reversal signals can be small relative to spread and trading cost.

**Project implications.** Separate overnight gaps from the first intraday move. Test the confirmation rule without look-ahead. If quote data are unavailable, use conservative slippage and treat the result as preliminary.

**Assessment.** High learning value when paired with momentum tests: the two systems make different predictions in some market conditions.

### 2.4 VWAP-deviation mean reversion

**Mechanism.** Calculate session VWAP and measure the price deviation from it. Test a move back toward VWAP after a sufficiently unusual deviation, usually with a trend or volatility filter because price can remain far from VWAP during a directional session.

**Research evidence.** VWAP is also a widely used execution benchmark, which is a different use. This review did not identify strong Nifty-specific evidence establishing a VWAP-fade strategy. The rule should be treated as a hypothesis with explicit thresholds, regime conditions, entries, exits, and costs.

**Project implications.** A bar-based prototype can calculate VWAP if volume is reliable. Trading-quality evaluation needs executable bid/ask marks and realistic fills. Do not credit a fill merely because a candle touched a trigger.

**Assessment.** High learning value for reference prices and regime filters; compare it against momentum and opening-range baselines rather than assuming it works.

### 2.5 Nifty spot-futures lead-lag and basis

**Mechanism.** Compare the futures price with a fair-value estimate based on spot, carry, financing, dividends, and time to expiry. Test whether one market leads the other or whether the basis reverts. A lead-lag finding is not an arbitrage unless both legs can be executed after costs.

**Research evidence.** Indian studies report different lead directions across periods and methods. Sehgal and Dutt report Nifty spot leading domestic futures for August 2008 to March 2015 [4]. Danak and Patel report futures-market price discovery and a spot lag in their 2020 study [5]. NSE research using 2003-2007 data also reports futures-led short-run price discovery and deviations from equilibrium [6]. These differing results make a fresh synchronized measurement necessary.

**Project implications.** Match timestamps and use executable quotes. Include carry, both spreads, fees, partial-leg exposure, and basis widening. Record when one leg fills before the other.

**Assessment.** High Indian-market relevance, with medium-to-high implementation complexity. Start only after timestamp alignment and cost accounting work.

### 2.6 VWAP or TWAP execution

**Mechanism.** Split a parent order into child orders. TWAP spreads activity across time; VWAP schedules more activity when historical market volume is expected to be higher. The order manager tracks acknowledgements, rejections, partial fills, cancellations, and residual quantity until the deadline.

**Research evidence.** The BIS Markets Committee describes VWAP and TWAP schedules and discusses flexibility versus schedule adherence [7]. That report concerns FX execution and institutional order sizes; its mechanics are useful references, not a Nifty retail performance result.

**Project implications.** Measure completion ratio, average fill price, decision-price shortfall, adverse movement while waiting, and residual quantity. Slicing a small own-account order may add operational complexity without benefit.

**Assessment.** Useful as an execution and order-lifecycle exercise. Keep execution quality separate from signal quality.

### 2.7 Pairs and relative-value trading

**Mechanism.** Model the spread or hedge ratio between two related instruments. Trade when the relationship moves far from an estimated equilibrium, then exit on convergence or when the relationship breaks. Define how to size both legs and handle a one-sided fill.

**Research evidence.** Gatev, Goetzmann, and Rouwenhorst studied daily U.S. stock pairs over 1962-1997 and noted that some measured returns may reflect market-microstructure effects [8]. High-frequency equity pairs research highlights sensitivity to transaction costs and execution speed [9]. Neither study validates intraday Nifty pairs.

**Project implications.** Estimate relationships only on past data and test out of sample. Simulate each leg independently because one may fill without the other. Track hedge-ratio drift and periods when the pair relationship fails.

**Assessment.** Medium-low first-project fit. Valuable after single-instrument tests because it teaches spread risk and joint-fill mechanics.

### 2.8 Order-flow imbalance

**Mechanism.** Aggregate changes in best-bid and best-ask queues, including trades, limit-order arrivals, and cancellations. Test whether net pressure at the top of the book predicts a near-term mid-price move, while accounting for available depth.

**Research evidence.** Cont, Kukanov, and Stoikov studied timestamped trades and quotes for 50 U.S. stocks. They report a short-interval relation between order-flow imbalance and price changes, with price impact inversely related to market depth [10]. Their event-level inputs cannot be reconstructed from one-minute candles.

**Project implications.** Useful inputs include timestamped best bid/ask prices and sizes, trades, quote freshness, and consistent clock alignment. Test instrument- and time-of-day-specific effects. If only periodic snapshots are available, state that cancellations and queue dynamics are unobserved.

**Assessment.** Closest to a short-horizon HFT signal in this list, but low initial fit without quote-event data and measured latency.

### 2.9 Limit-order market making

**Mechanism.** Post both bid and ask quotes and try to earn spread while managing inventory created by fills. Shift quote price or size as fair value, inventory, order arrivals, and adverse-selection risk change. A central risk is being filled just before the market moves against the quote.

**Research evidence.** Avellaneda and Stoikov formalize quote placement in a limit-order book while accounting for inventory risk and order-arrival rates [11]. Their model is a foundation for studying market making, not a complete or proven profitable bot.

**Project implications.** A credible simulator needs order-book events, queue/fill estimates, cancel-replace latency, inventory limits, and adverse-selection measures. A paper fill at the displayed price is not enough to estimate queue execution.

**Assessment.** Low fit for the initial retail-API system. Study it to understand spread capture, queue priority, and inventory controls.

### 2.10 Delta-hedged options volatility

**Mechanism.** Compare implied volatility with a forecast of realized volatility. Buy or sell a defined option position, such as a straddle, and use futures to manage delta. P&L depends on the path, re-hedging, gamma, theta, vega, skew, jumps, and costs.

**Research evidence.** Hull and White show that a minimum-variance hedge can differ from the standard model delta when volatility and the underlying move together [12]. Bollen and Whaley document a relationship between public option-order pressure and the shape of S&P 500 implied volatility [13]. These papers explain hedging and option-pricing behavior; they do not establish a short-term Nifty options strategy.

**Project implications.** The system needs synchronized option quotes, an implied-volatility surface, realized-volatility estimates, contract details, and a hedge simulator. Mid-price-only option P&L can be misleading. Include re-hedging costs and jump scenarios.

**Assessment.** Low initial fit because it combines multiple volatility exposures and demanding data. Defer until the core execution loop and options foundations are understood.

## 3. How a complete trade runs

A signal is only one stage of the system. A complete trade should follow this sequence:

1. **Observe:** ingest timestamped prices, quotes, trades, contract definitions, and session state.
2. **Decide:** record the signal, no-trade conditions, and reason for entry using only available past information.
3. **Control risk:** check size, exposure, daily loss, allowed instruments, stale data, and kill conditions.
4. **Place:** choose order type, price, quantity, and time-in-force; record submission and broker response.
5. **Manage:** handle open, partial, rejected, cancelled, and filled states; update position and send exits.
6. **Reconcile:** compare broker records with local state and compute net P&L after fees and slippage.

For a paper system, retain a log of market timestamp, signal state, risk decision, order ID, order state transitions, fill quantity/price, fees, position changes, exit reason, and final net result. This supports finding whether an outcome came from the signal, order handling, or fill assumptions.

## 4. Recommended learning and evaluation order

1. Study systems 1-4 first, with the same instrument, time window, risk rules, and cost assumptions so the signals can be compared fairly.
2. Build system 6 as an order-lifecycle simulator. Measure signal results separately from execution quality.
3. Study system 5 after spot and futures timestamps and instruments are aligned; study system 7 only if the data supports two-leg fill simulation.
4. Defer systems 8-10 until quote-event, options, and queue data are available and operational costs are understood.
5. Use chronological out-of-sample periods. Report net expectancy, drawdown, trade count, turnover, fill rate, slippage, and results by time of day and market regime.

A positive gross backtest is not enough. A candidate should move forward only when its rule is explicit, data supports it without look-ahead, fills can be modeled conservatively, and results remain credible after Indian brokerage and statutory costs. No system in this review is ready for live capital on the evidence reviewed.

## 5. What not to transfer directly

- Results from other markets or old periods do not establish a current Nifty effect.
- A price or quote touch does not guarantee a passive order fill or queue position.
- A market-making model does not supply exchange-specific matching behavior, broker latency, or current order costs.
- VWAP as a schedule or reference price does not establish directional alpha.
- A spot-futures lead-lag observation is not a risk-free arbitrage; basis, execution, and legging risks remain.
- Option volatility results depend on the option surface, hedge timing, liquidity, and transaction costs.

## Sources

1. [Tsai et al. (2019), “Assessing the profitability of timely opening range breakout strategies on index futures markets,” IEEE Access 7, 160-170](https://doi.org/10.1109/ACCESS.2019.2899177). Tested time-aware opening-range rules on five non-Indian index-futures markets using one-minute data.
2. [Baltussen, Da, Lammers, and Martens (2021), “Hedging demand and market intraday momentum,” Journal of Financial Economics 142(1), 377-403](https://doi.org/10.1016/j.jfineco.2021.04.029). Multi-asset futures evidence and discussion of hedging demand.
3. [Grant, Wolf, and Yu (2005), “Intraday price reversals in the US stock index futures market: A 15-year study,” Journal of Banking and Finance 29(5), 1311-1327](https://doi.org/10.1016/j.jbankfin.2004.04.006). Opening reversals and the effect of a bid-ask cost proxy.
4. [Sehgal and Dutt (2016), “Domestic and international information linkages between NSE Nifty spot and futures markets,” Decision 43(3), 239-258](https://doi.org/10.1007/s40622-016-0137-1). India sample from August 2008 to March 2015.
5. [Danak and Patel (2020), “A Study of Efficiency of Index Futures, Lead-Lag Relationship, and Speed of Adjustments in India using High-Frequency Data,” Indian Journal of Finance 14(4), 7-23](https://doi.org/10.17010/ijf/2020/v14i4/151705). Nifty spot/futures study across multiple intraday intervals.
6. [Gupta and Singh, “Price Discovery and Arbitrage Efficiency of Indian Equity Futures and Cash Markets,” NSE research paper](https://nsearchives.nseindia.com/content/research/res_paper_final185.pdf). Five-minute Nifty and liquid-stock data from April 2003 to March 2007.
7. [BIS Markets Committee (2020), “FX execution algorithms and market functioning”](https://www.bis.org/publ/mktc13.htm). VWAP, TWAP, and other execution algorithms in FX markets.
8. [Gatev, Goetzmann, and Rouwenhorst (1999), “Pairs Trading: Performance of a Relative-Value Arbitrage Rule,” NBER Working Paper 7032](https://www.nber.org/papers/w7032). Daily U.S. equity pairs research; later published in Review of Financial Studies (2006).
9. [Bowen, Hutchinson, and O'Sullivan (2010), “High Frequency Equity Pairs Trading: Transaction Costs, Speed of Execution and Patterns in Returns”](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1611623). High-frequency pairs research with focus on costs and execution speed.
10. [Cont, Kukanov, and Stoikov (2011), “The price impact of order book events”](https://arxiv.org/abs/1011.6402). Order-flow imbalance, short-interval prices, and market depth in U.S. equities.
11. [Avellaneda and Stoikov (2008), “High-frequency trading in a limit order book,” Quantitative Finance 8(3), 217-224](https://doi.org/10.1080/14697680701381228). Limit-order market making and inventory risk.
12. [Hull and White (2017), “Optimal delta hedging for options,” Journal of Banking and Finance 82, 180-190](https://doi.org/10.1016/j.jbankfin.2017.05.006). Minimum-variance delta hedging.
13. [Bollen and Whaley (2004), “Does Net Buying Pressure Affect the Shape of Implied Volatility Functions?,” The Journal of Finance 59(2)](https://doi.org/10.1111/j.1540-6261.2004.00647.x). Public order pressure and S&P 500 implied volatility.

---
*System research note | Public-source review and project synthesis; no Nifty strategy validation claimed.*
