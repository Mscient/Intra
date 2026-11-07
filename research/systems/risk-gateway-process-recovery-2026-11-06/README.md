# Offline subprocess verification evidence

See [the report](../2026-11-06-risk-gateway-process-recovery-verification.md) and [operating notes](../../../Intra-Paper-System/docs/INDEPENDENT-RISK-GATEWAY.md) for commands, exact scope, assumptions and limitations.

- `checks.txt`: final repeat, 728 parent checks / 308 child JVMs / 24 forced terminations.
- `first-run-checks.txt`: first execution with the same results; final repeat uses the public production writer for post-acknowledgement kills.
- `repeat-journal-sha256.txt`: 42 matching non-ownership journal fixtures; deliberately missing files match absence. Ownership races allow either winning ID.
- `jdk-identity.txt`, `source-sha256.txt`, `package-sha256.txt`: exact installed/tested working-copy identities.
- `existing-447-checks.txt`: existing 447-check risk gateway suite, unchanged lifecycle digest.
- `risk-replay-regressions.txt`: 84 risk-adapter checks plus replay, execution-cost, v3 artifact and signed settlement checks.
- `risk-audit-regressions.txt`: 79 audit checks plus 274 settlement-risk checks; expected rejected inputs/breach cases appear in the transcript.
- `accounting-regressions.txt`: accounting ledger checks.
- `evaluation-regressions.txt`: 161 registered-evaluation checks plus replay/cost/artifact regressions.

Final fixture/transcript/JAR directory, retained locally:

```text
Intra-Paper-System/build/risk-gateway-process-verification/e78950702fba4d4d9282f04c38d499f7
```

The original 447-check run is `Intra-Paper-System/build/risk-gateway-verification/a505a7a01bce4c85a5c563b8046b2699`. Full child logs, synthetic journals/checkpoints, kill exit results and compiled artifacts remain in ignored build directories. The checked-in evidence contains small text transcripts and hashes. Process kills leave the OS/cache running and do not prove power-loss or checkpoint-store durability.
