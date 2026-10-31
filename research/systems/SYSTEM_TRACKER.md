# Trading System Research Tracker

Status reflects source review only. No system below has been backtested on current Nifty data or validated through the project's retail broker route.

| ID | System | Research state | Main data / execution gap | Next action |
|---|---|---|---|---|
| S01 | Opening-range breakout | Public-source review prepared | Nifty bars plus conservative quote/fill assumptions | Define a simple range, confirmation, stop, and timed exit for offline testing. |
| S02 | Intraday time-series momentum | Public-source review prepared | Time-aligned Nifty returns, costs, and out-of-sample splits | Compare a small predeclared set of horizons against reversal baselines. |
| S03 | Opening reversal or gap fade | Public-source review prepared | Separate overnight gap from intraday movement; spread/slippage | Test only after a non-lookahead confirmation rule is defined. |
| S04 | VWAP-deviation mean reversion | Hypothesis review prepared; direct Nifty evidence not established | Reliable volume, bid/ask fills, and trend/regime labels | Treat as a hypothesis and compare to momentum and ORB. |
| S05 | Nifty spot-futures lead-lag and basis | India literature review prepared; samples/methods differ | Synchronized spot/futures timestamps, executable two-leg quotes, carry | Re-measure on current data after instrument alignment. |
| S06 | VWAP/TWAP execution | Execution literature review prepared | Broker order states, partial fills, decision price, actual costs | Build order-lifecycle paper simulation and measure completion/shortfall. |
| S07 | Pairs / relative-value trading | Public-source review prepared | Two-leg data, hedge-ratio stability, partial-leg handling | Defer until a specific pair and joint-fill model exist. |
| S08 | Order-flow imbalance | Public-source review prepared; not tested on Nifty | Timestamped quote/trade updates and depth | Defer unless permitted broker data supports event-level measurement. |
| S09 | Limit-order market making | Model review prepared; no local performance evidence | Queue position, order events, cancel latency, inventory/fees | Study mechanics only; defer implementation. |
| S10 | Delta-hedged options volatility | Public-source review prepared; no Nifty strategy validation | Option surface, realized volatility, hedge fills, Greeks and costs | Defer until basic options and execution research are complete. |

The next research step is a data-capability and instrument-selection matrix, followed by comparable offline tests of S01-S04 and an order-lifecycle baseline for S06. Keep all candidates in research/paper status until realistic costs and fills are measured.
