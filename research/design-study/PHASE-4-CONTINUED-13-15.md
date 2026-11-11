# Design Study: A Jane Street-Class Proprietary Trading System
## Phase 4 continued - Sections 13-15

Nov 2, 2026 | @Prashant

Continuation prepared and sources rechecked Nov 3, 2026. This continues the four-phase design study. “Jane Street-class” is an engineering ambition used for comparison; no statement about Jane Street’s private systems or strategies is made as fact.

**G3 implementation clarification, Nov 11, 2026:** The [three remaining workstreams](../systems/G3-REMAINING-WORKSTREAMS.md) require a reviewed strategy/test-plan freeze, qualified authorized dated data and executable costs, then preregistered chronological evaluation and independent review. Freeze the test universe before outcomes and the final candidate/universe selection before holdout access. G3 closes only when evidence supports promotion and identifies the eligible trading universe. An all-fail evaluation is a valid recorded outcome that leaves G3 open; deadlines and synthetic replay do not close it. D39 requires both the 3× trading-cost hurdle and positive net holdout evidence after fixed/funding costs and capital charge. This dated implementation clarification does not claim a new public-source verification.

**Scope:** Educational system design only. This is not personalized financial, legal, or tax advice. The proposed architecture is a reference plan, not permission or a recommendation to trade. A live stage remains conditional on current rules, broker approval, complete evidence, and the operator’s own decision.

---PAGEBREAK---

## 0. Read this first

This continuation completes Phase 4: Section 13 ranks the 15 principal failure modes; Section 14 sets the staged roadmap, exit criteria, operating organization, and engineering culture; Section 15 gives a reading list and learning path. It then records Phase 4 decisions, source verification limits, new sources, selected literature, and a closing note for the whole study.

The earlier Phase 4 export ends after Section 12 on page 47. The earlier Phase 1-2 export contains Sections 1-8, Decisions D1-D24, and Sources S1-S27. The supplied Phase 3-4 export contains Sections 9-12, Decisions D25-D35, and Sources S28-S41.

### 0.1 Conventions carried over

The four claim tags are unchanged:

- [PUBLIC FACT] - supported by a numbered public source.
- [INDUSTRY PRACTICE] - a general operating pattern, not a claim about one firm.
- [INFERENCE] - a derived recommendation; the calculation or reason is shown.
- [SPECULATION] - a plausible possibility without direct evidence.

Nothing about Jane Street’s internal systems is presented as fact. Decisions continue at D36, Sources at S42, and assumptions at A13. References such as Section 9.4 and Section 12.6 point to the supplied earlier phases.

The original study is dated Nov 2, 2026. To keep the audit trail accurate, new sources in this continuation were checked on Nov 3, 2026. The NSE contract-information page used below displays an update date of Nov 2, 2026.

### 0.2 New assumptions for the roadmap

A1-A12 remain as recorded in the earlier phase. Change any of the following assumptions and recut the affected roadmap and learning path.

| # / field | Assumption | What changes if it is wrong |
| --- | --- | --- |
| A13 - starting skills | Computer-science degree level, strong Java and Spring Boot. Market microstructure, options theory, and applied statistics are learning goals. | Section 15 shortens: skip material already mastered and retain the evidence outputs. |
| A14 - time | 15-25 hours per week alongside studies or a job; 20 hours is the planning value. Full-time, about 40 hours, roughly halves the calendar time for S0-S3, but not S4-S6, which wait on market days. | Scale the week ranges in Section 14.2; do not lower evidence thresholds. |
| A15 - budget | Planning base from Section 12.8: INR 6 lakh recurring cash per 12 months and INR 6 lakh live-loss budget against INR 1 crore of risk capital. The operator’s own numbers replace these at Gate G1. | Recalculate the stop levels and budget table in Sections 14.7-14.8 before any live stage. |

### 0.3 Three findings that change earlier sections

1. **A delta limit is safe only when the book can be exited.** Section 9.4 capped net delta at plus or minus 10 Nifty-equivalent lots for INR 1 crore of risk capital. At the study’s scenario inputs of 65 units per lot and Nifty at INR 25,000, one futures lot represents INR 16.25 lakh notional. Ten lots represent INR 1.625 crore. A roughly 0.62% adverse move costs about INR 1 lakh; a 2% move costs INR 3.25 lakh. K2 limits loss only when an exit can be completed. Add a stuck-book cap that fully reprices the portfolio at spot shocks of plus or minus 2% and implied-volatility shocks of plus or minus 3 points, then applies an exit haircut of one tick per option leg and 0.1% on futures. Under each scenario, session loss including losses already booked must remain within the INR 1 lakh hard daily-loss limit. The first-order delta calculation alone is INR 1,00,000 / (2% x INR 16,25,000) = 3.08 lots, rounded down to plus or minus 3. That is a ceiling, not a target: gamma, vega, the exit haircut, and losses already booked can bind sooner. This tightens the limit immediately under D28. [INFERENCE] -> D37.

---PAGEBREAK---

2. **A fixed lot cap blocks cost-efficient small-premium clips.** Replace Section 9.4’s per-order cap with the smallest of one quarter of displayed top-of-book size on the order’s side, INR 32,500 of premium, and 1.5 Nifty-equivalent delta lots. INR 32,500 is five lots x 65 x INR 100 under this scenario. A 20-lot order at INR 5 premium and delta 0.05 has INR 6,500 premium and 1.0 delta-equivalent lot; it passes if displayed depth supports it. A five-lot at-the-money order with delta 0.5 has 2.5 delta-equivalent lots and fails; three lots is the cap. Apply tightening now. Any loosened out-of-the-money clip waits for D28’s cool-off and Gate G3. [INFERENCE] -> D38.
3. **The strategy gate selects the live universe.** Keep Nifty as the initial build universe for capture, reference data, and simulator plumbing. Do not assume that the first live strategy must trade Nifty. Section 12.2’s example puts a Nifty futures round trip near 0.098% of notional, or about 24.5 index points. At the Phase 1 three-times-cost hurdle, a strategy needs about 0.29% of gross edge before that example clears its cost margin. The large-cap stock example in Section 12.5 needs about 12 basis points. Select the first live universe only after the chosen strategy, executable data, fees, and capacity pass Gate G3. [INFERENCE] -> D41.

### 0.4 Review amendments added in this continuation

The supplied partial draft adds four review points to the earlier calculations. The arithmetic below was checked against the worked examples in Section 12.

1. **D37 uses full stressed liquidation loss, not delta alone.** Apply spot plus or minus 2% and implied-volatility plus or minus 3 points, reprice every option, and add one tick per option leg and 0.1% of futures notional as exit haircuts. Compare each stressed session loss with the remaining daily-loss room after losses already booked. The plus or minus 3-lot figure is only a first-order ceiling; the portfolio revaluation can require less. Tightening applies immediately under D28. [INFERENCE]
2. **D39 includes the capital charge.** With risk capital K = INR 1 crore and an assumed annual hurdle r = 7%, r x K is INR 7 lakh. At INR 240 crore annual turnover, that adds about 2.9 basis points to the Section 12.5 break-even gross edge: 4.0 bp trading cost + 2.9 bp fixed cost + 2.9 bp capital charge = 9.8 bp. That is about 2.45 times the 4.0 bp trading cost. The existing 3x cost hurdle is 12 bp, so it remains the higher evidence threshold. [INFERENCE]
3. **The runtime choice reflects A13 and the broker-API route.** Use Java 21 for the trading core and Spring Boot for the control plane and dashboards. If the selected route measures in the tens of milliseconds, that makes a low-level rewrite a poor starting assumption; profile the whole path before considering C++ or Rust at a later tier gate. [INFERENCE] -> D42.
4. **Broker and exchange route details remain live-gate checks.** The supplied draft lists possible constraints on per-segment order rate, API-key use, static-IP changes, standby-IP allowlisting, and market-price-protection handling. Treat each as a broker- and exchange-specific item to verify against current official documents before G5. The partial draft does not provide direct source links for its broker summaries, so these details are not repeated here as verified public facts. [INFERENCE] -> D47.

## 13. Top 15 failure modes

### 13.1 Scoring and ranking

The scores estimate risk for the assumed INR 1 crore account during its first 12 months of live trading. They are subjective planning judgments, not measured probabilities. Replace them with the near-miss and incident record after six months of operation. Several modes can combine; in particular, an inability to exit can defeat a control that assumes the broker or venue will accept an order. [INFERENCE]

| Score | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- |
| Likelihood in first year | Under 5% | 5-15% | 15-40% | 40-70% | Over 70% |
| Impact of one bad instance | Under INR 10,000 | INR 10,000-1 lakh | INR 1-3 lakh | INR 3-10 lakh | Over INR 10 lakh, or account/regulatory consequence |
| Detectability | Automatic, seconds | Automatic, minutes | Within a day | Within a week | Could remain silent for weeks |

Risk score = likelihood x impact. Rank ties by slower detectability, then higher impact, then lower failure ID. IDs follow the operating lifecycle rather than the score order. Scores do not reduce controls for low-frequency, high-consequence events: F1, F2, F6, and F14 keep hard barriers regardless of rank. [INFERENCE]

| Rank | ID / failure mode | L/I/D | Score | Kill level | Main control |
| --- | --- | --- | --- | --- | --- |
| 1 | F7 - The edge never existed | 5/3/5 | 15 | Stop rule | Frozen research card, 3x cost hurdle, holdout |
| 2 | F12 - Agent or human process failure | 3/4/4 | 12 | K0/K1; change freeze | Human authority, review, asymmetry rule |
| 3 | F9 - Signal decay or crowding | 4/3/4 | 12 | Size cut | Live markout and decay monitors |
| 4 | F3 - Broker and internal state diverge | 3/4/3 | 12 | K1 | Unknown-state block; independent reconciliation |
| 5 | F8 - Cost wall or rule change | 4/3/3 | 12 | Pause strategy | Effective-dated reference data; cost tests |
| 6 | F6 - Expiry or short-gamma loss | 3/4/1 | 12 | K2 | Greek limits; expiry controls; flat time |
| 7 | F2 - Cannot exit: stuck book | 2/5/2 | 10 | K2; manual route | Stuck-book cap; manual path; intraday only |
| 8 | F1 - Runaway or duplicate orders | 2/5/1 | 10 | K1, then K3 | Order caps; idempotency; immutable release |
| 9 | F4 - Stale or wrong data | 3/3/2 | 9 | K0 | Data-age gate; contract-master check |
| 10 | F5 - Margin or funding shock | 3/3/2 | 9 | K1 or K2 | Margin headroom; capital firewall |
| 11 | F11 - Infrastructure, clock, IP, or journal failure | 3/3/2 | 9 | K0/K1; failover | Watchdog; recovery and standby drills |
| 12 | F13 - Key-person or daily-ritual failure | 3/3/2 | 9 | Automatic K2 at flat time | Default-flat policy; runbook |
| 13 | F15 - Programme failure | 4/2/4 | 8 | Stop rule | Stage gates; cash and loss budgets |
| 14 | F10 - Rate limits or broker restrictions | 3/2/2 | 6 | K0 or K1 | OPS bucket; current route checklist |
| 15 | F14 - Conduct or regulatory failure | 1/5/5 | 5 | K3 | Intent controls; complete journal |

F12 and F9 both score 12 with detectability 4; the stated impact tie-break puts F12 first. Other equal-score ties use detectability, impact, then ID. The score is a prioritization aid, not a substitute for worst-case engineering. The most likely loss is F7: a strategy that never had net edge. [INFERENCE]

### 13.2 Failure cards

Each card gives a mechanism, controls, response, and a test. The operating runbook should fit on one page and name the detector, immediate action, decision owner, disconnect point, evidence to preserve, and follow-up test. [INFERENCE]

#### F1 - Runaway or duplicate orders (rank 8; K1, then K3)

A bad release, retry loop, ambiguous acknowledgement, or duplicate send can flood the order path. The SEC’s Knight Capital record documents a faulty deployment and millions of erroneous orders; it is a precedent for release and order-state controls, not a forecast for this system. [PUBLIC FACT] [S36]

**Control and response [INFERENCE]:** independently enforce per-order, open-order, aggregate-position, and order-rate caps; reserve risk for working orders; use idempotent client IDs; require a verified release hash on every host; and keep the kill state outside the strategy process. K1 blocks new orders; verify broker orders and positions, then use K3 if order flow continues. Do not resume until reconciliation is clean.

**Test [INFERENCE]:** monthly paper drill injects duplicate sends and retries; assert K1 within one second and verify the maximum leaked orders stays inside the documented bound.

#### F2 - Cannot exit: the stuck book (rank 7; K2, then manual path)

NSE’s 24 February 2021 settlement order records a halt across its segments from 11:40 a.m.; trading resumed at 3:45 p.m. after a 3:30 p.m. pre-open, and market hours were extended to 5 p.m. at NSE, BSE, and MSE. NSE also publishes operating price ranges for derivatives; an order acceptance path cannot be treated as a guaranteed fill. [PUBLIC FACT] [S48] [INFERENCE] The order records that SEBI’s show-cause notice alleged a failure to ensure preparedness and readiness to move operations to the disaster-recovery site; applicants settled without admitting or denying the findings of fact or conclusions of law. The combined settlement amount was INR 72.648 crore, including adjudication proceedings involving three other NSE employees. [PUBLIC FACT] [S43]

**Control and response [INFERENCE]:** apply D37’s full revaluation under spot plus or minus 2% and volatility plus or minus 3 points, with the stated exit haircuts and the remaining session-loss budget. Keep the early-stage book intraday, set a flat time before the chosen broker’s current square-off cutoff, restrict entries to qualified liquidity, and stop adding short gamma late in the session. A second exchange is only a possible contingency if the same exposure can be traded there and the route is available; it is not an automatic hedge. If the primary path fails, stop entries, cancel working entries when possible, invoke the rehearsed alternate contact or manual route, and record whether the book remains stuck. K2 is not a guaranteed stop.

**Test [INFERENCE]:** quarterly paper drill simulates API loss; follow the written manual procedure and record time to a reconciled flat state. A simulated drill cannot prove a live exit will be available.

#### F3 - Broker and internal state diverge (rank 4; K1)

Partial fills, late or duplicated updates, rejected cancels, restarts, or a manual broker-app order can leave the local ledger different from the broker’s position.

**Control and response [INFERENCE]:** reconcile order, trade, and position views at least every 60 seconds and at session close. Treat unknown states as blocking states; build positions from broker execution updates; quarantine events with no valid transition; and never retry an uncertain order blindly. K1 on the first mismatch. Resume only after a clean tie-out.

**Test [INFERENCE]:** drop, duplicate, and reorder acknowledgements and fills; require a mismatch alarm within 60 seconds and prevent new exposure until repaired.

#### F4 - Stale or wrong data (rank 9; K0)

A stalled feed, sequence gap, bad clock, wrong token, or stale contract master can make a plausible signal refer to the wrong price or instrument.

**Control and response [INFERENCE]:** alarm at one second of feed age and stop the affected instrument at two seconds, as proposed in Section 9.4. Check sequence continuity, timestamps, symbols, expiry, lot size, tick size, and contract-master hash. Cross-check a second source where licensed and available. K0 on the affected instrument; K1 if the fault is broad. Mark the session unusable for research when capture integrity is lost.

**Test [INFERENCE]:** replay a stalled socket, sequence gap, and wrong-token master; assert the expected K0 before any new order can pass.

#### F5 - Margin or funding shock (rank 10; K1 or K2)

An intraday margin increase, volatility jump, expiry add-on, delayed funding, or broker debit can remove the headroom needed to hold or hedge the position. The earlier study’s margin references remain subject to the source dates and broker’s current policy. [PUBLIC FACT] [S8] [S37]

**Control and response [INFERENCE]:** monitor available funds and projected stressed margin; use the proposed 50% soft and 70% hard utilization limits; keep a separate liquidity buffer; and prohibit automatic top-ups. K1 at the hard threshold and K2 when the projected stressed book breaches its funding budget. Use the current broker schedule for square-off times and charges.

**Test [INFERENCE]:** run a paper margin shock across the broker’s current calculator and stress schedule; verify that order entry blocks before the funding buffer is consumed.

#### F6 - Expiry or short-gamma loss (rank 6; K2)

Near expiry, a small underlying move can change option risk quickly; a holiday-shifted expiry, settlement rule, or stale contract file can also leave the wrong position open.

**Control and response [INFERENCE]:** set expiry-specific entry cutoffs and a mandatory flat-time check; revalue gamma and vega under the D37 grid; prohibit new short-gamma exposure in the last hour; and keep overnight or expiry holding out of early stages. K2 if the cutoff, risk, or contract check fails. Current contract terms must be loaded from effective-dated exchange files. [PUBLIC FACT] [S47]

**Test [INFERENCE]:** replay expiry, holiday, and near-expiry gap sessions; require the strategy to reject entries after cutoff and reach flat state before the configured time.

#### F7 - The edge never existed (rank 1; stop rule)

Repeated strategy searches, look-ahead, selection on one history, unrealistic fills, or missing costs can create an apparent edge that vanishes out of sample. The backtest-overfitting and data-snooping literature describes these selection risks. [PUBLIC FACT] [S52] [S53]

**Control and response [INFERENCE]:** freeze a research card and primary metric before testing; keep a variant ledger; preserve chronological holdout data; include fees, spread, slippage, partial and missed fills, and capital charge; and require D39’s three-times-cost hurdle plus positive net holdout evidence. If the strategy fails the preregistered gate, reject it or return to research without spending the live-loss budget.

**Test [INFERENCE]:** an independent reviewer reruns the frozen pipeline from recorded hashes and confirms that the holdout was not used to tune the rule.

#### F8 - Cost wall or rule change (rank 5; pause strategy)

Broker charges, taxes, lot size, exchange limits, contract rules, or data availability can change enough to erase a narrow edge or make the route unavailable.

**Control and response [INFERENCE]:** keep fees, contracts, limits, and calendars in effective-dated reference data; run golden cost tests before every release; and reprice the strategy after any published or broker-notified change. Pause the strategy if the D39 cost hurdle, D38 clip limits, or operating assumptions no longer hold. Current contract files and operating rules must be checked at the live gate. [PUBLIC FACT] [S44] [S46] [S47]

**Test [INFERENCE]:** change a sample fee, lot-size, and rate-limit record in replay; verify that the report changes and the old run cannot be mistaken for the new one.

#### F9 - Signal decay or crowding (rank 3; size cut)

A once-valid signal can weaken as participants, market structure, volatility, or execution conditions change. More capital or a larger clip can also lower realized edge through impact.

**Control and response [INFERENCE]:** monitor net markouts, fill quality, capacity, drawdown, and regime splits against predeclared thresholds. Keep a shadow baseline. Cut size or pause when the lower confidence bound or decay rule is breached; do not retune on the same live window and call it confirmation.

**Test [INFERENCE]:** inject deteriorating fills and markouts into replay; verify the size-cut and pause states activate at the preregistered levels.

#### F10 - Rate limits or broker restrictions (rank 14; K0 or K1)

An order-rate ceiling, broker throttle, API-key rule, static-IP condition, or order-type conversion can block or alter the route. The cited NSE implementation standard states an initial threshold of 10 orders per second per exchange and segment, subject to exchange adjustment; it is not a target rate. [PUBLIC FACT] [S46]

**Control and response [INFERENCE]:** maintain a conservative OPS token bucket and order-to-trade monitor, but configure the actual limits from the chosen broker’s current documents and exchange rules. Verify API-key, static-IP, standby-IP, and market-price-protection handling before G5. K0 or K1 on throttling, rejection bursts, or a changed rule; never split activity across credentials to evade a limit.

**Test [INFERENCE]:** inject 429s, rate-limit responses, and a static-IP mismatch; assert backoff, order freeze, and no uncontrolled retry.

#### F11 - Infrastructure, clock, IP, or journal failure (rank 11; K0/K1 and failover)

A host restart, network outage, clock jump, lost IP allowlist, full disk, or corrupt journal can impair the feed or order route and make recovery unsafe.

**Control and response [INFERENCE]:** monitor independent heartbeats, clock offset, IP and token status, disk space, journal lag, and session ownership. Prevent active/standby split-brain. Fail closed on journal integrity or replay mismatch; preserve the last known state and reconcile with the broker before restart.

**Test [INFERENCE]:** quarterly restore from a known checkpoint, plus a paper host/network loss drill; compare the recovered order and position ledger with the source journal.

#### F12 - Agent or human process failure (rank 2; K0/K1 and change freeze)

An unreviewed patch, alert fatigue, post-hoc holdout tuning, rushed threshold change, or unclear authority can bypass otherwise sound controls.

**Control and response [INFERENCE]:** only the human operator controls capital, credentials, strategy selection, risk limits, release, and live launch. Agents may research, draft, test, and review, but they receive no broker secrets and cannot approve orders or loosen limits. Freeze changes after a material incident; require an independent human review before G5; record the reason, reviewer, diff, replay result, and rollback. NIST secure-development and incident-response publications are useful checklists, not guarantees. [PUBLIC FACT] [S49] [S50] [S51]

**Test [INFERENCE]:** run a tabletop exercise with an intentionally unsafe agent suggestion and a production-like alert; verify the proposal is rejected and the change log captures the decision.

#### F13 - Key-person or daily-ritual failure (rank 12; default K2 at flat time)

The one-human operating assumption creates a coverage gap when the operator is unavailable, distracted, or unable to complete reconciliation and close procedures.

**Control and response [INFERENCE]:** define a default-flat time before the selected broker’s cutoff; prevent new entries after the operator’s declared stop time; make the close checklist machine-readable; and let a missing human acknowledgement block the next session. Do not rely on an agent as a substitute for a qualified human.

**Test [INFERENCE]:** skip the operator’s acknowledgement in paper mode and verify that the system stops entries and reaches the planned flat state.

#### F14 - Conduct or regulatory failure (rank 15; K3)

An order pattern, data use, access arrangement, or recordkeeping gap can create account, broker, exchange, or regulatory consequences even if the strategy loses little money. The low likelihood score does not soften the impact rating.

**Control and response [INFERENCE]:** use only authorized data and access; retain a complete order and decision journal; prohibit manipulative intent and attempts to evade controls; and recheck current SEBI, exchange, and broker requirements before G5 and after material changes. Stop the route and seek qualified review if the permitted operating basis is unclear. [PUBLIC FACT] [S44] [S45] [S46]

**Test [INFERENCE]:** an independent reviewer traces sampled decisions from input data through order, fill, and risk checks and verifies that the recorded controls match current approved documents.

#### F15 - Programme failure (rank 13; stop rule)

The project can consume its cash and time budget without producing reliable data, reproducible research, or a strategy that clears costs. Sunk cost can then pressure the operator into relaxing gates.

**Control and response [INFERENCE]:** use the G0-G6 exit criteria, stop discretionary spend at the A15 ceiling, and separate recurring cash from the live-loss budget. If no strategy clears G3, keep the result as a completed research outcome and do not open G5.

**Test [INFERENCE]:** at each gate, have a reviewer assess a redacted evidence packet against the written exit criteria; a missing item fails the gate.

### 13.3 Stop-the-line response

On an unknown position, uncontrolled order flow, wrong contract, corrupted journal, daily-loss breach, or failed kill drill: stop new entries, cancel working entry orders where the channel is available, preserve the evidence, and reconcile positions through broker reports. If exit is unavailable, mark the state as stuck and follow the escalation plan. Do not restart from a guessed state. Only the human operator can approve reactivation after the failed control is corrected and retested. [INFERENCE]

## 14. Phased roadmap, gates, and operating culture

### 14.1 How to use the roadmap

The stages are sequential gates, not calendar promises. A stage can be repeated, paused, or retired. The durations and evidence thresholds below are planning judgments. At 20 hours a week, S0-S3 take roughly 20-29 weeks in this plan. At 40 hours a week, their calendar may be about half as long. S4-S6 depend on market sessions and evidence accumulation, so extra study hours do not compress them by the same ratio. [INFERENCE] [A14]

### 14.2 Stages and exit criteria

| Stage / gate | Indicative time | Work | Exit evidence |
| --- | --- | --- | --- |
| S0 - charter / G0 | 2-3 weeks | Freeze the system boundary, access route, risk capital definition, programme budget, stop rules, data permissions, and responsible human. Keep all order routing disabled. | Signed risk charter; owner knows how to stop the system; unresolved legal, data, or budget inputs are listed rather than guessed. |
| S1 - foundations / G1 | 4-6 weeks | Complete the first market-structure, derivatives, and statistics blocks in Section 15. Write one testable strategy hypothesis and pre-register its universe candidates, benchmark, rule, costs, and rejection criteria. | One dated research card with a frozen primary metric and an explicit statement of what evidence would reject it. Set personal A15 budget values here. |
| S2 - data and simulator / G2 | 6-8 weeks | Build effective-dated reference data, event journal, contract model, deterministic replay, cost model, and paper broker. Capture or acquire only data whose use terms permit the planned research. | Frozen input and configuration hashes reproduce the same event and accounting ledger. Every critical data gap is either resolved or explicitly excludes the affected result. No broker order route exists. |
| S3 - strategy evidence / G3 | 8-12 weeks | Compare the frozen strategy with the declared baseline on chronological development and holdout data. Include fees, spread, slippage, missed fills, exposure, drawdown, and sensitivity. Record all attempted variants. | No look-ahead or unexplained data issue; replay is reproducible; net holdout evidence meets D39 and the pre-registered test; lower confidence bound is positive under the chosen method. The strategy gate selects the live universe here. |
| S4 - forward paper / G4 | At least 60 complete market sessions; about 12 weeks | Run read-only market data and the local simulator through full sessions. Reconcile signals, hypothetical orders, simulated fills, and positions every day. Drill the kill switch monthly. | All sessions have complete run records; no unresolved position or journal mismatch; no open high-severity incident; repeated kill drills pass. Paper results remain operational evidence, not proof of profitability. |
| S5 - smallest live pilot / G5 | At least 60 market sessions; usually 3 months or longer | Optional stage. Before any order route is enabled, obtain current broker and exchange approval, confirm the applicable rules, complete an independent human review, and use the smallest size consistent with the strategy. Apply D37-D40 without overrides. | Only the human operator authorizes launch. The pilot stays inside daily, position, order, and programme caps; every session reconciles. Any hard stop pauses new trading pending review. |
| S6 - scale or remain small / G6 | Minimum six months of live evidence for a tier review | Keep the strategy at the proven tier while measuring net markouts, capacity, operations, and cost changes. Do not equate elapsed time with permission to increase risk. | Consider an upgrade only if the strategy is capacity-bound, the added expected annual benefit is at least twice the added fixed cost, and the predeclared lower confidence bound on six months of live net markouts is positive. Otherwise remain at the current tier or stop. |

### 14.3 Gate rules

- G0 sets the operator’s risk capital, recurring spend, live-loss ceiling, and stop authority before market access.
- G1 freezes one primary strategy hypothesis and the full list of tried variants. A strategy cannot choose its final universe by looking at holdout performance.
- G2 qualifies the exact data, effective-dated contracts, cost schedule, and replay behavior for that strategy.
- G3 promotes only a strategy that survives the pre-registered cost and evidence rules. It also chooses the universe. If no candidate clears the gate, remain in research or paper mode.
- G4 tests operating readiness with read-only data. It does not grant permission to send orders.
- G5 is a separate human decision after current regulatory, broker, system, and independent-review checks. Verify the actual order-rate, API-key, static-IP, standby-IP, and order-type rules for the selected route; treat D47’s checklist as a prompt to verify, not as current broker policy.
- G6 is an evidence review. No stage raises limits automatically.

A gate fails closed when required evidence is missing. Document the reason and the smallest next action that could resolve it. Do not replace a failed data or execution gate with a stronger backtest. [INFERENCE]

### 14.4 Organization and engineering culture

The role boundaries below are proposed controls for A10’s one-human operating model. [INFERENCE]

| Role | Can do | Cannot do |
| --- | --- | --- |
| Human operator | Owns scope, risk capital, strategy selection, credentials, release approval, and the decision to stop or resume. | Delegate final risk, capital, or live-launch authority to an agent. |
| AI agents | Search public sources, draft research, propose code, review diffs, generate failure scenarios, and summarize logs. | See broker secrets; place, modify, or cancel orders; loosen a control; approve a deployment; or decide a strategy is safe. |
| Independent human reviewer | Before G5, review the risk model, release, operating checklist, and evidence with fresh eyes. | Substitute for the operator’s own approval or guarantee a strategy result. |
| Risk gateway | Enforces deterministic limits and can block new exposure independently of strategy output. | Depend on strategy code to stop itself. |

Because A10 has one human operator, an AI agent is not a second-person control. If no independent human review is available before G5, remain in paper mode. [INFERENCE]

Use a written change record for every risk-relevant edit: reason, author, reviewer, affected limits, replay result, release hash, and rollback. Make it normal to stop on an unexplained alert. After a loss or incident, record what happened, what the system knew at the time, and which control should change. Do not reward limit expansion or hide a failed run. These are operating recommendations, not claims about a particular firm. [INDUSTRY PRACTICE]

### 14.5 Strategy gate chooses the trading universe

A11 still sets Nifty futures and options as the first build scope for capture, reference data, and simulator work. It does not decide the instrument for the first live strategy.

At G3, compare eligible universes against the same frozen strategy and executable cost model. The choice must account for expected gross edge, brokerage and statutory costs, spread, slippage, capacity at the intended clip, data quality, contract history, margin, and expiry handling. The decision is the instrument with qualifying evidence, not the instrument easiest to name in the roadmap.

Under the prior study’s worked assumptions, a one-lot Nifty futures round trip costs about 0.098% of notional, so the 3x edge rule implies roughly 0.29% gross expected edge before the strategy clears its cost margin. The large-cap stock example is around 12 basis points. These are study examples, not current quotes or universal thresholds. If no universe clears the strategy gate, keep the system in paper mode. [INFERENCE] -> D41.

### 14.6 Runtime and tier upgrades

For the assumed broker-API route and A13 skill set, start with Java 21 for the trading core and use Spring Boot for the control plane and dashboards. Keep the hot path small and measurable. A tens-of-milliseconds external route is a reason not to begin with a C++ or Rust rewrite; it is not proof that internal latency never matters. Profile data handling, risk checks, and order construction before changing languages. [INFERENCE] -> D42.

Use Section 12.6’s tier rule. Stay at Tier 3 unless measured evidence shows the current order-rate or market depth is constraining a strategy that otherwise passes its tests. Replay the strategy across measured added delays and compare net value with the full incremental cost of hardware, connectivity, staff, compliance, and support.

Consider an upgrade only when the incremental annual benefit is at least twice the incremental fixed cost, the strategy is capacity-bound at the current tier, and six months of live net markouts have a positive predeclared lower confidence bound. A lower-latency architecture is not itself an edge. [INFERENCE] -> D36.

### 14.7 Stop rules

Pause new trading immediately on the first of: the daily loss limit; a risk-state mismatch; an unknown live position; stale data while exposed; failed order/position reconciliation; a broken kill switch; an unauthorised release; or a material rule or broker change that has not been reviewed. [INFERENCE]

For an incident, cancel working entry orders where possible, preserve the journal, reconcile positions, and complete a written review. Do not resume during the same session after a severe incident. Only the human operator may authorize resumption after the failing control has been corrected and the affected gate has been repeated.

D28 remains in force: tighten a limit immediately when risk rises; loosen it only after a one-day cool-off and human approval. The cool-off does not override D37’s stuck-book stress cap or D40’s hard budget ceiling.

### 14.8 Budget table

The following is the planning example carried from Section 12.8 and A15, not a recommendation for any individual. Gate G1 replaces it with the operator’s own precommitted amounts. The stop behaviors are design inferences. [INFERENCE]

| Budget item | Planning value | Trigger | Required response |
| --- | --- | --- | --- |
| Risk capital | INR 1 crore | The capital base used for exposure examples | Define it before G0; do not treat it as an acceptable loss amount. |
| Hard daily loss | INR 1 lakh | Realised plus unrealised trading loss and costs reach the daily limit | Stop new entries for the session and reconcile. |
| Recurring cash budget, 12 months | INR 6 lakh | Planned cash spend for the year is exhausted | Stop new discretionary spend and review the programme before renewing. |
| Live-loss budget | INR 6 lakh | Cumulative live net loss reaches INR 3 lakh | Yellow gate: pause live trading and review. At INR 6 lakh, stop the live programme. |
| Total programme tuition | INR 12 lakh | Recurring cash plus live-loss budget | Do not transfer unused recurring cash into the live-loss limit or vice versa. |
| Base delta / order limits | Plus or minus 3 Nifty-equivalent lots; 1.5 delta-equivalent lots per order | Any scenario or aggregate open-order calculation exceeds its cap | Reject or reduce the order. Full repricing can force a lower position limit. |

### 14.9 What Section 14 concludes

The project advances only when the evidence gate is passed. S0-S3 are work and review gates; S4 tests operations; S5 is optional and requires fresh human approval; S6 may conclude that Tier 3 is the right permanent tier. The calendar never overrides a failed cost, data, risk, or regulatory check.

## 15. Reading list and learning path

### 15.1 Starting point

A13 assumes strong Java and Spring Boot and a computer-science degree level. Do not spend the programme relearning general programming. Use Java 21 for the trading core and Spring Boot for the control plane and dashboards; use Python for data analysis and research iteration. Learn C++ or Rust only if measured profiling and the tier gate show that the chosen route needs them. [INFERENCE] [D42]

The target learning sequence is market structure, derivatives and volatility, statistical testing, execution economics, reliable event-driven systems, then current operating rules. Each block ends in an artifact that can be reviewed; reading time is not an exit criterion.

### 15.2 Staged learning path

| Learning block | Core materials | Study output |
| --- | --- | --- |
| 1. Market mechanics | Harris, Trading and Exchanges; current NSE contract and market-structure documentation. | A glossary and a small order-book/auction simulator. Explain spread, queue priority, impact, and how a signal differs from a fill. |
| 2. Options and volatility | Natenberg, Option Volatility & Pricing; Hull, Options, Futures, and Other Derivatives; Gatheral, The Volatility Surface; Sinclair, Volatility Trading. | A checked option repricer with delta, gamma, vega, theta, and scenario P&L. State the model’s inputs and limits. |
| 3. Statistical research | Cartea, Jaimungal, and Penalva, Algorithmic and High-Frequency Trading; Chan, Algorithmic Trading; White’s Reality Check; Bailey et al. on backtest overfitting. | A pre-registration template, variant ledger, chronological split plan, uncertainty estimate, and rejection report. |
| 4. Execution and costs | Harris; Lehalle and Laruelle, Market Microstructure in Practice; Johnson, Algorithmic Trading & DMA; Cartea et al. | A cost model and simulator that distinguish signal return, executable markout, fill, fees, and capacity. |
| 5. Data and production systems | Kleppmann and Riccomini, Designing Data-Intensive Applications; Gregg, Systems Performance; Google SRE; NIST SSDF and incident-response guidance. | An event journal, deterministic replay, release manifest, recovery procedure, dashboards, and a written incident drill. |
| 6. Rules and operating practice | Current SEBI circulars and regulations; current NSE implementation standards; the chosen broker’s current API and operating documents. | A dated compliance checklist reviewed before G5, with named owners for rule, broker, data-license, and audit-log obligations. |

### 15.3 Recommended order at 20 hours per week

- **Weeks 1-3:** Block 1. Finish the market-mechanics notes and build the toy book model.
- **Weeks 4-7:** Block 2. Learn option mechanics and complete the independent repricer.
- **Weeks 8-10:** Block 3. Read the validation papers and write the experiment registration before testing variants.
- **Weeks 11-13:** Block 4. Define the fill and cost contract and compare it with the worked cost examples in Section 12.
- **Weeks 14-18:** Block 5. Design the journal, replay, release, recovery, and operational evidence needed for G2-G4.
- **Ongoing:** Block 6. Recheck official sources and broker terms before any live stage and after material changes.

These are planning ranges, not a credential or a promise of readiness. At 15 hours per week, extend the calendar; at 25 hours, shorten it only when the outputs meet the same review standard. Full-time work compresses study and build stages S0-S3 roughly, but does not remove the live-session requirements in S4-S6. [INFERENCE] [A14]

### 15.4 Further reading and limits

The book tracker in the workspace remains the source for access and reading status. It records Harris as in progress with full-text access still pending; the remaining books are queued. This continuation has not read those books in full. Use publisher, library, open-access, or authorized preview copies. Keep book notes in your own words and do not distribute copyrighted scans.

The two statistical papers listed in the literature section are methodological inputs, not proof that a particular strategy works in Indian index derivatives. The regulatory materials describe public rules at the time checked; they are not a substitute for a current broker or legal review. [S44] [S45] [S46] [S47] [S52] [S53]

## Phase 4 decision log

| Decision | Ruling | Why |
| --- | --- | --- |
| D36 | Stay at Tier 3 unless measured strategy evidence passes the capacity and cost-benefit upgrade gate. | The speed decision is an economics decision, not an engineering status reward. Section 12.6 provides the incremental-benefit test. [INFERENCE] |
| D37 | Reprice the entire book at spot plus or minus 2% and volatility plus or minus 3 points; add one tick per option leg and 0.1% on futures as exit haircuts. Each stressed session loss, including losses already booked, must remain within the hard daily-loss limit. Treat plus or minus 3 Nifty-equivalent lots as a first-order ceiling for the INR 1 crore example; full revaluation can bind sooner. Apply tightening now. | A broker or venue may not complete an exit. The 3.08-lot linear result is not a liquidation model; the D37 stress grid and remaining loss budget govern. [INFERENCE] |
| D38 | Cap each order at the smallest of one quarter of top-of-book size, INR 32,500 of premium, and 1.5 Nifty-equivalent delta lots. Tighten immediately; permit any loosening only after the D28 cool-off and G3. | A fixed 5-lot cap ignores both cost-efficient small-premium clips and position risk. Count working orders in the aggregate risk reservation. [INFERENCE] |
| D39 | Keep the three-times-trading-cost hurdle and require positive net evidence after realistic fees, spread, slippage, fill sensitivity, and the capital charge. | At the Section 12.5 assumptions, 4.0 bp trading cost + 2.9 bp fixed cost + 2.9 bp capital charge gives 9.8 bp break-even; 3x trading cost is 12 bp and remains the higher hurdle. [INFERENCE] |
| D40 | Precommit separate recurring-cash, daily-loss, and total live-loss budgets at G1. At the example values, pause at 50% of the live-loss budget and stop at 100%. | A calm-time ceiling prevents escalation after losses. The example is not an individualized risk recommendation. [INFERENCE] |
| D41 | The strategy gate chooses the first live universe. Nifty remains the initial build universe for data and simulator plumbing, not a promised live instrument. | Cost, liquidity, capacity, dated data, and measured edge differ by instrument. A strategy that fails the cost gate does not proceed to live trading. [INFERENCE] |
| D42 | Use Java 21 for the trading core and Spring Boot for control-plane services and dashboards on the assumed broker-API route. Defer C++ or Rust unless profiling and a tier review justify the change. | Strong Java is an existing skill under A13, and measured route latency must justify the added language and systems burden. [INFERENCE] |
| D47 | Before G5, verify the selected broker’s current OPS, API-key, static-IP, standby-IP, and order-type handling directly from official exchange and broker documentation. | The supplied broker summaries have no direct source links; treat the listed details as validation questions rather than established rules. [INFERENCE] |

## Sources

### S42 - Carry-forward verification boundary

S1-S41 and their cited claims belong to the supplied earlier phases. They were not independently re-verified as part of this continuation. In particular, carry forward the earlier claims about fees and taxes, market access and retention, broker-specific procedures, tier costs, member capital, current instrument details, and the status of legal proceedings only with the original source references and dates. Recheck any number or rule that will be used in an implementation. S42 is a record of this limit, not a new public source.

### New public sources checked Nov 3, 2026

**S43. SEBI, “Settlement Order in the matter of failure of systems of NSE and NCL upon occurrence of glitch on February 24, 2021,” June 20, 2023; and NSE Integrated Annual Report 2020-21.** The SEBI order records the trading halt, resumption, settlement terms, and the allegations in the show-cause notice; the NSE report describes its account of the telecom-link and storage-system failure. The settlement order states that applicants settled without admitting or denying facts or conclusions of law. [SEBI settlement order](https://www.sebi.gov.in/enforcement/orders/jun-2023/settlement-order-in-the-matter-of-failure-of-systems-of-nse-and-ncl-upon-occurrence-of-glitch-on-february-24-2021_72858.html) | [NSE annual report](https://nsearchives.nseindia.com/web/sites/default/files/inline-files/NSE%20Annual%20Report%202021_0.pdf)

Knight Capital remains source [S36] in the supplied earlier phase; it is not renumbered here.

**S44. SEBI, “Safer participation of retail investors in Algorithmic trading,” Circular SEBI/HO/MIRSD/MIRSD-PoD/P/CIR/2025/0000013, Feb 4, 2025.** [SEBI circular](https://www.sebi.gov.in/legal/circulars/feb-2025/safer-participation-of-retail-investors-in-algorithmic-trading_91614.html)

**S45. SEBI, revised implementation timeline, Sept 30, 2025.** The circular states the framework and exchange implementation standards apply to all stock brokers from Apr 1, 2026. [SEBI circular PDF](https://www.sebi.gov.in/sebi_data/attachdocs/sep-2025/1759232056254.pdf)

**S46. NSE, “Implementation Standards for Safer Participation of Retail Investors in Algorithmic Trading,” NSE/INVG/67858, May 5, 2025, and related operational circulars.** The standards specify the initial threshold of 10 orders per second per exchange/segment, tagging, risk-management and audit-trail requirements; the exchange can adjust the threshold. [NSE implementation standards](https://nsearchives.nseindia.com/content/circulars/INVG67858.pdf) | [NSE consolidated NNF circular](https://nsearchives.nseindia.com/content/circulars/SURV74008.pdf)

**S47. NSE, Equity Derivatives Contract Information and Contract Specifications.** The contract-information page displayed an update date of Nov 2, 2026 and links to the current permitted-lot-size and quantity-freeze files. The Oct 3, 2025 NSE circular set the Nifty 50 market lot to 65 for the affected contracts. Load the effective contract file for the actual trade date. [NSE contract information](https://www.nseindia.com/static/products-services/equity-derivatives-contract-information) | [NSE Oct 2025 lot-size circular](https://nsearchives.nseindia.com/content/circulars/FAOP70616.pdf) | [NSE contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications)

**S48. NSE, Equity Derivatives Price Bands.** The current page describes 10% operating ranges for index futures and a delta-based contract range for index and stock options. It does not promise that an order will fill. [NSE price-band page](https://www.nseindia.com/static/products-services/equity-derivatives-price-bands)

**S49. NIST, SP 800-218, Secure Software Development Framework (SSDF) Version 1.1, Feb 2022.** The NIST SSDF page also lists a Version 1.2 revision as draft; check status before treating a draft as normative. [SP 800-218](https://csrc.nist.gov/pubs/sp/800/218/final) | [SSDF publication status](https://csrc.nist.gov/projects/ssdf/publications)

**S50. NIST, SP 800-218A, Secure Software Development Practices for Generative AI and Dual-Use Foundation Models, July 2024.** [NIST publication](https://csrc.nist.gov/pubs/sp/800/218/a/final)

**S51. NIST, SP 800-61 Rev. 3, Incident Response Recommendations and Considerations for Cybersecurity Risk Management, Apr 2025.** [NIST publication](https://csrc.nist.gov/pubs/sp/800/61/r3/final)

**S52. Bailey, David H., Jonathan Borwein, Marcos López de Prado, and Qiji Jim Zhu, “The Probability of Backtest Overfitting,” Journal of Computational Finance, 2017.** [Published article DOI](https://doi.org/10.21314/JCF.2016.322) | [Open author copy](https://escholarship.org/uc/item/4w1110bb)

**S53. White, Halbert, “A Reality Check for Data Snooping,” Econometrica 68(5), 1097-1126, 2000.** [Published article DOI](https://doi.org/10.1111/1468-0262.00152)

## Selected literature

### Market structure and derivatives

- Harris, Larry. 2003. *Trading and Exchanges: Market Microstructure for Practitioners*. A foundational market-mechanics text in the supplied reading list.
- Natenberg, Sheldon. 2015. *Option Volatility & Pricing*, 2nd ed. Core options mechanics and volatility study.
- Cartea, Álvaro, Sebastian Jaimungal, and José Penalva. 2015. *Algorithmic and High-Frequency Trading*. Models, execution, and market-making.
- Gatheral, Jim. 2006. *The Volatility Surface*. Volatility-surface modeling reference.
- Hull, John C. 2021. *Options, Futures, and Other Derivatives*, 11th ed. Derivatives mechanics and risk.
- Sinclair, Euan. 2013. *Volatility Trading*, 2nd ed. Volatility strategies and risk.

### Validation and systems

- White, Halbert. 2000. “A Reality Check for Data Snooping.” *Econometrica*, 68(5), 1097-1126. DOI: 10.1111/1468-0262.00152.
- Bailey, David H., Jonathan Borwein, Marcos López de Prado, and Qiji Jim Zhu. 2017. “The Probability of Backtest Overfitting.” *Journal of Computational Finance*. DOI: 10.21314/JCF.2016.322.
- Kleppmann, Martin, and Chris Riccomini. *Designing Data-Intensive Applications*, 2nd ed. Use for storage, event logs, consistency, and recovery; follow the access status in the workspace tracker.
- Gregg, Brendan. 2020. *Systems Performance*, 2nd ed. Measurement and production diagnostics.
- Beyer, Betsy, Chris Jones, Jennifer Petoff, and Niall Richard Murphy, eds. 2016. *Site Reliability Engineering*. Release, monitoring, response, and learning practices.
- National Institute of Standards and Technology. 2022-2025. SP 800-218, SP 800-218A, and SP 800-61 Rev. 3. Secure development, generative-AI development controls, and incident response.

Literature entries are reference suggestions, not a claim that the full texts were read during this continuation. Check edition, access, and publication details before formal citation. The book tracker remains authoritative for the current reading status.

## Closing note for the whole study

This four-phase study describes a disciplined way to learn and evaluate a trading system. It does not establish that any strategy has an edge, that a broker route will be available, or that a loss cap can guarantee an exit. The first result may be a well-instrumented paper system that concludes no candidate clears its costs or data gates.

The core operating rule is simple: promote evidence, not confidence. Freeze assumptions, price execution honestly, keep risk independent of strategy code, record every state change, and stop when the evidence or operating state is unknown. Never present this conceptual design as Jane Street’s internal architecture or as personalized financial, legal, or tax advice.
