# Synthetic daily operations evidence fixtures

Every file here is synthetic, caller-supplied diagnostic evidence. No broker, feed, account, credentials, licensed market data or actual operating session was used. Times and values are fixed. These fixtures rehearse the checker and evidence workflow; they do not close G4.

| Bundle | Expected report | Trigger |
|---|---|---|
| [clean-completion](clean-completion/session-evidence.json) | PASS | Explicit checks, fresh observations, two matched fills, flat positions, exact P&L/fee/cash reconciliation and reviewed zero incident/stop registers. |
| [stale-inputs](stale-inputs/session-evidence.json) | FAIL | Old input was explicitly allowed to create new risk. |
| [missing-acknowledgements](missing-acknowledgements/session-evidence.json) | INCOMPLETE | Required session-close acknowledgement is absent. |
| [open-positions](open-positions/session-evidence.json) | FAIL | One filled unit remains open at session end. |
| [unmatched-events](unmatched-events/session-evidence.json) | FAIL | A risk fill references an execution absent from the complete execution ledger. |
| [hash-tampering](hash-tampering/session-evidence.json) | FAIL | The output net P&L was changed after recording its expected SHA-256; original/replayed bytes also differ. The deliberate mismatch must remain in this fixture. |
| [halted-session](halted-session/session-evidence.json) | PASS | Stale input blocks risk, references an acknowledged incident/stop, binds a gateway halt, and closes flat with HALT_AUTHORITY review. |
| [settled-session](settled-session/session-evidence.json) | PASS | A signed position closes through an explicit synthetic settlement event with sourced, dated charge lines. |
| [missing-settlement-costs](missing-settlement-costs/session-evidence.json) | INCOMPLETE | One required settlement charge component is absent; no zero fee is inferred. |

See [the caller schema and commands](../../docs/DAILY-OPS-EVIDENCE.md). `expected-results.json` is the matrix used by the synthetic drill and focused tests. Each bundle declares its four local artifacts and byte hashes. Copy a bundle to prepare evidence; update declared SHA-256 hashes when intentionally changing artifact bytes. Preserve unknowns rather than inventing timestamps, successful checks, or zero activity.

Run `synthetic-daily-ops-drill.ps1` with a built JAR and an output directory outside this fixture tree. A zero drill exit means the expected PASS/FAIL/INCOMPLETE matrix matched; it does not mean all sessions passed. Repeating the command with unchanged fixture bytes writes identical JSON report bytes.
