# Research gap audit: primary-source review

**Reviewed:** 2026-11-03 (Asia/Kolkata)
**Input:** user-provided Intra research gap audit prepared 2026-11-03
**Purpose:** separate claims supported by current primary exchange/regulator pages from hypotheses and secondary-source dates. This note does not authorize market-data capture, broker access, or orders.

## Findings supported by current primary NSE pages

- NSE's [Closing Auction Session page](https://www.nseindia.com/static/products-services/closing-auction-session) says Phase 1 applies to cash-segment stocks that have derivative contracts. It describes CAS from 15:15 to 15:35, including the 15:20-15:30 order-entry phases and 15:30-15:35 matching.
- The same page says the equity derivatives segment trades 09:15-15:40, lists NSE/FAOP/74467 dated 2026-05-29, and says an "Indicative Index" is disseminated during CAS. Its current page does not, by itself, prove the exact go-live date or the source semantics of any particular broker candle.
- NSE's [Equity Derivatives Pre-Open page](https://www.nseindia.com/static/products-services/equity-derivatives-pre-open-session), marked updated 04/09/2026, says the session applies to single-stock and index futures. It describes 09:00-09:05 market and limit order entry, 09:05-09:10 limit-only entry, random closure in the last two minutes, 09:10-09:12 matching, and a 09:12-09:15 buffer. It says current-month futures are eligible, extended to next-month futures during the last five trading days before current-month expiry, and permits algo market orders in the initial five minutes.
- NSE circular NSE/FAOP/71582 (2025-12-02) confirms that NSE/FAOP/71092 introduced the F&O pre-open and that pre-open order records were included from 2025-12-08. [Circular PDF](https://nsearchives.nseindia.com/content/circulars/FAOP71582.pdf)

## Claims that remain unverified or are hypotheses

- The audit's claimed 2026-09-07 effective date for the revised pre-open timings was not found in a primary circular during this review. The NSE product page shows a current schedule but does not expose its effective-from date in the schedule text. Keep the date provisional until its implementing circular is obtained.
- The audit's 2026-08-03 CAS / 15:40 effective date is not established by the current landing page alone. NSE/FAOP/74467 is listed there, but the circular itself must be read to record its implementation date and any derivatives settlement-price changes.
- The NSE CAS page's indicative-index dissemination makes a CAS-related explanation for the SmartAPI NIFTY 2026-10-30 15:14-to-15:28 gap plausible, but does not identify SmartAPI's candle source or establish that explanation. Do not fill, discard, or relabel the missing interval based on this hypothesis.
- Tuesday expiry, holiday shifting, the exact dated NIFTY lot-size timeline, expiry-day ELM/calendar-spread treatment, the OPS threshold/counting rule, and SmartAPI AB00-AB11 meanings still need their own dated primary-source review before use in implementation rules.

## Repository consequence

The Java replay already takes first/last expected bar timestamps from a user-supplied per-date calendar and hashes the calendar into the run identity. It has no default 15:30 close to replace. The real implementation gap was that runs could not identify which exchange-rule regime the caller assigned to each date.

Open calendar rows resolve through a hashed effective-dated registry. The validator checks the regime ID, effective date, exchange/segment/contract kind, matching source reference, timezone, regular-session window, lot size, and tick size. The run manifest preserves the applied rule fields and source metadata. Registry statuses and source hashes are still caller assertions; the software does not fetch circulars or establish source authenticity. The bundled example is deliberately synthetic, and the official NSE rule rows remain to be populated after the outstanding primary-source review.

## Follow-up schedule comparison

- The current NSE [Equity Derivatives Pre-Open page](https://www.nseindia.com/static/products-services/equity-derivatives-pre-open-session), marked updated 04/09/2026, describes market and limit entry from 09:00–09:05, limit-only entry from 09:05–09:10, random closure in the last two minutes, matching from 09:10–09:12, and buffer to 09:15. It also states current-month futures eligibility, extension to next-month futures in the last five trading days, and algo market orders during the initial five minutes.
- The dated NSE/FAOP/71092 circular (2025-11-03) and NSE/FAOP/71582 update (2025-12-02) describe the original schedule with entry ending at 09:08 and random closure between the seventh and eighth minute. NSE/FAOP/71582 says pre-open order-log indicators apply from 2025-12-08; it does not identify the effective date of the later 09:10 schedule.
- The current NSE pre-open page also describes single-price equilibrium selection (maximum executable quantity, then minimum imbalance, then closest to previous close) and a matching sequence for market and limit orders. A generic call-auction primitive now records those caller-supplied mechanics, but no historical session is routed through it; the no-equilibrium handling still requires a dated source decision.
- This is an unresolved primary-source timeline conflict: the current NSE product page supplies the current schedule but not its effective-from date, while the dated circulars establish the older schedule. Do not create historical registry regimes for the newer schedule until its implementing circular/effective date is found.
- The NSE [contract specifications page](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications) currently lists Tuesday expiry and previous-trading-day holiday adjustment for index derivatives, plus current cycle descriptions. It directs users to current contract files for applicable lot size; the page's general tick-size table is level-banded for index futures and is not a substitute for dated instrument-master values.
- The NSE [Closing Auction Session page](https://www.nseindia.com/static/products-services/closing-auction-session) lists NSE/FAOP/74467 as dated 2026-05-29 but does not expose the derivatives circular's effective date or full settlement-price details. Keep the reported 2026-08-03 date provisional until the circular itself is obtained and checked.

## Source pages

- [NSE Closing Auction Session](https://www.nseindia.com/static/products-services/closing-auction-session)
- [NSE Equity Derivatives Pre-Open](https://www.nseindia.com/static/products-services/equity-derivatives-pre-open-session)
- [NSE/FAOP/71582 PDF](https://nsearchives.nseindia.com/content/circulars/FAOP71582.pdf)
- [SEBI Circular 2025/79](https://www.sebi.gov.in/legal/circulars/may-2025/measures-for-enhancing-trading-convenience-and-strengthening-risk-monitoring-in-equity-derivatives_94293.html)
