# Offline K0–K3 kill-policy state machine

This Java 21 component turns explicit, caller-supplied `SafetyObservation` samples into a deterministic `KillDecision`. It implements the K0–K3 response levels from the final implementation plan. It is a policy model only: it does not monitor feeds, receive broker events, cancel orders, flatten positions, stop processes, lock credentials, or provide an authenticated control plane.

## State and transitions

- `NONE`: risk-increasing actions remain permitted by this policy result.
- `K0_PAUSE`: pause new risk. It clears automatically only after a non-empty, caller-configured healthy dwell is observed with strictly increasing monotonic timestamps and no observation gap exceeds the configured maximum. A renewed trigger or missed-health interval restarts the dwell.
- `K1_CANCEL_AND_BLOCK`: recommend canceling working orders and block new risk. It remains latched after its trigger disappears until explicit human clearance reports order/position reconciliation, incident review, and healthy controls.
- `K2_FLATTEN_AND_HALT`: recommend canceling, requesting protected risk reduction, and halting. A configured number of distinct K1 incidents can escalate to K2. Clearing requires the same human attestations.
- `K3_DISCONNECT`: recommend disconnecting the process and using the separate human manual path. It clears only with all K1/K2 attestations plus process-restart verification.

K1 incidents are separated by an observation in which no K1 trigger is active. Sustained K1 severity counts as one incident. K2/K3 triggers do not contribute to the repeated-K1 threshold. Escalation is sticky; this implementation never automatically lowers a latched level. A new session can start only in `NONE`, dates must strictly increase, and observation IDs are unique within the session. UTC observations must not regress; supplied monotonic times must strictly increase. Duplicate observation IDs and out-of-order observations fail closed without consuming the rejected observation ID.

## Input and authority limits

Every trigger, health sample, monotonic timestamp, and human-clearance field comes from the caller. `HumanKillClearance` records operator/reference strings and booleans; it does not authenticate the operator or validate evidence. The controller has no durable journal, process isolation, watchdog, alert transport, failover, API-session control, or broker/manual-route integration. It cannot demonstrate that an action completed. No signal should be treated as executed merely because it appears in `requiredResponses`.

The policy mapping is based on the plan's K0–K3 response table. K0 trigger taxonomy, healthy dwell/gap, repeated-K1 threshold, and clearance evidence need independent operational review before use. K2 flattening is a request for protected risk reduction, not a guarantee that an exit can execute. This component does not implement the plan's seven risk layers, daily-loss arithmetic, margin computation, or the independent risk gateway.

## Focused checks

From the repository root in PowerShell with `JAVA_HOME` set to JDK 21:

```powershell
& .\Intra-Paper-System\test-operational-kill-controller.ps1
```

The focused runner covers K0 healthy dwell and gap reset, K1 action and human-clearance requirements, repeated-K1 escalation, K3 restart attestation, duplicate IDs, and non-increasing monotonic timestamps. It does not simulate process failure, order cancellation, position reconciliation, hardware watchdog behavior, or a live session.
