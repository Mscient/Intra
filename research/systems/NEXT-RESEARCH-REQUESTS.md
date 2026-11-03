# Next research requests for Intra

Use primary NSE, NSE Clearing, SEBI, and Angel One SmartAPI documents wherever available. For every rule, record the document number, publication date, effective date, product/segment scope, source URL, exact page/paragraph/table, and later amendments. Mark forum posts and broker summaries as leads only. Do not include credentials, private account IDs, or a recommendation to place live orders.

## What each research topic will unblock

| Topic | Information to return | Coding destination |
|---|---|---|
| Dated NSE rules | Official circulars, exact effective dates, covered contracts, and amendment history for hours, pre-open, expiry/holidays, lot/tick, margin add-ons, and settlement | Effective-dated rule schedule and validation against session regime IDs; instrument-master/calendar import |
| 15:28 NIFTY candle gap | Authorized raw examples, official index/futures comparisons, timestamp semantics, missing-bar behavior, and rights/retention evidence | Feed importer, source-aware completeness reconciliation, session diagnostics |
| SmartAPI behavior and rights | Official endpoint schemas, limits, timestamps, history coverage, persistence terms, reconnect/status semantics, and unknowns | Read-only adapter/decoder fixtures, API rate limiter, OMS event parser, data-rights gate |
| Retail-algo scope | Primary rules for OPS counting, static IP, tags/Algo-ID, kill switch, and each effective date | G0 compliance record and outbound-message budget; broker order egress remains disabled |
| Costs and margin | Dated exchange/regulator rates, source files and update times, broker invoice comparisons, SPAN/ELM archive coverage | CostSchedule, SettlementCostSchedule, MarginRequirementSchedule, RiskGate |
| Option model | Independent BSM and Black-76 conventions, benchmark cases, Greeks units, expiry clock, and edge conditions | EuropeanOptionPricer and later portfolio valuation integration |
| Execution realism | Official order types/priority/protection, authorized L1/L2/order-event history availability, queue-reconstruction limits, and latency evidence | Fill model, quote replay, capacity/queue assumptions; no claim beyond captured data |
| Statistical evidence | DSR/PBO method, trial-count rules, dependence/purging assumptions, and preregistered thresholds | Evaluation package, trial registry, and strategy research card |
| Operations | Primary clock requirements and reconnect/recovery semantics; distinguish read-only metrics from order-path latency | Feed lifecycle, clock/quote-age metrics, paper recovery drills |

## Priority 0: rule timeline and the 15:28 index-feed gap

1. Retrieve and read NSE/FAOP/74467. Establish the implementation date, exact derivatives-session changes, trade-modification end time, closing-price/settlement methodology, and whether any index derivative settlement input changed. Include the circular PDF and exact page/paragraph references.
2. Retrieve NSE/FAOP/71092 and every later circular changing F&O pre-open. Resolve the effective date of the schedule on NSE's page updated 04/09/2026. State eligible futures maturities, phases, random closure, matching, buffer, and market-order rules. Reconcile the circular with current NSE page text.
3. Establish NIFTY expiry weekdays and holiday adjustment from the exchange circulars, and build a dated timeline of lot size, tick size, expiry cycle, and contract specifications from official contract masters. Include actual source files and their hashes where rights permit.
4. Investigate the SmartAPI NIFTY 1-minute 2026-10-30 gap from 15:14 to 15:28 without repairing it. Compare authorized broker candles with NSE historical NIFTY index data and the active NIFTY future for at least one pre-CAS date and several post-CAS dates. Establish timestamp conventions, data field semantics, missing-bar behavior, and whether the index feed contains a CAS indicative value. Report hashes and access/retention terms. A coincident time window is not proof of cause.

**Deliverable:** a source-backed effective-dated rule table plus a reproducible feed-gap evidence note. Leave any unproven fields marked unresolved.

## Priority 1: compliance and broker/feed interfaces

5. Read the primary SEBI retail-algo circulars, NSE implementation standards, and glide-path updates. Resolve the OPS threshold's scope and counting semantics: client/exchange/segment, new orders, modifications, cancellations, rejects, and algo tags. Confirm static-IP, Algo-ID, and kill-switch requirements from the text in force. Do not infer the rule from a broker blog.
6. Read the current official SmartAPI rate-limit, historical-candle, WebSocket V2, and order-update documentation. Record limits and field/status definitions with version/date. Separate official documentation from forum samples; list reconnect replay, sequence numbers, duplicate handling, request counting, and idempotency as unknown where no spec exists.
7. Verify what SmartAPI candle timestamps represent (bar start/end, timezone, treatment of empty minutes, duplicates and revisions) and its historical F&O coverage/retention terms. Confirm whether data may be retained and used for local research under the account/product terms. Do not capture a live feed until the separate G0 data-rights gate is documented.

**Deliverable:** an API capability matrix with claims graded as official, provisional, or unknown; no credentials or live-order experiment.

## Priority 2: risk, costs, valuation, and evaluation

8. Inspect NSE Clearing's official download/archive pages for dated SPAN, ELM, and margin-parameter files. Determine what can be replayed historically, file update frequencies/timestamps, contract coverage, and the difference between exchange requirements and broker/client margin. Give one dated example with original files and hashes.
9. Build effective-dated fee/tax source rows for futures and options, including premium trade STT, exercise STT, exchange/IPFT/SEBI fees, stamp duty, GST basis, and settlement charges. Cite official notices and then compare to a redacted sample contract note/invoice if one is available. Separate verified statutory rules from broker-specific charges.
10. Review the supplied option-pricing benchmark, archived at research/systems/option-pricing-benchmark, against an independent documented reference. Specify conventions for expiry time, day count, rates/dividend yield, futures versus spot forwards, and output units. Include a Black-76 futures-option case and boundary/no-arbitrage checks. Do not treat a synthetic grid as market validation or the supplied JSON as an independent oracle.
11. Read the Deflated Sharpe Ratio and Probability of Backtest Overfitting papers. Recommend a preregistered trial ledger, dependence-aware sample-size treatment, chronological/purged validation and sealed holdout protocol. State which assumptions require the total number of tried variants and set thresholds before seeing outcomes.
12. Find primary evidence for clock synchronization requirements and broker/feed reconnect behavior. Propose read-only measurements (UTC offset, quote age, inter-arrival gaps, reconnect duration) and explicitly separate them from order-path latency, which cannot be measured without orders.
13. Research the historical availability and licensing of incremental L2 or order-event data for NIFTY futures/options. Establish whether exchange order IDs, priority timestamps, cancellations, and depth changes are available at the required granularity, and which queue-position claims can or cannot be reconstructed. Include a representative schema and an evidence-based fill-model boundary.


**Deliverable:** a dated, source-attributed risk/evaluation research pack with unresolved items explicit. No paper/live order routing is authorized by this research list.