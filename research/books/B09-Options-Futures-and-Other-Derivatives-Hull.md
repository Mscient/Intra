# B09 — Options, Futures, and Other Derivatives: Research for the Nifty System

**Author:** John C. Hull  
**Edition / publisher:** 11th edition, Pearson; published 13 April 2021, copyright 2022  
**Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset  
**Research date:** 1 November 2026 (Asia/Kolkata)  
**Research status:** Official Pearson scope, edition information, and contents reviewed; full book not accessed or read.

> **Purpose and evidence boundary.** Pearson’s public title page supplies the 11th-edition description and chapter list. It does not provide the whole book. This note identifies the portions most useful for modeling, contract understanding, and risk controls; exact derivations and worked examples remain unread.

## 1. What Hull adds to the book sequence

Pearson describes the book as a broad derivatives text covering futures, options, pricing, hedging, volatility, risk, and market practice. The 11th edition updates reference-rate material, rough-volatility models, machine learning in derivatives pricing/hedging, and regulatory context. [1]

For this project, Hull is the reference for derivative contract mechanics and valuation foundations. B02 and B05 address trading interpretation of options/volatility; B03, B06, and B07 focus on execution and microstructure. Hull helps check the underlying financial mechanics that connect Nifty futures/options, carry, pricing, Greeks, and portfolio risk.

## 2. Most useful chapter groups

The official contents list 37 chapters. [1] A practical Nifty reading set is:

| Chapters | Relevance before implementation |
|---|---|
| 2–3: futures markets, central counterparties, hedging with futures | Contract/clearing mechanics, futures exposure, and hedge interpretation. |
| 4–5: interest rates and forward/futures prices | Carry and basis assumptions for a spot/futures input. |
| 10–12: options markets, option properties, option strategies | Contract mechanics, payoff/exposure combinations, and common strategy behavior. |
| 13–15: binomial trees, stochastic calculus, Black–Scholes–Merton | Model foundations and price/hedge assumptions. |
| 17–20: index/currency options, futures options, Greeks, smiles/surfaces | Directly relevant to index derivatives, local sensitivities, and skew. |
| 21–23: numerical methods, VaR/expected shortfall, volatility/correlation estimation | Model computation and portfolio-risk measurement. |
| 37: derivatives mishaps | Governance, model/operational failure lessons. |

Other chapters on swaps, securitization, credit, commodity, and short-rate derivatives are useful reference material but are not an initial Nifty paper-system requirement.

## 3. Translate derivative mechanics into explicit system inputs

### 3.1 Contract and clearing details belong in metadata

The futures and options market chapters are a reminder that a derivative is a standardized contract with an underlying, expiry, multiplier/lot, price increment, settlement and margin rules, and an exchange/clearing process. Represent each as effective-dated reference data. Do not hardcode past expiry, lot-size, or tick rules into a strategy.

The current NSE contract-specification page lists current equity-derivatives descriptors and trading-cycle information. Read the actual daily/official contract file for the product and date rather than relying on a textbook example. [2]

### 3.2 Forward/futures basis is part of the pricing convention

For index options, a pricing input might use spot with a carry/dividend assumption or a futures forward consistent with the expiry. These are not interchangeable without a documented convention. Save the underlying source, timestamp, basis, expiry-time calculation, rate assumption, and model version with each option mark. Reconcile a model price to observed bid/ask, not just last trade.

The book’s general pricing equations are not a source for today’s INR rate, Nifty contract schedule, broker margin, or API quote semantics. Those are current operational data to acquire separately.

### 3.3 Greeks are local risk summaries, not loss limits

Pearson’s contents include the Greek letters and index options. [1] For the project, compute each Greek with documented units and aggregate across the entire position. Delta/gamma/vega/theta changes describe local model sensitivities; they do not bound a gap, a surface dislocation, an unfilled hedge, or a forced exit.

Alongside Greeks, calculate scenario P&L for combined underlying, implied-volatility, skew, and time moves. Use alternative assumptions for stale or missing quotes, and include option bid/ask liquidation values. Keep position-level and portfolio-level results.

### 3.4 Model value and executable value are different

The book’s option-pricing and numerical chapters are useful for checking theoretical values. But a model mark is not a fill. In a retail API system, orders are discrete and can be rejected, delayed, partially filled, or executed at the bid/ask rather than a midpoint. A paper model should preserve both the theoretical mark and the available executable quote, with timestamp and source.

Use the option’s own quote and depth. Nifty spot or futures may be the forecast input, but they cannot establish the selected option’s execution cost.

## 4. Risk measurement for a small paper account

Pearson lists Value at Risk, expected shortfall, and volatility/correlation estimation as separate chapters. [1] These are useful summaries, but they should not be used as the only controls for an intraday options position. A single-tail quantile estimate may not capture short-dated jumps, expiry behavior, or a broken hedge workflow.

For the paper system, pair statistical risk measures with deterministic controls:

- maximum quantity/position and session loss;
- scenario losses for large underlying moves and volatility/skew shifts;
- maximum loss if a hedge is delayed, rejected, or partially filled;
- stale-data and connection stop conditions;
- margin/funds checks and contract identity;
- policy for flattening or carrying risk at session end.

Compute risk on the net portfolio and also inspect each leg, because offsetting model Greeks can hide legging or liquidity exposure. Treat all model inputs and risk measures as timestamped estimates with a known data window.

## 5. Model implementation sequence

1. Build and version the instrument master and expiry calendar.
2. Implement a deterministic forward/carry calculation with transparent inputs.
3. Add pricing and Greeks for simple European-style index options using verified contract assumptions.
4. Validate prices against synchronized, quality-checked quotes and report model residuals.
5. Run deterministic shock scenarios and portfolio aggregation.
6. Add Monte Carlo or more advanced numerical procedures only for a defined pricing/risk problem.
7. Measure hedge and order costs in a paper workflow before any model-based strategy evaluation.

The sequence uses textbook foundations while keeping pricing, signal, execution, and risk monitoring separable.

## 6. What not to transfer directly

- Pearson’s examples and rate conventions may be based on non-Indian markets. Use current INR and NSE contract inputs.
- General derivatives theory does not tell us the broker’s current API route, order rate, allowed order types, margin rules, or data retention.
- A hedge ratio does not guarantee that the hedge can be executed at the modeled quote.
- A textbook risk statistic is not a substitute for fixed order, position, and session-loss limits.
- The 11th edition’s coverage of machine learning in pricing and rough volatility is not a reason to introduce ML before data integrity and a simple model baseline exist.

## 7. Evidence and next actions

- Edition date, scope, features, and the 37-chapter contents are confirmed from Pearson’s official page.
- Full text, worked examples, technical notes, and companion software were not accessed.
- Obtain authorized full text; focus on the chapter groups above and log actual chapters/pages read.
- Keep B09 “in progress” until the selected material is read and specific model assumptions are recorded.

## Sources

1. [Pearson — *Options, Futures, and Other Derivatives*, 11th edition](https://www.pearson.com/en-us/subject-catalog/p/Hull-Options-Futures-and-Other-Derivatives-RENTAL-11th-Edition/P200000005938?view=educator) — publication metadata, edition features, and table of contents.
2. [NSE — equity derivatives contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications) — current exchange contract descriptors and cycles.

---
*B09 research note • Public-source synthesis and Nifty derivatives-system translation. Full-text reading remains open.*
