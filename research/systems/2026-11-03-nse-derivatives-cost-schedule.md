# NSE equity-derivatives charge reference (as of 2026-11-03)

This note records public statutory and exchange charges relevant to offline NIFTY futures and option-premium replay. It is not an account tariff, tax opinion, or a verified all-in cost schedule. Rates can change; use the source in effect on each trade date and retain the applicable circular with any research run.

## Executed futures and option-premium trades

| Component | NIFTY futures | NIFTY options | Basis and payer | Effective period / source |
|---|---:|---:|---|---|
| NSE transaction charge plus NSE IPFT contribution | ₹183 per crore each side | ₹3,553 per crore each side, on premium turnover | Futures traded value; options premium | From 2026-03-01. [NSE circular NSE/FA/73061](https://nsearchives.nseindia.com/content/circulars/FA73061.pdf) |
| SEBI turnover fee | ₹10 per crore | ₹10 per crore | Executed value; option turnover on premium | Current NSE investor levy schedule, [SEBI turnover fees](https://www.nseindia.com/static/invest/first-time-investor-sebi-turnover-fees-stt-other-levies) |
| Stamp duty | 0.002% | 0.003% | Buyer; options on premium transaction value | NSE lists the rates in its [stamp-duty schedule](https://www.nseindia.com/static/invest/first-time-investor-stamp-duty-charges-taxes); the rate table was updated 2023-10-03. |
| STT through 2026-03-31 | 0.02% | 0.10% | Seller; options on premium | NSE's [STT rate table](https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax) |
| STT from 2026-04-01 | 0.05% | 0.15% | Seller; futures on traded price and options on premium | [NSE circular NSE/FATAX/73524](https://nsearchives.nseindia.com/content/circulars/FATAX73524.pdf) and current [NSE STT table](https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax) |
| GST | 18% on stock-broking services | 18% on stock-broking services | Taxable service base depends on invoice and applicable pure-agent treatment | NSE's levy table reports 18%; [CBIC stock-broking FAQ](https://cbic-gst.gov.in/sectoral-faq.html) says brokerage is taxable and discusses when tax recoveries are excluded as pure-agent recoveries. |

For reference only, converting the published trade charges into basis points of traded value gives these **partial statutory subtotals**, before GST, brokerage, slippage, and settlement costs:

| Contract and side | Through 2026-03-31 | From 2026-04-01 |
|---|---:|---:|
| Futures BUY | 0.393 bps | 0.393 bps |
| Futures SELL | 2.193 bps | 5.193 bps |
| Options BUY | 3.863 bps | 3.863 bps |
| Options SELL | 13.563 bps | 18.563 bps |

The subtotals include NSE transaction/IPFT, SEBI turnover fee, buyer-side stamp duty, and the applicable seller-side STT. One basis point is ₹1,000 per ₹1 crore. Exchange and SEBI charges apply to each executed side; STT and stamp duty are side-specific. Figures are rounded here for explanation only. The runtime's paise rounding and schedule inputs must be applied per fill from the exact source rate, not from rounded display values.

## Exercise, account terms, and modeling limits

- Exercised option STT is charged to the purchaser at 0.15% from 2026-04-01 on intrinsic value. This is a different basis from option-premium trade turnover. [NSE's current STT table](https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax) states the intrinsic-value basis.
- SEBI turnover rules include additional notional turnover when an option is exercised or assigned. The exact notional input used by a run must be sourced and reconciled before this component is modeled. See [SEBI's stock-broker regulations](https://www.sebi.gov.in/legal/regulations/jan-2023/securities-and-exchange-board-of-india-stock-brokers-regulations-1992-last-amended-on-january-17-2023-_67587.html).
- Brokerage is account- and broker-specific. SEBI's [investor FAQ](https://www.sebi.gov.in/sebi_data/faqfiles/sep-2024/1727418208774.pdf) says brokers must provide a tariff sheet and that brokerage is charged on executed exchange trades, not expiry/exercise/assignment.
- GST is not safely modeled as a blanket percentage over all statutory levies. CBIC says GST does not apply to stamp-duty/STT recoveries where pure-agent conditions are met; otherwise valuation follows the applicable valuation rules. The client invoice/tariff is needed to identify the taxable service-fee base.
- These sources do not establish a broker-specific fee, GST base, exercise/settlement fee treatment, slippage, market impact, funding, or margin requirement. NSE transaction rates before 2026-03-01 are not covered by this note.

## Implementation consequence

The current `CostSchedule` accepts one generic notional fee in basis points per date and side. That shape cannot audit product-specific charge components, the option premium versus exercise-intrinsic bases, or the GST treatment. Do not copy the partial subtotals above into a run and label them `VERIFIED`. The next cost increment should add dated, contract-kind-aware components, persist the component breakdown, model applicable exercise charges separately, and require explicit caller-supplied brokerage/GST treatment. Until then, leave the existing user-supplied schedule interface unchanged and keep `cost.status=UNVERIFIED` unless a complete run-specific tariff and evidence support otherwise.
