# B06 — Market Microstructure in Practice (Second Edition): Nifty System Research

**Editors:** Charles-Albert Lehalle and Sophie Laruelle  
**Edition / publisher:** Second edition, World Scientific, 2018  
**ISBN:** 978-981-3231-12-2 (print edition record)  
**Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset  
**Research date:** 1 November 2026 (Asia/Kolkata)  
**Research status:** Publisher/library metadata and public table-of-contents material reviewed; full book not accessed or read.

> **Evidence boundary.** The World Scientific product page could not be retrieved by the browsing tool. Public bibliographic records and visible contents establish the edition and its chapter map; they do not supply the complete text. The book’s documented Europe/US market examples are treated as context, not as current NSE facts. The practical requirements below are project synthesis.

## 1. Why this book matters

The second edition addresses how market structure and liquidity affect execution. Its visible contents cover market fragmentation and venue share, smart order routing, tick size, dark liquidity, volume curves, spreads, volatility, quoted quantities, HFT and systemic risk, trading organization, impact, scheduling/routing, and order-book dynamics. The publisher description emphasizes data analysis and practical processes such as smart order routing and trade scheduling. [1–2]

This is a useful bridge between B03’s individual execution models and the actual decision about what to measure in the market. It asks how liquidity is distributed, how the market’s design changes the meaning of quotes and volume, and how timing/venue decisions affect execution.

## 2. Topics and their Nifty relevance

| Book topic | Transferable question | Nifty-first use |
|---|---|---|
| Fragmentation and market share | Where is executable liquidity, and how does its distribution change? | Start with the chosen NSE instrument and route; do not assume other venues are interchangeable. |
| Smart order routing (SOR) | Which venue/order path best satisfies the execution objective? | Defer SOR until the strategy is authorized for multiple venues and comparable live data exist. |
| Tick size and quote value | How does price granularity affect spread, queue, and liquidity? | Use the current contract’s tick metadata; measure at the chosen futures/option contract. |
| Dark pools/hidden liquidity | What liquidity is not visible in displayed depth? | Do not infer hidden size from a retail quote snapshot. |
| Volume curves and trade scheduling | How does activity vary over the day, and what is a feasible participation rate? | Build a time-of-day baseline from authorized traded-instrument data before a VWAP/POV design. |
| Market quality and HFT | How do spreads, volatility, quantities, and speed relate to outcomes? | Measure the user’s own data route; do not infer latency advantage from HFT case studies. |
| Market impact and optimal trading | What is the cost/risk tradeoff for a target order? | Establish arrival-price and cost benchmarks before modeling a schedule. |
| Order-book dynamics | Do depth and order-flow changes contain information about subsequent price moves? | Research only if the feed supports timestamped book updates; snapshots alone limit the claim. |

The older edition’s examples discuss European regulation/venues and US market events. They help formulate questions about venue design, but Nifty deployment requires current Indian exchange and broker documentation. The second edition’s order-book focus is still a useful research agenda, not evidence that the same features predict NSE Nifty futures or options today. [1–3]

## 3. Build a liquidity state, not a single liquidity score

The contents identify traded volume, bid-ask spread, volatility, and quoted quantities as separate liquidity variables. [2] These dimensions can diverge: heavy volume can coexist with a wide spread, and displayed size can vanish before an order reaches the exchange.

**Project design:** for each candidate contract and time bucket, preserve and report:

- bid/ask prices and sizes, spread in points and ticks, quote age, source, and timestamp;
- last trade price/size/time separately from the quote;
- realized volatility over a defined window and its estimator;
- traded volume and trade count with the data source and time aggregation;
- order events and cancellation/replacement activity if available;
- effective spread, fill rate, post-fill markout, and cost per completed unit.

Do not combine these into one score before checking their individual meaning and stability. The project should also retain missing and stale fields as explicit quality flags. If the broker exposes only periodic quotes, mark event-level additions/cancellations and queue dynamics as unobserved.

For derivatives, collect the same state for the exact contract traded. Nifty spot volume or index value is not the executable liquidity of a Nifty option. A futures proxy can inform a forecast, but it does not tell whether an option order filled or how much spread it paid.

## 4. Separate the “when” problem from the “where” problem

The book’s contents distinguish trading schedules from smart routing. [1–2] A schedule determines when and how quickly a desired quantity is traded; routing determines where or through which market path it is sent. These require different data and constraints.

For the initial project, the broker route and instrument are not yet specified. First collect broker/API capabilities, venue availability, order types, and timestamp semantics. Then compare a simple execution baseline against a fixed time-sliced or volume-aware schedule. Only consider routing if the account route can actually access multiple venues, the exchange rules permit it, and synchronized data are available. A theoretical SOR does not create venue access.

Before building a schedule, define:

- a target position/quantity and deadline;
- the benchmark price (arrival mid, arrival trade, or another documented reference);
- the acceptable price-risk and completion risk;
- participation limits based on verified volume data;
- treatment of a missing quote, unusual session, expiry, or halt;
- how partial fills and unfilled residuals are measured.

The project should compare schedule performance on cost and completion, not just average price. A strategy that avoids spread but misses the deadline may be worse for the stated objective.

## 5. Measure impact at multiple horizons

The book’s description connects market impact, trade scheduling, and order-book dynamics. [1–2] For our purposes, distinguish immediate execution cost from price movement after the trade:

1. **Implementation shortfall:** signed difference between the fill(s) and a predeclared decision/arrival benchmark, plus explicit costs.
2. **Temporary impact proxy:** price movement close to execution that later partially reverses, measured only if the data can resolve it.
3. **Longer-horizon markout:** signed price movement after a fill at selected horizons, to reveal adverse selection or delayed impact.

The measures depend on timestamps and a valid benchmark. If the feed only has minute bars, report minute-level costs; do not imply millisecond estimates. Compare small order sizes to larger paper sizes without assuming linear impact. The book’s models and market cases need recalibration for a retail-size Nifty order.

## 6. Order-book dynamics are data-dependent

The second edition adds a substantial order-book-dynamics section, according to the public book description. [3] This is relevant only if the feed includes sufficiently frequent, correctly ordered updates. A best-bid/ask snapshot does not reveal the sequence of queue additions, cancellations, and executions that produced it.

Before testing a book signal, document whether the data contain:

- event time versus receive time;
- sequence/update IDs or another reliable ordering key;
- all visible levels or only best quotes;
- update and reset behavior;
- trade messages linked to book changes;
- contractual permission to retain and replay the data.

If any are missing, narrow the research claim to snapshot-level association. Do not pretend a snapshot ratio is a queue-aware event-flow measure or hidden-liquidity detector.

## 7. Fragmentation, systemic risk, and safeguards

The book links venue fragmentation and HFT activity to market quality, systemic risk, and the Flash Crash. [1–2] The practical value for a small system is to consider liquidity stress and feedback, even when it is not colocated or routing across venues. A sudden spread/depth deterioration can make a previously tolerable order policy unsafe.

Add observable stop conditions for stale data, spread widening, depth loss, excessive rejects, repeated partial fills, connection faults, and abnormal volatility. A safe system needs a defined behavior for “do not trade,” not just an entry signal. Any limit, circuit-breaker, and order-rate settings must follow current exchange/broker requirements rather than a historical chapter.

NSE’s current public page says retail Client Direct API facilities are subject to exchange circular NSE/INVG/73992 dated 30 April 2026 and member application/registration steps. The specific broker’s route must be confirmed against the current circular and the broker’s own controls. [4]

## 8. Suggested application sequence

1. Record exact contract, venue, broker route, data permission, and update granularity.
2. Build the four-part liquidity report: volume, spread, volatility, and quoted quantity.
3. Segment by time of day and contract; flag expiry/event sessions rather than blending them silently.
4. Measure spread, slippage, markout, completion, and impact for a simple paper execution baseline.
5. Explore schedule improvements only if enough quote/trade data support them.
6. Defer SOR, hidden-liquidity inference, and queue-aware signals until the data and account route make them observable.
7. Add liquidity-stress stop conditions to the paper system before any live-order discussion.

## 9. Evidence limits and next actions

- Full text and quantitative appendix were not obtained. No proxy or scan was used.
- The book’s market-fragmentation case studies focus substantially on US/European market design. Recheck all venue, regulatory, and tick assumptions for NSE.
- No claim is made that any order-book feature predicts current Nifty returns.
- Obtain authorized full text and review the chapters on liquidity variables, scheduling/routing, and order-book dynamics before implementation.
- Keep B06 in progress until the actual reading is completed.

## Sources

1. [Google Books — *Market Microstructure in Practice*, second edition](https://books.google.com/books/about/Market_Microstructure_in_Practice.html?hl=en&id=TtFKDwAAQBAJ&output=html_text) — second edition metadata, publisher description, visible chapter list.
2. [World Scientific — publisher table-of-contents PDF](https://pstobibliothekpublic01.z1.web.core.windows.net/toc/hm00604104.pdf) — public TOC for second edition.
3. [Google Books — second-edition description and order-book-dynamics addition](https://books.google.com/books/about/Market_Microstructure_in_Practice.html?id=i2j3nQEACAAJ) — bibliographic description; edition details cross-checked against catalog records.
4. [NSE — Decision Support Tools / Algorithms trading](https://www.nseindia.in/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — current Client Direct API/retail-algo page.

---
*B06 research note • Public-source synthesis and Nifty system translation. Full-text reading remains open.*
