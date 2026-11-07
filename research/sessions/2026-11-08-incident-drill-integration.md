# Offline incident-drill integration and CI verification

Session: `faaffbde-911f-481f-b053-30c1d0ea66be`
Date: 2026-11-08 (Asia/Calcutta)
Branch: `codex/offline-incident-drills-20261107`
Base: fetched `origin/main` at `5785958ac70196074ed5c8e13133a9288b749f52`.
Implementation commit: `408cb72f8112c25c1a3d1723013be1dc8dae8383`.

## Integrated work

Four standalone Java classes, the strict scenario acceptance suite, three PowerShell commands, nine synthetic fixtures, the operating guide, and the verification report were ported from the older dirty checkout. The project CI now executes the incident suite as its twelfth focused runner and checks it against the production JAR. The independent source checkout, which also contained ongoing unrelated changes, was left untouched.

The runner invokes the existing `OfflinePortfolioRiskAdapter` and adds scenario safety state: SAFE_HALT, exact RECONCILED state, and an explicitly authorized RESET transition. It remains separate from the general simulator CLI and has no broker, account, network, credential, or order-routing capability.

## Validation

The full local Windows Java 21 offline CI run completed with `VALIDATION_STATUS=PASS` for source commit `408cb72f8112c25c1a3d1723013be1dc8dae8383` using Microsoft OpenJDK 21.0.13.0.2. All twelve focused suites passed, including:

- Incident drill: 198 acceptance checks against the standalone JAR and 198 against the production JAR; all nine scenarios were repeated in separate JVMs and verified with expected exit codes and identical manifest hashes.
- Risk gateway: 447 checks; process recovery: 728 checks, 308 child JVMs and 24 forced terminations.
- Risk replay: 84 checks; risk audit: 79 focused checks and 274 settlement scenarios; settlement audit: 88 checks.
- Portfolio trial evaluation: 165 checks plus 30,019 frozen synthetic HAC checks.
- Daily operations: 455 assertions; intended-use preflight: 300 source and 300 packaged checks plus ten synthetic CLI checks.
- Multi-record permission coverage: 102 scenarios / 1,648 assertions against the standalone and production JARs.

The production JAR `Build-Commit` exactly matched the tested source revision. JAR SHA-256: `3fc39b5888974c6c08c77799e7995b93843d3d9fd05ca33fbf8b7f92d28714a1`. `git diff --check` and PowerShell syntax parsing passed. Retained logs and artifacts: `C:\Users\prash\AppData\Local\Temp\codex-incident-drill-ci-20261107`.

## Limits

All drill inputs are synthetic. A passing drill is not a G4 operating session and does not prove live recovery. Restart replays a risk-adapter checkpoint in-process; it does not test controller process death, broker reconnection, OS reboot or power loss. The scenario's reset authority is not authenticated human approval. G0/G2/G4 remain open.
