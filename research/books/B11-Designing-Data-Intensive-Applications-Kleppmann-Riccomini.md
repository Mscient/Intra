# B11 — Designing Data-Intensive Applications, 2nd Edition: Research for the Nifty System

- **Authors:** Martin Kleppmann and Chris Riccomini
- **Edition / publisher:** Second edition, O’Reilly Media, February 2026
- **ISBN:** 978-1-098-11905-8
- **Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset
- **Research date:** 1 November 2026 (Asia/Kolkata)
- **Research status:** O’Reilly edition contents and public previews reviewed, including Chapter 2 and selected chapter openings; complete book not accessed or read.

> **Evidence boundary.** This note uses the publisher’s table of contents and lawful public previews. It is an original design translation for this project, not a substitute for the book’s full explanations, examples, or exercises. B11 remains in progress until authorized full-text reading is documented.

## 1. Why this book matters before implementation

The second edition is organized around trade-offs in data-system architecture, nonfunctional requirements, data models, storage, schema evolution, replication, sharding, transactions, distributed-system failure, consistency, batch and stream processing, and responsible use of data. The public Chapter 2 preview explicitly frames performance, reliability, scalability, maintainability, operability, simplicity, and evolvability as requirements that should be stated rather than assumed. It also calls out averages, medians, and percentiles when describing latency. [1][2]

For the Nifty project, this points to a concrete pre-build rule: design around the actual data and the broker route available, not around a hypothetical exchange-grade event feed. A single-process Python research and paper-trading application can be reliable and auditable without starting with a distributed cluster.

## 2. Define the system’s promises in measurable terms

Write separate functional requirements and operational requirements before selecting databases or frameworks.

**Functional requirements to settle:** instrument/expiry selection; which signal inputs are permitted; order intent and price rules; order state transitions; position accounting; session close behavior; and the inputs needed to reproduce a research decision.

**Operational requirements to measure:** maximum acceptable data age, tolerated feed gaps, order-state reconciliation behavior, recovery after a process restart, log retention, replayability, and latency from local decision to broker acknowledgement or fill. Measure each boundary the system can actually observe. A broker acknowledgement time is not exchange matching time, and client-received timestamps do not establish the exchange event time.

Use a small set of service objectives that can be checked from logs: for example, percentage of market updates that arrive within a declared age limit; fraction of order attempts ending in a known state; and p50/p95/p99 processing and acknowledgement times with a sample count. Keep the objective values as project decisions based on observed API behavior, not numbers borrowed from a book or an HFT environment.

## 3. A practical data layout

### 3.1 Preserve a raw record and derive everything else

Treat the original authorized broker/exchange response as the evidence record. Store a compact append-only event stream for the fields the provider permits retaining. Each record should include:

- provider event timestamp if supplied, local receive wall-clock timestamp, and local monotonic elapsed-time marker;
- symbol/contract identifier, expiry, strike, option type or future, and instrument-master version;
- source endpoint or feed type, sequence number if present, and connection/session identifier that contains no secret;
- payload schema version, application version, and explicit quality flags for missing, stale, duplicate, delayed, or malformed values.

Keep derived bars, features, option surfaces, signals, and model outputs reproducible from this record. Never rewrite raw data to make it look clean. Append a correction or add a cleaned derived table with a documented rule and code version. If data-retention terms prohibit storing a particular field or stream, honor those terms and record the resulting research limitation.

### 3.2 Keep event time distinct from arrival time

Markets and brokers expose different clocks. Store the source timestamp and local receive timestamp separately. Use a monotonic clock for elapsed processing intervals on one machine; use wall-clock time for human and cross-system correlation, with clock-synchronization uncertainty recorded. Do not compare monotonic clock values across machines or interpret a local receive timestamp as an exchange timestamp.

For historical replay, use the event ordering and granularity the source really provides. If two updates have identical coarse timestamps and no sequence number, preserve that ambiguity instead of inventing an order.

### 3.3 Start with a single-node source of truth

The book contrasts operational and analytical systems and describes trade-offs among storage engines. For this project, the first implementation should normally separate a durable order/event ledger from research tables, while keeping both on one machine or one database until measurements justify more infrastructure. A transactional local database plus versioned flat-file or columnar research exports can be enough for early studies. Pick only after documenting expected write rate, query shape, recovery needs, backup method, and data license.

Do not introduce Kafka, a distributed database, sharding, or cloud services just because the table of contents discusses them. They add operational failure modes. The initial workload is likely limited by data access, broker constraints, and research validity before storage throughput.

## 4. Order handling as a fault-tolerant state machine

The publisher preview’s treatment of transactions and distributed failures is directly relevant to broker APIs: a local process, network, or remote service can fail between a request and its response. A timed-out request leaves uncertainty about whether the broker accepted the order. [3][4]

Maintain an append-only order event ledger such as: intent created, risk checks passed/failed, request sent, acknowledgement received, partial fill, fill, cancel requested, cancel confirmed, rejection, timeout, and reconciliation result. Build the current order and position view by reducing those events. Save a unique local intent ID and the broker order ID when known. Do not assume a client-generated ID gives true broker-side idempotency unless the broker documents that guarantee.

After a timeout or reconnect, stop submitting dependent orders until the broker’s open orders, fills, and positions have been queried and reconciled. A retry must be based on the observed broker state and documented API semantics, not on the assumption that “no response means no order.” If the state remains unknown, fail closed and require manual review in the paper system.

For early development, use one order writer and a serialized order-state transition path. A second process or replica is not a reliability improvement until split-brain behavior, duplicate submissions, shared state, and recovery have explicit solutions.

## 5. Make schema evolution and replay explicit

Instrument definitions, broker payloads, fee schedules, and order fields change. Version the stored schema and the parser separately. Add fields compatibly where possible, retain old event interpretation code for replay, and record the instrument-master version used for each contract. Backfill or migration rules should be deterministic and logged.

Run research features as derived computations over a fixed raw-data snapshot. Save the dataset identifier, code revision, feature schema, parameters, and source versions with each result. This lets a later code change regenerate a result and show which assumption changed. Never silently blend a revised contract file with an older series.

## 6. Use batch first; add streaming only for a measured need

A batch-first pipeline is easier to replay and audit: ingest authorized market data, validate and store it, derive bars/features, then evaluate research. Real-time paper trading can consume the same validated event format with a small in-process queue. Add streaming infrastructure only when the actual throughput, availability, or latency requirement cannot be met by this simpler design.

For stream handling, define behavior for duplicate, late, out-of-order, missing, and repeated events. Book contents include reasoning about time, stream joins, fault tolerance, and derived state; a project adaptation is to treat a late correction as a new fact and recalculate affected derived outputs, rather than silently overwriting a prior signal or order decision. [1]

## 7. Nifty-specific pre-build checklist

1. Obtain the broker’s written limits and semantics for market-data granularity, timestamps, retention, order acknowledgement, modifications, cancellation, retries, and reconnects.
2. Confirm the current NSE and broker classification for the intended Client Direct API path. NSE’s public guidance is updated as rules and circulars change; the current page references an April 2026 circular. [5]
3. Specify raw event fields and privacy/retention boundaries before collecting data.
4. Implement deterministic parsing, quality flags, deduplication, and replay before strategy logic.
5. Define the order state machine and recovery/reconciliation procedure before order submission is enabled.
6. Record service metrics separately from P&L and strategy metrics.
7. Keep the first version local and simple; document the measured reason before adopting distributed components.
8. Preserve a no-order mode and a fail-closed path for stale data, unknown positions, or broken broker connectivity.

## 8. Limits and reading plan

The second edition is recent and its broad system-design lessons are useful, but its examples span many application types. They do not establish the correctness of a specific trading system or authorize a particular broker/API use. A data architecture cannot recover tick-level order-book facts that were never supplied. A distributed-system pattern does not eliminate broker uncertainty or market risk.

Read the full authorized chapters on nonfunctional requirements; data models and event sourcing; storage/retrieval; encoding and evolution; transactions; distributed-system faults and clocks; stream processing; and privacy before implementing the corresponding components. Keep the initial implementation single-node unless measured constraints require more.

## Sources

1. [O’Reilly — *Designing Data-Intensive Applications*, 2nd edition](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/) — edition metadata and full chapter map.
2. [O’Reilly — Chapter 2, “Defining Nonfunctional Requirements”](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/ch02.html) — lawful public preview on performance, latency, reliability, scalability, maintainability, and evolvability.
3. [O’Reilly — Chapter 8, “Transactions”](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/ch08.html) — lawful public opening on failures during writes, application crashes, network interruption, concurrent updates, and race conditions.
4. [O’Reilly — Chapter 9, “The Trouble with Distributed Systems”](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/ch09.html) — chapter map and public opening on fault-oriented design.
5. [NSE — Decision Support Tools / Algorithmic Trading](https://www.nseindia.com/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) — current exchange page for Client Direct API/retail algorithmic-trading procedures; recheck before implementation.

---
*B11 research note • System-design synthesis for the Nifty retail-API paper system. Full-text reading remains open.*
