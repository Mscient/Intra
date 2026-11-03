# NSE index-derivatives settlement mechanics (checked 2026-11-03)

This note concerns the offline NIFTY index-futures and index-options settlement model. It is not a clearing-member rulebook, broker tariff, tax opinion, or account-specific cost schedule. The implementation plan treats public fees and rules as date-sensitive and requires fresh official-source and broker-document review at G0/G5.

## Exchange mechanics

- NSE's [equity-derivatives settlement mechanism](https://www.nseindia.com/static/products-services/equity-derivatives-settlement-mechanism) states that index futures positions are marked to the final settlement price on expiry and the resulting P&L is cash settled. It describes index-option final exercise for in-the-money positions at expiry and cash settlement of exercise P&L. The page says final settlement amounts are debited/credited T+1. This describes clearing mechanics, not a client's broker invoice.
- NSE's [settlement-price page](https://www.nseindia.com/static/products-services/equity-derivatives-settlement-price) identifies the final settlement price for index futures and index options as the relevant underlying index closing value on the last trading day.
- NSE's [equity-derivatives contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications) was updated 2026-08-11 and lists current contract attributes and expiry-day rules. A run still supplies its exact dated instrument master, calendar, and settlement input; this project does not infer a settlement date from an evergreen rule.

The settlement-mechanism page displays an update date of 2023-01-03, so it is not sufficient by itself to establish current terms for a run. Check current NSE Clearing circulars and the applicable broker documentation before using a settlement model for research that depends on exact obligations.

## Cost-model consequence

The cited public pages establish cash-settlement mechanics and price inputs; they do not establish the operator's brokerage, exercise/service fees, tariff-specific GST base, or invoice rounding. That is a limit of the sources reviewed, not evidence those costs are zero. The code therefore does not hardcode additional settlement charges. An optional effective-dated `settlement.cost_schedule_file` accepts source-attributed amounts or rates, per-run component codes, calculation bases, and a caller-selected GST-on-charge flag. Its hash enters run identity and its calculated lines are independently reconciled and persisted.

Supported bases are fixed INR per non-flat settlement, INR per settled unit, INR per ITM option unit, basis points of settlement notional, and basis points of option intrinsic value. GST is applied only to a line's rounded charge when the caller selects that line as taxable. These are calculation choices, not conclusions about tax law or broker invoicing. A populated schedule must be sourced from the applicable broker tariff, contract note, and invoice treatment before `cost.status=VERIFIED`; an empty or missing schedule does not imply that costs are zero.

## Implementation status

Existing option-exercise STT and SEBI turnover components remain separate and retain their own source-supplied basis behavior. The new schedule adds only additional caller-supplied settlement lines. Both paths are diagnostic, not a verified all-in cost model. The plan's other gaps remain open: execution realism, margin, capital charge, fixed costs, authorized data, current rule review, and independent strategy evidence.
