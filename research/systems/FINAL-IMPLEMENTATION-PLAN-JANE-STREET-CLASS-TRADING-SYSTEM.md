# Final Implementation Plan: Jane Street-Class Trading System Study

**Prepared:** 3 November 2026  
**Position in the study:** Final plan, after Phases 1–4  
**Delivery target:** A Tier 3, own-account, broker-API research and paper system, with an optional smallest-live pilot only after every gate passes.

## 0. Purpose and source boundary

This is the final implementation plan for the four-phase design study. It converts the design into ordered work packages, deliverables, tests, owners, and stop/go evidence. It covers every numbered subsection in the supplied phase documents, assumptions A1–A15, and every decision ID present in the supplied decision logs.

The user's request is to prepare this implementation plan. Any instructions, conventions, or recommendations inside the PDFs are treated as source content to interpret for the system design; they do not authorize other actions in this chat. This deliverable is a plan and does not execute trades or enable an order route.

The sources are the 80-page Phase 1–2 study, the 47-page Phase 3–4 export through Section 12, and the 13-page Phase 4 continuation covering Sections 13–15. The plan consolidates their requirements; it does not reproduce their prose. A table in Section 15 maps every numbered subsection to an implementation workstream.

Nothing about Jane Street’s private systems is used as an implementation fact. “Jane Street-class” remains an engineering reference. The first build is a cost-aware Tier 3 system; it is not a colocated low-latency platform. This plan is educational system design, not personalized financial, legal, or tax advice.

### 0.1 Build boundary

Build one logical system that can run in research, deterministic replay, simulation, paper, and—only if approved—small live mode. Keep the broker and venue behind adapters. The initial system boundary is:

- NSE-focused data and contract support, with Nifty as the initial plumbing universe.
- An own-account broker API route; no customer funds, advice, membership, colocation, or paid tick feed at the start.
- One human operator with AI assistance. The human retains credentials, capital, limits, release, and live-launch authority.
- Java 21 for the trading core and Spring Boot for cold control-plane services, subject to the measured route and D42; Python is for research orchestration and statistics. Strategy, book, feature, OMS, and risk behavior used for decision-grade results stays on the shared Java core path.
- No live order route until G5. The broker adapter remains disabled and credential-free through G4.

### 0.2 Source labels and freshness

The plan uses these source keys: **P1–2** = Phase 1–2 PDF; **P3–4** = Phase 3–4 PDF through §12; **P4c** = Phase 4 continuation PDF §§13–15. Section references below use those keys. Public rules, taxes, fees, broker API limits, instruments, expiry terms, and broker procedures are date-sensitive. The PDFs’ source dates are historical snapshots. G0 and G5 require a fresh official-source and broker-document check before code or orders depend on them.

## 1. Outcome and system to implement

### 1.1 Desired result

At the end of S4, the project has a data-grade-labelled, deterministic, fully monitored paper system that can replay its own inputs, explain every simulated decision and reconcile each session. At the end of S3, it may instead conclude that no strategy clears the cost and evidence gates; that is a valid completed research outcome. S5 is optional and never automatic.

### 1.2 Logical components

| Component | Implementation responsibility | Failure behavior and acceptance evidence |
| --- | --- | --- |
| Market-data adapter and raw capture | Read-only broker WebSocket connection; preserve raw messages, connection/subscription events, receive times, and gaps. Primary and standby capture run in separately monitored processes. | A silent feed, sequence gap, or missing subscription pauses the affected instrument. Raw data is immutable; daily completeness reports reconcile against expected activity and available official summaries. |
| Reference-data and calendar service | Effective-dated instrument master, tick and lot sizes, expiries, sessions, auctions, holidays, fees, taxes, margins, OPS/OTR parameters, price bands, and broker order rules. | A missing or unacknowledged changed snapshot blocks new risk. Every backtest records the exact reference-data version. |
| Normalizer, event journal, and replay | Versioned raw-to-normalized transformations; ordered append-only journal for market, configuration, strategy, OMS, risk, and broker events; deterministic virtual-time replay. | Hashes reproduce on replay. Journal lag, integrity failure, or unrecoverable gap prevents new exposure. Order-related records are retained at least eight years, or longer if current requirements require it. |
| Book and feature engine | L1/L2 book and features supported by the actual feed; microprice, imbalance, OFI, volatility, trade sign, lead/lag, session/auction state, and data-quality flags. | The book is marked untrusted on stale data, gaps, crossed/invalid states, or contract mismatch. Do not make queue-position claims from a feed that does not expose order-level priority. |
| Strategy interface | Receives normalized state and emits order intents only; no strategy writes broker messages. Versioned fair value, risk parameters, model, and rationale. | Strategy can be independently paused, replayed, and compared with a frozen benchmark. No order intent bypasses OMS or risk. |
| OMS and broker adapter | Explicit order state machine; unique monotonic client IDs; idempotent execution processing; broker status recovery; simulated broker first. | Unknown is a first-class state. A mismatch blocks new orders. Live API credentials and order submission remain disabled before G5. |
| Inline risk and independent risk gateway | Fast order checks plus separate process and position tracker built from broker execution updates and reconciliation, not OMS self-reports. | Risk failure blocks new risk. Cancels and risk-reducing actions may be throttled but never blocked. Independent tests break each limit. |
| Watchdog, kill and manual path | Separate failure domain monitors feed age, clock, journal, heartbeat, P&L, broker reconciliation, and process liveness. K0–K3 actions; human broker app/web terminal on a separate device/network. | K1/K2/K3 remain reachable if strategy code is hung. Drill every level and record time-to-cancel and time-to-flat. |
| Simulator and research plane | Same Java core binaries in replay, exchange emulator for order rules, conservative queue/fill models, measured latency inputs, effective-dated costs and capital charge, output markouts/P&L/capacity. Python handles experiment registration, statistics, plots, and reports. | Repeated runs with identical inputs, build, and reference data produce identical hashes. Unavailable L3 data is explicitly reflected in the data grade and fill assumptions. |
| Operations, compliance, and security | Release manifest, signed build, SBOM, secrets handling, role controls, runbooks, compliance map, monitoring, alerts, daily/incident records. | No agent has broker credentials or order authority. Missing logs, stale rules, failed readiness checks, or unresolved incidents block promotion. |

### 1.3 Data flow and state contract

Implementation order is: **source and permissions → raw capture → normalized events/reference snapshots → deterministic book and feature state → strategy intent → OMS → inline risk → independent gateway → broker adapter → broker reports/reconciliation → journal → replay/simulator → experiment evidence**.

The event journal is the system’s audit spine. Every output is derived from ordered inputs, configuration versions, software build identity, and seeded randomness. Prices and quantities in order logic use integer ticks and units; timestamps carry exchange/feed time, local wall-clock time, and monotonic receive/process time. Configuration changes are journalled events activated at an explicit sequence boundary.

## 2. Strategic and economic choices

### 2.1 Strategy sequence

1. **First engineering strategy:** a queue-aware market maker implemented in the simulator and paper mode only. Its purpose is to force the book, OMS, risk, simulator, and cancel path to exist. Tick-scale quoting is not a live strategy on the retail API route.
2. **First live candidate, if one qualifies:** one slower relative-value strategy, selected only after preregistered data, cost, capacity, and holdout tests. The expected gross edge must meet the three-times-round-trip-trading-cost hurdle and net evidence must remain positive after slippage, fixed costs, and capital charge.
3. **Candidate comparison at G3:** liquid stocks, Nifty futures/options, and other eligible instruments can be compared under the same frozen strategy rules and dated cost model. A11 defines the initial build scope; D41 makes the live universe an output of G3.
4. **Deferred:** latency arbitrage, live tick-scale market making, options market making, event-driven trading, cross-venue arbitrage, and any strategy whose edge depends on unavailable data, Tier 2/1 latency, special access, or unverified legal treatment.

### 2.2 Unit economics and acceptance threshold

Use the annual identity from §12.1:

`annual net = Σ round trips × notional × (gross edge − trading cost − slippage/impact) − fixed costs − capital hurdle × capital tied up`.

The Section 12.5 example gives 4.0 bp trading cost, 2.9 bp fixed-cost allocation, and 2.9 bp capital charge, for about 9.8 bp break-even. The three-times-trading-cost rule is 12 bp in that example and remains the higher acceptance hurdle. Every instrument gets its own effective-dated cost model; the Nifty future example’s roughly 0.098% round trip is not interchangeable with the large-cap stock example. These are study inputs, not live quotes.

### 2.3 Technology and tier choices

Use the simplest implementation that meets the measured Tier 3 broker route. D42 selects Java 21 for the core and Spring Boot for cold-path control services under A13. D19’s C++/Rust stack remains a later-tier option; it does not justify a rewrite before profiling. Keep one logical architecture across tiers. Buy commodity connectivity/data/hardware only when its evidence gate and budget pass. No FPGA, colocation, kernel bypass, or membership spend in the initial plan.

## 3. Work breakdown and dependencies

| ID | Work package | Depends on | Required output |
| --- | --- | --- | --- |
| WP0 | Charter, access, legal/rule and budget baseline | None | A1–A15 confirmation, owner, data permissions, current route review, written risk charter, stop authority, cost/loss budgets. |
| WP1 | Market model, instrument shortlist, hypothesis registry | WP0 | Order-book glossary, toy simulator, candidate universe set, strategy card, benchmark, cost formula, variants list, rejection criteria. |
| WP2 | Capture, data quality, and reference service | WP0 | Raw capture, normalized schema, effective-dated master/rules, quality grade, completeness/reconciliation report. |
| WP3 | Deterministic core, state and OMS | WP2 event contract | Book, event engine, order state machine, journal, stable hashes, restart and recovery path. |
| WP4 | Risk gateway, watchdog, kill and controls | WP0; WP3 event/order contract | L0–L6 controls, K0–K3, independent position state, limit-breaker suite, manual runbook. |
| WP5 | Simulator, cost model, and experiment pipeline | WP2; WP3; WP4 | Shared-code replay, conservative queue/fill model, time/latency model, fee/tax/margin/capital model, immutable result manifests. |
| WP6 | Strategy candidates and statistical validation | WP1; WP5 | Queue-aware market-maker simulator/paper strategy; one slower RV candidate; preregistered evaluation and holdout report. |
| WP7 | Security, release and production-readiness pipeline | WP2–WP5 | CI gates, signed artifacts, dependency/SBOM review, secret separation, broker contract tests, recovery drills, dashboards. |
| WP8 | Forward paper campaign | WP0–WP7 | At least 60 complete sessions, 20 consecutive passing pre-open checks, daily reconciliation and successful drills. |
| WP9 | Optional smallest-live pilot | G4 and independent review | Broker-approved order route, minimal size, current controls, session logs, no hard-limit breach, 60-session review. |
| WP10 | Capacity/tier decision | At least six months of live evidence, if S5 occurs | Net markout confidence interval, capacity study, delay sensitivity, total incremental cost/benefit, stay-or-upgrade decision. |

**Dependency rule:** WP4 is built alongside WP3, not after strategy completion. WP5 does not accept a separate research implementation of order, book, strategy, or risk logic. WP8 starts only after G3 and full paper readiness evidence. WP9 cannot start because a calendar date elapsed; it requires a separate human decision.

## 4. Stages, calendar, and exit gates

Planning basis: A14’s 20 hours/week. S0–S3 take about 20–29 weeks sequentially. At about 40 hours/week, their calendar may roughly halve, but S4–S6 wait for market sessions and evidence. Durations are planning estimates, not promises.

| Stage / gate | Time | Build and review work | Exit evidence and decision |
| --- | --- | --- | --- |
| S0 / G0 — charter | 2–3 weeks | Freeze scope, route, operator, ruleset version, data rights, risk capital, yearly spend, live-loss cap, stop rules, incident contact, human reviewer and no-order default. Verify the selected broker API/static-IP path without placing orders. | Signed charter; current source list; assumptions marked confirmed or unresolved; explicit statement that order egress is disabled. Fail closed if route, legal basis, or budget is unclear. |
| S1 / G1 — foundations | 4–6 weeks | Complete targeted microstructure, derivatives and statistics learning; build order-book toy; option repricer; paper hypothesis registry; compare potential instruments using an initial cost model. | One frozen research card with economic mechanism, horizon, expected sign/size, data, benchmark, costs, variant count, universe candidates, and rejection criteria. Set actual A15 amounts. |
| S2 / G2 — data and simulator | 6–8 weeks | Build WP2–WP5 foundation: raw capture, reference service, event journal, deterministic core, state machines, risk gateway, paper broker and replay/simulator. No broker order route. | Same frozen data/build/config produces identical hashes and ledger. No unexplained critical data gap. Golden cost tests, state-transition tests, risk-breaker tests, restart/replay and data-quality gates pass. |
| S3 / G3 — strategy evidence | 8–12 weeks | Run queue-aware market-making in simulation/paper and one slower RV candidate through preregistered development and one-shot holdout evaluation. Include fees, taxes, brokerage, spread, slippage, partial/missed fills, latency, impact, margin, fixed costs and capital charge. | No look-ahead or unresolved data issue; strategy meets 3× trading-cost hurdle and positive net holdout lower confidence bound under declared method; capacity and sensitivity acceptable. G3 selects the live universe. If none qualify, remain in paper/research or stop. |
| S4 / G4 — forward paper | At least 60 complete market sessions; about 12 weeks | Read-only market data, full-session core/replay, simulated orders, daily reconciliation, daily operator ritual, monthly K0–K3 drills, manual-route rehearsal, restore/replay drill, alerts and incident review. | 60 complete run records; 20 consecutive automated pre-open checks pass; no unexplained position/journal mismatch or open severe incident; repeated drills pass; no missing compliance evidence. This is operating evidence, not proof of profit. |
| S5 / G5 — smallest live pilot, optional | At least 60 sessions; usually 3 months or longer | Independent human reviews system, limits, strategy, build, rules and runbooks. Obtain current broker/exchange acceptance. Enable one strategy and one selected universe with minimal size, D37–D40 controls, no overnight positions, and default-flat behavior. | Human operator signs the launch. Every session reconciles. No hard daily, programme, position, order, compliance or journal breach. Any hard stop pauses new trading and requires review; no scale-up on elapsed time alone. |
| S6 / G6 — stay small or tier review | At least six months of live evidence for a tier review | Measure net markouts, fills, capacity, latency sensitivity, operations and full costs. Re-run rule, capital and data review. | Upgrade only if capacity binds at the current tier, added annual benefit is at least 2× added fixed and operating cost, and six months of live net markouts have positive predeclared lower confidence bound. Otherwise stay Tier 3 or stop. |

## 5. Detailed implementation sequence

### 5.1 G0–G1: lock requirements before building

1. Confirm A1–A15 in the Assumptions Register. Do not silently substitute current facts for assumptions.
2. Confirm the account is own capital only, the chosen broker route, current API rights, static-IP and standby rules, permitted order types, rate limits, OTR treatment, audit and data-retention terms.
3. Freeze one initial data universe for build plumbing (Nifty per A11), not the eventual live universe. Record fallback sources and missing-history limits.
4. Recalculate current statutory charges, brokerage, margins, order/square-off fees, capital hurdle, annual fixed cost, and time/opportunity cost. The PDF figures remain historical planning examples.
5. Sign the research card. It must state strategy mechanism, horizon, primary metric, benchmark, costs, signal/data availability, participation cap, number of tested variants, and falsification criteria.
6. Keep all order egress disabled. Create a human-readable launch/stop authority record and a list of unresolved G0 items.

**G1 acceptance:** the operator can explain the signal, the cost wall, the data limitations, and the precise evidence that would reject the hypothesis. No strategy is described as profitable.

### 5.2 G2: build the shared data and event core

1. **Raw capture:** record exact WebSocket payloads, receive monotonic/wall time, feed time if supplied, subscription list, connection state, sequence/gap/reconnect events. Raw records are append-only and checksummed. Capture begins before strategy claims.
2. **Normalization:** use a versioned schema for quote, depth, trade, instrument, session, order, fill, configuration, and risk events. Normalized fixes create a new version with changelog; never edit raw data.
3. **Reference data:** effective-date instrument tokens, contracts, tick/lot, price bands, expiry, market sessions/auctions, holidays, fees/taxes, margin, OPS, OTR, price protection and broker-specific behavior. Daily diff before the open; human acknowledges material changes.
4. **Core state:** deterministic event engine; one writer per state shard; integer prices/quantities in order logic; time is an input; no environment or wall-clock hidden inputs; seeded randomness journalled; state hashes every fixed event interval.
5. **Book/features:** build the deepest book supported by licensed/available data, and no deeper. Record whether each field is snapshot, aggregated L2, or order-level L3. For broker snapshots, disable exact-queue claims and use conservative fill assumptions.
6. **OMS:** legal order-state transition table including Created, PendingNew, Working, PartiallyFilled, PendingCancel, PendingReplace, Filled, Canceled, Rejected, Expired, and Unknown. Pending/unknown quantities reserve worst-case exposure. Duplicate executions are idempotent by execution ID.
7. **Recovery:** start SAFE, verify config checksum, restore snapshot, replay tail and hashes, rebuild book, recover broker/session sequence, query orders/trades, mass-cancel unknowns, independently reconcile position state, and only then permit explicit strategy release.

**G2 acceptance:** a clean-room replay of a frozen journal produces the same outputs and hashes; data gaps and contract mismatches are visible; the broker adapter remains read-only.

### 5.3 G2: build controls and operational visibility in parallel

Implement all seven risk layers and K0–K3 before enabling any market-making loop. The strategy’s own stop is advisory; it is never the independent risk control. The risk gateway and watchdog run separately from the strategy and maintain their own position/risk view. Put the human kill path on a separate broker app/device and network.

Deploy counters and histograms for feed age, sequence gaps, clock offset, journal lag, queue depth, API latency, order rate, cancels, rejects/429s, open orders, fills, position/Greeks, P&L, margin, OTR, kill state, reconnects and standby health. Alerts cause automatic action when specified; email-only alerts do not satisfy a control.

### 5.4 G2–G3: implement the simulator and strategy research

- Replay the production Java core in virtual time. The simulator models venue validation, order states, queue/fill probability, latency, fees/taxes, margin, impact, partial fills, missed fills, cancellations and auction/session rules.
- Run at least three execution views where data permit: risk-averse queue as lower bound; calibrated probabilistic or L3 model as central estimate; measured latency distribution. Label evidence by data grade and model assumptions.
- Build cost/accounting tests for every instrument and dated rule. Separate signal markout, fill markout, realized spread, price impact, trading cost, hedge cost, capital charge, and net strategy P&L.
- Start with interpretable linear/ridge models. Add boosted trees only when a preregistered net benefit survives costs, latency and holdout. Deep models require stronger evidence and do not get a lower acceptance bar.
- Register every feature, parameter, instrument and test variant. Use chronological splits, purged/embargoed cross-validation where labels overlap, deflated-Sharpe/backtest-overfit controls, placebos, regime splits, and one-shot sealed holdout.
- The queue-aware market maker is used to validate infrastructure in simulation and paper; it does not get a live ticket under D26. The slower RV candidate must clear D39 and the capacity/data gate.

### 5.5 G3: strategy and universe review

For each candidate, report gross edge, all-in trading cost, slippage/impact, fixed-cost allocation, capital charge, expected participation, clip size, net markout, drawdown, stress loss, capacity, and uncertainty. Compare strategy candidates on identical split rules and dated costs. The candidate with the cleanest evidence—not the one with the most convenient ticker—selects the live universe under D41. If no strategy passes, the live route remains disabled.

### 5.6 G4: production-like paper operation

Run the full routine for at least 60 complete sessions. The operator logs in daily and records an 08:45 IST go/no-go. Before each session: load dated references, compare contract/rule changes, verify time/feed/journal/standby health, confirm risk limits and budget remaining, run the pre-open checklist, and verify that strategy/release hashes match approvals. During the session: watch health and limits, stop on unknown states, do not hot-patch, and preserve all alerts/actions. After close: confirm flat state for early phases, reconcile broker/sim/ledger, score data completeness, capture fills/markouts, review cost/P&L, and archive the run manifest.

G4 requires 20 consecutive passing pre-open checks in addition to 60 complete sessions. Record every kill drill, outage, discrepancy, and near miss. Unresolved issues reset the affected evidence window.

### 5.7 G5: optional live pilot

Before G5, perform a fresh rules/broker/access review and obtain any broker/exchange acceptance required by the route. An independent human reviewer examines code, risk, operational evidence, release, capital, credentials, data rights, strategy rationale, conduct controls and incident plans. If a qualified independent reviewer is unavailable, stay paper.

Enable only the approved strategy, selected universe, small risk amount, approved release hash, and required access permissions. Enforce no overnight risk, expiry/settlement cutoffs, a predeclared flat time, a separately available manual path, and the current broker’s square-off/fees. No capital top-up is automatic. Do not increase size during the pilot. One hard breach pauses live use pending written review and repeated gate evidence.

### 5.8 G6: capacity and tier review

Measure where the strategy is constrained: external route delay, broker rate limits, book depth, impact, data quality, margins, fixed cost, or team coverage. Replay at measured additional delays; estimate incremental net P&L only from the strategy’s own data. Compare against the full added fixed cost of compute, colocation, feeds, connectivity, security, staff, compliance and support. D36 is not a calendar promotion: stay Tier 3 unless capacity binds and the ≥2× benefit test and six-month lower-confidence-bound test pass. Kernel bypass, C++/Rust, hardware timestamps and FPGA work remain deferred until that gate.

## 6. Starting control catalogue

These are starting values from the design, not universal or individualized advice. G0 replaces them with current rule/broker-compatible configuration; G1 confirms the operator’s actual budgets. Limits are versioned, tested and changed only under D28.

| Control | Initial design value | Implementation rule |
| --- | --- | --- |
| Soft daily loss / K0 | 0.5% of risk capital; ₹50,000 in the ₹1 crore example | Stop new quotes/entries; manage existing orders/positions safely. |
| Hard daily loss / K2 | 1.0%; ₹1,00,000 example | Cancel, flatten through protected orders when available, halt, reconcile. Stressed session loss under D37 must fit the remaining daily room. |
| Weekly/monthly hard loss | 2.5% / 5% | Stop live activity and require human review. |
| Peak drawdown | 8% | Halt live program pending review. |
| Margin use | Soft 50%; hard 70% of account | Block new risk at soft policy and K1/K2 at hard/projected stress breach. |
| Data age | Alarm at 1 s; stop affected instrument at 2 s | Fail closed while exposed or when feed age is unknown. |
| Clock offset | Alarm at 20 ms; stop at 100 ms | Record source and offset; use monotonic and exchange/feed time separately. |
| Order-rate bucket | Internal sustained 5 OPS, burst 8 OPS; stricter current broker/exchange limit always wins | Portfolio-level conservative cap; no API-key sharding to evade route controls. Reconfirm whether official thresholds aggregate per segment or route. |
| Open orders | Initial ceiling 4 per instrument, 20 total | Reduce if measured time-to-cancel or current rate budget cannot clear the book within the kill SLA. Test broker cancel-all behavior. |
| Price collar | Larger of 3 ticks or 1% from approved reference | Use effective-dated tick and reference price; reject stale/unknown reference. |
| Delta | ±3 Nifty-equivalent lots at ₹1 crore is a first-order ceiling | Full D37 revaluation can bind lower; positions and working orders reserve risk. |
| Gamma / vega | Strategy-specific; initial ceiling at 25% of margin-implied capacity | Reprice stress grid, expiry, liquidity, gap, skew and vol changes. |
| Per-order size / D38 | Minimum of ¼ displayed depth, ₹32,500 premium, and 1.5 Nifty-equivalent delta lots | Count open orders and all fills in aggregate risk. Example: ₹5 option at Δ0.05 can admit 20 lots if depth allows; ATM Δ0.5 is capped at 3 lots. |
| Stuck-book / D37 | Spot ±2%, IV ±3 points, all four combinations, full option revaluation, one tick/option-leg plus 0.1% futures exit haircut | For each scenario, booked session loss plus stressed exit loss must not breach the hard daily loss budget. A 3.08-lot linear derivation rounds down to 3, but is only a ceiling. |
| Live-loss / D40 | ₹6 lakh example; pause at ₹3 lakh, stop at ₹6 lakh | Separate from ₹6 lakh annual recurring cash budget; total planning tuition ₹12 lakh. Replace at G1. |

### 6.1 K0–K3 response contract

| Level | Action | Typical trigger | Clear rule |
| --- | --- | --- | --- |
| K0 Pause | Stop quotes/new entries; preserve safe order/position management | Soft loss, stale feed, gap, clock issue, local unknown instrument state | Auto-clear only after health conditions hold for the configured period; journal reason. |
| K1 Cancel/block | Cancel working orders, block new risk, retain position monitoring | Reject/OPS storm, state mismatch, watchdog heartbeat loss, broad feed problem | Human clears after broker/order/position reconciliation. |
| K2 Flatten/halt | Cancel, then reduce risk through protected marketable limits where route works; halt strategy | Hard daily loss, hard margin, D37 stress breach, repeated K1 or human trigger | Human clears only after review; no claim that flattening is guaranteed during an outage. |
| K3 Disconnect | Stop process, revoke/lock API session, use manual route | Suspected compromise or runaway not stopped by K1/K2 | Human-only recovery after credentials and state are reconciled. |

Across all levels, risk checks may throttle cancels but must never block them. Each level is idempotent, re-entrant, journalled, and independently tested. Drill monthly in paper and quarterly during any approved live pilot; measure time-to-cancel and time-to-flat.

## 7. Release, test, security, and compliance evidence

### 7.1 Required test layers

- **Unit/property:** integer money/quantity arithmetic, costs, Greeks, state transitions, feature invariants and effective-date lookups.
- **Decoder and input fuzzing:** malformed, truncated, duplicate, reordered, impossible and stale market/broker events.
- **Golden replay:** identical input/build/config yields identical state and output hashes.
- **Differential:** paper/live-adapter path and research/replay path agree on shared events and feature/strategy/risk results.
- **Limit-breaker:** deliberately violate each order, position, loss, margin, delta, gamma, vega, rate, OTR, price-collar and session limit; assert rejection/kill behavior and safe cancel path.
- **Fault injection:** dropped/duplicated/reordered updates, 429s, token expiry, reconnect, stale feed, clock shift, journal disk full, host loss, split-brain and standby failover.
- **Simulator validation:** compare predicted fills and markouts against forward paper observations; publish model error, coverage and data grade.
- **Broker contract tests:** current symbol, lot, tick, price protection, API/session behavior, order states, cancel semantics, rate limits and manual path; run in read-only mode until G5.
- **Performance:** stage-wise p50/p95/p99, tail causes, allocations/GC, CPU, memory, queue delay, broker round trip, and run-to-completion. Track the ten S1–S10 stages; do not optimize a microsecond component when broker route latency is binding.
- **Security/release:** static analysis, dependency audit, secrets scan, signed artifact, SBOM, human diff review, immutable release manifest, rollback, paper soak and off-hours deployment.

### 7.2 Promotion rule

Promotion requires all applicable tests green on the exact release hash, configuration hash and reference-data version. Risk-relevant changes take the D28 one-full-trading-day cool-off unless an immediate tightening is required. No hot patch during market hours. Deploy outside market hours, run pre-open verification on every host, and start in shadow or reduced pilot size as the gate requires.

### 7.3 Security and agent boundary

Use least-privilege identities, private secret storage, broker-side IP restrictions when supported, credential rotation, endpoint hardening and tested backups. Keep secrets out of code, logs, prompts, data samples and agent tools. Agents can research, draft, test, review and report; they cannot hold credentials, place/cancel orders, loosen controls, approve release, select live capital or authorize restart. Web/vendor input is untrusted until verified. An agent is not the independent human reviewer.

### 7.4 Evidence and retention

Every order-linked record includes raw/normalized events, client/exchange IDs, timestamps, strategy and model version, reason, risk checks, config/release hashes, broker response, fill, cancels, P&L/markouts and reconciliation status. Hash-chain and store off-host; target eight years minimum from D33, with G0 checking whether current requirements require more. A written strategy rationale and conduct checklist are required per live strategy. Near settlement windows use the stricter approved participation cap.

### 7.5 Daily, weekly, and incident operation

**Daily:** human login; 08:45 IST go/no-go; acknowledge reference-data/rule diffs; confirm session/auction calendar; check account capital, margin, limits, feed, clock, journal, watchdog and standby; enable only approved build; set flat time; no overnight positions in early phases.  
**During session:** observe; no rule/strategy hot patch; keep order and OTR buckets; respond to K0–K3; preserve journal.  
**At close:** ensure flat state in early stages; reconcile broker, OMS, independent risk and simulator; review P&L/markouts/costs/data grade; archive session manifest and issues.  
**Weekly/monthly:** review drift, capacity, cost updates, budget, near misses, rules watchlist, backups and K0–K3 drills.  
**Incident:** stop entries, cancel where possible, preserve evidence, treat unknown position as blocking, reconcile authoritative broker view, document who decided, retest the failed control, and require human approval before resumption.

## 8. Build-versus-buy and budget gates

Build the book/state engine, strategy interface, OMS state machine, independent risk gateway, simulator, reference-data versioning, accounting and release/evidence pipeline. Buy or use approved services for broker access, commodity cloud hosting, monitoring transport, clocks/capture hardware, and historical data only when the relevant gate and budget support them.

Start with one production VM plus standby in India as A9 specifies; isolate the standby and watchdog sufficiently to avoid a shared failure domain. Do not build a custom exchange protocol stack for a broker API. Do not purchase paid tick data, Tier 2 colocation, kernel-bypass cards, FPGA, or exchange membership until G3/G6 proves the constraint and the benefit test passes.

At A15’s example, recurring cash is ₹6 lakh per 12 months, live-loss ceiling ₹6 lakh, and risk capital ₹1 crore. G1 replaces these with the operator’s values. Do not transfer unused cash budget to live risk or vice versa. Record time/opportunity cost as well as cash. Any forecast that breaches the agreed envelope returns to G1 for a re-scope; do not widen the loss budget after drawdown.

## 9. Failure-mode implementation map

| Source failure mode | Preventive implementation | Detection / response / proof |
| --- | --- | --- |
| F1 runaway/duplicate orders | Idempotent IDs, caps, immutable releases, feature-flag hygiene | Duplicate-send drill; K1 within one second; order leak bounded. |
| F2 stuck book | D37 stress limit, intraday-only early phases, manual path, flat cutoff | API-outage drill; record reconciled time-to-flat; document residual risk. |
| F3 broker/internal divergence | Unknown-state OMS; independent broker-fed position state | Drop/duplicate/reorder events; K1 within 60 seconds; reconcile before resume. |
| F4 stale/wrong data | Data-age gates, sequence/contract-master/version checks | Stall/gap/wrong-token replay; K0 before order acceptance. |
| F5 margin/funding shock | 50/70% thresholds, stress margin and no auto top-up | Margin shock simulation; order block before buffer is consumed. |
| F6 expiry/short gamma | Expiry cutoffs, D37 Greeks, no late new short gamma, flat policy | Holiday/expiry/gap replay and mandatory-flat drill. |
| F7 nonexistent edge | Preregistration, holdout, cost/capital model, variant ledger | Independent frozen-pipeline rerun; reject if threshold fails. |
| F8 cost/rule change | Effective-dated source data and golden cost tests | Change fee/lot/OPS in replay; old run cannot be misattributed. |
| F9 signal decay/crowding | Markout, fill, regime, impact and capacity monitor | Inject decay; size cut/pause at predeclared thresholds. |
| F10 rate/broker restrictions | Conservative OPS bucket; verified API/static-IP/order-type config | 429, throttle, key/IP/order-type fault tests; no uncontrolled retries. |
| F11 infrastructure/clock/journal | Standby, watchdog, monotonic clock, off-host journal, restore | Network/host/journal restore drill; broker tie-out after restart. |
| F12 agent/human process | Human authority, review, change freeze and D28 | Unsafe-agent tabletop; no credential/order access or unauthorized release. |
| F13 key-person/ritual | Default-flat time, missing-ack blocks next run | Skip human ack in paper; entries stop and flat procedure executes. |
| F14 conduct/regulatory | Authorized data/access, intent checks, logs and rule review | Independent trace from decision through fill and approval evidence. |
| F15 program/budget failure | Sequential gates, cash/loss budgets, no sunk-cost override | Reviewer checks gate packet; missing evidence fails promotion. |

## 10. Assumptions Register A1–A15

| ID | Design assumption carried into the plan | Implementation effect / re-cut trigger |
| --- | --- | --- |
| A1 | Indian index/stock derivatives and liquid cash equities/ETFs on NSE; US/CME comparators | Initial build uses NSE adapter and dated Indian contracts. Different asset classes require a new cost/rule/data gate. |
| A2 | NSE primary, BSE secondary; SEBI rulebook; US/EU comparators only | No US/EU order route in initial scope. Change venue/jurisdiction and redo compliance. |
| A3 | 1–3 people; ₹1–10 crore proprietary capital range; Tier 1 is reference ceiling | Tier 3 build and independent human review before G5; recheck fixed-cost/capacity balance if capital/team changes. |
| A4 | Tier 3 now (1–50 ms) with Tier 2 path (10–50 µs); Tier 1 under 10 µs described, not built | Instrument full external route first; no latency claim from architecture diagrams. |
| A5 | Original Phase 1–2 C++20/Rust hot-path assumption; OCaml comparison | D42 supersedes A5 for this implementation: Java 21 core based on A13 and broker route. Preserve architecture interfaces for a justified future rewrite. |
| A6 | Learn → paper → small live; no real capital until Phase 4 gates pass | No live order route before G5 and human approval. |
| A7 | Initial rules/fees checked 1 Nov 2026; facts are date-sensitive | Fresh official check at G0 and G5; every public rule is versioned with source/date. |
| A8 | Own account only, broker API, no customer money/advice, membership, colocation, or paid tick feed at start | Provider-neutral broker adapter; do not accept third-party funds or advice functionality. |
| A9 | One static-IP cloud VM in India plus standby; broker WebSocket data only | Capture and standby IP rules are G0/G5 checks; data-grade limits enforced. |
| A10 | One human plus AI agents; human alone approves capital, credentials, limits and live deployment | Agents are safety/research aids, never a second-person approval or broker operator. |
| A11 | Nifty futures/options first; Sensex second; stock options only if justified | Nifty is initial plumbing scope; D41 decides live universe at G3. |
| A12 | Source snapshot as of 2 Nov 2026; AI/ML, OTR, proceedings and exchange parameters may move | Watchlist and official-source review before live release. |
| A13 | CS-degree level; strong Java/Spring Boot; market structure/options/statistics are learning goals | Teach through outputs; skip mastered material without lowering evidence gates. |
| A14 | 15–25 h/week, planning 20; full-time halves S0–S3 approximately, not S4–S6 | Scale calendar only; keep evidence and market-session requirements unchanged. |
| A15 | Example ₹6 lakh annual recurring cash, ₹6 lakh live-loss cap, ₹1 crore risk capital | G1 replaces with actual precommitment; recompute loss, margin, cap and cost examples. |

## 11. Decision implementation register D1–D42 and D47

The actions below are binding implementation dispositions of the decisions present in the provided PDFs. D43–D46 do not appear in the source pack; they are recorded as missing IDs, not guessed requirements.

| Decision | Implementation disposition | Gate / evidence |
| --- | --- | --- |
| D1 | Keep Tier 1 as reference; implement Tier 3 with path to Tier 2 on one logical architecture. | G0 scope; G6 only for tier change. |
| D2 | NSE/SEBI primary; BSE secondary; US/CME comparators only. | G0 venue and rulebook map. |
| D3 | Cost wall is a strategy input and universe filter. | G1/G3 dated cost model and hurdle report. |
| D4 | Use microprice/OFI/cross-asset inputs and an inventory-skewed reservation-price framework as research baseline. | G2 feature parity; G3 ablation and net evidence. |
| D5 | Model ladder: ridge/linear first, boosted trees only for persistent net benefit, deep models deferred. | G3 holdout, latency and complexity evidence. |
| D6 | Risk-averse queue fill model as lower bound; calibrated/L3 central estimate where data allow; measured latency distributions. | G2 simulator; G3 calibration report. |
| D7 | Purged/embargoed validation where needed, trial registry, deflated-Sharpe and sealed vault. | G1 registry; G3 holdout audit. |
| D8 | Paper and tiny live probe before broader capital use; probe is still optional and gated. | G4 then optional G5. |
| D9 | Session, tick, fee/tax and rule data effective-dated; results tagged by regime. | WP2 regression tests. |
| D10 | Same code path for book/features/strategy/risk in research and production. | G2 differential test. |
| D11 | Build queue-aware market maker for sim/paper and one slower RV candidate with ≥3× round-trip cost expected edge. | G3 frozen report; market maker remains no-live by D26. |
| D12 | Skip latency arbitrage; defer event-driven and options market making. | Scope lock at G0; reopen only with new evidence/review. |
| D13 | Same logical architecture across tiers; hardware placement changes later. | Module interfaces and tier plan. |
| D14 | Event-sourced deterministic core; time input; integer order arithmetic; hashes; same build for replay/live. | G2 golden replay. |
| D15 | Run-to-completion per shard; separate risk and journal processes for independence. | G2 performance/state tests. |
| D16 | Inline plus independent risk; independent position state; cancels never blocked. | WP4 limit-breaker and cancel tests. |
| D17 | Stage-wise latency budgets from an independent measurement path; track tail/p50. | G2 baseline; G6 tier review. |
| D18 | Consider kernel bypass before FPGA only if measurements show kernel path binding. | Deferred to G6; no initial spend. |
| D19 | Original C++20/Rust/Python stack is superseded for the current route by D42; retain as later-tier option. | D42 and G6 profile. |
| D20 | OS/hardware tuning is measured and change-controlled, never folklore. | Each tuning change has before/after distribution and rollback. |
| D21 | Explicit order FSM with Unknown and exhaustive transition tests. | G2 OMS gate. |
| D22 | Fail closed, prevent split-brain, warm standby and kill to both hosts. | WP7 failover and kill drills. |
| D23 | Build book, strategy, OMS, risk gateway and simulator; buy/contract for feeds, clocks, capture hardware and historical data as needed. | G0/G3/G6 spend gates. |
| D24 | Versioned reference-data service for ticks, sessions, fees, taxes, contracts and exchange specs. | WP2 golden effective-date tests. |
| D25 | Retail broker API route stays at or below the strictest current verified threshold (source planning limit 10 OPS). | G0/G5 official route config; local limit lower. |
| D26 | Tick-scale market making is simulation/paper only; any live strategy targets ~5 OPS or less. | G3/G5 order-rate acceptance. |
| D27 | Seven risk layers; fail closed; cancels never blocked. | WP4 failure injection and drills. |
| D28 | Tighten immediately; loosen only by human after at least one full trading-day cool-off and written reason. | Change-control test and audit record. |
| D29 | K0–K3 drilled monthly in paper/quarterly live; open-order cap derived from time-to-cancel. | G4/G5 drill records and measured cancel SLA. |
| D30 | Own raw capture from week one; every result data-graded; no queue-dependent claims from snapshots. | WP2 daily completeness and data-grade report. |
| D31 | Replay/differential/limit-breaker gates; immutable releases; no market-hours hot patch. | WP7 release pipeline. |
| D32 | Agents may improve safety; human alone loosens, deploys, has credentials and approves capital. | Access audit and G5 independent review. |
| D33 | Order journal hash-chained/off-host, at least eight years or current longer requirement. | G0 retention review and restore drill. |
| D34 | Written rationale and conduct checklist per live strategy; participation caps near settlement. | G5 strategy approval package. |
| D35 | Daily human login, 08:45 IST go/no-go, predeclared flat time, no overnight in early phases. | 20 pre-open passes and 60 paper sessions. |
| D36 | Stay Tier 3 unless evidence proves capacity binds and upgrade economics pass. | G6 only. |
| D37 | Full revaluation at spot ±2%, IV ±3 points, all four combinations; exit haircut one tick per option leg/0.1% futures; session-loss budget includes booked loss. ±3 lots is only a first-order ceiling at ₹1 crore. | WP4 tests; G1 replaces budget; G5 stress report. |
| D38 | Order cap is minimum of ¼ displayed depth, ₹32,500 premium, 1.5 delta-equivalent lots; working orders reserve aggregate risk. | G2 formula/property test; G3 may relax only after D28 cool-off. |
| D39 | Three-times trading-cost hurdle plus positive net evidence after realistic execution, fixed costs and capital charge. | G3 net edge/capital model. |
| D40 | Precommit recurring cash, daily and total live-loss budgets at G1; pause 50%, stop 100% of live-loss ceiling. | G1 signed budget; K2/K3 tests. |
| D41 | G3 strategy evidence selects first live universe; Nifty is build scope, not mandatory live instrument. | G3 comparative decision memo. |
| D42 | Java 21 core; Spring Boot control plane/dashboards; consider C++/Rust only if profile/tier gate justify. | G0 pinned toolchain; G6 latency/benefit profile. |
| D43–D46 | Not present in the supplied PDFs/decision logs. No implementation requirement inferred. | Add only if the owner supplies the missing decisions; baseline sign-off should record this gap. |
| D47 | Verify current broker/exchange OPS aggregation, API-key rules, static/standby IP, and order-type/MPP behavior from direct official documents. | G0/G5 route checklist; never treat undated broker summaries as rules. |

## 12. Traceability crosswalk: every numbered subsection

This crosswalk names every numbered subsection present in the three PDFs. Each listed subsection is represented in the plan’s work packages, gates, control catalogue, or learning outputs.

| Source subsections | Design topics accounted for | Implementation destination / evidence |
| --- | --- | --- |
| §1.1, §1.2, §1.3, §1.4, §1.5 | System purpose; six logical layers; three tiers; ten principles; conclusions on edge, cost, breadth and strategy | §§1–3: target architecture, Tier 3 scope, determinism, one-code-path, fail-closed, costs-first and strategy selection. |
| §2.1, §2.2, §2.3, §2.4, §2.5, §2.6, §2.7 | Fill/markout accounting; edge sources; breadth; capacity; India cost wall; what is/is not copyable; prohibited conduct | §§2, 5.4–5.5, 7.4, 9: P&L/capital formula, cost gate, capacity analysis, strategy limits, conduct checklist. |
| §3.1, §3.2, §3.3, §3.4, §3.5, §3.6, §3.7, §3.8, §3.9, §3.10, §3.11 | LOB state; order types; matching/priority; queue regimes; auctions; fees; adverse selection; inventory; information; latency; Indian market rules | WP1/WP2/WP3/WP5: order/book state model, effective-dated order/session rules, features, cost/markout model, measured delay, source freshness gate. |
| §4.0, §4.1, §4.2, §4.3, §4.4, §4.5, §4.6, §4.7 | Strategy specification; market making; ETF/index arb; cross-venue/stat arb; options/vol; event-driven; rates/FX; first-strategy choice | §2.1 and WP6: market-making paper/sim, slower RV candidate, comparison matrix, deferred-strategy list and G3 universe decision. |
| §5.1, §5.2, §5.3, §5.4, §5.5, §5.6, §5.7, §5.8, §5.9, §5.10 | Research workflow; features; labels; model ladder; overfit/decay; simulator; why backtests lie; paper/probes; credibility scorecard; Phase 1 decisions | §§3–5, 7: registration, immutable data, shared core, holdout, placebos, simulator bounds, 60-session paper gate, decision register D1–D12. |
| §6.1, §6.2, §6.3, §6.4, §6.5, §6.6, §6.7, §6.8, §6.9 | Drivers; components; flows; threading; order lifecycle; replay/recovery; venue extension; failover; tier differences | §§1, 3, 5, 7: module catalog, event flow, one-writer/run-to-completion, OMS FSM, hash replay/restart, adapters/standby, Tier 3 now. |
| §7.1, §7.2, §7.3, §7.4, §7.5, §7.6, §7.7, §7.8 | Latency definitions/constants/budgets; beyond tick-to-trade; tails; measurement; distance/media; optimization order | §§2.3, 5.8, 5.8 G6; §7.1 tests: measure full broker route and stage distributions, tail/p50, independent tap, optimize only the binding stage. |
| §8.1, §8.2, §8.3, §8.4, §8.5, §8.6, §8.7, §8.8, §8.9, §8.10, §8.11 | Stack selection; languages; hot-path discipline; OS/hardware; networking; clocks; protocols; observability; public Jane Street tooling; transferable principles; build/buy; decisions | §§1.2, 2.3, 7, 8; WP7: Java 21/D42, Python analysis, measured performance, clock/observability, buy/build boundaries. |
| §9.1, §9.2, §9.3, §9.4, §9.5, §9.6, §9.7, §9.8, §9.9, §9.10, §9.11 | Failure speed; Knight; seven risk layers; limit catalog; kills; options; model risk; AI governance; monitoring; incidents; conclusion | §§5.3, 6, 7, 9; F1–F15 map; K0–K3 drills; D28–D29/D37–D38 and independent-risk acceptance. |
| §10.1, §10.2, §10.3, §10.4, §10.5, §10.6, §10.7, §10.8, §10.9, §10.10, §10.11, §10.12, §10.13 | Available data; four zones; quality; reference/calendar; testing; environments; release; trading-day timeline; records; secrets; agents; go-live checklist; conclusion | §§1.2, 3, 5, 7; WP2/WP7: raw/normalized/derived/research data, quality gates, daily process, release evidence and access restrictions. |
| §11.1, §11.2, §11.3, §11.4, §11.5, §11.6, §11.7, §11.8, §11.9, §11.10, §11.11, §11.12, §11.13, §11.14 | Regulators/access; retail algo; OTR; conduct; taxes; records; clocks; AI; watchlist; US/EU comparators; obligation/component/evidence; conclusion; decisions | §§0.2, 5.1, 5.7, 7.3–7.5; WP0/WP2/WP7: rule-source register, OPS/OTR, costs, journal retention, clock, AI and live compliance checks. |
| §12.1, §12.2, §12.3, §12.4, §12.5, §12.6, §12.7, §12.8, §12.9 | Annual economics; brokerage cost wall; fixed costs; capital drag; worked sensitivity; tier-upgrade rule; hidden/time costs; tuition/stop budget; conclusion | §§2.2, 5.1, 5.5, 5.8, 8; D39/D40; G1 budget and G6 benefit threshold. |
| §13.1, §13.2, §13.3 | Risk scoring/ranking; F1–F15 cards; stop-the-line response | §§6, 9: every failure has preventive controls, detector, response and test. |
| §14.1, §14.2, §14.3, §14.4, §14.5, §14.6, §14.7, §14.8, §14.9 | Gate model; S0–S6; gate rules; organization; universe gate; runtime/tier; stop rules; budgets; conclusion | §§3–8: complete roadmap, owner boundaries, D41/D42, hard stop conditions and budgets. |
| §15.1, §15.2, §15.3, §15.4 | Starting skills; staged learning; 20-hour sequence; reading limits/access | G1 and learning outputs under §5.1; tracker/access status remains separate from implementation evidence. |

## 13. Learning and operator readiness

Learning runs alongside implementation. A13 means general programming is not a course prerequisite: use the existing Java/Spring skills, Python for research, and defer C++/Rust until measured profiling and a tier gate justify them. Reading time is not evidence of readiness; each block ends with a reviewable artifact.

| Block / planning weeks | Core material | Required output |
| --- | --- | --- |
| 1. Market mechanics / weeks 1–3 | Harris, *Trading and Exchanges*; current NSE market and contract documents | Glossary and toy book/auction simulator; explain spread, priority, impact, and why a signal is not a fill. |
| 2. Options and volatility / weeks 4–7 | Natenberg, *Option Volatility & Pricing*; Hull, *Options, Futures, and Other Derivatives*; Gatheral, *The Volatility Surface*; Sinclair, *Volatility Trading* | Independently checked option repricer for delta, gamma, vega, theta and scenario P&L, with assumptions and limits. |
| 3. Statistical research / weeks 8–10 | Cartea, Jaimungal and Penalva, *Algorithmic and High-Frequency Trading*; Chan, *Algorithmic Trading*; White’s Reality Check; Bailey et al. on backtest overfitting | Preregistration template, variant ledger, chronological splits, uncertainty estimate and rejection report. |
| 4. Execution and costs / weeks 11–13 | Harris; Lehalle and Laruelle, *Market Microstructure in Practice*; Johnson, *Algorithmic Trading & DMA*; Cartea et al. | Cost and fill model separating signal return, executable markout, fills, fees and capacity. |
| 5. Data and production / weeks 14–18 | Kleppmann and Riccomini, *Designing Data-Intensive Applications*; Gregg, *Systems Performance*; Google SRE; NIST SSDF and incident-response guidance | Event journal, deterministic replay, release manifest, recovery procedure, dashboards and incident drill. |
| 6. Rules and operations / ongoing, before G5 and after material changes | Current SEBI circulars/regulations, NSE implementation standards and the selected broker’s current operating/API documents | Dated compliance checklist with named owners for rules, broker terms, data licences and audit records. |

At A14’s 20 hours/week, weeks 1–18 are planning ranges. At 15 hours/week, extend the calendar; at 25, shorten it only if the same outputs pass review. Full-time work may compress S0–S3 but does not reduce S4–S6 market-session evidence. The workspace book tracker remains authoritative for reading/access status. Use authorized publisher, library or open copies; do not treat suggested literature as fully read or as evidence that a strategy works.

## 14. Source, literature, and promotion controls

### 14.1 Source carry-forward register

S1–S41 remain the cited source record in the earlier phase documents and were not independently reverified by the Phase 4 continuation. S42 records that boundary. Recheck every time-sensitive rule, fee, contract, access term and broker procedure from current official material before implementation depends on it.

| Source | How it is used in implementation | Required handling |
| --- | --- | --- |
| S42 | Verification boundary for S1–S41 | Preserve original citations and dates; do not silently promote historical claims to current rules. |
| S43 | Historical NSE/NCL outage and settlement record informing F2 | Use as an outage scenario; preserve the distinction between the settlement order, allegations, and exchange account. |
| S44–S45 | SEBI retail-algorithm framework and revised implementation timeline | Verify current applicability and effective dates at G0 and G5. |
| S46 | NSE implementation standards, including the stated initial 10 OPS threshold and audit/risk requirements | Verify the current exchange and broker aggregation rules; 10 OPS is a ceiling/reference, not a target. |
| S47–S48 | Effective-dated derivative contracts, lots, specifications and price bands | Load the contract files applicable to each session. A price band does not guarantee execution. |
| S49–S51 | Secure development, generative-AI development controls and incident response | Apply to release, agent access, incident and recovery controls; verify whether newer editions or drafts are in force. |
| S52–S53 | Backtest-overfitting and data-snooping methods | Use as research safeguards, not proof of edge; document the chosen method and its limits. |

### 14.2 Items that block promotion

These items must be closed before the gate that depends on them; they do not block drafting this plan:

- Current legal/rule basis, broker API contract and route-specific OPS/OTR/order-type/static-IP behavior.
- Account entitlement, actual fees/taxes, margin, square-off rules, data rights, and retention obligations.
- Historical and forward data completeness, feed depth and timestamp semantics; no L3/queue claims until the feed supports them.
- Actual strategy hypothesis, expected edge, sample size, capacity and uncertainty; none is established by the design study.
- Actual annual operating/time budget and live-loss ceiling; A15 is illustrative until G1.
- D43–D46, absent from the supplied decision logs; only the owner can provide their intended text.
- Independent human reviewer and manual path before any live pilot.

## 15. Final definition of done

The implementation plan is complete as a plan when it can be executed without inventing a strategy, a data entitlement, a current rule, a budget, or a live-risk approval. The system itself is complete at G4 only as a monitored paper system. Live trading remains optional and requires G5. Tier promotion remains optional and requires G6. A failed gate means stop, repair the specific evidence gap, or conclude the project without live trading.

The plan is the final artifact of the four-phase study. Any later change is a versioned plan amendment linked to a changed assumption, source, decision, or measured result.

Operating principle: promote evidence, not confidence. Freeze assumptions, price execution honestly, keep risk independent of strategy code, record every state change, and stop when evidence or operating state is unknown. This design establishes neither a strategy edge nor a guaranteed exit, and says nothing about Jane Street's private architecture.
