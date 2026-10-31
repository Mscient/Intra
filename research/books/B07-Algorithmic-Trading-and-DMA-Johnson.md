# B07 — Algorithmic Trading & DMA: Research for the Nifty Retail-API System

**Author:** Barry Johnson  
**Edition / publisher:** 2010, 4Myeloma Press  
**ISBN:** 978-0-9563992-0-5  
**Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset  
**Research date:** 1 November 2026 (Asia/Kolkata)  
**Research status:** Public bibliographic records and table of contents reviewed; no full text or authorized chapter preview obtained.

> **Purpose and evidence boundary.** Public catalogue records identify the book and list its major parts. The author’s old book website did not load through the research interface, and the full book was not obtained. This note uses only the public catalogue-level contents to identify questions relevant to the project. It does not claim to summarize the unread chapters or to reproduce any text. No proxy-hosted scan was used.

## 1. Scope visible in public records

The National Bibliography record identifies a 2010 book about algorithmic trading and direct market access. Its contents are organized around: trading and market microstructure; algorithms, orders, transaction costs, and optimal trading; order placement and execution tactics; enhancements and infrastructure requirements; and advanced topics such as portfolios, multi-asset trading, news, and data mining/AI. [1–2]

That broad scope maps well to this project’s pre-build questions, especially execution goals, order management, transaction-cost analysis, and infrastructure. Its date is also a material limit: the book predates India’s 2025–2026 retail API/algo implementation requirements. Treat its infrastructure and regulation headings as background, not current operating instructions.

## 2. A practical decision framework suggested by the contents

### 2.1 Choose the objective before the algorithm

The contents place “optimal trading strategies” before order placement and execution tactics. [1] For the Nifty system, distinguish:

- **Alpha strategy:** decides whether and in which direction the account should hold risk.
- **Execution algorithm:** decides how to complete a specified target position.
- **DMA/API path:** the authorized mechanism that accepts and routes the order to a broker/exchange.

Do not judge an execution method by standalone trading profit. Compare its cost and completion quality against a fixed target and a declared benchmark. The alpha model should be assessed separately.

Before selecting an execution method, define the benchmark, urgency/deadline, risk aversion, target size, acceptable slippage, and treatment of any residual quantity. These are useful design questions derived from the book’s listed optimal-strategy material; they are not claims about specific unread formulas.

### 2.2 Treat transaction costs as an explicit part of the research

The book’s contents include transaction costs and optimal trading. [1] A Nifty backtest should not treat the next bar’s close as a costless execution price. For each order, preserve the decision-time quote, selected limit, send/ack times, partial and final fills, cancellation, and fees. Report price movement during execution, spread paid or saved, opportunity cost from non-fills, and fees separately.

For a retail broker API, the observed cost includes more than a theoretical market-impact estimate. It can include spread, price movement while an order is pending, missed completion, partial-fill exposure, brokerage, exchange charges, statutory taxes, and API/broker behavior. Model only the components supported by data; mark the rest as unknown and run conservative sensitivity cases.

### 2.3 Order placement is a risk choice

The contents identify order placement, execution tactics, and infrastructure as separate implementation subjects. [1] This encourages explicit choices about aggressiveness, order price, size, and deadline. A passive limit may reduce spread crossing but risks non-fill and adverse selection. A more aggressive order can complete sooner but may pay a wider spread or move through visible depth.

For this project, start with a capped marketable-limit policy and a passive-limit comparator in a paper/sandbox environment, if those order types are permitted on the selected API route. Track partial fills and cancellation races. Do not assume “limit” means no execution risk: a resting position can become unwanted exposure after the market moves.

The exact allowed order types and parameter semantics must come from the current broker interface and NSE rules, not the 2010 text. In particular, do not port US/European DMA assumptions directly into an Indian retail API.

### 2.4 Execution tactics should respond to measurable conditions

The catalogue’s execution-tactics and enhancement topics suggest examining market conditions such as price, volume, liquidity, and volatility in an order policy. [1] A simple implementation can begin with fixed rules: skip when data are stale; do not widen a limit beyond a price collar; reduce participation when displayed depth is thin; stop retrying after a set number of rejects; and leave an audit record when a target remains incomplete.

Only add predictive inputs after the baseline is measured. A forecast of volume or price can be wrong, so an adaptive schedule needs guardrails, a fallback path, and a comparison against a non-predictive baseline. A reactive policy that chases every update can worsen execution and produce excessive API activity.

### 2.5 Infrastructure and order management are product requirements

The book’s contents include infrastructure, order management, order entry/routing, testing, clearing/settlement, and regulatory compliance. [1] For a reliable paper system, use an explicit order state machine with unique client order IDs, duplicate-intent protection, broker/exchange order IDs, status reconciliation, and idempotent recovery after reconnects. Store each transition with timestamps and rejection reasons. Keep the trading decision process separate from broker transport and position reconciliation.

Before the first paper order, check: quote age, connection health, contract identity, price/quantity limits, duplicate state, current position, available funds/margin as reported by the broker, allowed order type, and the correct market session. Define behavior for timeout, reject, partial fill, late acknowledgement, cancel failure, reconnect, and an inconsistent broker position.

The current NSE public page says retail Client Direct API facilities use the exchange process under circular NSE/INVG/73992 dated 30 April 2026 and member registration/application steps. Confirm the selected broker’s implementation, tagging, permissions, and controls against current documentation. A book published in 2010 cannot describe this current process. [3]

## 3. What is useful now versus later

| Topic in public contents | Initial project decision |
|---|---|
| Order types and market structure | Read a lawful full copy if obtained; independently confirm current NSE matching/order rules and broker semantics. |
| Transaction-cost measurement | Use now: make cost and non-completion observable before strategy optimization. |
| Optimal execution and tactics | Use as a research framework after defining a target and benchmark. |
| Order management and infrastructure | Use now to specify reliable paper/API states, logging, reconciliation, and fault handling. |
| Portfolios and multi-asset trading | Defer until one Nifty instrument and the single-instrument workflow are validated. |
| News and AI/data mining | Defer until a specific data source, timestamp policy, and measurable hypothesis exist. |
| World-market appendices | Treat as historical orientation; validate India-specific venue and rules from current sources. |

The first system milestone should be a read-only recorder and paper order journal, not a generalized institutional execution platform. For small own-account size, benchmark whether a sophisticated execution algorithm adds anything over one simple capped order and a clear timeout policy.

## 4. Nifty-specific pre-build checklist

1. Select the instrument and venue: Nifty futures or a named options contract; document effective-dated contract metadata.
2. Ask the broker which Client Direct API route applies and what it exposes: quotes, timestamps, depth, order events, allowed order types, rate controls, sandbox, and retention/data-use terms.
3. Define the target and benchmark before evaluating an execution algorithm.
4. Implement an append-only order journal and position reconciliation before enabling automated entries.
5. Measure spread, latency, completion, partial fills, fees, and post-fill markout using the data actually available.
6. Compare a simple baseline to a more complex tactic on chronological holdouts.
7. Keep live access disabled until broker/exchange requirements and kill controls are documented.

## 5. Evidence limits and next actions

- The book’s full text and authorized excerpt were not available in this research pass; only catalogue-level description and contents were used.
- Detailed order-type behavior, equations, and strategies from the book remain unverified.
- The book’s 2010 treatment cannot be used as current India compliance guidance.
- Obtain an authorized copy or institutional-library access; then read the chapters on transaction costs, optimal trading, order placement, execution tactics, infrastructure, and testing.
- Keep B07 “in progress” until actual chapter reading is recorded.

## Sources

1. [Open British National Bibliography — *Algorithmic trading & DMA*](https://obnb.uk/p15531429-algorithmic-trading-dma-an-introduction-to-direct-access-trading-strategies) — publisher, date, ISBN, and catalogue contents.
2. [Google Books — *Algorithmic Trading & DMA*](https://books.google.com/books?hl=en&id=xgRcYgEACAAJ&pgis=1) — bibliographic record and page count.
3. [NSE — Decision Support Tools / Algorithms trading](https://www.nseindia.in/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — current Client Direct API/retail-algo page and circular reference.

---
*B07 research note • Catalogue-based scope synthesis and Nifty system translation. Full-text reading remains open.*
