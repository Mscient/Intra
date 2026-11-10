# Synthetic proposed G7 fixtures

**SYNTHETIC ONLY.** No real operational evidence, approved G0–G6 decision, owner
adoption or authenticated independent review is represented here.
`g7_closed=false`, `operation_authorized=false`, `scale_authorized=false`.

The clean-looking fixture contains matching envelope hashes, synthetic original
gate-decision declarations, explicit coverage placeholders and a complete local
history. Its expected overall status is `BLOCKED`, because authentication and
genuine human decisions are unavailable. It cannot become eligible or authorize
anything. The `missing-period` variant preserves an explicit `INCOMPLETE` missing
period finding under that block. The `build-drift` variant adds a logged change and
its human stop/requalification requirements; it stays blocked.

The [clean-looking](dossiers/clean-looking/manifest.json),
[missing-period](dossiers/missing-period/manifest.json) and
[build-drift](dossiers/build-drift/manifest.json) dossiers are exported by
`G7SyntheticFixtures examples` from the
focused test classes. The focused suite also generates original synthetic feed,
daily, replay/risk/settlement and normalized-ledger evidence and adversarial cases
under its ignored validation output. Every synthetic dossier registers exact
artifact paths/sizes/hashes and a canonical history. Generated test artifacts are
software diagnostics and do not establish market/account completeness.

Run [the documented commands](../../docs/G7-OPERATIONS-EVIDENCE.md) with a new
output directory outside the dossier. `evaluate` exit 3 is expected for these
fixtures; `verify` exit 0 establishes byte identity only. Keep private evidence and
raw history outside public reports and the repository.
