# Forward paper runtime

## Current release candidate (2026-11-16)

See [the current release record](FORWARD-PAPER-COMPLETION-2026-11-16.md) and [operating workflow](OPERATING-GUIDE.md). The V2 session identity binds a canonical `input-manifest.json`; `session-manifest.json` binds the published report/checkpoint and verified journal. `paper-replay-verify <bars.csv> <run.properties> <session-dir>` regenerates the committed prefix and compares all five artifacts byte for byte, using the writer's owned handle for the Windows-locked journal. No new strategy, OMS, fill, cost or accounting model was introduced.

Paper pre-open and the incremental core require complete reference coverage. Ordinary paper inputs are restricted to Nifty futures; the existing `SYNTHETIC` futures fixture is explicitly permitted as plumbing. Options remain later-stage work. Historical/scoring/artifact boundaries also reject diagnostic gaps. Exact duplicate IDs are checked before advancing the clock, so an old retransmitted event retains its original clocks and produces no second decision or fill. Conflicting duplicates and new reversed events still halt.

An active session now rechecks its retained input/checkpoint anchors before appending an event or publishing/verifying reports. A changed anchor cannot be silently overwritten by the next checkpoint. Report/manifest storage failure disables further processing. `session_complete` requires a normal close, all source events and expected bars, no quality warnings, and flat exposure. `operating_evidence_eligible` and `performance_claim_eligible` remain false even then. The sections below retain the original runtime design and historical handover evidence; V1 journals need their original JAR, with no automatic migration.

Final verified files, commands, outcomes and remaining limits are in the [2026-11-15 handover](FORWARD-PAPER-COMPLETION-2026-11-15.md): 28 offline suites passed, including 195 paper checks; packaged restart/report/stop checks passed.

This is an operating mode of the existing Intra-Paper-System. `paper-run` drives the existing single-contract strategy, `RiskGate`, simulated OMS, L1 execution book, fee calculation, trade accounting, and ledger reconciliation from local timestamped events. Every result is `PAPER_PLUMBING_ONLY` and performance-ineligible. G0 remains open.

## Existing components reused

| Existing component | Integration |
|---|---|
| `BacktestEngine`, `Strategy`, `SmaCrossoverStrategy` | Historical runs and paper sessions call the same extracted execution and completed-bar loop. Strategies receive only completed bar prefixes. |
| `RiskGate` | Position, notional, margin, entry-rate, entry-count and daily-loss limits remain in the shared core. Qualified L1 quotes also check the same daily-loss latch. |
| `QuoteExecutionBook`, `QuoteExecutionGate`, `QuoteQualityPolicy` | Existing IOC quantity, lot, slippage, displayed-liquidity consumption, and quality rules. No independent paper fill model. |
| `SimOrderLifecycle`, `SimFill`, `SimFeeBreakdown`, `RunLedgerReconciler` | Existing transitions, execution IDs, position flow, closed-trade allocation, fees and P&L reconciliation. |
| `RunConfig`, `ValidatedDataset`, instrument master, session calendar, cost/margin schedules, permission admission | Existing local input validation and provenance. One supplied session and contract per paper runtime. |
| `RawCaptureJournalWriter/Reader` | Forced, locked, hash-chained input and ledger records. An owned-channel verification method supports Windows mandatory file locks. |
| `RunIdentity` and existing checkpoint principles | Frozen input/strategy/config identity plus the running class/JAR hash. Paper checkpoints anchor journal length, count, terminal hash and reconstructed report hash. |
| Historical checkpoint store, independent risk gateway, K0–K3 controller, daily-ops and incident tools | Inspected and retained. Historical checkpoints require a frozen bar index and are not misused for partial paper event transactions. The independent portfolio risk gateway and attested K0–K3 controller are separate tools; this mode uses the existing single-contract risk core and local stop controls. Synthetic runtime reports do not supply their external operating attestations. |

## Commands

Build with the existing Java 21 tooling. Run from the project directory, using a JAR built from the same source throughout a session:

```powershell
rtk proxy java -jar <jar> paper-run examples/synthetic-futures-bars.csv examples/synthetic-run.properties <new-session-dir>
rtk proxy java -jar <jar> paper-verify examples/synthetic-futures-bars.csv examples/synthetic-run.properties <session-dir>
rtk proxy java -jar <jar> paper-stop <session-dir>
```

An optional last integer on `paper-run` limits the number of new events processed. Repeating the command with the same files, build and session directory verifies and reconstructs the committed prefix, then continues from its cursor. `paper-stop` creates a local `STOP` marker; the runtime applies it before the next event transaction. The Java API also exposes a synchronized, durable `requestStop()`.

`MarketDataProvider` is sealed and permits only `LocalMarketDataProvider`. Its frozen immutable events can be synthetic fixtures or pre-existing, permission-admitted local input. There is no connectivity enable flag, broker client, credential field, or order-placement/modification/cancellation endpoint. Extending the source requires a code change and the still-missing authorization evidence. No account or market-data collection is part of these commands.

The CLI uses existing normalized bars and optional verified quote journals. The Java API accepts an explicit local `PaperEvent` list for fault drills. A malformed decoder result is a typed `MALFORMED` event; raw diagnostic exception text is not accepted as an event reason.

## Clock, ordering and visibility

Events preserve source UTC, receive UTC and receive monotonic time. Receive UTC drives the decision clock. Equal receive times retain input order. The recorded-source adapter explicitly merges quotes first, completed bars second, and observed opens third; quote sequence breaks equal quote ties. Runtime input is never silently sorted. UTC or monotonic reversal, conflicting duplicate IDs, sequence gaps, changed contract identity and reversed quote source timestamps halt the session. Exact duplicates are recorded and ignored. Initial event and quote sequences must start at one; a mid-stream journal needs an explicitly reviewed baseline before future support.

Recorded bar opens contain only the opening price and contract metadata. High, low and close are set to that opening price, volume/open interest are absent, and the opening hash excludes future completed-bar information. The strategy runs when the completed bar has actually arrived. Future bars/quotes cannot become observable through allowed future clock skew. Future data, stale data, missing sides and malformed books block new simulated entries.

For `BAR_NEXT_OPEN`, freshness is one configured bar interval and fills use an observed opening event. For L1, the existing configured quote maximum age applies. Latency-matured pending orders execute only on a received open/quote event; a missing event at the nominal arrival time never grants access to a later quote early. A qualified bid is the mark for an open long position on L1 quote events; completed-bar closes retain the existing strategy/accounting mark behavior. Historical bar replay and normal zero-latency local paper fixtures are checked for exact signal/fill/fee/trade/P&L equality. A delayed or sparse feed can legitimately change execution timing.

## States and session controls

Pre-open checks validate config binding, local data admission, contract mapping/expiry, calendar, positive risk limits, deterministic strategy support, supported policies, a nonempty clock source, and journal creation/locking. A failed pre-open check raises an error before a session becomes usable. Initial state is `READY`; qualified data moves it to `RUNNING`.

Disconnect, malformed/stale data and missing usable quotes cause `DEGRADED`, cancel local pending simulated intents, and block both strategy decisions and execution. `RECONNECT` does not clear the latch. A `RECOVER` event requires connection state and a fresh subsequent input; gaps or ambiguous bar/open sequences still halt. `HALTED` is terminal for that session, including daily-loss, sequence, contract and clock faults. `OPERATOR_STOP` and `SESSION_CLOSE` produce `STOPPED`. Terminal sessions do not consume more provider events.

Heartbeats exercise stale-feed and session-close checks using the event clock. This offline mode does not claim a wall-clock watchdog for a live socket. It requires a supplied heartbeat/close event to observe silence or the passage of time. The recorded adapter supplies the session-close event.

One contract/session is supported. Automatic flattening, explicit expiry settlement, next-session orders and option-stress input integration are rejected in this mode. `REQUIRE_FLAT` does not invent an exit at close: an open position remains explicit and requires operator review. Pending-at-close count and the subsequent local expiry transition are recorded. No settlement, account balance, or liquidation price is inferred. Existing portfolio and settlement replay tools remain available separately.

## Persistence and report verification

`paper.journal` persists each normalized incoming event and the resulting complete ledger/report snapshot using the existing raw-capture envelope. Payloads use the versioned Java record representation; the frozen local source and identical running code are required to reconstruct them. The journal stores the received event before invoking the core. `checkpoint.txt` is forced to a same-directory staging file and atomically published only after the input and ledger records are forced.

Recovery verifies the entire journal against the separately stored checkpoint before replaying any strategy prefix. It then compares every reconstructed output with its persisted ledger and verifies the final report digest. A missing, corrupt, changed or truncated journal, extra uncommitted record, orphan checkpoint staging file, changed source/config/reference/cost input or changed compiled code prevents resumption. A `.session.lock` lease prevents concurrent session writers. Restart never blindly trusts a serialized mutable strategy or an order counter.

`session-report.txt` is deterministic and includes runtime observations, state/reasons, warning/disconnect/recovery counts, duplicates, risk halts, strategy decisions, orders, all OMS/risk events, fills, fee breakdowns, trades, position/equity snapshots, last-mark P&L, pending/open positions, session completeness and reconciliation. `paper-verify` reconstructs the ledger and requires an exact report match. Reconciliation passing establishes internal accounting consistency; it does not assert a complete or safe operating session.

Hash chains and local checksums detect inconsistency against retained checkpoints, not an attacker coherently replacing all local history and anchors. Independent custody, power-loss durability and filesystem/host failure qualification remain unverified. Every transaction currently retains a complete ledger snapshot; sustained live-feed throughput, storage growth and long-session recovery performance remain unqualified.

## Offline verification

```powershell
rtk proxy node test-paper-runtime.mjs build/<fresh-focused-dir>
rtk proxy node test-offline.mjs --output-root build/<fresh-full-suite-dir>
```

`PaperRuntimeTest` covers normal operation and historical parity; restart before/after fills; duplicate suppression; stale, malformed, future, out-of-order and unknown data; clock/sequence failures; reconnect/recovery; risk rejection; operator stop; session close with pending/open exposure; checkpoint/journal/report corruption; and separate JVM recovery. All inputs are synthetic. Exact final commands/results are recorded in the completion note after verification.

## G0 evidence still required

The [G0 charter](G0-CHARTER-OPEN-ITEMS.md) remains open: responsible operator/reviewer and stop authority, account/API eligibility and current route terms, market-data access/storage/replay/retention rights, approved universe and current market/broker rules, private approved capital/risk limits and actual cost evidence, and independently reviewed operating/recovery evidence. Runtime plumbing and synthetic drills do not satisfy the planned forward-session counts, pre-open streak, human attestations, data authenticity, broker eligibility, or strategy evidence.
