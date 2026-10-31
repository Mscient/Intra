# B03 — Algorithmic and High-Frequency Trading: Research for the Nifty System

**Authors:** Álvaro Cartea, Sebastian Jaimungal, and José Penalva  
**Edition / publisher:** First edition, Cambridge University Press, 2015  
**ISBN:** 978-1-107-09114-6  
**Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset  
**Research date:** 1 November 2026 (Asia/Kolkata)  
**Research status:** Publisher-frontmatter and legal-excerpt synthesis, with India-specific source checks. The complete book has not been accessed or read.

> **Purpose and evidence boundary.** Cambridge provides the book’s frontmatter, contents, author reading guide, and a lawful excerpt from the opening market-structure material. These sources do not include the full book or the later chapter derivations and examples. Later-chapter descriptions below are limited to the authors’ own scope summaries and contents listing. Engineering recommendations are this project’s synthesis, not quotations or claims from the authors.

## 1. What the accessible sources establish

Cambridge University Press identifies this 2015 book as a mathematical treatment of electronic trading. The authors describe a progression from market mechanics and empirical facts, through stochastic optimal control, into models of execution, volume targeting, market making, statistical arbitrage, and order imbalance. Their preface says they use US equities to introduce market data and stylized facts, and explicitly says the book does not cover IT aspects of algorithmic trading or market regulation in detail. The book can inform model objectives and market assumptions, but it cannot supply the India API implementation or compliance design for this project. [1]

The publisher excerpt covers the Part I introduction and opening sections of Chapter 1. It distinguishes liquidity/noise or fundamental trading, informed trading, and market making, while cautioning that these labels can overlap. It discusses basic electronic order-book mechanics and explains that a marketable order can consume liquidity across price levels (“walk the book”). The excerpt uses US examples, including NASDAQ. Treat its matching-priority discussion as a teaching example, not as confirmation of current NSE matching rules or of a broker’s order handling. [2]

The accessible material confirms the book’s topic map, not a full-text reading. No claim is made here about exact equations, parameter choices, theorem conditions, exercise answers, or numerical conclusions from Chapters 2–12.

## 2. Book map and a useful reading order

The publisher’s contents list three parts and twelve chapters. [1]

| Part | Chapters | Why it matters to this project |
|---|---|---|
| I. Microstructure and empirical facts | 1–4: electronic markets and the limit order book; market microstructure; price and return evidence; activity and market quality | Understand what order and quote data represent, then measure the actual instrument before proposing a strategy. |
| II. Mathematical tools | 5: stochastic optimal control and stopping | Provides tools for formulating decision problems. Optional for a first telemetry and replay baseline, but relevant if we later need to derive an optimizer. |
| III. Algorithmic and high-frequency trading | 6–9: continuous execution, price/order-flow constraints, limit and market orders, volume/VWAP schedules; 10: market making; 11: pairs/statistical arbitrage; 12: order imbalance | Offers model families. Check assumptions against the Nifty instrument, data, costs, broker route, and retail-sized orders. |

The authors recommend reading Part I before the advanced strategy models. Their shorter course path is Chapter 1, the control material in Part II, then selected Chapters 6, 8, and 10. For our use, read Chapters 1–4 first; Chapter 5 when the math need becomes concrete; then Chapters 6, 8, 9, 10, and 12 selectively. Chapter 7’s dark-pool model is not an initial Nifty retail-API requirement. Chapter 11 can wait until a separate multi-instrument research case exists. [1]

The contents page separately names spreads, volatility, market depth, trade size, price impact, order messages, cancellations, and hidden orders as dimensions of market quality. A single “volume” column or top-of-book price does not represent all of these. [1]

## 3. Concepts to carry into system design

### 3.1 Model exchange mechanics before modeling an edge

**Book evidence:** an electronic market receives orders, maintains a book, and matches compatible interest under exchange-defined rules. Limit orders may rest or be cancelled; aggressive orders may consume displayed liquidity across price levels. Participant motives differ, and the categories are not perfectly separable. [2]

**Project implication:** represent observed state and system actions separately. Preserve instrument/contract identity, best bid/ask and sizes, quote timestamp/source, trades if available, order submissions, acknowledgements, rejects, modifications/cancellations, partial fills, final fills, and broker order identifiers. Preserve both market/event time and local receive/send time when the API supplies them. Keep the raw record separate from cleaned signals.

A quote snapshot is not a full event-by-event limit-order book. Without sequence numbers or event-level updates, the system may not know whether displayed size was added, cancelled, depleted, replenished, or simply observed between changes. That limits queue reconstruction, cancellation dynamics, and fill estimates. Mark those quantities “not observed” instead of inferring them from sparse snapshots.

The excerpt’s price-time-priority discussion is generic/US-market context. Before using queue-position assumptions, confirm NSE’s matching rules and how the broker API maps order types, modifications, and timestamps. An order accepted by the broker is not proof that it reached the exchange at the time or price assumed by a backtest.

### 3.2 Treat liquidity as several measurable costs

**Book evidence:** the empirical chapters examine spreads, volatility, depth, trade size, price impact, order messages, and cancellations. The later models distinguish execution objectives and market impact rather than treating all fills as equivalent. [1]

**Project implication:** report execution quality per order and in aggregate. A useful first set is:

- Arrival-mid slippage: side × (fill price − mid at decision time), with side = +1 for a buy and −1 for a sell.
- Effective spread relative to the decision mid: twice that signed per-unit amount, when quote and fill timestamps are valid.
- Fill ratio: filled quantity divided by submitted quantity.
- Completion ratio: quantity filled against the intended target.
- Acknowledgement and cancel latency distributions, not only averages.
- Post-fill markout: side × (fill price − mid at horizon h), so positive indicates the mid moved against the fill side.
- Explicit brokerage, exchange charges, taxes, and other fees, versioned by effective date.

Use markout horizons the acquired data can resolve; for example, a few seconds and a minute for an intraday system. If the available feed is minute-level, do not report sub-minute markout or latency performance. Attribute fees and slippage separately from forecast P&L.

### 3.3 Separate execution alpha from forecast alpha

The book’s structure distinguishes optimal execution, market making, and order-flow information. A schedule that reduces the cost of completing an intended trade is not evidence that the trade direction itself has positive expected return. [1]

**Project implication:** identify each experiment’s objective before evaluation:

1. **Forecast:** predict a future return or risk quantity using only data timestamped before the decision.
2. **Execution:** complete a specified buy/sell target while managing cost, risk, and deadline.
3. **Market making:** quote both sides and manage inventory and adverse selection. This is a different problem from scheduling an existing trade.

Score forecasts against a simple time-aligned benchmark, and score execution against the same target executed by a defined baseline. Do not combine strategy returns, quote-to-fill assumptions, and costs into one unexplained P&L number.

### 3.4 Execution urgency and impact need a target and deadline

The preface describes Chapters 6–7 as large-position acquisition/liquidation models over a chosen window. It says the models incorporate temporary and permanent price impact, urgency, price barriers, order-flow trends, and lit/dark venues. Chapter 8 adds limit orders or a combination of limit and market orders and may target a schedule. [1]

**Project implication:** before selecting an execution model, specify target quantity, start/end time, allowed order types, maximum slippage, and penalty for incomplete execution. Compare a simple marketable-limit baseline and a passive-limit policy in replay or paper trading. Record missed fills and opportunity cost as well as paid spread. Do not score a passive strategy only on successful fills; that creates selection bias.

The stated models concern large positions and stylized assumptions. A small own-account order may have little measurable permanent impact, but this is a hypothesis. Measure spreads and actual execution at intended Nifty instrument and size. For options, evaluate each relevant strike/expiry’s spread and displayed size; index liquidity does not guarantee equal liquidity in a selected option contract.

### 3.5 VWAP and percentage-of-volume are execution schedules

The publisher describes Chapter 9 as targeting a percentage of market trading rate, percentage of cumulative volume, and VWAP, including stochastic volume models. [1]

**Project implication:** use VWAP or a participation schedule as a cost/execution benchmark, not as a directional signal. A schedule depends on a volume estimate and the instrument’s intraday volume curve. Estimate the curve from data available before the test interval; document treatment of holidays, expiry sessions, opening/closing periods, and unusual events. If broker data do not provide reliable trade volume for the traded contract, the system cannot claim to track its market volume accurately.

For a first retail-scale system, compare simple alternatives (one marketable limit at arrival or a fixed time-sliced schedule) before implementing stochastic volume control. Added model complexity is justified only if it improves cost or completion out of sample after fees and rejected/unfilled orders.

### 3.6 Inventory and adverse selection matter to market making

The preface says Chapter 10 studies quote placement while accounting for inventory aversion, adverse selection, and short-term trends. The excerpt cautions against equating “market maker” with “always provides liquidity”: a strategy can sometimes provide and sometimes take liquidity. [1–2]

**Project implication:** inventory is a state variable and risk exposure, not just a position counter. A quote policy must specify how prices/sizes change as inventory grows, when quoting is disabled, how stale data are handled, and how inventory is reduced. Measure markout after passive fills: a high passive fill rate can be bad if fills occur just before price moves against the strategy.

These concepts may help diagnose why passive orders lose money. They do not imply that this project should operate a market-making strategy. A retail broker API, uncertain queue position, limited event data, order-rate controls, and broker/exchange rules can make model assumptions inapplicable. Keep market making out of the initial implementation unless a future data and operational review supports it.

### 3.7 Order imbalance needs a precise definition and cost test

Chapter 12’s contents describe limit-order-book volume information, intraday features, a Markov-chain model, market orders, price jumps, daily features, and optimal liquidation. [1] Define any implemented measure exactly: book levels, displayed versus executed volume, side convention, normalization, sampling interval, and event-time alignment. A snapshot bid/ask-size difference is not interchangeable with an event-flow measure built from submissions, cancellations, and executions. In options, open interest is a distinct contract-count field; it is not automatically the order-book imbalance studied here.

India-specific evidence is useful but not decisive. A 2021 *Finance Research Letters* study examined 195 highly active NSE stocks using transaction data and book information; it reports short-horizon order-imbalance predictability strongest over roughly five minutes and fading within thirty minutes, with information reaching deeper book levels. The instruments are stocks, and the study does not establish a current Nifty futures/options edge. [3]

A 2026 *Journal of Prediction Markets* article studies hourly imbalance in 196 NSE stocks. It reports persistent imbalance and some return-prediction ability, while a simple imbalance-based strategy’s apparent abnormal returns disappear after transaction costs. This is a warning to test net costs and distinguish predictability from implementable profit. It is a stock study, not a Nifty index-options validation. [4]

**Project implication:** order imbalance is a candidate feature for later research, not a signal to wire into orders. Begin with precise feature definitions and time-aligned diagnostics. Then test incremental predictive value after bid-ask effects, fees, realistic fills, and out-of-sample splits. With periodic snapshots only, state explicitly that cancellation/order-flow dynamics are unobserved.

### 3.8 Pairs trading and statistical arbitrage are outside initial scope

Chapter 11 covers pairs and statistical-arbitrage approaches. [1] The initial brief is Nifty first and a retail broker API. A pairs model adds instrument synchronization, hedge ratios, relationship stability, joint fills, and legging risk. Defer it until a specific pair or cross-asset hypothesis exists and the data support it.

## 4. Pre-build data and engineering requirements

The authors explicitly exclude IT implementation. The following requirements are project synthesis of the book’s model topics and retail-API constraints.

### 4.1 Data capability matrix

Before strategy work, ask the broker what is available and permitted for the chosen segment/account. Record actual fields, update behavior, timestamps, retention rights, and whether data are delayed, sampled, or event-level.

| Data or event | Minimum fields to retain | If unavailable |
|---|---|---|
| Instrument/contract master | Symbol/token, exchange, expiry, strike, option type if relevant, lot/tick size, effective date/source | Do not hardcode changing descriptors; block unsupported contracts. |
| Quotes | Bid/ask, sizes, exchange/broker timestamp, local receive time, source, sequence/version if supplied | Limit claims to coarse mark-based research; no queue or precise fill inference. |
| Trades/volume | Price, size, exchange time, source, trade/sequence ID where supplied | Do not claim VWAP/participation accuracy for the traded contract. |
| Order lifecycle | Client and broker/exchange IDs, side, quantity, price, send/ack/fill/cancel times, status, reject reason | Do not model completion as instantaneous or guaranteed. |
| Costs | Broker charges and applicable taxes/fees, with effective date | A zero-cost backtest is not decision-quality. |
| Clock/health | Local clock status, data age, API heartbeat, error state | Fail closed on stale or disconnected data. |

Use monotonic local time for elapsed-time measurement where possible and preserve wall-clock time separately for audit. Keep the raw event journal append-only; derive cleaned datasets reproducibly from it. These are design recommendations, not capabilities guaranteed by a broker.

### 4.2 First measurable baselines

Before any strategy, capture a read-only or paper baseline and report quote spread/depth by time and contract; quote age and missing updates; decision-to-send, send-to-acknowledgement, and cancel-response latency distributions; rejects, partial fills, completion and cancellations; arrival slippage, effective spread, post-fill markouts, and all-in cost; and differences between a spot/futures proxy and the actual option contract if options are traded.

Use the actual execution instrument’s data. Nifty spot depth is not executable depth in Nifty options. If no usable market-data archive is available, prioritize authorized data access or a slower research scope; do not invent event data from bars.

### 4.3 Conservative replay rules

A historical quote touch is not a guaranteed fill. Do not fill a passive order merely because a later trade printed at its price unless data and queue model support that inference. Report a conservative scenario and a less conservative sensitivity case. A marketable limit is bounded by its price cap; include non-fill and partial-fill outcomes when the market moves away. Model broker response and exchange order lifecycle separately when the API reports them separately.

### 4.4 Controls and stop conditions

Before sending an API order, specify maximum order size and position, exposure and session-loss limits, allowed instruments/order types, order-rate limits, price collars, stale-data timeout, retry rules, duplicate-order protection, and a manual kill path. Log every state transition. Use a sandbox or paper route to evaluate disconnects, rejects, partial fills, late acknowledgements, duplicate messages, and cancellation races. These are system requirements the book does not provide; confirm current details with the broker and exchange.

NSE’s current retail algo/API page routes Client Direct API applications through exchange circular NSE/INVG/73992 dated 30 April 2026. Confirm the broker’s exact classification, tagging, order types, permissions, and operational requirements against the current circular before relying on an API workflow. This note is not a legal interpretation. [5]

## 5. Staged use of this book for the Nifty project

1. **Market and access inventory:** document the exact instrument (Nifty futures or selected options), broker API route, data source, timestamps, order types, and permitted rate. Confirm current broker/exchange procedures.
2. **Data capture:** build a read-only recorder for authorized quotes and a separate order-lifecycle journal. Check timestamps, gaps, duplicate updates, and clock behavior.
3. **Execution measurement:** compute spread, fill ratios, slippage, markouts, fees, and completion against a simple baseline. Do not optimize a strategy before these metrics are observable.
4. **Deterministic paper/replay:** compare passive and marketable-limit rules with conservative fill assumptions, including incomplete execution. Keep forecasts separate from execution.
5. **Simple schedule research:** compare a fixed schedule and, only where volume data are adequate, a VWAP or participation benchmark. Measure whether complexity adds value.
6. **Feature hypotheses:** test order imbalance and other short-term features offline. Require cost-adjusted, out-of-sample improvement before paper deployment.
7. **Advanced optimization:** study Chapter 5 and implement stochastic control only after state variables, objective, constraints, and data inputs are defined and validated.

Given the stated constraints, an appropriate early deliverable is a paper-trading system with sound telemetry and execution measurement. The evidence does not justify calling it high-frequency trading or expecting a low-latency advantage through a retail API without event-level data and measured latency. This is a conclusion from the project constraints, not a claim by the book.

## 6. What not to transfer directly

- **US equity facts to NSE:** the authors use US equities for empirical discussion; market structure, contracts, matching rules, and data access differ. Re-measure on the chosen NSE instrument. [1]
- **NASDAQ queue examples to NSE:** the excerpt’s order-book illustration is not a substitute for NSE rules or broker behavior. Confirm both independently. [2]
- **Institutional scale to own-account size:** many execution models are framed around large target positions over fixed windows. Re-check impact, risk, and schedule benefit at actual order size. [1]
- **A model objective to profitable signal:** lower execution cost does not establish directional edge; apparent predictability can disappear after costs, as the India OIB paper illustrates. [4]
- **Book coverage to live readiness:** the authors exclude IT and detailed regulation. Current broker implementation, exchange rules, authentication, controls, and auditability need independent work. [1, 5]

## 7. Open questions and next actions

- Obtain a lawful full copy through purchase, library, or institutional access and read selected chapters; record exact chapters/pages actually covered. Do not use unauthorized proxy scans.
- Ask the broker for its current Client Direct API classification, tagging/registration steps, quote granularity, timestamp semantics, order lifecycle fields, rate limits, allowed order types, sandbox, and data-retention/use terms.
- Decide whether the first traded instrument is Nifty futures or options. The book’s examples are equities; option-specific spread, contract, and risk requirements need the Natenberg track and current NSE contract data.
- Build a field-by-field data capability matrix before claiming book-depth, queue, order-flow, or volume-schedule research.
- Keep B03 “in progress” until authorized full-text reading is performed. This note is a public-source synthesis, not a completed cover-to-cover review.

## Sources

1. [Cambridge University Press — official frontmatter, contents, preface, and How to Read this Book](https://assets.cambridge.org/97811070/91146/frontmatter/9781107091146_frontmatter.pdf) — 2015 publication data; chapter map; author-described scope, US equity context, exclusions, and reading path.
2. [Cambridge University Press — official excerpt](https://assets.cambridge.org/97811070/91146/excerpt/9781107091146_excerpt.pdf) — lawful sample of Part I and opening Chapter 1 material on participants, orders, and the limit order book.
3. [Tripathi, Dixit & Vipul, “Information content of order imbalance in an order-driven market: Indian Evidence,” *Finance Research Letters* 41 (2021), 101863](https://doi.org/10.1016/j.frl.2020.101863) — NSE stock study; OIB and short-horizon return evidence.
4. [Jindal, Bajpai & Yadav, “Order Imbalances and Market Efficiency: Evidence from a Pure Order-Driven Market,” *The Journal of Prediction Markets* (published 25 February 2026)](https://www.ubplj.org/index.php/jpm/article/view/2235) — NSE stock sample; abstract reports persistent hourly OIB, return-prediction ability, and disappearance of simple strategy abnormal returns after costs.
5. [NSE — Decision Support Tools / Algorithms trading](https://www.nseindia.in/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — public page describing Client Direct API / retail algo application under circular NSE/INVG/73992 of 30 April 2026.

---
*B03 research note • Public-source synthesis and system translation. Full-text reading remains open.*
