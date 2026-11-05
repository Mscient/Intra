# Offline portfolio accounting ledger

`PortfolioAccountingLedger` aggregates signed positions and P&L across the exact-time frames produced by `SynchronizedBarFrameBuilder`. It is a reusable portfolio accounting component; it is used by the diagnostic portfolio strategy replay and CLI.

## Input and ordering contract

- Call `mark(frame, executionsAtFrame)` with frames in strictly increasing timestamp order.
- Every supplied execution timestamp must exactly equal the frame timestamp, reference an instrument in that frame, and have a quantity divisible by that dated bar's lot size.
- New execution IDs require strictly increasing caller-supplied sequence numbers. Repeating an identical ID/payload is idempotent; reusing an ID with a different payload fails.
- A frame's instrument set and each dated contract identity must remain stable across the ledger run. Effective-dated token, lot-size, and tick-size changes are permitted; upstream dated-reference validation remains required.
- A frame is applied transactionally. If an execution, sequence, or arithmetic check fails, none of that frame's fills, fees, positions, or marks are committed; the frame can be corrected and retried.

## Accounting

Positions are signed in underlying units; BUY adds and SELL subtracts. Average open basis is maintained per instrument. Opposite-side executions realize P&L on closed units; an execution larger than the open position closes it and opens the residual in the new direction. Long and short positions are supported by this accounting primitive, but it does not imply that a strategy, exchange, broker, or risk gateway permits either direction.

For each frame, BAR_START data is marked at bar open and BAR_END data at bar close. Gross unrealized P&L uses signed quantity and remaining open basis. Net P&L is:

`realized gross P&L + unrealized gross P&L - caller-supplied fees`

Any slippage embedded in the supplied execution price affects gross P&L; the ledger does not separately calculate slippage. Fees are explicit inputs and are not verified against a tariff or invoice.

## Limits

`settleFinalSession` consumes explicit dated events and optional source-attributed cost terms after the final completed frame. It closes each selected instrument's entire signed residual position using its exact remaining basis, with atomic batch validation and separate settlement audit records. Flat events are retained without charges. See [the settlement contract](PORTFOLIO-SETTLEMENT.md) for calculations, CSV schemas, rounding, and examples. This terminal operation does not manufacture ordinary executions or permit further frame opens.

This component does not establish input rights or completeness, validate source timestamps/calendars, generate signals or orders, infer fills, handle pending orders, calculate cash/margin/capital, infer costs or taxes, or produce a performance-eligible backtest. It does not replace the current single-contract engine's ledgers or the separate independent-risk-gateway workstream. No network or broker order route is present.

Run focused checks with `.\test-portfolio-accounting.ps1` in PowerShell under JDK 21.
