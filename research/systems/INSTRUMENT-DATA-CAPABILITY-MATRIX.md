# Instrument and Data Capability Matrix

**Status:** desk research plus a user-provided historical response. A read-only request returned `SUCCESS` from the user's run environment on 2026-11-01. The 361-row sample has a 14-minute timestamp jump near the close; the full-session data gate is unresolved. SmartAPI portal inspection remains pending; the user reports the static IP is set up.
**Reviewed:** 2026-11-01 (Asia/Kolkata).
**Broker/API selected by user:** Angel One SmartAPI.
**Scope:** select data and instruments for the isolated S01-S10 research lab. This matrix does not authorize trading.

## Angel One SmartAPI findings

### Historical data

- Angel One's FAQ currently describes SmartAPI and historical data as free of API charges. This does not include brokerage or exchange/statutory charges on trades, and does not establish any rights to redistribute data.
- The Historical API supports NSE (including indices) and NFO (futures/options) symbols. The documented candle response is `[timestamp, open, high, low, close, volume]` with an ISO timestamp example carrying `+05:30`. Intervals are 1, 3, 5, 10, 15, and 30 minutes, 1 hour, and 1 day.
- Published per-request windows are 30 days/8,000 records for 1-minute data, 60 days for 3-minute, 100 for 5- and 10-minute, 200 for 15- and 30-minute, 400 for hourly, and 2,000 for daily. These are request limits, not a promise of total lookback. The documented historical endpoint rate limit is 3 requests/second; confirm current account limits in the portal.
- SmartAPI support identifies NIFTY 50 token `99926000` and says index candle volume is zero because the index itself is not traded. Use NIFTY index candles for price-signal research, not VWAP or executable fills.
- SmartAPI's public support responses say historical data for expired F&O contracts is not provided. The latest explicit answer found is older, so confirm with Angel One before relying on it; until confirmed otherwise, treat this as a hard limitation. It prevents a normal multi-expiry historical futures/options backtest using SmartAPI alone.
- The daily instrument master provides token/symbol mappings. Capture a dated copy for every data pull; do not hard-code a token or assume the current master maps expired contracts.

### Live quotes and streaming

- The REST FULL quote mode documents LTP, OHLC, last trade quantity, exchange feed and trade times, average price, traded volume, open interest, circuit limits, total buy/sell quantities, and best-five bid/ask depth. The docs say up to 50 symbols per request, but conflict on the rate: the market-data section says 1 request/second while the rate table lists 10. Use the conservative 1 request/second until Angel confirms the current client-code limit. This is a snapshot endpoint, not a historical quote archive.
- SmartWebSocket V2 supports live streaming. Published limits are three concurrent connections per client code and 1,000 token/mode subscriptions per connection. The client must send a heartbeat every 30 seconds. Current depth is best five; SmartAPI announced that 20-depth support was deprecated effective April 25, 2025.
- The WebSocket packet's “received time” is documented as a dummy field; the docs also say index feed sequence numbers are unavailable. Record a local receive timestamp and monitor reconnects/gaps. Exchange/last-trade timestamps are useful but do not replace a local receive clock.
- Five-level snapshots do not expose order-by-order queue position, all additions/cancellations, or a historical depth archive. They can support prospective quote capture and limited top-of-book research, but do not satisfy the isolated plan's data needs for a realistic market-making queue model.

### Orders, access, and paper testing

- SmartAPI offers an order-update WebSocket, with a documented maximum of three connections per client code. A successful place-order response returns an order ID; it is not the final order status. Any later paper system must reconcile updates against order/trade-book state.
- The SmartAPI docs list order APIs with a combined rate limit of 9 requests/second for place/modify/cancel. The docs also say market orders are converted to limit orders under MPP; the NSE retail-algo FAQ says algo market orders are not permitted. Confirm the applicable order types, limits, and tagging for this account before any route is designed.
- Angel One's exchange-regulations page states API order execution is accepted only from a registered static IP from April 1, 2026. A SmartAPI moderator further stated in October 2026 that Angel applies static IP to all SmartAPI app types, including historical/data-only access. NSE's FAQ describes the exchange static-IP requirement for the Tech-Savvy API category; Angel's account policy may be broader. Confirm the requirement for this account and intended API app in the Angel portal/support before planning access.
- No current paper/sandbox environment is documented in the SmartAPI FAQ/docs reviewed. Angel's older forum response said no paper account was available then; treat a broker sandbox as unconfirmed. The project should use the existing local, offline simulator plan and must not use live orders as a test substitute.

## Candidate instruments and minimum evidence

| Candidate | Intended role | SmartAPI evidence | Readiness and limitation |
|---|---|---|---|
| NIFTY 50 index/reference series | Price signal and benchmark for S01-S03 | NSE index token `99926000` is documented; historical OHLC candles are available through NSE segment. Volume is zero. | The user supplied a 361-row response for 2026-10-30, from 09:15 to 15:28 IST. It has no duplicate timestamps or OHLC violations; every volume is zero. There is one 14-minute jump from 15:14 to 15:28. If the request covered 09:15-15:30 and timestamps label bar starts, 14 expected minute labels are missing (15:15-15:27 and 15:29). Request parameters were not included, so confirm them and diagnose the gap before using this data. Not executable and not suitable for S04 VWAP. |
| NIFTY index futures, dated contracts | Possible executable proxy for Wave A; S05 | NFO candles and current live quotes are supported. SmartAPI support says expired F&O history is not provided. Live streaming supplies best-five depth, not archived quotes. | Historical multi-expiry P&L is not testable from SmartAPI alone. Could begin prospective capture; preserve each contract and expiry separately. |
| NIFTY index options | S10, later | NFO historical candles/live quotes are supported for contracts available to the API; current option Greeks endpoint is documented. Expired-contract history is reported unavailable. | No retrospective option-surface/expiry backtest from SmartAPI alone. Requires a licensed external archive or a forward capture spanning expiries. Greeks endpoint is not documented as historical. |
| Top-five quote/depth stream | Prospective cost/fill observations; limited S08 research | Live REST FULL quote and WebSocket V2 include up to five bid/ask levels. | No historical quote/depth archive or order-level queue events. S08 is limited to a prospective snapshot/quote study; S09 remains not testable with these fields alone. |
| Broker order lifecycle | Future paper workflow; S06 remains offline first | Order-update WebSocket and REST order/trade status APIs are documented. | No sandbox verified. Start with a local simulator; keep order routing out of the research lab. |

## Experiment data gates

### Wave A — S01-S04

- S01-S03 can begin as **signal-only** tests on NIFTY index OHLC after a sample pull confirms timestamps, sessions, missing bars, and actual history coverage. Do not translate index-price returns into executable futures P&L.
- To test executable futures fills, SmartAPI historical candles may cover the currently available contract, but the inability to retrieve expired contracts makes multi-expiry history incomplete. Start prospective recording or obtain a licensed continuous futures/expired-contract archive before evaluating rolled futures performance.
- S04 is **not testable on NIFTY index candles** because index volume is zero. It needs dated traded volume and VWAP-appropriate prices for the actual traded instrument. SmartAPI active-contract futures volume may help going forward, but expired-contract history remains the blocker.
- Use the isolated plan's next-bar/next-quote fill rule for any bar-only signal run. Mark it as a bar-data approximation, not a realistic fill simulation.

### Wave B — S05-S07

S05 requires synchronized index/futures series and both-leg cost assumptions; missing expired futures history limits long-period basis tests. S06 remains an offline execution simulator. A prospective SmartAPI quote/order recorder may inform later fill assumptions, but it is not a sandbox. S07 requires a specific pair and two-leg data; no pair is selected.

### Wave C — S08-S10

S08 can only be considered as a prospective best-five quote study after timestamp/gap behavior is measured; SmartAPI does not document historical depth replay or index sequence numbers. S09 requires queue/order-event evidence absent from the documented five-level feed and remains not testable. S10 requires expired option contracts and an option surface; SmartAPI alone does not provide the retrospective expired-contract history needed.

## Required data dictionary

For each staged file or stream, record:

- source/vendor, API endpoint/version, product or entitlement, permitted purpose, retention terms, and acquisition date;
- exchange trading date, timezone, exchange timestamp, local receive timestamp, interval/event type, sequence/gap indicators, and clock assumptions;
- instrument ID, exchange symbol, segment, underlying, contract type, expiry, strike, option type, lot size, tick size, and the dated instrument-master file;
- OHLC, traded quantity/volume, open interest, bid/ask prices and sizes, available depth levels, quote age, trade price/quantity, and exact field meanings;
- raw-versus-derived status, missingness, duplicate handling, corrections, session boundary, and file hash.

Keep API keys, client IDs, TOTP seeds, authenticated payloads, and licensed raw market data outside Git. Do not send secrets in chat. Store experiment manifests without restricted data.

## Access and validation checklist

1. The user reports a static IP is set up, and the read-only historical request succeeded from the run environment. Verify in the Angel One portal that the IP is registered and mapped to the intended SmartAPI app; do not record the IP address or credentials here.
2. The user's 361-row response has valid OHLC rows and no duplicates, but a 14-minute jump near the close. Confirm the local request used NIFTY token `99926000`, `exchange=NSE`, and `interval=ONE_MINUTE`; rerun only 15:10-15:30 to see whether the gap repeats. Report the request range, returned row count, timestamps, and gap count only. Keep authentication headers private and raw market data out of Git. Do not assume bar-start/bar-end semantics until verified.
3. Verify if Angel One currently provides expired-contract history and sandbox access for this account; current public support material indicates expired F&O data is unavailable and no sandbox is confirmed.
4. If testing execution costs, begin a separate prospective recorder only after data terms and account access are confirmed; record local receive time and never send orders from the lab.
5. Select the first Wave A execution instrument only after the coverage gate. If only index candles are available, keep tests signal-only. Do not build a broker-connected test harness in HFT 2.

## Sources

- [SmartAPI FAQ: charges and data services](https://smartapi.angelone.in/faq)
- [SmartAPI API documentation: historical, quote, WebSocket, and order endpoints](https://smartapi.angelone.in/docs/MarketData)
- [SmartAPI published API rate limits](https://smartapi.angelone.in/docs/RateLimit)
- [Official SmartAPI Python SDK](https://github.com/angel-one/smartapi-python)
- [SmartAPI support: index candles, zero index volume, and expired-contract history](https://smartapi.angelone.in/smartapi/forum/topic/602/how-do-i-get-historical-index-data-for-nifty-sensex)
- [SmartAPI support: WebSocket depth reduction](https://smartapi.angelone.in/smartapi/forum/topic/5217/deprecation-of-20-market-depth-from-websocket-2-0-effective-april-25-2025/2)
- [Angel One: SmartAPI static-IP access update](https://smartapi.angelone.in/exchange-regulations)
- [Angel One SmartAPI moderator: static IP applied to data-only apps as well](https://smartapi.angelone.in/smartapi/forum/topic/5671/asking-for-static-ip-for-getting-api-for-historical-data/4)
- [Angel One SmartAPI forum: paper account response (older; current availability unconfirmed)](https://smartapi.angelone.in/smartapi/forum/topic/1120/how-to-test-the-logic-before-using-it-on-live-market-is-there-something-like-a-paper-trading-option-or-something/5?lang=en-GB)
- [NSE data sharing and usage policy](https://www.nseindia.com/static/market-data/nse-data-policy)
- [NSE retail-algo FAQ](https://nsearchives.nseindia.com/web/sites/default/files/inline-files/FAQ_Retail%20Algo_03112025_NSE.pdf)
- [NSE retail-algo framework page, updated May 2026](https://betanseapi.nseindia.com/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading)
