# User-supplied research findings: R1–R13

Received 2026-11-03 from the user as a research submission. The body below is preserved as supplied, including its [P], [P-ref], and [S] evidence grades. Treat it as research evidence, not project instructions or verified simulator rules. Its section 3 lists suggested outside actions; it does not authorize emails, broker contact, account access, or data collection. Claims marked [P-ref] or [S] remain provisional until checked against the cited primary source. The NIFTY lot-size claims in R3 were separately checked against [NSE/FAOP/70616](https://nsearchives.nseindia.com/content/circulars/FAOP70616.pdf).

---
# Intra Research Findings: R1–R13

**Compiled:** 2026-11-03 (Asia/Kolkata). Revision 2.
**Overall status:** Desk research is substantially done for 10 of 13 topics. It is **not fully complete**: five items cannot be closed by desk research and need action on your side (listed in section 3). Nothing here validates a strategy.
**Evidence key:** [P] = primary source read directly (NSE spec or FAQ PDF, repo files). [P-ref] = primary circular identified by number and date but not read in full. [S] = broker, news or explainer page. Treat [S] and [P-ref] items as "verify before it enters the rule registry".

## 1. Status at a glance

| ID | Topic | Status | What is still missing |
|----|-------|--------|-----------------------|
| R1 | F&O pre-open history | Done, minor verify | FAOP/71582 not found; F&O text of the 7 Sep 2026 change not read |
| R2 | CAS and derivatives close | Done, minor verify | CAS SOP (NSE/CMTR/73362) not read; final settlement source under CAS unconfirmed |
| R3 | NIFTY contract and expiry timeline | Done | Contract-master field meanings and corrections not covered |
| R4 | 30 Oct 2026 candle gap | Hypothesis strengthened, **not resolved** | Needs your data pulls (section 3) |
| R5 | SmartAPI docs and terms | Done, with unknowns | Data-retention and research rights unknown |
| R6 | Retail-algo obligations | Mostly done | OPS counting rules and kill-switch text rest on secondary sources |
| R7 | Historical order-book data | Done | Tariff and licence terms unknown; needs a request to NSE |
| R8 | Clock, reconnect, recovery | Partial | No Indian clock-drift rule found for retail clients |
| R9 | Fees, taxes, settlement | Mostly done | Broker tariff, exercise and settlement charges not covered |
| R10 | SPAN, ELM, margin | Partial | Public historical archive unconfirmed |
| R11 | Option-pricing validation | Plan only | Conventions below are from standard references, not re-verified here |
| R12 | Statistical backtest evidence | Done | Method references confirmed; thresholds are your decision |
| R13 | Signal-to-execution boundary | Done, with new hazards | No S01–S03 markout results exist yet to review |

## 2. Findings

### R1. F&O pre-open [P-ref, S]

- NSE/FAOP/71092 (2025-11-03): 15-minute call auction for index and stock futures, live 2025-12-08. Current-month futures; next-month futures join in the last five trading days before expiry. Options and spreads excluded.
- Original phases: entry 09:00–09:08 with a random close between the 7th and 8th minute; matching 09:08–09:12; buffer 09:12–09:15. Opening price = equilibrium price; if none, the first normal trade sets it. Stop-loss and IOC orders not permitted in pre-open. Market orders convert to limit at the opening price; unmatched limit orders carry over.
- **Revised from 2026-09-07** (reported for equity and equity derivatives): market orders allowed only 09:00–09:05; limit orders only from 09:05; matching 09:10–09:12 (replaces 09:08–09:12); buffer 09:12–09:15. References: NSE/CMTR/74969 (2026-07-01), SEBI circular of 2026-08-28, NCL/CMPT/76060 (2026-08-31).
- Historical order data tags pre-open records `PO` for F&O from 2025-12-08 (spec v1.17) [P].
- **Open:** FAOP/71582 and any amendments; whether the random close still applies in the 09:05–09:10 limit-only phase (sources differ).

### R2. Closing Auction Session and derivatives close [P-ref, S]

- NSE/FAOP/74467 (2026-05-29); live 2026-08-03. CAS 15:15–15:35 for F&O stocks only. Equity derivatives trade 09:15–15:40. Post-close session 15:50–16:00.
- Derivatives closing price still the VWAP of trades 15:10–15:40.
- NSE and BSE run separate auctions; reference and closing prices can differ.
- **Index behaviour** [S, several consistent sources]: the "actual" Nifty stays at its last continuous-session print (about 15:15) during CAS by design; a separate **indicative index close** updates through the auction. On 2026-08-03 the index was about 24,573 at 15:15 and closed at 24,774.30. One source reports an average 15:30-versus-15:15 difference of about +0.42% over the first four CAS sessions. On 2026-09-08 the indicative level reportedly ranged roughly 23,375–23,806 before a close of 23,635.10.
- **Conflict:** one explainer puts continuous trading's end at 15:30; NSE's own page and most sources say 15:15 for F&O stocks. Use the SOP to settle it.
- **Open:** which value is the final settlement price for index F&O on expiry days under CAS, and the timing relative to the 15:40 derivatives close. This affects the Java simulator's expiry settlement policy.

### R3. NIFTY contract and expiry timeline [P-ref, S]

- Lot size 75 → 65. Circular NSE/FAOP/70616 was **issued 2025-10-03**; the new lot applies to newly introduced contracts from **2025-10-28 (EOD)**. The earlier "conflict" in dates was issue date versus effective date.
- Weekly: last 75-lot expiry 2025-12-23, first 65-lot expiry 2026-01-06. Monthly: last 75-lot 2025-12-30, first 65-lot 2026-01-27. Quarterly and half-yearly revised from 2025-12-30 (EOD).
- **Design point:** lot size belongs to the contract (expiry), not the trade date. Existing contracts run off at their old lot while new ones carry the new lot.
- Weekly expiry on Tuesday; holidays move expiry to the prior trading day. Earlier history reported as 25 → 75 → 65.

### R4. 2026-10-30 candle gap (unresolved) [P repo, S]

Repo facts: 361 rows (09:15–15:28), reproduced by a 15:10–15:30 request returning 15:10–15:14 plus 15:28 only. 361 = 360 labels from 09:15 to 15:14, plus one stray 15:28 bar.

What the new evidence adds:
- 2026-10-30 is a Friday, after the 2026-08-03 CAS go-live. The actual index is described as frozen at the 15:15 print during CAS, so a missing 15:15–15:27 run is **consistent with a regime effect rather than a random SmartAPI fault**.
- This does not explain 15:28. It could be an indicative-index value, a different series, or an artefact. Source semantics are still unknown.

Tests that would close it (need your data):
1. Pull NIFTY 1-minute candles for several sessions before 2026-08-03 and several after; compare completeness from 15:10 to 15:30.
2. Inspect the 15:28 bar (OHLC, volume, equal to the last close or not) and compare with NSE's published official and indicative values for that day.
3. Pull a complete 2026-11-02 session.
4. Cross-check with a 5-minute request and NSE's historical index data.

Rule stays: preserve the gap, do not interpolate, do not assert cause until the tests are done.

### R5. SmartAPI documentation and terms [P repo, S]

- Repo matrix already records: historical windows (1-minute 30 days / 8,000 records), 3 requests/second documented, WebSocket heartbeat every 30 seconds, three connections per client code, 1,000 subscriptions per connection, best-five depth only, dummy received-time field, no index sequence numbers, static IP applied to all app types including data-only (moderator, Oct 2026).
- Newer forum reports (Oct 2026): getCandleData limit reduced from 150 to 120 requests/minute per support; rate-limit errors at very low request rates. Budget for the lower figure.
- Expired F&O history: support says not provided; the latest explicit answer is about two years old. Confirm in writing.
- **Unknown:** retention, redistribution and research rights; reconnect, replay and duplicate semantics. Mark unknown.

### R6. Retail-algo obligations [P NSE FAQ, S]

From the NSE FAQ (NSE/FAQ dated 2025-11-03) [P]:
- **Static IP** is required only for the "tech-savvy" client who uses an API to place orders. Angel One's own policy appears broader.
- **All orders arriving through a client API count as algo orders and need tagging**, even below the 10 OPS threshold (Annexure I, para 2.8 of NSE/INVG/69255, 2025-07-22).
- **Hosting:** strategies run on the broker's servers; tech-savvy clients may host at their own static IP.
- **Tag format** for client direct API/IBT/STWT: first 12 digits `444444444444`, 13th digit 0, 2 or 4.
- **Market orders are not permitted for algos** (point 8.1.1.12 of NSE/MSD/67753, 2025-04-29). The IOC prohibition quoted in the FAQ is for the commodity segment; whether IOC is allowed for equity-derivative algos is not stated there. Verify before relying on the simulator's IOC modes as realistic.
- Tech-savvy clients bear the strategy outcome; the member keeps RMS responsibility. Tech-savvy individuals are not required to join the monthly mock sessions.
- Circular chain: SEBI 2025-02-04 (SEBI/HO/MIRSD/MIRSD-PoD/P/CIR/2025/0000013); NSE/INVG/67858 (2025-05-05); NSE/MSD/67753 (2025-04-29); NSE/INVG/69255 (2025-07-22); corrigendum NSE/INVG/70309 (2025-09-19).
- Threshold: 10 orders per second per exchange or segment, initially; brokers may set stricter limits. Framework mandatory from 2026-04-01.

**Open:** a broker staff reply on a public forum says modifications count toward the 10 OPS limit [S]; cancellations are reported as counting too. Confirm in NSE/INVG/69255. Kill-switch text and broker-versus-client responsibility detail not read.

### R7. Historical order-book and execution data [P NSE spec v1.19]

NSE Data & Analytics sells **Historical Order & Trade Data** for CM, F&O, CD and COM. Specification v1.19 (2026-08-03) was read directly.

- **F&O order records:** order number, jiffy timestamp (65,536 jiffies per second from 1 January 1980), buy/sell, activity type (1 entry, 3 cancel, 4 modify), underlying, instrument type, expiry, strike, option type (CE/PE European, FF future), quantity in lots, limit price in paise, trigger price, market flag, stop-loss flag, IOC flag, spread/combination type, algo indicator (algo, non-algo, via SOR), client identity flag (custodian, proprietary, client).
- **F&O trade records:** trade number, jiffy time, contract identifiers, price, lots, **buy order number and sell order number**, and algo and client flags for each side. There is **no explicit aggressor flag**; aggressor may be inferable from order numbers and timestamps (unverified).
- Disclosed quantity is not applicable in F&O. F&O has `PO` but **no `CA` record indicator**; `CA` was added to the cash market only.
- **Two versions.** Trim (from 2026-05-18): fewer fields, available daily for the current date. Full: orders after a **90-calendar-day** lag, trades after **30 calendar days**.
- **Delivery:** SFTP (needs your SSH RSA public key and a static public IP; previous three days only), a downloader client, or AWS S3 in the Mumbai region. Files arrive about 01:00–03:00 IST.
- **Access:** subscription by email to marketdata@nse.co.in; an undertaking or agreement document applies. **Tariff and licence terms not read.** Free sample files for F&O order and trade data are linked from NSE's page and can be used to test a parser.
- **Availability arithmetic:** full orders for 2026-08-03 (CAS go-live) became available about 2026-11-01; full orders for 2026-10-30 about 2027-01-28; full trades for 2026-10-30 about 2026-11-29.
- **What it can and cannot support:** order events with order numbers should support book reconstruction in principle. Whether it supports queue-position and fill claims has not been tested; validate on the sample files first. It does not provide your own order's queue position or latency.
- **Real-time:** NSE's real-time page lists F&O Level 1 and 2 feeds; a vendor post of roughly 2023 says NSE stopped tick-by-tick for F&O. Prospective order-level capture of F&O is therefore likely unavailable; five-level snapshots remain the ceiling.
- Third-party vendors advertise historical tick data (TrueData, GoCharting); redistribution and licence status unverified.

### R8. Clock, reconnect and recovery operations [S]

- **No Indian clock-drift requirement for retail API clients was found.** International benchmarks, for design only: FINRA 50 ms; MiFID II RTS 25 at 1 ms for non-HFT algorithmic trading and 100 µs for HFT; CIRO ±50 ms.
- India: the CERT-In directive of 2022-04-28 requires entities in scope to sync to NIC or NPL NTP servers (samay1.nic.in, samay2.nic.in, time.nplindia.org); applicability to a private research box is unclear.
- NSE's own fair-access policy describes an in-exchange time server; no client tolerance was found.
- SmartAPI: heartbeat every 30 seconds; a third-party SDK says SmartWebSocketV2 handles reconnect and resubscription. Replay, sequence and duplicate guarantees are undocumented; treat as unknown.
- **Recommendation:** adopt a stricter internal target (for example, log local receive time, measure offset against an NTP source each session, and alert past a threshold you set), and keep read-only feed metrics separate from order-path latency.

### R9. Fees, taxes and settlement costs [S, P-ref]

| Date | Change | Source |
|------|--------|--------|
| 2026-03-01 | NSE exchange charge for futures 0.00173% → 0.00183%; options premium 0.03503% → 0.03553% (NSE/FA/73061, 2026-02-27); some sources say this includes the investor-protection fund, others list IPFT separately | [P-ref, S] |
| 2026-04-01 | STT futures 0.02% → 0.05% sell side; options premium 0.10% → 0.15% sell side; exercise 0.125% → 0.15% of intrinsic value (NSE/FATAX/73524, 2026-03-31) | [P-ref, S] |

- Stamp duty (buy side): futures 0.002%, options 0.003% of premium. SEBI turnover fee ₹10 per crore. GST 18% on brokerage + exchange charge + SEBI fee, not on STT or stamp duty [S].
- **Open:** whether IPFT is folded into the 0.00183% / 0.03553% figures (sources conflict); exercise and settlement charges; Angel One's tariff (use your contract note).

### R10. SPAN, ELM and broker margin [P NSE page, S]

- NSE's public SPAN page (last updated 2024-07-04) gives parameters: index price scan range 6σ scaled by √2, capped at 9.3% of the underlying price; volatility scan 25% of annualised EWMA volatility with a 4% minimum; calendar spread charge 1.75% of the far-month contract for index derivatives. These may have changed since; the page is stale.
- Daily SPAN files (`nsccl.YYYYMMDD.s.spn.gz`) and exposure files (`ael_DDMMYYYY_parallel.csv`) are published to members via the extranet.
- **Open:** whether any public historical archive exists, its coverage, and correction behaviour. Keep margin proxies labelled as proxies, and do not treat SPAN as ELM-inclusive.

### R11. Independent option-pricing validation (plan) [not verified in this session]

- NSE index options are European (option types CE/PE in the order spec) [P], so BSM or Black-76 are the applicable families. Black-76 uses the same-expiry future as the forward.
- Conventions to pin down in writing: expiry timestamp (weekly expiry at 15:30 per one source; confirm against the CAS settlement answer), day-count basis, rate source, dividend treatment for spot-based BSM, and Greek units (per 1% volatility or per 1.0; per day or per year).
- Validation set: put-call parity, no-arbitrage bounds, finite-difference Greeks, boundary cases (very short expiry, deep in or out of the money, zero volatility), and comparison against a separately written implementation. The ZIP benchmark remains synthetic vectors, not an oracle.

### R12. Statistical backtest evidence [S, repo spec]

- Confirmed references: Bailey and López de Prado on the **deflated Sharpe ratio**; Bailey, Borwein, López de Prado and Zhu, "The Probability of Backtest Overfitting", *Journal of Computational Finance* 20(4), 2017 (DOI 10.21314/jcf.2016.322; SSRN 2326253), which introduces CSCV. Also Politis and Romano (1994) and Romano and Wolf (2005), cited in the repo spec.
- The Wave A spec already pre-registers rules, a 60/20/20 chronological split, a stationary bootstrap and Bonferroni across six comparisons. Gaps to add:
  - **Trial ledger:** count every variant considered across the whole programme (S01–S10 chosen from 15 books), not only the three fixed rules, before applying deflation.
  - **CSCV/PBO** needs many configurations; with three fixed rules it is not meaningful. Use it only if you later search parameters.
  - **Regime-aware split** (see R13) and a power review.
  - Overlapping labels: 5- and 30-minute diagnostics overlap decision points; keep them descriptive, as the spec already does.

### R13. Signal-to-execution boundary [P repo, S]

- Only a partial-session smoke run exists (104 candles on 2 Nov, to 10:58). There are no S01–S03 markouts yet.
- **S03 prior-close hazard (new).** Under CAS the official close is set about 15:35 and can differ from the 15:15 print by tens of basis points. The spec computes the gap from the "prior regular-session close". If that is taken from the last SmartAPI candle (15:14), the gap input is biased on post-2026-08-03 sessions by an amount comparable to the signal itself. Source the prior close from NSE's official index close series.
- **Session-end definition.** Index data effectively stops at 15:15; futures trade to 15:40. Define the last eligible S02 decision time as 15:00 so that its 15-minute label ends by 15:14, which makes S01 and S02 robust to the gap.
- **Regime breaks inside the sample:** 2025-12-08 (F&O pre-open), 2026-08-03 (CAS), 2026-09-07 (pre-open realignment, which changes how the opening price used by S01 and S03 is formed). A 60/20/20 split will put the holdout wholly in the newest regime.
- **Power (rough, assumes a 0.15% standard deviation per 15-minute move, not measured):** a 20-session holdout gives a 99.17% interval of about ±9 bps; 50 sessions about ±5–6 bps.
- **Smallest next slice:** (1) R4 pre/post tests and a regime-dated calendar; (2) count obtainable complete sessions; (3) S01–S03 signal-only markouts as specified, with the fixes above; (4) only if one passes, a prospective recorder for the active-month future, started after data rights are confirmed, used first as a pipeline check through the Java engine's next-open mode.

## 3. Blocked on you (cannot be closed by desk research)

1. **R4:** run the data pulls in section 2.
2. **R5:** get written confirmation from Angel One on data retention and research rights, and on expired-F&O history.
3. **R7:** email marketdata@nse.co.in for tariff and the undertaking; test the free F&O sample files.
4. **R9:** reconcile one redacted contract note against the rates above.
5. **R10:** ask NSE Clearing or your broker whether historical SPAN and ELM files are available outside the member extranet.

## 4. Regime timeline for the effective-dated registry

| Date | Change | Confidence |
|------|--------|------------|
| 2025-10-28 (EOD) | 65-lot applies to newly introduced NIFTY contracts | Medium-high |
| 2025-12-08 | F&O pre-open begins | High |
| 2025-12-30 | Quarterly and half-yearly contracts move to 65 lot | Medium |
| 2026-01-06 | First weekly expiry with 65 lot | Medium-high |
| 2026-01-27 | First monthly expiry with 65 lot | Medium |
| 2026-03-01 | NSE F&O exchange charge revision | Medium |
| 2026-04-01 | STT increase; retail-algo framework mandatory | Medium |
| 2026-08-03 | CAS live; derivatives close at 15:40; index frozen at 15:15 print | Medium-high |
| 2026-09-07 | Pre-open realignment (market orders until 09:05; matching 09:10–09:12) | Medium |

## 5. Sources

- NSE CAS page: https://www.nseindia.com/static/products-services/closing-auction-session
- NSE FAOP/75472 (cites FAOP/74467): https://nsearchives.nseindia.com/content/circulars/FAOP75472.pdf
- NSE FAOP/71092 (hosted copy): https://stocko.in/wp-content/uploads/2025/11/FAOP71092.pdf
- NSE Historical Order and Trade Data spec v1.19: https://nsearchives.nseindia.com//web/mediaattachment/2026-09/NSE_Hist_Order_Trade_Data_1.19_20260902144045.pdf
- NSE paid historical data page: https://www.nseindia.com/static/market-data/eod-historical-data-subscription
- NSE paid real-time data page: https://www.nseindia.com/static/market-data/real-time-data-subscription
- NSE retail-algo FAQ: https://nsearchives.nseindia.com/web/sites/default/files/inline-files/FAQ_Retail_Algo_03112025_NSE.pdf
- NSE/INVG/67858: https://nsearchives.nseindia.com/content/circulars/INVG67858.pdf
- NSE SPAN parameters page: https://www.nseindia.com/static/products-services/equity-derivatives-span-risk-parameter-files
- Zerodha on CAS (indicative index close): https://zerodha.com/z-connect/general/everything-you-need-to-know-about-closing-auction-session-cas
- CAS and Nifty minute data (secondary): https://www.multibagg.ai/market-pulse/articles/cas-impact-nifty-minute-data-cmsz40wuh4ik30zqmuw8gjlbj
- Pre-open change from 2026-09-07 (secondary): https://tradebetter.5paisa.com/t/changes-to-the-pre-open-session-in-equity-equity-derivatives-w-e-f-7th-sep26/967
- Lot size circular coverage (secondary): https://fyers.in/notice-board/revision-in-index-derivatives-contracts-lot-size/ and https://flattrade.in/kosh/nse-revises-derivatives/
- Charges and STT (secondary): https://onetradejournal.com/tools/total-trading-cost-calculator and https://zerodha.com/charges/
- SmartAPI forum: https://smartapi.angelone.in/smartapi/forum/topic/5708/ and https://smartapi.angelone.in/smartapi/forum/topic/4012/
- Bailey et al., PBO: https://papers.ssrn.com/abstract=2326253
- CERT-In NTP directive explainer: https://www.medianama.com/2022/05/223-cybersecurity-directive-time-sync/
- Repo: https://github.com/Mscient/Intra (README, INSTRUMENT-DATA-CAPABILITY-MATRIX.md, WAVE-A-SIGNAL-SPEC.md)

## 6. Follow-up primary-source checks (2026-11-04)

These checks supplement, and do not overwrite, the compiled findings above.

| Topic | Primary source read | Verified from the document | Still unresolved |
|---|---|---|---|
| R1 pre-open | [NSE/FAOP/71582](https://nsearchives.nseindia.com/content/circulars/FAOP71582.pdf), dated 2025-12-02 | The F&O order log and split order log add book type 8 / `PO` for pre-open records effective 2025-12-08. The circular explicitly continues NSE/FAOP/71092 dated 2025-11-03. | FAOP/71092's full phase/order eligibility text and the alleged September 2026 revision were not verified. No session schedule was changed in the simulator. |
| R2 CAS rollout | [NSE/FAOP/75472](https://nsearchives.nseindia.com/content/circulars/FAOP75472.pdf), dated 2026-07-30 | Page 1 says F&O trading-modality changes associated with equity CAS go live 2026-08-03 and identifies NSE/CMTR/73362 (2026-03-18) and NSE/FAOP/74467 (2026-05-29) as the governing SOP/change circulars. | The full SOP and price-setting/expiry settlement treatment remain unverified; the 75472 mock-session table is not itself evidence for normal live phases. Do not encode settlement policy from this check. |
