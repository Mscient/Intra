# G1 targeted learning pack

Prepared 2026-11-08. **Owner learning and acknowledgment: PENDING_OWNER_INPUT.** These original explanations, reading prompts and exercises provide review material, not a training-completion certificate. Source IDs refer to the [dated source register](g1-foundations-2026-11-08/source-register-v1.md); inaccessible readings are explicitly leads. Use authorized publisher/library copies for the suggested books in the final plan §13 and Phase 4 continuation §15; no full-book reading is claimed.

## 1. Microstructure and the order-book model

A book is outstanding willingness to trade at particular prices and quantities. The best bid is the highest buy price; the best ask is the lowest sell price. Their difference is the displayed spread, and their midpoint is a valuation reference. Buying at the ask and later selling at the bid spends spread even if the midpoint never moves. A midpoint change is information about prices, not evidence of an attainable trade.

A limit order sets a price boundary but can remain unfilled. An aggressive order consumes opposite liquidity; a passive remainder waits. Price/time priority puts better prices first and earlier orders first at the same price. IOC permits immediate partial execution and cancels the remainder; DAY can rest until session end. A stop is a trigger, and its subsequent order still faces execution constraints. These are exchange mechanics [S01]; the selected broker/API may allow a narrower order set, which is unresolved at G0 [S17–18].

Queue position means quantity/orders ahead at a level at the relevant exchange processing time. Sending a request first on one's own clock does not establish first arrival at the exchange. Additions, cancellations, hidden liquidity, price changes, and one's own partial fills can change the queue. A broker's five-level aggregate snapshot lacks individual priority identities and intervening events [S10,S12]. A price touching a limit in a candle cannot establish either priority or fill. This is an information-limit inference, not a measured probability of execution.

Inventory exposes a liquidity provider to price movement while waiting or exiting. Favorable displayed spread can be offset by adverse selection: counterparties may trade just before the price moves against the filled order. Model reservation prices can respond to inventory, but the Avellaneda–Stoikov model requires price and arrival assumptions and is not a calibration for this route [S13, §§1–2.4]. Define an adverse-selection diagnostic for side a ∈ {+1 buy,−1 sell}: `a × (mid_at_fill+h − mid_at_fill)`; negative means the post-fill move hurts that side. Keep this apart from spread and fee accounting and do not debit it again if realized P&L already includes it.

Latency has several clocks: exchange event, vendor publication, local receipt, completed-bar decision, simulated release and execution. A historical bar label may identify a start or end, not the instant information became available. Delaying an action can change price, available size and whether it fills. Market impact is the change associated with one's demand for liquidity and later responses; a top-of-book size cap alone does not estimate it. Inventory/latency/impact sensitivities are analytical assumptions until authorized observations support them.

Reuse the existing [toy book contract](../../Intra-Paper-System/docs/TOY-ORDERBOOK.md) and `book/ToyLimitOrderBook.java`. Its state uses integer ticks/units, call order for FIFO, resting-price matching, partials and cancellation. Its market-IOC sweep is an exchange-neutral teaching operation with no protection. It excludes hidden orders, self-trade prevention, latency, broker policy and calibrated impact. The generic call-auction engine has separate clearing/allocation logic; continuous matching must not be applied indiscriminately to auction phases. Historical pre-open/CAS regimes still require dated sources [S19 and NEXT-RESEARCH-REQUESTS].

Toy exercise: rest two sell orders, A for 2 units then B for 3 units at 101 ticks; submit a buy IOC for 4. Explain why A fills 2, B fills 2, B retains 1, and a separate buy IOC with no opposite liquidity misses completely. Cancel/reinsert B and explain the new priority. These invented ticks and units are not Nifty lots, quotes, or fees. The focused G1 runner exercises the existing book for this purpose.

Reading: NSE Trading System [S01], official SDK parser and Kite depth structure [S10,S12], Avellaneda & Stoikov §§1–2.4 [S13]; authorized Harris *Trading and Exchanges* and Lehalle/Laruelle *Market Microstructure in Practice* as book leads.

Comprehension checklist (human review remains pending):

- Explain a price signal, midpoint markout, order intent, partial fill and realized contract P&L using separate events.
- Walk through FIFO, cancel/reinsert, missed IOC and residual inventory in the toy exercise.
- Identify which timestamps determine observability and which cannot measure order-path latency.
- Explain why snapshots, candles and theoretical quotes cannot establish passive queue fills or capacity.
- Show where spread, adverse selection and impact enter a diagnostic without double-counting.

Not covered / uncertain: actual broker-allowed order types, protection/tags, arrival latency, cancellations between snapshots, L3 rights/history, queue calibration, historical auctions, and execution counterfactuals. No human comprehension result has been recorded.

## 2. Derivatives and contract identity

An index is a calculated reference; a futures contract is a dated obligation; an option is a listed right with a strike, expiry and call/put identity. Contract identity also needs venue/segment, effective symbol/token mapping, multiplier/lot, tick and reference snapshot. Today’s token or lot does not reconstruct an old contract. NSE’s specification page and lot-change circular demonstrate why expiry and tick/lot policies need effective dates [S02,S03]. A roll is closing one contract and opening another, with two executable markets and costs; stitching price levels can hide the basis jump and both trades.

For a long future, a simplified gross change is units × (exit future price − entry future price). For a long option sold before expiry it is units × (exit premium − entry premium). Neither equals units × the index change. Premium can respond to time, implied volatility and nonlinear exposure even when the underlying is unchanged. Long-option expiry payoff is `max(S−K,0)` for a call or `max(K−S,0)` for a put, times units; net economics also includes the purchase premium and charges. The settlement reference S must be a sourced settlement input, not a convenient final candle.

Daily futures mark-to-market, final futures settlement, daily option premium settlement and final option exercise are different cash-flow events [S08]. Keep contract P&L, cash/principal, fees and collateral separate. Settlement timing at clearing-member level does not establish a broker client's cash availability. Current stock delivery treatment and index closing methodology must not be inferred from the older grouped settlement page. The G1 shortlist excludes stock-derivative execution rather than guessing these inputs.

The existing `EuropeanOptionPricer`, `Black76OptionPricer`, `OptionPricingDiagnostics`, independent validation runner and archived R11 fixtures cover theoretical value/Greeks and numerical checks. BSM uses spot/carry assumptions; Black-76 uses a forward/futures input with discounting. Model inputs include volatility, time, rates and conventions; Greeks are local sensitivities with declared units. They are not prices at which a counterparty will trade. Large shocks, stale volatility, jumps and surface errors can defeat linear delta approximations. Keep full revaluation/stress diagnostics separate from premium-based fill/accounting ledgers. Existing independent numerical tests do not establish market volatility inputs, liquidity or margin adequacy.

Reading: NSE specifications/lot transition [S02–03], NSE Clearing settlement sections [S08], project [independent pricer validation](option-pricing-benchmark/R11-OPTION-PRICER-VALIDATION.md); authorized Hull/Natenberg/Gatheral/Sinclair editions from the book tracker.

Comprehension checklist:

- Distinguish an index, one dated future, one dated option and a continuous chart.
- Reconstruct a lot-change/expiry example from effective contract records without a timeless constant.
- Explain premium-return, notional-return, collateral and payoff denominators.
- Explain why a Greek, model price or stress result is not an executable quote or guaranteed exit.
- Identify the settlement price and charge inputs required for an expiring position.

Not covered / uncertain: licensed contract/margin history, validated implied-volatility surface, broker margin/assignment procedures, holiday/CAS settlement methodology, client funding, and live pricing accuracy. Owner derivatives acknowledgment is pending.

## 3. Statistics and research design

A hypothesis predicts an effect from a specified mechanism and information set. A parameter search asks which of many rules scored best. Search is useful for development but expands the set of tried alternatives. Choosing a universe, horizon, filter, cost model or primary metric after viewing outcomes is also selection. Count failed, abandoned and inspected variants, not just the surviving file. The PBO paper's common performance matrix and CSCV procedure require complete configurations on synchronous observations [S14, Algorithm 2.3]. Unknown older experiment history must remain unknown.

A benchmark answers a stated question. Flat/no-signal gives a zero signal-score comparator; it is not a fee-free executable investment. A fixed opposite-direction comparator can test reversal direction. Comparing market making with passive-touch fills against marketable trades with crossing costs would answer a different question unless both models are declared. Fix units and observation unit: intraday signals within a session share market conditions, so many candles do not imply many independent samples.

Order complete sessions chronologically into development, validation and untouched holdout. Freeze split dates from data-quality/calendar review before computing forward labels. Development may assess precision and choose permitted rules; validation evaluates the frozen rule; holdout is a single final test. Labels/positions that cross boundaries need exclusion/purging determined before results. Random shuffling leaks time/regime information. Do not choose a pair, instrument or parameter from holdout and then call it held out.

Look-ahead includes using a close before its bar is available, today’s contract master for expired history, revised data unavailable at the decision, and selecting only sessions with profitable completed exits. Selection bias includes hiding no-signal sessions, missing prices, failed fills and failed trials. Synthetic fixtures test software behavior with invented processes; they cannot estimate a market effect. Index markouts omit contract basis/premium, spreads, actual fills, fees, funding and capacity, so even statistically positive signal results cannot establish executable net P&L.

Multiple testing changes the meaning of a good score. White's Reality Check and DSR are authorized reading leads [S15–16]; the project [DSR/PBO audit](2026-11-04-dsr-pbo-audit.md) and [HAC protocol](../../Intra-Paper-System/docs/PORTFOLIO-SHARPE-HAC-PROTOCOL.md) document existing formulas and their limits. This increment changes none of them. HAC is a fixed-candidate dependence diagnostic, not a cure for selecting the winner. PBO complements can include later dates and are not chronological forecasting evidence. A local registration timestamp/hash detects identity changes relative to an anchor; it neither proves independent preregistration nor unobserved holdout access.

Reuse the Wave A baseline's equal-weight session signal score, chronological 60/20/20 planning split, and six predeclared primary comparisons across three rules and two confirmatory partitions. Exact dates and precision remain `PENDING_G2_DATA`. The specified stationary bootstrap and adjusted intervals are research-design proposals already in Wave A, not newly implemented or certified by G1. Validation/holdout thresholds never get tuned. An interval including zero is inconclusive; an entirely negative adjusted holdout interval rejects the positive directional claim. Broken rights, timestamps or completeness mean not testable before scoring.

Reading: PBO §§1–2/Algorithm 2.3 [S14]; White/DSR [S15–16] through authorized copies; [Wave A spec](WAVE-A-SIGNAL-SPEC.md), existing methods audit/HAC protocol, and authorized Chan/Cartea books as leads.

Comprehension checklist:

- State one mechanism, exact event, timestamp, metric, benchmark and rejecting observation without searching a grid.
- Count a changed universe, execution filter, horizon and abandoned run as appropriate variants/exposures.
- Explain session clustering, chronological boundaries and a label crossing a split.
- Distinguish statistical rejection, inconclusive evidence and data not testable.
- Explain why timestamps, hashes, synthetic tests and DSR/PBO/HAC outputs cannot prove preregistration or profitability.

Not covered / uncertain: total historical trial count/exposure, actual sample sufficiency/power, market stationarity, independent reviewer judgment, and future holdout controls. No human learning/selection/review is claimed complete.
