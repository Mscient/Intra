# Candidate instrument, data and cost comparison

Version 1, 2026-11-08. **No market instrument/source is currently permitted for collection or an eligible trial by G0 evidence.** “Retain” below retains a desk-research candidate, not a data entitlement, chosen universe, procurement or trade. Nifty-first is **ASSUMPTION_PENDING_OWNER_CONFIRMATION (A11)**. Current main's [G0 open-items record](../../Intra-Paper-System/docs/G0-CHARTER-OPEN-ITEMS.md) has no signed closure evidence. Prior Angel selection in the 2026-11-01 matrix is a research lead; the later G0 record requires confirmation. No private response or public quote was downloaded or repaired.

Source IDs resolve through the [source register](g1-foundations-2026-11-08/source-register-v1.md). Runtime fields reuse the existing instrument master, dated token mappings, dataset manifests, calendars, permission coverage/preflight and feed audit. Actual dataset history and minimum statistically usable sample remain pending G2 review; record fields/history requirements before requesting any data.

| Candidate / economic role | Identity and decision | Source availability evidence, 2026-11-08 | Retain/defer/reject reason |
| --- | --- | --- | --- |
| C_INDEX: NIFTY 50 directional reference | Calculated index; S01–S03 signal-only, no order or derivative multiplier | Prior Angel matrix documents user-supplied partial/sparse candles and zero index volume; not re-acquired. Current Angel docs inaccessible [S09]. No licensed complete history established. | RETAIN_PENDING_RIGHTS_AND_QUALITY for signal question. REJECT as a fill/P&L instrument or VWAP input. Timestamp/closing-period gap unresolved. |
| C_FUT: dated NIFTY future | Potential later executable directional/basis proxy; contract expiry, venue, symbol/token, lot/tick and master required | Angel SDK exposes live quote fields [S10]; old support claim of no expired F&O history is only a lead. Kite continuous expired futures documentation supplies daily candles [S11], not the intraday quotes needed here. No vendor selected or permitted. | DEFER execution until dated multi-expiry history, quotes, rights, calendars, fees and margins exist. Continuous-chart roll is not a trade. |
| C_OPT_CALL: dated NIFTY call | Educational nonlinear-exposure comparison; possible later option/hedge study | SDK lists quote fields [S10]; no authorized expired option surface/archive or historical Greek inputs established. Current official lot/spec examples are reference evidence [S02–03]. | DEFER. Unknown surface, exercise inputs, premium spread, margin/hedge and rights prevent economic comparison. A call is not a futures-return substitute. |
| C_OPT_PUT: dated NIFTY put | Complementary payoff/stress comparison, not a selected short or hedge | Same source/coverage gaps as call; distinct strike/right/expiry must remain identifiable | DEFER. Separate bid/ask/premium and exercise/settlement evidence needed; do not infer symmetry from modeled Greeks. |
| C_SYNTHETIC: toy exchange-neutral ticks/units | Order-book mechanics and software verification only; no listed instrument | Existing `ToyLimitOrderBook`, independent numerical pricer fixtures and explicit synthetic local inputs | RETAIN for software/learning exercises under this task; REJECT as market, calibration, profit or permission evidence. |

The shortlist does not add stocks, ETFs, other indices or a live route to an unconfirmed G0 scope. The final plan allows G3 to compare other eligible instruments after owner/source review; their omission now is not evidence Nifty is superior. S05 basis/RV remains conceptual because the index is not an executable cash leg; a cash basket/ETF hedge would require a separately approved universe and costs.

## Required data contract and history

| Candidate | Fields / sampling | Timestamp / reference requirements | Minimum usable history and limits |
| --- | --- | --- | --- |
| C_INDEX | One-minute OHLC, index identifier, raw bar label/interval; no invented traded volume. S01 first 15-bar range; S02 30-minute lookback; S03 prior regular close/open. | Preserve source time/offset and verified bar start/end role, publication/receive availability if supplied; record unavailable receive history. Completed-bar decision no earlier than close and actual availability. Dated official regular/special-session calendar and closing reference. | Per score: complete required session and 30-minute maximum diagnostic horizon, plus prior regular session for S03. For research: nonempty chronological dev/validation/holdout, complete no-signal sessions included, development-only precision review and independently approved sample floor **PENDING_G2_DATA** before labels. One sparse session is not enough. No whole missing-day inference from weekdays. |
| C_FUT | Dated one-minute OHLCV/OI for signal context; timestamped bid/ask prices/sizes and trade fields at source event frequency for execution/latency. Raw/lifecycle capture and gap indicators needed. No fixed sample rate inferred from a stream. | Exchange/feed, last-trade, local UTC receive and monotonic receive/process times distinguished. Daily effective symbol/token mapping, expiry, lot/tick, session and settlement references; separate dated margins/fee tables. | Same chronological/power requirements plus coverage across declared contracts/expiry regimes. Required number of regimes/history floor **PENDING_G2_DATA**. Preserve each expiry separately, no survivor-only current master or continuous-chart fills. Missing expired intraday quotes cannot be recovered from daily candles. |
| C_OPT_CALL / C_OPT_PUT | Actual listed premium OHLCV/OI and bid/ask size/time; synchronized underlying futures/index reference; full chosen strike/expiry grid for any surface study; sourced rate/volatility/model inputs separately. | Contract right/strike/expiry/effective master/lot/tick; event and receive clocks on every leg, session/expiry cutoff, settlement price and exercise reference. Neither LTP nor a theoretical Greek is a bid/ask. | Complete synchronized eligible sessions and all predeclared strike/expiry candidates, including illiquid/missing ones. Surface/archive, minimum regimes/precision and usable sample **PENDING_G2_DATA**. Current listed contracts introduce survivorship; no permission or reliable expired history established. |
| C_SYNTHETIC | Invented integer ticks/units and ordered calls; explicit synthetic option parameters | Call order/virtual time only; no exchange identity or real clock claim | Deterministic fixture cases sufficient for specified software behavior only; no market sample minimum can be met by synthetic data. |

Data-quality inspection may identify split sessions but must not compute forward outcomes to choose the universe, sample floor or rule. Any history request must specify product, actual contract IDs, field semantics, date range, quality grade and licensing scope. Current API snippets cannot establish how empty minutes, late revisions or publication delays are handled. Reconnect support does not imply replay of every missed market event.

## Permissions, execution and cost availability

Each action below is independently unresolved. Do not substitute source availability or `SCOPE_MATCH` from a synthetic checker for rights.

| Candidate/source | Collect | Local store/backups | Replay/backtest | Derived outputs | Share/publication |
| --- | --- | --- | --- | --- | --- |
| C_INDEX / Angel historical lead | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED |
| C_FUT / Angel quote/history lead | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED |
| C_FUT / Kite alternative desk lead | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED |
| C_OPT_CALL, C_OPT_PUT / any broker/archive | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED |
| C_SYNTHETIC / newly authored fixtures | AUTHORIZED_TASK_SOFTWARE_ONLY | AUTHORIZED_TASK_SOFTWARE_ONLY | AUTHORIZED_TASK_SOFTWARE_ONLY | SOFTWARE_DIAGNOSTICS_ONLY | SYNTHETIC_LABEL_REQUIRED |

NSE policy ties use/sharing to relevant documentation [S07]; no legal interpretation or permission approval is supplied here. Obtain private product/account terms and qualified review covering purpose, non-display computation, locations, backups/retention/deletion, derived outputs and named recipients. Existing permission coverage and intended-use preflight can then assess scope; both expressly decline legal/authentication approval.

| Input | C_INDEX signal | C_FUT execution | C_OPT_CALL / C_OPT_PUT |
| --- | --- | --- | --- |
| Instrument/lot/tick/expiry | Index identity/calendar UNKNOWN; multiplier not a signal input | Required dated contract files UNKNOWN; public specs DATED_REFERENCE_ONLY [S02–03] | Same plus strike/right UNKNOWN |
| Brokerage/exchange/clearing/STT/stamp/SEBI/GST | Trading costs not applicable to an index score; no “net index P&L” | Public dated rates in G1-COST/v1; actual broker tariff/clearing/GST base UNKNOWN | Premium bases differ; exercise basis/additional charges UNKNOWN |
| Spread/latency/slippage/impact | Not modeled as executable | UNKNOWN, later caller-supplied sensitivity pending measurement | UNKNOWN on option and any hedge leg |
| Queue/partial/missed/adverse selection | No fill claim | Existing L1 IOC is a model; real completion/queue evidence UNKNOWN | Same; sparse premiums/hedge asynchrony add gaps |
| Settlement/reference/margin | Not a trade; prior close semantics UNKNOWN | Required explicit dated settlement/fee/margin rows UNKNOWN | Exercise/settlement price, turnover basis, charges, margin UNKNOWN |
| Funding/capital/fixed/time | Research operating costs privately owner supplied | PENDING_OWNER_INPUT; no zero allocation | PENDING_OWNER_INPUT; no inferred margin from premium/Greeks |

No estimates are substituted for blanks. The [cost wall](G1-COST-WALL-V1.md) defines scenario equations with all components visible. The existing cost/settlement schedules demand caller inputs; this planning CSV deliberately cannot be used as a runtime schedule. All actual market trials remain ineligible.
