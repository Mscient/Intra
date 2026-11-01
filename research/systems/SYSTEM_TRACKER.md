# Trading System Research Tracker

Status reflects source review only. No system below has been backtested on current Nifty data or validated through the project's Angel One SmartAPI route. See [the instrument/data capability matrix](INSTRUMENT-DATA-CAPABILITY-MATRIX.md) for current API coverage and data gates.

| ID | System | Research state | Main data / execution gap | Next action |
|---|---|---|---|---|
| S01 | Opening-range breakout | Public-source review prepared | NIFTY index candles can support signals; futures expired history and historical fills are unavailable through SmartAPI alone | Start signal-only on index after sample quality review; collect prospective futures quotes before execution-cost testing. |
| S02 | Intraday time-series momentum | Public-source review prepared | Same history/fill limits as S01; chronological splits need enough clean index data | Define a small horizon set and test signal-only after confirming historical coverage; do not report futures P&L from index returns. |
| S03 | Opening reversal or gap fade | Public-source review prepared | Index candles support signal research; future contract mapping and fills are not historical | Define a non-lookahead rule; keep it signal-only until executable contract data are available. |
| S04 | VWAP-deviation mean reversion | Hypothesis review prepared; direct Nifty evidence not established | Index volume is zero; expired futures history unavailable through SmartAPI | Not testable on index. Consider prospective active-futures volume capture; no backtest until an adequate historical series is available. |
| S05 | Nifty spot-futures lead-lag and basis | India literature review prepared; samples/methods differ | Index plus active futures data available, but expired futures archive and synchronized quote history not established | Check paired data coverage; defer long-period basis P&L until expired contracts and synchronized prices are available. |
| S06 | VWAP/TWAP execution | Execution literature review prepared | SmartAPI order updates exist; no sandbox verified and historical quotes are absent | Keep offline; build the local simulator after parent-order inputs and prospective quote data are ready. |
| S07 | Pairs / relative-value trading | Public-source review prepared | Specific pair, two-leg history, and joint-fill evidence missing | Defer until a pair and both-leg historical data source are selected. |
| S08 | Order-flow imbalance | Public-source review prepared; not tested on Nifty | Prospective five-level WebSocket quotes only; no historical event archive and no index sequence numbers | Limit to a prospective data-readiness capture; otherwise mark not testable. |
| S09 | Limit-order market making | Model review prepared; no local performance evidence | Five-level depth without historical queue/order events or queue position | Not testable with SmartAPI fields alone; defer until suitable event-level data and simulator are available. |
| S10 | Delta-hedged options volatility | Public-source review prepared; no Nifty strategy validation | Expired option history unavailable through SmartAPI; historical surface and fills absent | Defer; requires licensed expired-contract history or a prospective multi-expiry data capture. |

Follow the isolation and evaluation requirements in [ISOLATED-TESTING-PLAN.md](ISOLATED-TESTING-PLAN.md). Start with a local, read-only sample review and the data gates in the matrix; keep all credentials out of the repository. Any prospective quote capture must remain separate from the lab's test code and must not route orders. Keep every candidate in research/paper status until realistic costs and fills are measured.

