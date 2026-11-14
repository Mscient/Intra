# Offline verification and correctness increment

The project uses JDK 21 directly (`javac --release 21` and `jar`), with no Maven/Gradle project or third-party Java test framework. Automated Java tests are executable mains using explicit assertions. Node runs those mains and packages the dependency-free production JAR; PowerShell provides build and runner wrappers.

## Commands

From the repository root, with an installed JDK 21 and Node:

```powershell
node .\Intra-Paper-System\test-offline.mjs --java-home $env:JAVA_HOME --output-root <new-output-folder>
```

The output folder must be new. The runner compiles every production/test source and the Java pricing validation harness, rejects unlisted executable test mains, runs the suites, and packages only after every selected suite passes. Each child exit and its full output are saved under `logs/`; failures return exit code 1 and remain visible on the console. `logs/summary.json` records individual PASS/FAIL/NOT_RUN values, source hashes, build identity, classification, and package hash. No stale output is reused. `--expected-commit <sha>` additionally requires a clean checkout of that revision, as used by CI.

For a focused check:

```powershell
node .\Intra-Paper-System\test-offline.mjs --java-home $env:JAVA_HOME --output-root <new-output-folder> --suite simulation.SingleContractAdmissionTest
```

Focused runs explicitly report `FOCUSED_ONLY`; unselected suites and packaging remain `NOT_RUN`. The default run covers all Java executable entry points and the option-pricer mathematical harness. Independent QuantLib 1.44 comparison requires a separately installed pinned Python environment and is explicitly not run by this dependency-free runner. No package installation, market-data download, broker connection, credential read, or account access is part of these commands. Synthetic inputs and illustrative pricing anchors provide plumbing/numerical evidence only.

## Run identity and saved artifacts

Relative to checkout HEAD `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`, single-contract identity intentionally changes from `INTRA_RUN_ID_V10`/`V11_TRIAL` to `INTRA_RUN_ID_V12`/`V13_TRIAL`. The preserved uncommitted implementation already introduced this change; this increment verifies and records it.

Identity includes the entire admitted dataset input-hash map, ordered lexicographically by logical input ID, with a map count and length-prefixed UTF-8 keys/values. This includes bars, instrument master, session calendar, market-rule regimes, optional execution/component costs, margin requirements, expiry settlement, settlement costs, quote journal, option valuation inputs, and admitted permission evidence. Existing configuration/build/strategy inputs and sorted strategy parameters retain their prior encoding. Registered trial evidence binds the strategy card, split plan, parameter configuration, and exact registration record. Paths or incidental output labels are not newly added as inputs.

The run manifest declares the identity format and persists the complete admitted map as `dataset_input_file_sha256`, while retaining the existing `input_file_sha256` names. Manifest schema is now 15. Output-only `verify` continues to verify supported saved layouts without recomputing their input identity. `replay-verify` recomputes the current identity and exact output bytes; older identity formats/checkpoints do not migrate automatically, and prior manifest-schema bytes cannot match new schema-15 replay bytes. Preserve earlier artifacts and use the matching original build for historical replay verification. New runs/checkpoints use the current format.

Expanded partial-fill recovery coverage found that schema-1 order deltas recorded only newly created orders, dropping later updates to an already checkpointed order. Checkpoint payload schema 2 records new and changed orders and restores them by stable ID, preserving ledger order and immutable intent fields. The binary envelope and checksums remain unchanged. The current reader still accepts schema 1, but cannot reconstruct updates that an older build never persisted; inconsistent legacy recovery fails reconciliation. Older binaries reject schema-2 payloads. Preserve old checkpoints and replay with the matching build rather than rewriting them.

## Diagnostic admission

`ValidatedDataset.load(...)` permits diagnostic inspection of a gap only within the configured override/cap; it retains the supplied rows and completeness/anomaly counts. `loadForSimulation(...)` and `requireSimulationReady(...)` make simulation admission explicit. The engine, CLI run path, partition reporting, and artifact publication share the guard. It rejects incomplete sessions, missing intervals, spacing anomalies, or an observed/expected bar deficit before strategy evaluation, checkpoint restoration, or scored publication. Errors retain the configured override/cap and observed counts. No bars are fabricated or interpolated. The downstream statistical evaluator also already rejects incomplete saved session audits.

## Evidence scope

The baseline and final command/results are recorded in `OFFLINE-CORRECTNESS-2026-11-14.md`. Tests cover single-contract execution, journals, checkpoint corruption/recovery, OMS transitions/reservations, signed accounting, settlement, dated costs, artifact tampering, deterministic replay, trial/holdout controls, and synthetic operating evidence. This does not close data rights, authorized multi-expiry history, current rule authenticity, verified costs/margins, execution calibration/capacity, independent custody, power-loss durability, connected paper-clock integration, or observed G4 operating-session gates.
