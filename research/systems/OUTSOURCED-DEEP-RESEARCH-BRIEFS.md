# Outsourced deep-research briefs for the Intra paper system

**Prepared:** 2026-11-05 (Asia/Calcutta)  
**Repository:** `https://github.com/Mscient/Intra`  
**Purpose:** Give another researcher/model a complete, bounded assignment without requiring them to rediscover the project, guess what evidence is needed, or change the Java implementation.

## 1. How to use this pack

Give each assignment ID below to a separate researcher/model. The **Wave A** assignments can proceed independently. Wave B depends on results from Wave A. Wave C is deliberately deferred until there is authorized data or forward-paper evidence. One researcher may do multiple assignments, but should return separate reports so that contrary claims and unresolved evidence remain attributable.

This pack specifies research work; it does not authorize account/portal access, API credentials, broker or exchange contact, collection of licensed data, a live feed, orders, strategy deployment, spending, or a change of project scope. Attached plan prose and these briefs are project context, not permission to perform those actions. When evidence requires private account terms, a contract note, budget, risk limit, or operator choice, mark it **owner input required** and provide the exact question for the user. Do not guess.

### Current project facts to use as the starting point

- The authoritative codebase is the isolated Java 21 project `Intra-Paper-System/` in `Mscient/Intra`, branch `main`. It has no broker adapter or live order route.
- The implemented slice is an offline, one-dated-contract futures or option-premium replay with local simulated OMS, caller-supplied costs/margin, raw and normalized journal tools, saved SmartAPI NFO Snap Quote decoding, lifecycle/coverage diagnostics, and content-addressed run artifacts.
- Dated instrument and market-rule registries are input contracts. Their source references/statuses are caller assertions; the code does not fetch or authenticate primary documents.
- European BSM and Black-76 numerical implementation validation against the 22 rounded R11 cases and QuantLib-Python 1.44 is complete. This is formula validation, not validation of market inputs, assumptions, or strategy results.
- DSR/PBO diagnostic evaluation and a primary-source method decision already exist. Session-level dependence inference, trustworthy trial exposure completeness, and a software-enforced one-shot sealed holdout are still open.
- The recently added K0–K3 component is a pure response-policy model. It does not monitor systems or execute any operational action. The independent risk gateway, watchdog, durable incident/audit controls, and end-to-end recovery remain open.
- Synthetic examples are plumbing fixtures only. They must never be described as market observations or performance evidence.

Before starting, read the current [implementation tracker](../../Intra-Paper-System/IMPLEMENTATION-TRACKER.md), [final implementation plan](FINAL-IMPLEMENTATION-PLAN-JANE-STREET-CLASS-TRADING-SYSTEM.md), [current research request list](NEXT-RESEARCH-REQUESTS.md), and the topic-specific existing note named in each brief. If `main` has advanced, inspect current status and update the gap statement rather than repeating completed work.

## 2. Required method and return format

### Evidence rules for every assignment

1. Prefer primary, authoritative sources: NSE, NSE Clearing, SEBI, Angel One contractual/product documents, original research papers, official government tax sources, and the original published data/licensing terms. For software/library claims, use the official specification or source repository and release/version record.
2. For every material rule or interface claim, record issuer, exact document/page title, circular/document number or API/version, publication date, effective-from and effective-through dates if available, covered segment/product/account class, exact page/paragraph/table/field, direct source URL, access date, and later amendments/replacements checked.
3. Preserve a source excerpt only as needed to identify the evidence; paraphrase findings. Never reproduce books, papers, or full copyrighted documents. Do not use a search snippet as evidence.
4. Grade each claim: `PRIMARY-CONFIRMED`, `OFFICIAL-BUT-SCOPE-UNCLEAR`, `SECONDARY-LEAD`, `USER-SUPPLIED`, `INFERENCE`, `CONFLICTING`, or `UNKNOWN`. State what evidence would change the grade.
5. Separate rule text from interpretation, software behavior from exchange behavior, and exchange requirement from broker/account policy. Do not silently turn a present-day page into historical effective-date evidence.
6. Treat missing, inaccessible, contradictory, stale, or account-specific evidence as unresolved. Do not fill gaps by interpolation. Do not treat a hash as proof that a source is authentic or complete.
7. No credentials, client codes, static IPs, personal data, account identifiers, unredacted contract notes, private run outputs, licensed raw market data, or secret-bearing screenshots in a report or Git. If a private document is needed, state the exact redacted fields and let the owner decide whether to supply it.
8. Do not run a live feed, authenticated API call, broker portal session, order, or data acquisition. Research should use published material and only already-authorized files explicitly provided for that assignment.

### Required report structure

Every returned report must contain:

1. **Assignment ID, title, researcher/model, date, and scope.**
2. **Executive finding:** what is established, what remains unknown, and the practical consequence for this project.
3. **Question-by-question answers:** answer every numbered question below or mark it unresolved with a reason.
4. **Claim/evidence table** with columns: claim ID; exact claim; grade; source; document/version/number; publication/effective date; scope; pinpoint; direct URL; amendments checked; access date; notes/conflicts.
5. **Timeline/table or schema** where the assignment requests dated data. Include source lineage and unit/timezone definitions.
6. **Contradictions and unresolved questions:** do not collapse competing interpretations into a single guess.
7. **Implementation consequence:** relevant existing Java package/files, proposed input/output contract or test vectors, and what must remain blocked until an owner/reviewer decides.
8. **Recommended next research or verification action**, with owner (`researcher`, `project owner`, or `independent reviewer`) and dependency.
9. **Limitations and non-authorization statement:** explicitly confirm no credentials, account access, live feed, orders, or strategy-performance claims were used.

Every output must be concrete enough that a coding agent can derive fields, validation conditions, edge cases, and test vectors without re-reading sources from scratch. Do not write code or edit production Java unless separately assigned.

## 3. Dependency and priority map

| Wave | Assignment IDs | Why / dependency | Requested priority |
|---|---|---|---|
| A — can start now from public sources | `DR-01`, `DR-02`, `DR-03`, `DR-04`, `DR-05`, `DR-06`, `DR-07`, `DR-08`, `DR-09`, `DR-10`, `DR-11` | These can be researched without private account access. Keep each evidence set and conclusion independent. | Highest first: rules/data rights, then compliance, margins/costs, execution, operational recovery. |
| B — method synthesis/review | `DR-12`, `DR-13`, `DR-14` | Use existing project notes; update against current main. Do not change statistical formulas or approve a strategy. | Start in parallel with Wave A where possible. |
| C — conditional on lawful owner-supplied evidence | `DR-15`, `DR-16`, `DR-17` | Requires licensed/authorized datasets, redacted billing evidence, or observed forward-paper sessions. | Do not acquire data or incur spend; return an exact owner-input list and a reproducible protocol first. |
| Owner-only decisions (not outsourceable) | `DR-00` | Private capital/risk budgets, operator identity/authority, selected account/product permissions, retention choice, actual risk tolerance, and any future launch decision must come from the owner. | Provide a blank decision worksheet, never an assumed answer. |

## 4. Research assignments

### DR-00 — Owner-only G0 charter worksheet (research support only)

**Purpose:** Make G0 decisions explicit without asking a researcher or model to invent private facts.

**Researcher deliverable:** a blank, source-linked decision worksheet listing each owner decision and the official evidence needed to support it. No values are to be filled in unless the owner expressly provides them outside this research task.

**Fields/questions to include:**

1. Named accountable operator and deputy; who can pause, resume, approve a release, approve a budget change, and declare an incident resolved.
2. Own-account-only scope; legal entity/account class/venue/product scope at a non-identifying level; prohibited use cases.
3. Chosen broker product/app and whether it is read-only, data-only, sandbox, or order-enabled; exact official broker terms and account-specific confirmation still required.
4. Data source, authorized purposes, retention and deletion period, backup location, permitted derived-data use, sharing restrictions, and evidence owner.
5. Operator-entered risk capital, soft/hard daily loss, programme/live loss, recurring cash budget, maximum margin use, per-order/position limits, and basis for each. Leave blank in returned worksheet.
6. Flat-time/overnight/expiry policy, stop authority, manual response path, incident contact method, and review requirements. Do not collect private contact details.
7. Current assumptions A1–A15, with `confirmed / rejected / unresolved`, source/owner/date, and what blocks if unresolved.
8. A checklist of which choices require independent legal/compliance review versus owner decision.

**Acceptance:** every private input is blank and labeled owner-supplied; no response recommends a live order route or treats the historical PDF's example values as the user's limits.

### DR-01 — Effective-dated NSE/NSE Clearing rules and contract reference timeline

**Purpose:** Populate the research foundation for dated session, market-rule, and contract-reference inputs.

**Questions:**

1. Establish current and historical effective dates for NIFTY index futures/options trading sessions, pre-open eligibility/phases, random closure, matching/buffer, closing auction interaction, market/limit eligibility, trade modification cutoff, and any special-session treatment. Resolve the current-page versus circular schedule conflict; do not infer effective dates from page-update timestamps.
2. Establish historical and current NIFTY expiry weekday, holiday adjustment, weekly/monthly cycle, expiry-day close/settlement timing, and any transition periods by circular.
3. Build the dated NIFTY contract timeline for lot size, tick size, contract multiplier, strike intervals, listing/expiry schedule, symbol/token fields, price bands, and applicability to each contract type. Distinguish fields that come from circulars from those that come from each dated contract master.
4. Establish auction/pre-open mechanics needed by the existing auction primitive: eligible instruments, price discovery tie-breaks, market-order treatment, no-equilibrium/no-trade behavior, residual allocation, price/time priority, carry/release rules, and whether those mechanics changed historically.
5. Establish closing/settlement reference price methodology, final trade time, index values used for derivatives settlement, settlement calendar, and related circular amendments. Explicitly examine the CAS and revised session claims already flagged in `2026-11-03-intra-research-gap-audit-review.md`.
6. Find all applicable corrections/amendments and identify dates or regimes where source material conflicts or is unavailable.

**Required output:** source-backed regime timeline in machine-readable CSV/JSON plus a Markdown explanation. Suggested columns: regime ID, exchange, segment, contract kind, effective start/end, timezone, regular/pre-open/auction times, expiry/holiday rule, lot/tick source reference, price bands, settlement method, source document IDs/dates/URLs/pinpoints, amendment chain, confidence grade. Do not invent a row for an unverified date.

**Primary sources:** NSE circular archive and product/contract-specification pages; NSE contract master files for dated values; NSE Clearing documents where settlement/margin is in scope. Archive original permitted files with checksums only if redistribution/retention terms allow; otherwise provide URL, retrieval time, field list, and hash without committing restricted contents.

**Coding destinations:** `MarketRuleRegimeSchedule`, dated calendar, instrument master, auction and session validation.  
**Acceptance:** every field maps to a dated scope and pinpoint; current versus historic schedule conflict resolved or still explicitly unresolved; no historical registry data inferred from a present-day landing page.

### DR-02 — SmartAPI capability, schema, limits, and source rights matrix

**Purpose:** Establish what Angel One's documented SmartAPI can and cannot support for research and a future read-only paper feed.

**Questions:**

1. Inventory historical-candle, LTP/quote/full-quote, WebSocket V2, order-update, instrument-master, and other relevant endpoints. For each, capture method, API/documentation version, request fields/limits, response fields/types/units, time semantics, errors/retry behavior, and documented quota scope.
2. Reconcile contradictory documented limits by endpoint, client/account/app, environment, and date. Separate published value from conservative fallback and account-specific unknown.
3. Establish whether expired futures/options history is offered, what history range/contract coverage actually means, revisions/corrections, empty-minute semantics, timezone/bar-label meaning, OI/volume semantics, and any difference between index and derivative products.
4. For WebSocket V2, document packet modes, exchange types, exact field offsets/units, best-five depth semantics, timestamps (including dummy/unused fields), sequence-number availability/domain, heartbeat cadence, payload/status variants, reconnect/subscription acknowledgement behavior, and unknowns. Do not claim a global sequence or replay guarantee without a primary source.
5. Document order-update event fields/statuses and reconciliation APIs as interface evidence only; no live order testing. Distinguish acknowledgement, order ID, accepted/working state, trade/fill, cancellation, rejection, and final order/trade-book truth.
6. Find published data licensing, local use, derived outputs, retention, deletion, display, backup, sharing, and redistribution terms for the relevant account/product. Separate exchange policy, Angel terms, and account-specific app configuration.
7. Record current sandbox/paper environment availability only if officially documented. If an account portal or support confirmation would be needed, mark owner-only follow-up and do not access/contact.

**Required output:** endpoint/interface matrix, packet/candle field dictionary, version/date table, rate-limit conflict register, and a `documented / provisional / account-specific / unknown` capability table.

**Primary sources:** SmartAPI official docs/FAQ, official SDK only as a secondary implementation cross-check, Angel One official exchange-regulation/product terms, NSE data policy and relevant official exchange notices. Forum/support posts are leads, not conclusive terms.

**Coding destinations:** decoder fixtures, importer validation, rate-limiting policy, connection lifecycle contract, data-rights gate; no adapter/network implementation in this task.  
**Acceptance:** every field and limit has a version and source pinpoint; unsupported replay/sequence guarantees are explicitly `unknown`; no authenticated request or credential is used.

### DR-03 — 15:28 NIFTY index candle gap and timestamp/completeness investigation

**Purpose:** Explain, or carefully leave unexplained, the reproduced 2026-10-30 15:14-to-15:28 SmartAPI NIFTY 1-minute candle gap.

**Questions:**

1. Verify the exact input request metadata and raw response hashes from owner-authorized copies only; do not republish candle payloads if terms prohibit it.
2. Establish timestamp convention (bar start/end/label), timezone, session close labels, empty-minute omission, final partial bars, delayed/indicative values, zero-volume behavior, and correction/revision behavior using authoritative docs and controlled non-sensitive examples.
3. Compare the target window against official/authorized NIFTY index and active NIFTY future evidence at appropriate intervals, plus at least one ordinary non-CAS date and several post-change dates. State exact comparison method and data rights; don't use unauthorized or internet-scraped feeds.
4. Examine the relevant official CAS/session circular and whether an indicative index is published. Determine whether evidence supports a relationship to the broker gap; coincidence alone is not causal proof.
5. Test whether the same gap appears in separate permitted endpoints/intervals and whether the missing timestamps are absent, carried forward, collapsed, or relabeled. Do not synthesize missing rows or interpolate prices.
6. Define how a completeness checker should treat absent intervals, permitted market closures, zero volume, source outages, and intervals with no authoritative expected-activity schedule.

**Required output:** reproducible case note with request parameters, source hashes (only if permitted), timestamp table/coverage counts, comparison sources, expected versus observed labels, cause assessment with confidence grade, and a precise non-repair recommendation. Keep raw restricted data outside Git.

**Coding destinations:** candle importer semantics, data quality/completeness report, calendar/source expectation contract.  
**Acceptance:** distinguish observation from cause; no inferred missing candles; unresolved cause remains unresolved; retention permission documented or no data are attached.

### DR-04 — Data availability, archive licensing, and lawful research corpus

**Purpose:** Determine what historical data can lawfully support futures/options research across expiries and what the minimum viable data package is.

**Questions:**

1. Identify official exchange products for index, futures, options, trades, quotes, aggregated depth, order-level events, contract masters, and daily files; record granularity, history dates, update frequency, corrections, timestamps, fields, and delivery mechanism.
2. Identify public versus paid/entitlement-controlled data, research/backtest/local retention/derived-data/display/sharing rights, permitted compute/storage locations, deletion requirements, audit obligations, and whether vendor or exchange redistribution is prohibited.
3. Determine whether expired-contract data exist for NIFTY futures and options; distinguish continuous/front-month series from individually dated instruments. Explain roll construction limitations and why continuous prices cannot automatically be used as executable contract prices.
4. Compare official exchange archive, SmartAPI, and any licensed vendor offerings by field coverage and rights. Do not recommend spend; give exact vendor questions and evidence required before a purchase.
5. Define minimum viable datasets for: signal-only index research; executable dated-futures backtest; options pricing/vol surface; quote-based execution; L2 queue model; and prospective paper. Include date span, instruments/expiries, fields, synchronization accuracy, session coverage, and metadata.
6. Specify a research data manifest: source/product, permission reference, acquired time, trading dates, contract identity, interval, timezone, raw/derived flags, correction policy, source/master/calendar/config hashes, and retention/deletion class.

**Required output:** source/data product comparison and a rights-aware data acquisition specification with an explicit `not available / owner authorization required` state for every field.

**Coding destinations:** dataset quality labels, input contracts, data-rights gate, manifest/provenance.  
**Acceptance:** no unauthorized download or sample purchase; expired contract availability and allowed local retention are not inferred from API success.

### DR-05 — SEBI/NSE retail API/algo compliance and OPS interpretation

**Purpose:** Prepare a current, dated compliance issue map for the assumed own-account API route; do not provide legal advice or enable egress.

**Questions:**

1. Build the full amendment timeline for current SEBI retail algorithmic trading framework, NSE implementation standards, FAQs, circulars, glide paths, and effective dates.
2. Resolve OPS threshold scope and counting: per client/user/app/API key/IP/segment/exchange, order entry, modification, cancellation, rejection, duplicates, child/sliced orders, algo tags, static IP, and broker-side aggregation. If the primary sources don't answer, list questions for the broker/compliance officer; do not guess.
3. Identify registration/approval paths and the distinction among self-developed algorithms, broker-provided algos, third-party/vendor algos, API-generated orders, and manual orders. Determine applicability to an own-account retail client only from primary text.
4. Determine requirements for static IP/whitelisting, primary/standby IP, API-key mapping, OAuth/session, order tags/Algo-ID, kill switch, audit log, retention, testing, change management, and incident reporting. Map each requirement to a source and effective date.
5. Identify current permitted order types, price protection/MPP, market-order restrictions, order-rate/OTR restrictions, cancel/replace treatment, and any exchange/broker differences.
6. Build an applicability decision tree with questions requiring user account type, API app configuration, or broker confirmation. Do not access a portal or contact Angel/NSE/SEBI.

**Required output:** dated obligations matrix (`requirement`, authority, document/date/effective scope, owner, software control, evidence artifact, consequence if unknown), exact broker/compliance follow-up questions, and a list of unresolved facts.

**Coding destinations:** remain disabled route flags, G0 readiness documentation, future outbound budget/telemetry; no order egress.  
**Acceptance:** primary legal/regulatory text is directly reviewed and cited; blogs/forum summaries are leads only; no assertion that this repo or user is compliant.

### DR-06 — Effective-dated statutory and broker costs, exercise, and settlement charges

**Purpose:** Replace provisional cost plumbing with sourced schedules only after exact effective dates and tax bases are established.

**Questions:**

1. For futures and options separately, identify each applicable brokerage type, exchange transaction charge, IPFT, SEBI turnover charge, STT (premium trade versus exercise/settlement), stamp duty, GST base/rate, and other statutory levy for each side/transaction event.
2. Establish effective-from/through date, instrument/segment, turnover/notional base, side, premium/intrinsic/exercise basis, rounding level/method, paise rounding, minimum/maximum, exemptions, and applicability to expiry settlement for every component.
3. Research exchange/broker square-off/auto square-off, physical-settlement/expiry handling, exercise assignment, delivery/settlement, auction/penalty, and additional charges; keep broker-specific tariff separate from statutory charges.
4. Compare the sourced schedule to a redacted broker tariff/contract note/invoice only if the owner can lawfully supply it. List exact fields to redact; do not commit the invoice. Reconcile per-fill and per-day totals to paise and explain timing/tax-base differences.
5. Identify GST treatment and whether each fee/tax belongs in GST taxable base. If invoice-specific accounting treatment cannot be established from public documents, mark owner/accountant confirmation required.
6. Provide worked test cases for futures BUY/SELL, option premium BUY/SELL, ITM exercise, OTM/ATM expiry, partial lots, fee rounding, date-boundary schedule selection, and missing schedule coverage.

**Required output:** machine-readable dated schedule proposal plus formula workbook/table, source citations, worked calculations to paise, and a discrepancy matrix. Values without verified source or date must remain unresolved rather than entered as production defaults.

**Coding destinations:** `CostSchedule`, `SettlementCostSchedule`, fee breakdown and reconciliation.  
**Acceptance:** exchange/regulator rules separated from broker tariff and user-specific invoice; no current rate asserted without as-of date; no personal invoice data shared.

### DR-07 — Historical SPAN/ELM, expiry add-ons, calendar spread, broker margin

**Purpose:** Determine what margin evidence can be replayed historically and what our current caller-supplied schedule does not calculate.

**Questions:**

1. Inventory official NSE Clearing/SPAN/ELM and other risk-parameter files, archival coverage, timestamps/update frequency, contract coverage, formats, and corrections. Record availability and usage/retention terms.
2. Explain how exchange initial/portfolio margin, SPAN scan risk, ELM/add-ons, expiry-day add-ons, delivery/physical-settlement margin, calendar-spread benefit, and broker house/peak margin differ. Scope every statement by product, netting, maturity and effective date.
3. Identify historical file fields required to reconstruct a NIFTY futures or long/short options margin requirement and the exact formulas or official methodology. State which inputs are missing from this repo.
4. Define treatment for missing/late parameter files, intraday updates, contract changes, expired positions, holiday sessions, spread offsets, position aggregation, broker/client risk multipliers, and rounding.
5. Give one reproducible historical example from original permitted parameter files, with timestamps/hashes, contract positions, outputs, and independent reconciliation. If no rights or file is available, provide a schema and owner/vendor request instead of downloading.
6. State minimum safe scope for any first implementation, with unsupported short-option, portfolio, or broker-margin states rejected rather than approximated silently.

**Required output:** margin source/catalog table, dated formula/method note, field schema and example/proposed test vectors, data-access limitations, and precise coding boundary. No personal account values.

**Coding destinations:** margin schedule, risk checks, dated market-rule metadata, portfolio-position model.  
**Acceptance:** clearly distinguishes exchange requirement from broker requirement; states whether historical recomputation is possible; does not call a caller proxy “SPAN/ELM”.

### DR-08 — Market microstructure, order mechanics, L1/L2/L3 availability, and queue-model boundary

**Purpose:** Set evidence limits for simulated fills and identify what extra authorized data would be needed for calibration.

**Questions:**

1. From exchange/broker primary rules, document relevant NFO order types, tick/lot constraints, price bands/price protection, order priority, modify/cancel priority effects, partial fills, IOC behavior, opening/pre-open/closing auction, freeze quantities, and order rejection states.
2. Inventory permitted availability of historical top-five snapshots, L2 depth changes, trades, order IDs, exchange timestamps, arrival/priority sequence, cancellations, hidden/iceberg quantity, and market-by-order data for NIFTY futures/options. Identify whether each field is historically retained or only prospective/live.
3. Explain which queue position and passive fill claims are impossible with five-level snapshots. State what assumptions a risk-averse queue model can and cannot infer from snapshot data.
4. Define an event schema for quote/trade/depth/order lifecycle suitable for reproducible reconstruction, with sequence scope, exchange/local timestamps, source, instrument identity, corrections, and gaps.
5. Compare fill model tiers: next-bar price only, crossing/top-of-book IOC, conservative queue lower bound, probabilistic queue estimate, order-level reconstruction. For each, give required data, calibration, biases, validation and permitted claim level.
6. Specify latency measurements possible in read-only capture (receive/processing, inter-arrival, observed exchange-to-local only if clocks support it) versus those that require actual order transmission/acknowledgement. Do not request or recommend live orders for measurement.
7. Assess impact/capacity requirements and how much displayed size can support; distinguish quoted size, executed volume, hidden liquidity and sustainable participation.

**Required output:** order-rule matrix; data-field availability/rights grid; model tier table; fill-model boundaries and tests; no speculative queue probabilities.

**Coding destinations:** current quote replay, future passive fill model, toy book, data grade and capacity diagnostics.  
**Acceptance:** no simulated fill claim exceeds source granularity; any unavailable data is an explicit blocker, not generated synthetic “calibration”.

### DR-09 — Option market conventions, data quality, and model-input validation

**Purpose:** Numerical formula comparison is already complete; research the market conventions/input quality needed before theoretical values influence accounting or risk.

**Questions:**

1. Specify exchange/broker contract conventions for NIFTY index options and any futures options in scope: expiry date/time, exercise/settlement type, day count/time-to-expiry clock, holidays, strike/lot/tick, underlying reference, and cash/physical settlement behavior.
2. Establish reliable synchronized sources/fields for underlying spot/index, futures forward, option bid/ask/last, volume/OI, rates/dividends/carry, corporate/contract adjustments, and timestamps. Mark fields unavailable from approved sources.
3. Define quote cleaning/eligibility: crossed/locked markets, zero bid, stale or asynchronous legs, wide spreads, auction values, outliers, missing strikes, duplicate/revised records, and expiry boundaries. Do not present a fit from synthetic examples as market evidence.
4. Compare Black–Scholes spot/carry inputs with Black-76 forward/futures inputs for the exact instruments and settlement convention; specify Greek units and conversion/display conventions for risk reporting.
5. Assess whether a volatility surface is supportable with available strike/expiry coverage. Define minimum observations, arbitrage/monotonicity/convexity constraints, interpolation/extrapolation limits, sparse/illiquid handling, and validation metrics. Do not implement or select a calibration method without evidence.
6. Define synchronized market-data test vectors with expected behavior near expiry, deep ITM/OTM, low volatility, zero/negative rates if relevant, dividends/carry, stale underlying, asynchronous quote, and invalid implied volatility.
7. State what independent model/input review is needed before model value or Greeks can affect fees, P&L, margin, or account-level risk.

**Required output:** convention memo, market-input schema and source/rights matrix, quote-eligibility contract, future validation test matrix, and explicit open assumptions. Reference the completed R11/QuantLib pack; do not repeat its formula validation as if it solved input validation.

**Coding destinations:** option valuation sidecar, stress calculations, future surface module and accounting/risk gates.  
**Acceptance:** theoretical model values remain diagnostic only until input source and convention evidence pass; no claim of exchange or market validation from QuantLib alone.

### DR-10 — Clock, feed integrity, expected activity, and coverage methodology

**Purpose:** Specify evidence for whether a saved market-data session is usable, while separating caller plans from independently verified expected activity.

**Questions:**

1. Identify authoritative exchange/broker clock requirements, timestamp fields, sequence-number scope, session timestamp conventions, source clock guarantees, and any precision/accuracy tolerances. If no contractual guarantee exists, say so.
2. Define read-only measurements for local UTC offset, wall/monotonic divergence, exchange-to-receive age where clocks are comparable, quote inter-arrival silence, sequence gap, stale/future timestamps, reconnect duration, acknowledged subscriptions, and journal write lag.
3. Explain what the new `instrument-feed-coverage-report` proves relative to the caller's plan and what it cannot prove. Recommend independent expected-activity sources/records and daily reconciliation fields; do not promote an operator-declared schedule to exchange truth.
4. Specify handling of planned zero-activity windows, holidays, auctions, pauses, instrument expiry, source outage, subscription rejection, reconnect/reset, sequence reset/wrap, duplicate timestamps, wall-clock regression, and uncertain clock mapping.
5. Provide a completeness grade rubric with explicit prerequisites and “unknown/not certifiable” outcomes. Distinguish capture completeness, exchange-message completeness, instrument coverage, rights, and research suitability.
6. Provide synthetic and authorized-fixture tests for each failure mode; do not call synthetic fixtures evidence that the broker behaves that way.

**Required output:** metrics/coverage contract, expected-activity source catalog, grading rubric, incident examples, and test vectors. Note which data must come from an authorized operator versus a public source.

**Coding destinations:** raw-capture and feed-lifecycle journals, coverage report, future pre-open health checks and G4 records.  
**Acceptance:** every health threshold has a source or an explicitly labeled provisional owner-configured value; the report never labels a caller plan “complete” without independent support.

### DR-11 — Deterministic recovery, reconciliation, incident response, and operational drills

**Purpose:** Translate the final plan's SAFE-start recovery and G4 operations into a testable offline runbook and evidence checklist.

**Questions:**

1. Decompose recovery order: start SAFE, verify binary/config/input checksums, restore last snapshot, replay journal tail, rebuild book/strategy/risk state, reconcile order/execution/position state, and require explicit release. Identify what the existing checkpoint and `oms-recover` do and do not cover.
2. Specify behavior for truncated/corrupt journal, hash mismatch, missing tail, stale snapshot, duplicate/out-of-order executions, Unknown orders, disconnect while cancel pending, unacknowledged subscriptions, process restart, host restart, wall-clock discontinuity, and corrupted configuration.
3. Define independently maintained positions and order reconciliation artifacts; specify comparison keys, tolerance (if any), terminal/unknown behavior, and fail-closed conditions. Do not assume OMS self-state proves broker state.
4. Define K0–K3 drill scenarios and required observations: trigger time, detection time, recommendation time, human acknowledgement, cancel completion if a future paper simulator can prove it, position state, clearance authority, evidence reference, and reopen criteria. Clearly distinguish policy-model drills from real action drills.
5. Specify G4 daily ritual: pre-open, during-session monitoring, post-close reconciliation, data completeness, cost/fill review, incident capture, immutable run archive, operator signoff, and 20 consecutive pre-open passes/60 complete sessions evidence.
6. Define restore/replay drills, backup location/access review, event retention/deletion process, and paper-session reset rules. Retention period remains subject to current legal/contract requirements.
7. Map dependencies on separate-process watchdog, independent risk gateway, human manual path, notification transport, and external journaling. Do not claim those controls exist because the K0–K3 policy model exists.

**Required output:** state-transition/failure matrix, recovery runbook, drill cards, daily evidence schema, open dependency register, and negative tests.

**Coding destinations:** future recovery controllers, K0–K3 integration, session readiness reports, G4 runbook.  
**Acceptance:** every recovery state has a deterministic stop condition and required evidence; no route/live action is performed.

### DR-12 — DSR/PBO, trial exposure, dependence, and one-shot holdout: independent method review

**Purpose:** Review existing method notes and identify unresolved statistical decisions without quietly changing code or looking at holdout results.

**Start from:** `2026-11-04-dsr-pbo-audit.md`, `2026-11-04-effective-trial-and-sharpe-dependence-decision.md`, `PARALLEL-TASK-BRIEFS-2026-11-04.md`, current evaluator, and trial/outcome ledgers.

**Questions:**

1. Independently verify the existing DSR approximation, definitions of trial count, effective N sensitivity, and what the exposure ledger records. Identify any difference between registered trials, executed attempts, produced outcomes, viewed outcomes, and undeclared experiments.
2. Review DSR's independence/correlation assumptions and whether a conservative sensitivity range can be preregistered without estimating an unobservable effective N from insufficient data.
3. Evaluate session-return dependence methods, sample-size requirements, finite-sample behavior, bandwidth/block selection, deterministic repeatability, and failure states. Distinguish time dependence within one trial from dependence among trial variants.
4. Compare chronological walk-forward, CSCV/PBO, purging/embargo where labels overlap, and one-shot sealed holdout: what question each answers, what leakage/selection risk remains, and required data/trial alignment.
5. Review how holdout access could be enforced (not merely declared) with separate permissions, key custody, single-use report, audit of attempts/views, and failure/recovery rules. Do not access or compute any holdout performance.
6. Recommend a preregistration and report contract (family/trial identity, session return series, missingness, dependence method, parameters, uncertainty and caveats) without setting an after-the-fact pass threshold.
7. State exact changes that would require a new independent reviewer or separate code task; do not change the Java evaluator in this research assignment.

**Required output:** source-attributed methods review, corrected/confirmed equations in original frequency, method comparison table, compact proposed input/output schema, open choices and exact test vectors.

**Primary sources:** original Bailey & López de Prado DSR paper, original PBO paper, Lo Sharpe dependence work, and primary HAC/bootstrap references.  
**Acceptance:** explicitly label previously reviewed items; verify source details rather than copy existing memo uncritically; no holdout access, strategy performance claims, or coding edits.

### DR-13 — Independent-risk gateway, seven risk layers, and kill-control design review

**Purpose:** Supply a source- and systems-backed contract for the important coding work currently suitable for an independent parallel implementation.

**Questions:**

1. Translate plan control layers L0–L6 and D27/D38 into an order-independent contract. For each layer, state purpose, independent state source, inputs, output/latched decision, failure mode, clearance authority, and evidence artifact.
2. Define signed position accounting from unique executions, partial executions, corrections/cancels, busts, expiry/settlement, and duplicate/reordered/conflicting events. Explain reconciliation to an independent execution/position source.
3. Define working-order reservation semantics, including pending-new, partial, pending-cancel, pending-replace, unknown, cancel-reject, and risk-reducing orders. Explain when reservations may be released and why OMS states alone are insufficient.
4. Specify proposal checks for maximum lots/units, absolute and gross notional, instrument concentration, supplied margin versus computed exchange margin, daily/session loss, stressed exit loss, premium/underlying exposure/Greeks where supported, order rate/OPS, price collar, stale data, and portfolio aggregate. Mark each unsupported model input fail-closed.
5. Define the “cancels and risk-reducing actions never blocked” invariant without using it to permit a new risk-increasing order. Cover cancel throttling/deadline, protected reduce-only price, position uncertainty, K2/K3, and what to do when the route cannot execute.
6. Define event ordering, idempotency keys and conflicting reuse, monotonic/UTC clocks, journal durability, state snapshots, deterministic replay, independent process boundary, gateway heartbeat loss, and startup SAFE state.
7. Convert D37 stress (spot/IV scenarios and exit haircuts) and D38 aggregate clip/working-order cap into formulas only where units and source fields are explicit. Identify every owner-selected threshold and never fill with plan's illustrative amounts.
8. Design a failure-injection matrix that independently breaks each limit and invariant, including overflow, negative/unknown values, missing marks/margins, corrupted journal, duplicate/reordered events, and disagreement with OMS.
9. Provide a minimal Java API/event schema suitable for isolated implementation; identify no-overlap file/package boundary with current simulator and `OperationalKillController`.

**Required output:** gateway contract, layer/control matrix, event schema, state-transition table, mathematical definitions and units, failure injection catalog, and a known-limitations section. Separate source-verified rules from design inference and owner policy.

**Coding destinations:** a new `com.mscient.intrapaper.riskgateway` package and separate focused runner only after design review; no edit to current simulator, order routing, or K0–K3 package in this research task.  
**Acceptance:** independent position/reservation state is explicit; risk-increasing paths fail closed; cancel/reduce behavior is specified; all example thresholds are clearly marked illustrative, not owner-approved.

### DR-14 — Market hypothesis cards and instrument selection (research-only, no strategy approval)

**Purpose:** Produce falsifiable research candidates rather than coding instructions or profitability claims.

**Questions:**

1. For the plan's initial NIFTY plumbing universe and possible later universe comparison, enumerate candidate economic mechanisms (not parameter grids) and the market participant/constraint that could create an edge.
2. For each mechanism specify instrument/contract, holding horizon, signal timing/availability, expected sign, economic rationale, benchmark/null, likely adverse selection, capacity constraint, costs/fees/hedging needs, regime dependence, and falsification criterion.
3. Identify the minimum authorized history needed, whether it must be contract-specific, synchronized spot/future/options, quote/trade/depth, volume/OI, expiry/roll, or corporate/calendar event data. State if required evidence is not available under current SmartAPI access.
4. Explain leakage/lookahead risks, universe selection, survivorship/contract-selection, expiry stitching, and multiple testing. Give a preregistration variant taxonomy before any parameter search.
5. Include simple baselines and a “do not implement / not testable yet” list, especially strategies requiring L2/order IDs, expired options, or retail-route latency not available here.
6. Provide one blank research-card per candidate. Do not set tuned parameters, thresholds, expected return, or claim a candidate is promising from synthetic examples.

**Required output:** candidate comparison matrix and at most a small explicitly prioritized card set, each with data gates and kill/falsification criteria; no strategy code.

**Coding destinations:** existing blank strategy research-card template, future `SmaCrossoverStrategy` replacement only after review, experiment registry.  
**Acceptance:** no candidate is approved; every hypothesis is measurable and could be rejected; data insufficiency is reported rather than filled with proxies.

### DR-15 — Conditional authorized sample dataset and source comparison

**Purpose:** Validate data contracts against lawful examples only after the owner supplies or authorizes an appropriate sample.

**Questions:**

1. For a bounded date and instrument supplied by the owner, compare available authorized broker/exchange files for schema, units, timestamps, gaps, duplicates, revisions, contract identity, and cross-source price/volume differences.
2. Create a privacy-safe, rights-compliant test case description; do not commit raw data unless the license expressly permits it.
3. Test the current parsers/coverage methodology only in an isolated copy if separately approved. Report exact parsing differences; do not repair raw data.
4. Establish whether the license permits test fixtures, derived hashes, benchmark outputs, local storage, backup, and deletion.

**Owner prerequisite:** the owner must provide written permitted-use/retention scope and a redacted or synthetic-safe sample. A researcher may not acquire data or sign terms.  
**Acceptance:** if permission/sample is unavailable, return an executable checklist only and mark the task blocked on owner evidence; do not substitute public random data.

### DR-16 — Conditional redacted cost-note validation

**Purpose:** Compare a legally supplied contract note/invoice to statutory/broker cost formulas.

**Required fields if owner elects to provide:** date/time, exchange/segment, product/contract class, side, quantity/lots, turnover bases, fee line labels and amounts, tax bases, GST/tax, settlement/exercise events, broker tariff version. Remove name, client/account IDs, order IDs, PAN, bank/payment identifiers, addresses, signatures, contact details, and credentials. The owner should verify this redaction before sharing.

**Questions/output:** reconcile each line to `DR-06`, identify rounding/tax-basis discrepancies, separate statutory from broker charges, produce paise-level test vectors and unresolved accounting/tax questions. Keep the document itself out of Git.

**Acceptance:** no file retained or committed unless explicit terms permit; if absent, use cited public examples only and leave broker-specific rates unconfirmed.

### DR-17 — Conditional forward-paper evidence and capacity protocol (future gated task)

**Purpose:** Prepare, not run, the evidence design for G4/G6 after prior gates are passed.

**Questions:**

1. Define session completeness and reason codes, observable feed fields, operator pre-open/post-close events, build/config/reference hashes, simulated orders/fills, reconciliations, incidents, and missingness handling.
2. Define prospective read-only quote/clock/cancel-independent measurements possible without order submission; explicitly state that order-path latency and queue fill probability cannot be measured without authorized order events/data.
3. Define the planned 60 complete sessions and 20 consecutive pre-open evidence counters, reset rules, incident severity, evidence retention, and independent review. Do not count past synthetic runs as paper sessions.
4. For a later capacity review, define data needed for net markout, fill ratio, participation/impact, delay sensitivity, capital/margin, total operating cost, uncertainty, and measurement period; do not recommend a tier/spend now.

**Owner prerequisite:** G0–G3 and authorized prospective data/operational approval.  
**Acceptance:** protocol only; do not connect feeds, place orders, start a campaign, estimate capacity from synthetic data, or spend money.

## 5. Existing tasks completed or partly completed

| Existing topic | Current project state | What a new researcher should do |
|---|---|---|
| R11 option-pricer numerical validation | Complete: 22 rounded cases checked in Java and QuantLib-Python 1.44; boundary/no-arbitrage runner; CDF discrepancy fixed. | Work on `DR-09` market conventions/authorized input data, not a duplicate pricing-formula report. |
| R14 effective N and dependence-aware Sharpe | A source-backed methods decision exists; evaluator exposes raw-count and one-trial sensitivity. | Do `DR-12` as an independent critique/gap review; do not claim a dependence correction is implemented. |
| Instrument-token mapping | Local dated UTC registry implemented and focused-checked. | `DR-01` may source/populate a real dated registry, but must distinguish source files from code validation and leave unverified rows absent. |
| SmartAPI saved NFO quote decode | Offline mode-3 decoder exists for saved captures. | `DR-02` should audit packet field contracts, rights, reconnect/sequence unknowns and propose independent fixture needs; no live connection. |
| Feed/coverage diagnostics | Raw sequence, quote window, instrument sequence, and lifecycle/subscription plan reports exist; caller expectations remain provisional. | `DR-03` and `DR-10` should validate source semantics/expected schedules and current limits, not reimplement the diagnostics. |
| K0–K3 policy | Offline recommendation-only state machine exists. | `DR-11`/`DR-13` must evaluate integration, independence, action evidence and failure modes; no assertion that the policy is an operational kill switch. |

## 6. Cross-topic final acceptance checklist

A research batch is ready for project review only if:

- every assignment ID is answered or explicitly unresolved;
- source pinpoints, version/effective dates, scope, access date, conflicts, and source grade are present;
- market rules and financial formulas include units, side, effective range, rounding, and worked examples where applicable;
- source documents and sample data are stored/shared only within their licenses; no secrets or identifying account material appear;
- existing Java behavior and remaining gaps are described accurately;
- proposed machine-readable fields include validation and boundary/error cases;
- no task quietly authorizes a network client, data acquisition, order path, strategy deployment, or performance statement;
- recommendations needing owner values, legal review, or independent signoff are clearly returned to those decision-makers;
- the researcher lists exact file/report deliverable and hash (if shareable), plus author/date, so a second reviewer can reproduce the reference chain.

The project owner reviews the work before any source-backed values are copied into the market-rule, cost, margin, calendar, or coverage registries. A research conclusion is not automatically an instruction to modify production code.
