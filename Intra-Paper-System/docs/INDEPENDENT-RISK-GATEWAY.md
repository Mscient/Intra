# Independent risk gateway

This package is a deterministic, in-memory Java 21 library. It is isolated from the simulator and accepts only caller-supplied values and events. It has no network client, broker adapter, account interface, credential handling, or order-submission method. An accepted proposal creates an internal working-order reservation and an `OrderAcceptedEvent`; it does not send an order anywhere.

## State and calculations

- Executed positions are signed contract quantities derived only from accepted execution events.
- Working-order reservations retain the remaining quantity, limit price, and source-attributed margin input for each accepted order.
- Gross notional is the sum of absolute executed position notional at supplied marks and working reservations at their limit prices. Opposite positions and opposite working orders are not netted for gross exposure.
- Position limits use a worst-case interval from the executed position and all remaining buy/sell reservations. The proposal is included before acceptance.
- Supplied margin is aggregated per contract for executed positions and working reservations. Inputs retain source, reference, session, and as-of timestamp. These values are caller-provided proxies, not SPAN/ELM calculations or exchange margin determinations.
- Daily P&L is computed with decimal arithmetic from session opening positions/marks, executions' cashflows, contract multipliers, and supplied marks. A missing or stale mark makes open-position P&L unavailable and prevents new proposals from passing.
- A daily loss at or beyond the configured limit latches the session halt. Later mark recovery does not clear it; a new session event resets the halt.

## Risk-reducing closeout policy

After a daily-loss halt, a proposal is eligible for the closeout exception only when it is on the opposite side of an executed nonzero position, its quantity does not exceed that position, and no working order exists for that instrument. The exception still requires valid price, margin, session, and event-time inputs and still observes the position cap. Because a close order temporarily adds an unfilled reservation, the exception can exceed gross-notional and supplied-margin caps while it is working. It cannot cross the executed position through zero. Other proposals remain rejected until the next session.

## Event contract

Event timestamps must be nondecreasing in application order. Reapplying an event ID with an identical payload is a no-op. Reusing an ID with a different payload throws `RiskEventException`. Executions must reference an accepted working order, match its instrument and side, and not exceed its residual quantity. A cancellation releases all residual quantity. Session starts require no working orders and opening marks for carried positions.

The immutable `RiskState` snapshot distinguishes executed positions, all order lifecycle records, active working-order reservations, marks, P&L, halt state, and accepted event history. Rejected proposal decisions are cached in the gateway process but are not risk-state events.

## Limits

This is a single-process library with volatile in-memory state; it does not persist or authenticate an event ledger. Quantities are whole contracts, all instruments use one configured currency, and each instrument has a fixed contract multiplier and position cap. Caller-supplied marks, margin inputs, timestamps, instrument metadata, and event ordering are not independently sourced or verified. The library is not exchange behavior, account reconciliation, recovery control, a margin model, strategy validation, or live-trading authorization. No simulator integration is included.
