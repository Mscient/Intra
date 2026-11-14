# Offline incident and recovery drill verification

Date: 2026-11-07. Scope: local Java 21 software verification using entirely synthetic events. Contract and commands: [offline incident drills](../../Intra-Paper-System/docs/OFFLINE-INCIDENT-DRILLS.md).

Implemented four production classes in `ops.drill`, a standalone CLI and PowerShell build/run/test scripts, nine strict versioned example scenarios, deterministic evidence publication and replay verification, and a dedicated adversarial acceptance suite. Existing gateway/replay implementation files were not modified. Pre-existing unrelated working-tree changes were preserved.

The runner owns the existing `OfflinePortfolioRiskAdapter` and uses its risk decisions, incremental execution/cancellation, exact reconciliation and checkpoint restoration. It adds a latched SAFE_HALT state, separately bound RECONCILED state and scenario-authorized RESET. Checkpoint recovery requires the retained trusted digest to match the latest acknowledged adapter history. It never clears exposure or establishes a new anchor after a failed recovery. Missing acknowledgements persist until a confirmed terminal fill/cancellation; retries and duplicate deliveries cannot reserve or fill twice.

## Measured checks

On Windows 11 amd64, Microsoft JDK `21.0.13.0.2+1-LTS`:

- **198** packaged drill acceptance checks passed: six incident types, compounded incidents, signed exposure, partial fills, missed acknowledgements, retries and duplicates across checkpoint replay, conflicting IDs, wrong/expired/reused reset references, unauthorized actors, stale controls, residual mismatch, unknown acknowledgements, missing/corrupted/lagging checkpoints, forbidden reanchoring, position/gross/margin/daily-loss limits, strict parsing, byte determinism, tampering/rehashed false evidence and overwrite refusal.
- All **nine** fixture scenarios were run twice in separate JVMs and both copies verified by full regeneration. Both runs produced the same manifest hash and preserved expected status exits: six PASS/0, two INCOMPLETE/3, one deliberate FAIL/2.
- Dependency inspection computed the reachable closure from all packaged class edges, yielding 3,063 dependency lines, including 2,782 from existing non-drill classes. No reachable broker/general CLI, SmartAPI decoder or network client dependency was found. The existing `java.net.URI` value parser is allowed; it performs no network I/O. This is static dependency evidence, not an OS network-isolation certificate.
- Existing independent gateway suite: **447 checks in 13 groups passed**, including durable journal uncertainty and rollback rejection. Gateway replay hash: `c29af67dbe2c00a6c5dbdf76ebe595517e6737323041eabefc1957751c6d2d7b`.
- Existing packaged portfolio risk replay: **84 checks passed**, plus the portfolio replay engine, execution-cost schedule, v3 artifact verifier and signed settlement regression suites. These exercise the same existing replay/risk dependencies used by the new runner.

Tested JAR SHA-256: `e3dbfd48188cc3b83da7bbce660e03d4cfdf03991e04fdfdad904f5cc2c65a17`.

Local retained evidence (paths relative to `Intra-Paper-System`):

- Final implementation build and initial 198-check evidence: `build/incident-drill-checks/f18bce2a861445f5a3b7e9828508d690/`.
- Exact JAR: `build/incident-drill-checks/f18bce2a861445f5a3b7e9828508d690/package/f64587efc64b4b51b044ad8770026dbe/incident-drill.jar`.
- Final packaged script, transitive dependency audit and repeated CLI evidence: `build/incident-drill-checks/689a91fea2d445dcb499e88b8d1ad365/`.
- Gateway regression: `build/risk-gateway-verification/b78d99060f734617b7c7b0e4c43d89de/`.
- Portfolio regression classes: `build/risk-replay-checks-d318f398b3c24758957effa327c6c62a/`.

| Scenario | Result | Manifest SHA-256 |
| --- | --- | --- |
| feed-loss | PASS | `52bdc1e61f6994a4fb6332808057ee48dce2563001f36ab239427f750d4ef403` |
| stale-marks | PASS | `7bef0f98dc0e898089057ce9924158d2bcc19e18c4737b19d3144ab354b96054` |
| uncertain-order | PASS | `48040e4009c6a141456fc0a7e922f51bc48309d95e9eec6dc037ea52dedd12c9` |
| limit-breach | PASS | `dc3cb6b3c401e16780c60b848282b4ed3c27a79bfd3bcfc0a9d621622f1ac8ee` |
| corrupted-checkpoint | PASS | `0587941fe8db08b7abdea8e0dace1bc173315472f28e44efc55e2b44e63a195e` |
| restart | PASS | `59458327dd0fea742fb876b18bf4cb2227495d09a5d2d4d498e5df4374bb3de9` |
| missed-ack-incomplete | INCOMPLETE | `dce1cf4ecbad752044a2940660ec34619eab1a28c953763844a4e8fd90039659` |
| lagging-checkpoint-incomplete | INCOMPLETE | `1d2c58089d9a41185cea8d2b6dce43e2105891182e0b612a7cc4ed0c88f9f9c7` |
| expectation-fail | FAIL | `862faba40834710e02920d6b9189708ef5cf65131284ca3b02093c5bf6806f7c` |

The local sandbox caused JDK classpath discovery failures and an explicit `AccessDeniedException` resolving a JAR real path. Final package/acceptance and regression runs used approved execution outside that sandbox; no downloads or network calls were used. Initial exploratory checks are not the final measured package evidence above.

## Scope remains synthetic

These drills do not count as G4 operating sessions and do not prove live recovery. RESET uses a synthetic allowlist and caller-supplied reference, not authenticated human approval. RESTART replays an adapter checkpoint inside the same controller process; it is not a durable controller restart, broker reconnect, full strategy/accounting resume, process-kill, reboot, hardware-failure or power-loss test. No broker/account data, credentials or licensed market data were accessed. Hashes are integrity bindings, not signatures. Authentic live operating evidence, independent reconciliation, checkpoint custody and live recovery authorization remain open.

Work remains local and uncommitted alongside earlier unrelated changes. No G0/G2/G4 status was promoted and no book/access status changed.
