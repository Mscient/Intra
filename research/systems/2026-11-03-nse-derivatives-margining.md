# NSE equity-derivatives margining and simulator boundary (as of 2026-11-03)

This note distinguishes exchange margin mechanics from the margin input consumed by the isolated single-contract replay. It is research context, not account-specific margin guidance or a substitute for a current broker/clearing statement.

## Primary-source findings

- NSE Clearing describes equity-derivatives margin as portfolio-based SPAN risk. It publishes daily risk-parameter files for members to apply to portfolio positions; the SPAN implementation includes scanning risk, intracommodity spreads, net option value, and other terms. See [NSE Clearing SPAN](https://www.nseindia.com/static/products-services/equity-derivatives-nse-clearing-span) and the [SPAN risk-parameter files page](https://www.nseindia.com/static/products-services/equity-derivatives-span-risk-parameter-files).
- NSE lists initial margin as a combination that can include SPAN, delivery margin, and consolidated crystallized obligations. It separately describes ELM, intraday crystallized obligations, expiry obligations, and other margin components. See [NSE equity-derivatives margins](https://www.nseindia.com/static/products-services/equity-derivatives-margins).
- NSE Clearing states that SPAN risk parameters are supplied in a daily file and that portfolio offsets affect requirements. A single per-contract value therefore cannot independently reproduce the portfolio calculation. The [daily derivatives reports page](https://www.nseindia.com/all-reports-derivatives) publishes SPAN and contract-wise ELM files, whose format/version and applicable date must be checked before ingestion.
- SEBI's exchange-traded derivatives provisions describe client margin collection obligations and include initial, exposure/ELM, calendar-spread, mark-to-market, and other margins as specified by the exchange/clearing corporation. See the [SEBI exchange-traded derivatives regulations](https://www.sebi.gov.in/sebi_data/commondocs/oct-2023/Chapter-5-Exchange_Traded_Derivatives_p.pdf).

## Implementation consequence

`risk.margin_schedule_file` now accepts `effective_from,effective_through,instrument_id,contract_kind,margin_per_lot_inr,source_reference`. It resolves one caller-supplied amount per lot for the one dated contract and each open replay session. The range and instrument checks fail closed when coverage is missing or overlapping. The rate, source reference, and schedule hash are recorded in the fill ledger, run identity, and manifest; the independent risk reconciliation recomputes the entry-cap decision using that rate.

This is a dated **input schedule**, not a SPAN/ELM calculator. It does not parse or calculate daily SPAN risk arrays, account for portfolio offsets, calculate changing crystallized obligations, distinguish a member's collection buffer from exchange minimums, or model maintenance-margin calls/liquidation. Keep the run's margin evidence unverified until the exact file/date, contract mapping, account treatment, and source rights are reviewed. The older fixed `risk.margin_per_lot_inr` setting remains supported as an explicitly labeled proxy.
