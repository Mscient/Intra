# Instrument and Data Capability Matrix

**Status:** desk research only; no market-data files, broker connection, or experiment harness have been created.
**Reviewed:** 2026-11-01 (Asia/Kolkata).
**Scope:** select data and instruments for the isolated S01-S10 research lab. This matrix does not select a broker or authorize trading.

## What is confirmed

- NSE's published real-time product describes Level 1 (best bid/ask), Level 2 (up to five price levels), Level 3 (up to twenty levels), and tick-by-tick (full order book) data. Its direct feed is multicast over a dedicated leased line, or customers can use an authorized vendor. This is not assumed to be a free retail feed.
- NSE also lists 1- and 5-minute snapshot products, 15-minute delayed snapshots, and EOD/historical data products. NSE's EOD/historical page says F&O EOD order/trade data is delivered through SFTP and historical order/trade data is available through an online platform. A product being listed does not establish its price, license, completeness, or suitability for this project.
- NSE's data policy says the applicable agreement governs intended use and handling. Verify storage, replay, research/trading use, and redistribution terms before collecting or copying any licensed data.
- NSE's contract reference pages describe instrument identifiers, expiries, strike/tick information, and quantity-freeze references. Treat these as dated reference data: save the contract master used with each experiment instead of hard-coding today's values.

## Candidate instruments and minimum evidence

| Candidate | Intended role | Minimum data needed | Current evidence and gaps | Readiness |
|---|---|---|---|---|
| NIFTY 50 index/reference series | Signal and benchmark input for S01-S04 | Session calendar; dated, exchange-timestamped index observations or bars; source and interval metadata | NSE publishes index/reference information. An index series alone does not provide executable fills; broker history, granularity, and retention are unknown. | Candidate for signals only; not enough for execution-cost claims. |
| NIFTY index futures, one explicitly dated contract at a time | Possible common executable instrument for Wave A and S05 | Contract master; timestamped trades or bars; volume; best bid/ask for fill sensitivity; expiry and roll mapping; costs | NSE publishes contract specifications and F&O data products. No broker, API, history depth, quote granularity, or entitlement has been identified. Front-month selection is not yet approved. | Unselected; can be considered after broker/data review. |
| NIFTY index options | Deferred options research (especially S10) | Per-contract identifiers; expiry, strike, CE/PE, lot/tick; synchronized bid/ask and sizes; trades/volume; underlying futures/index; dated model inputs for Greeks/IV; expiry handling | NSE publishes option contract references and F&O products. No option quote-history source, surface completeness, timestamps, or retention terms are confirmed. A displayed chain alone is not a historical replay dataset. | Not ready for P&L testing. |
| Quote/depth/order events | S08 imbalance, S09 market making, and higher-fidelity fills | Exchange/source timestamp; local receive timestamp; sequence/gap/reconnect behavior; bid/ask prices and sizes; depth levels; trades and event types; queue/cancel information where relevant | NSE describes depth/tick products, but direct access path and cost are not established for this project. Broker API event coverage is unknown. | Mark S08/S09 not testable until the required event data are verified. |
| Broker order lifecycle | Later paper workflow; S06 execution simulation remains offline first | Submit/ack/modify/cancel/reject/partial/fill events; broker and exchange IDs; timestamps; requested/filled quantities; prices; rejection reasons; rate limits | Broker has not been named. No API, sandbox, order-event sample, or broker paper mode has been verified. | Blocked on broker selection and official API documentation. |

## Experiment data gates

### Wave A — S01-S04

Choose one signal series and one execution instrument, and record the mapping between them. For bar-based tests, require dated session data and a predeclared bar interval. S04 also needs a volume series suitable for its VWAP definition. If only bars are available, use the isolated plan's conservative next-bar/next-quote fill rule and label the result as a bar-data approximation. Do not treat an index close as an executable price.

### Wave B — S05-S07

S05 needs synchronized spot/index and futures inputs plus a two-leg cost/fill model. S06 needs fixed parent orders and decision prices, then compares schedules in the lab simulator; it does not need broker routing. S07 needs a specific pair selected from training data and both-leg data. Record partial-leg risk rather than assuming simultaneous fills.

### Wave C — S08-S10

Require the event-level, depth, queue, or synchronized option-surface inputs described in the testing plan. If the selected source lacks them, record `not testable with current data`; do not synthesize order flow or queue position from candles.

## Required data dictionary

For every staged file or stream, record:

- source/vendor, product or entitlement, permitted purpose, retention terms, and acquisition date;
- exchange trading date, timezone, exchange timestamp, receive timestamp if available, interval/event type, sequence or gap indicators, and clock assumptions;
- instrument ID, exchange symbol, segment, underlying, contract type, expiry, strike, option type, lot size, tick size, and the dated master-file version;
- OHLC, traded quantity/volume, open interest, and settlement/reference fields when provided;
- bid/ask prices, displayed sizes, available depth levels, quote age, trade price/quantity, and the exact meaning of each field;
- raw-versus-derived status, missingness, duplicate handling, corrections, and file hash.

Keep licensed raw inputs outside Git unless the applicable license explicitly permits committing them. Experiment manifests may store source identifiers, dates, schema version, and hashes without containing restricted market data.

## Broker/API verification checklist

The broker is not identified in the project brief currently present in this repository. Before choosing an instrument or building the lab input adapter, obtain official documentation or a sample payload for:

1. historical and live data products by segment and instrument;
2. bar intervals, quote depth, trade/volume/OI fields, timestamp semantics, history length, and missing-data/backfill behavior;
3. subscription charges, rate limits, connection limits, API versioning, and availability during reconnects;
4. market-data retention/replay rights and whether use for own-account research is covered;
5. paper/sandbox availability and the complete order-event lifecycle, without placing live orders;
6. broker-specific API-algo classification and operational requirements applicable on the intended route.

Do not place API keys, account IDs, or authenticated payloads in this repository. Redact personal/account data from any sample used for schema review.

## Next actions

1. Identify the intended broker/API product; review only its official documentation and a redacted sample response.
2. Fill the unknown cells above with source-backed facts and dated terms; obtain a lawful sample dataset or mark each candidate unavailable.
3. Select one common Wave A signal/execution instrument only after checking available history, timestamps, volume, quotes, and cost inputs.
4. Freeze a small, licensed dataset manifest and validate the lab-only ingestion schema. No strategy backtest is authorized by this matrix itself.

## Sources

- [NSE real-time data products](https://www.nseindia.com/static/market-data/real-time-data-subscription)
- [NSE EOD and historical data products](https://betanseapi.nseindia.com/static/market-data/eod-historical-data-subscription)
- [NSE data sharing and usage policy](https://www.nseindia.com/static/market-data/nse-data-policy)
- [NSE equity-derivative contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications)
- [SEBI circular on safer participation of retail investors in algorithmic trading](https://www.sebi.gov.in/legal/circulars/feb-2025/safer-participation-of-retail-investors-in-algorithmic-trading_91614.html)
