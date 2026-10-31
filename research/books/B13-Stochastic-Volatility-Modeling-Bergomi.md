# B13 — Stochastic Volatility Modeling: Research for the Nifty Options System

- **Author:** Lorenzo Bergomi
- **Edition / publisher:** First edition, Chapman & Hall/CRC, copyright 2016
- **ISBN:** 978-1-4822-4407-6
- **Project fit:** India; own account; Nifty first; retail broker API; no paid tick feed at the outset
- **Research date:** 1 November 2026 (Asia/Kolkata)
- **Research status:** Publisher contents, overview, and lawful preview material reviewed; complete book not accessed or read.

> **Evidence boundary.** The publisher and author outline the book’s scope and list its twelve chapters; the public preview exposes only a portion of the text. The model-selection guidance here is an original Nifty-focused synthesis. B13 remains in progress pending authorized full-text reading.

## 1. Scope and project relevance

Bergomi’s book addresses practical derivatives-modeling questions using local volatility, stochastic volatility, local-stochastic volatility, and multi-asset stochastic volatility. Its contents include forward-start options, variance swaps, options on realized variance, the Heston model, forward-variance models, volatility smiles and dynamics, equity smiles, and local-stochastic-volatility models. The author’s description says the work is motivated by pricing and hedging issues encountered in derivatives trading. [1][2]

For this project, its most useful role is to improve model-risk and hedge-risk thinking after there is a reliable Nifty options dataset. It is not needed to begin the data recorder, a simple futures paper system, or basic option-chain quality checks.

## 2. Keep three volatility quantities separate

### 2.1 Realized volatility

This is calculated from historical price changes over a defined sampling and time convention. It depends on the chosen return series, interval, market hours, data quality, and estimator. Treat it as a historical statistic or a forecast target, not as a traded option price.

### 2.2 Implied volatility

This is backed out from an option price under a specified pricing convention and inputs such as underlying/forward, expiry time, rates, and option style. It is sensitive to bid/ask width, stale quotes, low vega, and solver quality. Report which side or price was used and retain the quote-quality flags.

### 2.3 Model state and forecast

A calibrated local- or stochastic-volatility model is a representation of option prices and/or their dynamics under its assumptions. A good fit to today’s surface is not automatically a good forecast of future realized volatility or of tomorrow’s surface. Keep calibrated parameters, risk-neutral marks, physical forecasts, and observed outcomes in separate datasets and reports.

The book’s contents explicitly cover both local-volatility and stochastic-volatility approaches and the links between static and dynamic properties. [1] This distinction helps prevent a model fit from being promoted into a directional signal without separate evidence.

## 3. Nifty option-data requirements before calibration

Do not calibrate a dynamic model from a chain screenshot or asynchronous last-traded prices. Capture the best bid and ask, displayed size if permitted, source and receive times, exact contract ID, expiry, strike, call/put, lot/tick metadata, and a synchronized Nifty spot/futures reference. Record the instrument-master version and the forward/carry, rate, and expiry-time conventions.

Preserve wide, crossed, stale, zero-size, missing, and low-vega quotes as observations with quality flags. Do not silently smooth them into a complete surface. If a paid tick feed is unavailable and a broker API provides only snapshots or bars, state what information is missing and restrict conclusions to the resolution supported by that feed.

NSE describes India VIX as a 30-calendar-day expected-volatility measure calculated from Nifty option bid/ask quotes under an adapted CBOE method. That index is a useful market context series, but it is not interchangeable with a fitted single-expiry surface, realized volatility, or the output of a Bergomi-style model. [3] Check the current methodology and contract specifications when deciding whether any related tradable product belongs in scope.

## 4. A staged model plan

### Stage A: Validate observations and a simple surface

Before stochastic calibration, establish deterministic quote filters, moneyness and expiry conventions, implied-volatility inversion checks, and plots of bid/ask and fitted values by expiry. Track rejected contracts, missing strikes, residuals, and solver failures. Test the same process on held-out dates.

### Stage B: Use a simple model for a named decision

Name the decision first. Examples include measuring vega exposure under a skew move, projecting hedge P&L after a Nifty move, or comparing how surface level and skew changes affect an option position. Do not fit a model just because it is more mathematically elaborate than a static surface.

### Stage C: Add dynamics only when simple risks are inadequate

The contents include Heston and forward-variance models, smile dynamics, multi-asset volatility, and local-stochastic-volatility models. [1] These can be candidates for a defined failure in pricing, hedging, or scenario analysis. Compare them on the same observations and out-of-sample dates; record parameter stability and hedge outcomes, not only calibration error.

Use an explicit scenario set: underlying up/down, volatility level up/down, skew steepening/flattening, expiry shortening, and plausible gap moves. Report delta, gamma, vega, and scenario P&L separately, with actual spread, fees, and discrete hedge costs.

### Stage D: Test the hedge rather than assuming it

A model may fit a snapshot yet produce poor hedge behavior as the underlying and surface move. Compare predicted and realized hedge P&L over a forward paper period. Include re-hedging frequency, quote availability, transaction costs, slippage, partial fills, and periods when the chosen hedge contract is illiquid. Keep risk limits outside the model and cap exposure when inputs are stale or calibration fails.

## 5. Topics to defer or treat cautiously

| Topic | Initial Nifty relevance |
|---|---|
| Local-volatility dynamics and carry | Useful for understanding model-based option hedge behavior; calibrate only with reliable synchronized surfaces. |
| Forward-variance and stochastic-volatility models | Research after a stable surface and a concrete prediction or hedge question exist. |
| Variance swaps, realized-variance options, timer options, daily cliquets | Specialized payoffs; verify a relevant listed product and data before implementation. |
| VIX futures/options and multi-asset models | The book’s products and examples are not automatically NSE instruments; establish local contract availability and definitions first. |
| Local-stochastic-volatility calibration | Advanced model-risk work, not a first paper-system feature. |

## 6. Pre-build conclusions

1. Build a reproducible option quote archive and contract master before fitting models.
2. Establish a basic implied-volatility surface and quality diagnostics.
3. Separate observed implied volatility, historical realized volatility, model parameters, and forecasts.
4. Define a model question in advance and compare its output with a simpler benchmark.
5. Validate on held-out dates and forward paper observations; report calibration error and hedge P&L.
6. Include executable quote sides, hedge costs, liquidity, and failure cases.
7. Keep hard exposure and loss controls independent of model output.
8. Defer advanced models until the data can support them.

## 7. Limits and next reading

The book’s derivative-pricing and hedging examples do not prove a Nifty strategy or a profitable volatility risk premium. The quality of any model depends on the quality and synchronization of the option and underlying data. B13 is optional for the first build and should be revisited when there is a concrete options-hedging or surface-dynamics need.

Read the full chapters on local volatility, forward variance, stochastic-volatility smiles and dynamics, equity smiles, and local-stochastic volatility from an authorized copy before implementing those models.

## Sources

1. [O’Reilly/Chapman & Hall/CRC — *Stochastic Volatility Modeling*](https://www.oreilly.com/library/view/stochastic-volatility-modeling/9781482244076/) — edition record and contents covering Chapters 1–12.
2. [Lorenzo Bergomi — book overview](https://www.lorenzobergomi.com/) — author’s description of the modeling scope and trading/hedging motivation.
3. [NSE — India VIX Index](https://www.nseindia.com/static/products-services/indices-indiavix-index) — exchange description of the index and its Nifty-options basis.

---
*B13 research note • Options-modeling and hedging guidance for later Nifty research. Full-text reading remains open.*
