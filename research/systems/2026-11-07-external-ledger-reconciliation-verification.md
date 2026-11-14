# Offline external-ledger reconciliation verification

Date: 2026-11-07, Asia/Kolkata. Session: `937799f2-6b37-4a7b-b30f-5f437df1a081`. Baseline HEAD: `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`; pre-existing uncommitted CI, gateway, daily-operations, research and documentation changes were preserved.

Implemented the [standalone Java 21 audit and caller contract](../../Intra-Paper-System/docs/EXTERNAL-LEDGER-RECONCILIATION.md) in seven production classes under `audit/reconciliation`, one synthetic acceptance class, three PowerShell entrypoints and the fixed independently authored 22-record external-ledger fixture. It fully verifies/reconstructs single-contract and portfolio v3 replays, retains explicit correction/reversal history, uses IDs or unique documented fallback keys, separately checks quantities/positions/fees/cash/settlement, preserves unknown amounts, and atomically publishes seven deterministic report files plus a hashed manifest. Caller records and source attribution remain unauthenticated.

## Final validation

Microsoft JDK `21.0.13.0.2` compiled the complete production JAR. **227 acceptance checks in seven synthetic groups passed against that exact JAR**, including full and partial/multiple fills, cancellation and overfills, corrections across record types, reversals, missing records/history, duplicate event/entity/replay IDs/sequences, branched/cyclic history, explicit and ambiguous fallback keys, out-of-order events, fee/cash/position mismatches, signed short and explicit zero option settlement, unknown replay/external charges, coverage gaps, malformed UTF-8/CSV/decimals, external/replay/indirect-input/report tampering, rehashed report semantic rejection, repeatable bytes, relocation/locale/file-metadata invariance, actual verified single full/partial-IOC replays and integrated portfolio settlement. CLI PASS/FAIL/INCOMPLETE exits and artifact-only versus semantic verification were exercised.

Existing packaged regressions passed: **PortfolioSettlementAudit 88 focused checks** and **DailyOpsEvidenceChecker 455 assertions in five synthetic groups**. The fixed synthetic drill passed replay verification and all three audit modes in separate Java processes; its report bytes/manifest hash equal the packaged acceptance report. The PowerShell wrapper verified that artifact, and all three new PowerShell files parsed successfully. No further financial implementation changes were made after this JAR was built.

| Identity | Exact value |
|---|---|
| Tested JAR | `Intra-Paper-System/build/external-ledger-package/intra-paper-system.jar` |
| JAR SHA-256 | `bb943fb2c67d34ac19166e297d3d99a35f8b316d92eb4c7ed0a1eb700e4ff895` |
| JAR manifest build commit | `unknown-dirty` |
| Fixed synthetic replay ID | `287d1de95a4b084905c541d2d7d9b38a7b16e20e1598a0f8f207605d6fe24be1` |
| Fixed audit manifest/report ID | `1e49ed1e93e0b31b8b9e7555e86add3865b46bc4e8e8bab8886a852b1754ae30` |
| Packaged acceptance evidence | `Intra-Paper-System/build/external-ledger-packaged-checks/69692a8b1dbf4053a99a3dbd02107aab/` |
| Regression/drill logs | `Intra-Paper-System/build/external-ledger-regressions/` |
| Separate CLI drill evidence | `Intra-Paper-System/build/external-ledger-regressions/cli-drill/fe48a9e920f64977b907c56e361010cb/` |

The installed JDK required sandbox escalation to read compiled classpaths; no dependency download or network access occurred. Initial source acceptance runs exposed terminal order-status and quote-provenance coverage issues; both were corrected before the final packaged pass.

Only synthetic records were used. The cash contract is the replay's signed-notional accounting-flow convention with separate fee cash, not authenticated futures account/variation-margin cash or a known account balance. `NOT_MODELED` settlement fees remain unknown despite stored bookkeeping zero. Source authenticity, completeness of caller normalization/tariffs, real operational controls/sessions, power-loss durability and G0/G2/G4 remain open. No broker connection, account data, live route, performance/readiness or hosted CI claim was added. All work remains uncommitted/unpushed in the shared working tree.
