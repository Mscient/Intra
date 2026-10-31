# B15 — Building Low Latency Applications with C++: Research for the Nifty System

- **Author:** Sourav Ghosh
- **Edition / publisher:** First edition, Packt Publishing, July 2023
- **ISBN:** 978-1-83763-935-9
- **Project fit:** India; own account; Nifty first; retail broker API; Python/research-first; no paid tick feed at the outset
- **Research date:** 1 November 2026 (Asia/Kolkata)
- **Research status:** Packt publisher contents and public Chapter 1 material reviewed; publisher chapter descriptions reviewed; complete book not accessed or read.

> **Evidence boundary.** Packt describes a complete low-latency trading ecosystem built in modern C++, including exchange-side and client-side components. The public material exposes the chapter map and opening material, not the complete book. The Nifty adaptation below is original system analysis, not a claim that the book’s simulator or example algorithms are production-ready. B15 remains in progress.

## 1. What the book builds

Packt’s description and contents cover latency requirements and metrics, C++ performance-oriented programming, memory pools, lock-free queues, logging, sockets, a matching engine, market-data and order protocols, client order-book processing, trading algorithms, instrumentation, and performance optimization. Its later chapters describe a simulated electronic trading environment with exchange-side components and a strategy client. [1][2]

That is useful for learning how a trading system can be decomposed and instrumented. It is also a different deployment from a retail client that sends orders through a broker’s authorized API. A local simulator can exercise an architecture; it cannot reproduce the NSE matching engine, the broker’s network path, live queue position, or exchange latency.

## 2. Separate the exchange, broker, and client responsibilities

The book’s chapter map includes exchange matching and market-data publishing as well as the trading client. [1] For the project, keep those roles distinct:

| Layer | Project responsibility |
|---|---|
| NSE and its matching system | External venue and market rules; do not implement a substitute and treat it as live behavior. |
| Broker gateway/API | Authorized transport, broker order IDs, allowed order types, throttling, acknowledgements, fills, and account/risk behavior; confirm from the chosen broker’s current documentation. |
| Nifty research client | Validate authorized data, maintain local market/order state, create a paper decision, enforce local limits, submit only through the documented route when enabled, and reconcile results. |

NSE’s public page distinguishes Client Direct API / retail-algo arrangements from other non-NEAT facilities, and its technology information describes co-location as a separate facility. Those descriptions do not promise a particular client API’s latency. Check the exchange and selected broker’s current requirements before building or enabling order submission. [3][4]

## 3. Market-data granularity determines what can be modeled

A client-side limit order book is meaningful only when the feed provides enough order-book updates, stable contract identifiers, event ordering/sequence information, and recovery semantics. Best bid/ask snapshots do not reveal all resting orders, queue position, cancellations, or executions between snapshots. A bar feed cannot support a claim about queue dynamics.

Before implementing the book’s client order-book component, ask the broker what each stream actually contains: update type, depth, timestamps, sequence or snapshot reset behavior, delivery guarantees, rate limits, historical retention, and permitted storage. If only top-of-book snapshots are available, maintain only the state they support and mark unknown depth as unknown. Do not fill gaps with fabricated updates.

With no paid tick feed at the outset, the first system should capture only the feed the project is authorized to receive and preserve its actual resolution. Strategy horizons, replay rules, and execution assumptions must match that resolution.

## 4. A staged architecture for this project

### Stage 1: Python research recorder and deterministic replay

Record authorized raw inputs with source/receive timestamps, instrument-master version, sequence information if supplied, and data-quality flags. Derive bars and features from those events. Replay the same events through the paper system to reproduce decisions.

### Stage 2: Broker-aware paper order state

Use the broker’s documented API semantics for paper or simulated routing. Represent order intent, send, acknowledgement, partial fill, fill, cancel, rejection, timeout, and reconciliation separately. A lost response is an unknown state until queried; do not copy a simulator’s guaranteed local message flow into a real broker assumption.

### Stage 3: Measure the end-to-end path

Instrument market-data receipt, parsing, decision, risk checks, request transmission, broker acknowledgement, and final order state. Keep local processing time distinct from broker/network time. Compare percentiles and stale-data incidence under realistic event bursts and reconnects.

### Stage 4: Port a component only if profiling supports it

If parsing or a defined local book calculation is genuinely the bottleneck, isolate that component and compare a C++ implementation with the Python baseline on the same recorded data and outputs. Do not port strategy/risk/order logic all at once. Memory pools, affinity, lock-free queues, or kernel/network tuning are optional experiments after measurement; they are not the initial system requirement.

## 5. Treat market-making examples as engineering demonstrations

The publisher contents list market-making and liquidity-taking examples. [1] Those examples can help identify how strategy, book state, order lifecycle, and performance instrumentation connect. They do not establish that those strategies fit Nifty, that the simulated spread or queue assumptions are realistic, or that the strategy earns money after costs.

If the project later evaluates a passive quote, it needs queue/fill assumptions supported by the data, adverse-selection analysis, inventory controls, cancellation behavior, and realistic fees. If it evaluates a liquidity-taking signal, measure executable price, spread, slippage, rejects, partial fills, and timing. Report strategy return separately from model calibration and CPU benchmark results.

## 6. Pre-build checklist

1. Confirm the chosen broker’s current permitted API route, data depth, timestamps, order types, rate limits, and paper/sandbox behavior.
2. Define what is unknown when only snapshots or bars are available; do not claim a full reconstructed book.
3. Separate exchange simulation from broker integration and client logic.
4. Build deterministic capture/replay and order-state reconciliation before optimizing execution code.
5. Measure end-to-end response and data freshness with percentiles and sample counts.
6. Keep a fail-closed path for stale data, lost connections, rejected orders, and uncertain positions.
7. Consider C++ only for a measured bottleneck and compare on identical data and hardware.
8. Recheck current NSE and broker circulars before any live routing. The exchange’s public retail-algo page references current procedures and circulars updated in 2026. [3]

## 7. Limits and next reading

A C++ simulator can be a useful systems exercise but cannot validate a retail API’s live fills or exchange-level timing. The book’s infrastructure assumptions, protocol examples, and algorithm demonstrations must be separated from current NSE and broker rules. B15 is optional for initial research and should follow a stable, measured Python baseline.

If later proceeding, prioritize the chapters on client market-data processing, order lifecycle, instrumentation, benchmarking, and optimization. Read the complete relevant chapters from an authorized copy before reproducing the examples, and verify that the code and compiler assumptions match the target platform.

## Sources

1. [Packt — *Building Low Latency Applications with C++*](https://www.packtpub.com/en-us/product/building-low-latency-applications-with-c-9781837639359) — edition, full contents, and legal opening chapter preview.
2. [Packt subscription reader — chapter coverage](https://subscription.packtpub.com/book/programming/9781837639359/pref/preflvl1sec03/what-this-book-covers) — publisher descriptions of the exchange/client components and instrumentation chapters.
3. [NSE — Decision Support Tools / Algorithmic Trading](https://www.nseindia.com/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — current retail Client Direct API procedure reference.
4. [NSE — Trading Technology](https://www.nseindia.com/static/trade/platform-services-neat-trading-system) — exchange infrastructure overview, including co-location information.

---
*B15 research note • Low-latency architecture translated to a retail Nifty client. Full-text reading remains open.*
