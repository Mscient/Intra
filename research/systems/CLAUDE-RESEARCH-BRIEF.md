# Research brief for Intra offline trading-system implementation

Prepared 2026-11-03. This brief requests source-backed research to unblock the offline NIFTY derivatives backtester and paper-simulation system. The attached implementation plan is design guidance; this research request does not authorize account access, live data capture, broker orders, or trading.

## Received findings (2026-11-03)

The user supplied an R1–R13 report, archived at [`2026-11-03-r1-r13-compiled-findings.md`](2026-11-03-r1-r13-compiled-findings.md). Preserve its evidence grades: claims marked `[P-ref]` or `[S]` are leads, not verified exchange rules. Its section 3 describes possible outside follow-up; that text is not authorization to contact a broker or exchange, access accounts, or collect data.

R3's NIFTY lot-size transition was independently checked against [NSE/FAOP/70616](https://nsearchives.nseindia.com/content/circulars/FAOP70616.pdf): the circular is dated 2025-10-03, changes NIFTY from 75 to 65, takes effect 2025-10-28 EOD, and specifies the contract-cycle transition through 2025-12-30. This supports treating lot size as a dated contract attribute. R1's revised pre-open phases, R2's CAS and expiry-settlement behavior, R4's 2026-10-30 gap, fee and margin inputs, and data-rights questions remain unresolved pending the evidence described in the report. Preserve missing bars; do not interpolate or infer their cause. R12 now informs exact run-to-registration binding, while DSR/PBO scoring and holdout controls remain open. R13 has no S01–S03 markout results; no performance claim is eligible.

## Research method and required answer format

Use primary sources first: NSE, NSE Clearing, SEBI, and official Angel One SmartAPI documentation/terms. Use broker help pages, forums, GitHub samples, and social posts as leads only, clearly labeled secondary. Do not guess an effective date from a web page's last-updated date.

For every claim that may become a rule or input, return:

1. The claim and its relevance to the system.
2. Issuer, document/page name and number, publication date, effective date, amendments/supersession, and product/segment/contract scope.
3. A direct source link and exact page, paragraph, table, field, or API schema location. Quote only the short text needed to disambiguate it.
4. Whether the claim is verified, provisional, contradicted, or unknown, with a short reason.
5. The proposed normalized data row or interface field and its validity dates. Leave unsupported fields blank/unresolved.
6. Any original public source file, sample schema, or redacted example needed to implement or independently check the result. Include SHA-256 hashes where permitted and useful.

Do not include credentials, private account identifiers, personal data, or instructions to place an order. Do not use a live broker experiment to infer undocumented behavior. If a needed artifact is not public or its use rights are unclear, state that explicitly rather than bypassing access controls.

## Topics, requested information, and implementation destination

### Priority 0 — exchange timeline and feed-gap evidence

| ID | Research topic | Information to find | Related system work |
|---|---|---|---|
| R1 | NSE F&O pre-open history | Obtain NSE/FAOP/71092, NSE/FAOP/71582, and every later implementing/amending circular. Reconcile the original 09:00–09:08 schedule with the current NSE page's 09:00–09:10 entry schedule. Give the effective date for every phase change, eligible current/next-month futures and date logic, order types by phase, random-close behavior, matching sequence, buffer, and any algo-market-order restriction. | Populate the effective-dated market-rule registry, calendar validation, and call-auction selection only for supported historical dates. |
| R2 | Closing Auction Session and derivatives close | Read NSE/FAOP/74467 and all implementing/amending notices. Resolve implementation date, affected derivatives, 15:40 close, CAS phases, treatment of index futures/options, final/settlement-price method, and any relation to the indicative index. Compare the circular with the current NSE CAS page. | Dated session regimes, final-bar completeness, and expiry settlement-source policy. |
| R3 | Contract and expiry timeline | Establish historical NIFTY expiry weekday/cycle and holiday adjustment from dated circulars; compile lot-size and tick-size changes from official dated contract masters/circulars. Include sample files for representative dates before and after changes, source hashes, schema/field meanings, and corrections or replacement files. | Effective-dated instrument master, session calendar, lot/tick checks, expiry handling, and dataset fixtures. |
| R4 | 2026-10-30 SmartAPI 15:14–15:28 gap | Using only authorized data, compare the broker's NIFTY 1-minute candles with official NSE index data and the active dated NIFTY future for 2026-10-30, at least one pre-CAS date, and several post-CAS dates. Determine timestamp convention/time zone, empty-minute and revision behavior, candle field semantics, whether a CAS indicative value is included, and what each source actually represents. Preserve the gap; do not interpolate. Record data access, retention, and research-use terms and hashes where permitted. | Source-aware candle importer/completeness diagnostics and a reproducible gap evidence note; no unsupported repair or causal attribution. |

### Priority 1 — broker interface, legal rules, execution evidence, operations

| ID | Research topic | Information to find | Related system work |
|---|---|---|---|
| R5 | SmartAPI documentation and terms | From current official docs, capture endpoint schemas, historical-candle coverage/limits, request counting and rate limits, WebSocket V2 messages, order-update fields/status values, timestamps, reconnect behavior, replay/sequence guarantees, duplicate handling, idempotency, and local storage/research rights. Mark undocumented semantics unknown. Do not include keys or use a live feed. | Read-only decoder fixtures, rate limiter, quote/raw journal schemas, broker-state parser, and data-rights gate. |
| R6 | SEBI/NSE retail-algo obligations | Read primary SEBI circulars, NSE implementation standards, and later amendments/glide paths. Resolve the OPS threshold's counting scope (client, exchange, segment, algo), treatment of new/modified/canceled/rejected orders, tagging/Algo-ID rules, static IP, kill switch, registration, and each effective date. Clearly distinguish requirements for broker versus client. | G0 compliance record and a conservative future outbound-message budget. Broker order egress remains unavailable. |
| R7 | Historical order-book and execution data | Establish what licensed historical L1, L2, order-event, and trade-tape data can be obtained for NIFTY futures/options and retained for research. Give representative schemas and granularity. Determine availability of order IDs, priority/exchange timestamps, depth updates, cancellations, aggressor side, and sequence numbers; identify what queue position, hidden liquidity, latency, and fills cannot be reconstructed. | Quote/journal adapters, fill-model selection, capacity assumptions, and explicit model boundaries. |
| R8 | Clock, reconnect, and recovery operations | Find primary clock-synchronization and logging requirements; document timestamp precision/time zones and any allowed drift. For broker/feed sessions, identify specified heartbeat, disconnect, reconnect, resubscription, status reconciliation, and duplicate/replay semantics. Propose read-only operational metrics and distinguish them from order-path latency. | Feed lifecycle, clock/quote-age metrics, journal recovery, incident records, and paper recovery drills. |

### Priority 2 — costs, margin, option valuation, and research evidence

| ID | Research topic | Information to find | Related system work |
|---|---|---|---|
| R9 | Fees, taxes, and settlement costs | Build effective-dated rows for futures and options: brokerage where broker-specific, exchange transaction/IPFT charges, SEBI fees, stamp duty, GST base, premium-trade STT, exercise STT, and expiry/settlement charges. Cite statutory/exchange notices; separately identify broker tariff and invoice-specific items. If available, compare against a redacted contract note and explain every basis/rounding difference. | CostSchedule, component fee schedule, exercise/settlement schedule, and independent fee reconciliation. |
| R10 | SPAN, ELM, and broker margin | Locate official NSE Clearing historical SPAN, ELM, and margin-parameter files. Report archived date coverage, publication/update timestamps, contract identifiers, file layouts, replacement/correction behavior, and the distinction between exchange requirement and broker/client margin. Include one dated reproducible example and hashes. | Historical margin schedule import and risk gate; never label a configured proxy as exchange margin. |
| R11 | Independent option-pricing validation | Review the archived synthetic benchmark only as a test vector source, not an independent oracle. Provide a separately documented BSM spot-option and Black-76 futures-option benchmark with rate/dividend/day-count/expiry-time conventions, units for all Greeks and bump sizes, and boundary/no-arbitrage cases. Identify an independent library or authoritative worked example and compare expected values. Explain what market quotes/volatility-surface evidence is needed before valuation can affect replay. | Independent pricer validation, valuation/Greeks tests, and a later portfolio valuation path. |
| R12 | Statistical backtest evidence | Read the Deflated Sharpe Ratio and Probability of Backtest Overfitting papers and any primary statistical references needed. Recommend how to record every tried variant, estimate effective trials, address dependence/autocorrelation, purge/embargo overlapping labels, split chronologically, and seal a holdout. State assumptions, required inputs, and preregistered rejection thresholds before seeing results. | Trial ledger, research-card review, chronological evaluation workflow, and performance-claim gate. |
| R13 | Strategy-data-to-execution boundary | Review the existing Wave A / S01–S03 index-candle markout specification. State precisely what those markouts can establish and what is needed to test a candidate on a dated futures/options contract: signal timestamp, contract-selection rule, expiry handling, synchronized data, decision-to-arrival latency, fill evidence, fees/margin, and holdout. Recommend the smallest evidence-backed next replay slice without proposing a live strategy. | Research card, instrument-aware strategy runner, execution assumptions, and separation of markout diagnostics from executable P&L. |

## Suggested delivery order

The R1–R13 submission has been received. Continue with primary-source verification for R1–R3, authorized user data for R4, then the remaining rights, execution, cost, margin, pricing, and evaluation gaps. Keep each topic separately attributable and retain unresolved fields instead of converting secondary or circular-reference claims into rule rows.

The repository's existing tracker is `research/systems/IMPLEMENTATION-TRACKER.md`; the detailed open questions are in `research/systems/NEXT-RESEARCH-REQUESTS.md`. Research findings should be added as dated evidence, with source hashes where useful, rather than silently converting a hypothesis into a rule.
