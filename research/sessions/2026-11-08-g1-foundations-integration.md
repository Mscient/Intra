# G1 integration on current main — 2026-11-08

## Integration

Integrated the already-reviewed `codex/g1-foundations-20261108` branch onto the then-current `origin/main` at `0ae90aadc58fd86a4835917ffbbf992a7870bcf2` (G0 charter). The integration branch was `codex/g1-foundations-reviewed-20261108`; integration commit: `67169d1e421e20e0d916d01d7e8c30473a0bbc19`.

The merge retained both gate checkers in the Windows Java 21 matrix: G0 remains the fourteenth source suite, and G1 hypothesis-card validation is the fifteenth. Each is also exercised against the production JAR. The earlier G1 runner fixes and the G0 runner changes were reconciled. The original checkout and other worktrees were not modified.

## Validation

The full local runner passed on the integration commit using Microsoft OpenJDK `21.0.13.0.2+1-LTS` (`Microsoft-15780113`):

- All 15 selected source suites passed.
- Production JAR build passed; 13 packaged, drill, standalone, and manifest stages passed.
- Risk gateway: 447 checks; process recovery: 728 checks, 308 child JVMs, 24 forced terminations.
- Portfolio risk replay: 84 checks; risk audit: 79; settlement fixtures: 274; settlement audit: 88.
- Trial evaluation: 165 focused checks and 30,019 frozen synthetic HAC qualification checks.
- Daily operations: 455 assertions; intended-use preflight: 300; permission coverage: 102 scenarios / 1,648 assertions.
- Incident drill: 198 checks in source and packaged modes.
- Feed audit: 507 source checks, 507 packaged checks, and 557 synthetic drill checks.
- G0 charter: 551 checks in source and production-JAR modes, with repeatable cross-JVM report verification.
- Hypothesis cards: 168 checks in source and production-JAR modes.
- JAR `Build-Commit` exactly matched the tested integration commit; JAR SHA-256: `c8ea54fb4431928d25257d90298809c35ab8ad5127f8906a75588af2f6ccfc33`.

`git diff --check` passed. The retained output is at `C:\Users\prash\AppData\Local\Temp\codex-g1-main-ci-20261108`.

## Limits and gate status

All validation uses synthetic fixtures and tests software behavior only. G0 is still open and G1 remains incomplete: owner decisions, private A15 limits, candidate selection, learning acknowledgment, permissions, and independent review/freeze are outstanding. This work supplies no evidence of market-data rights, executable economics, profitability, or trading readiness. No market or account data, credentials, broker calls, or order routing were used.

Hosted Actions had not yet run at the time of this record.
