# Session 832ed4ad 3a4e 4a0b 8ebf 74ad92a93abe

- Chat ID: 832ed4ad-3a4e-4a0b-8ebf-74ad92a93abe
- Date: 2026-11-01 (Asia/Kolkata)
- Objective: Research ten systematic trading and execution archetypes relevant to the Nifty intraday paper system and record the results in the project repository.
- Status: Comparative public-source review prepared. No system has been backtested on current Nifty data or validated through the project's broker API.

## Work completed

- Reviewed the current project README, book tracker, session protocol, session index, and recent research-session records.
- Researched ten system archetypes: opening-range breakout, intraday momentum, opening reversal, VWAP-deviation mean reversion, Nifty spot-futures lead-lag/basis, VWAP/TWAP execution, pairs trading, order-flow imbalance, limit-order market making, and delta-hedged options volatility.
- Wrote a comparative research note covering each system's mechanics, published evidence, required data and execution assumptions, project fit, and evidence limits.
- Added a system tracker that marks each item as source-reviewed or hypothesis-only and states the data gap and next action.
- Updated README links and repository structure for the systems research area. The book tracker was not changed because no book's access or reading status changed.

## Evidence and access limits

- Sources include peer-reviewed papers, an NSE research paper, an NBER working paper, an arXiv paper, and the BIS Markets Committee report.
- Most empirical results use U.S. or Taiwanese markets; Indian spot-futures studies use different older periods and report different lead directions.
- This is a literature review, not a backtest. No current Nifty market data, broker fills, current cost schedule, or order-event feed was analyzed.
- VWAP-deviation mean reversion remains a hypothesis in this review; no strong Nifty-specific supporting study was identified.
- No system is presented as profitable, ready for live use, or validated for the retail broker route.

## Sources consulted

- https://doi.org/10.1109/ACCESS.2019.2899177
- https://doi.org/10.1016/j.jfinec.2021.04.029
- https://doi.org/10.1016/j.jbankfin.2004.04.006
- https://doi.org/10.1007/s40622-016-0137-1
- https://doi.org/10.17010/ijf/2020/v14i4/151705
- https://nsearchives.nseindia.com/content/research/res_paper_final185.pdf
- https://www.bis.org/publ/mktc13.htm
- https://www.nber.org/papers/w7032
- https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1611623
- https://arxiv.org/abs/1011.6402
- https://doi.org/10.1080/14697680701381228
- https://doi.org/10.1016/j.jbankfin.2017.05.006
- https://doi.org/10.1111/j.1540-6261.2004.00647.x

## Next actions

1. Build the instrument and data-capability matrix, including available quote granularity, timestamps, order lifecycle fields, costs, and data-retention terms.
2. Compare S01-S04 on a common instrument and evaluation protocol with conservative fills, actual applicable costs, and chronological out-of-sample periods.
3. Build the S06 order-lifecycle paper simulator and report fill, completion, latency, and shortfall measures separately from signal results.
4. Keep S08-S10 deferred until adequate event-level quote, queue, or option-surface data are available.
