# G2 admission validation evidence — 2026-11-11

Tested implementation: `3c774d2f2971c1ebe39d361e250057e9e872b971`. G2 remains INCOMPLETE. Only synthetic data was accessed.

- `original-logs/`: verbatim retained logs from the interrupted exact-commit matrix, including the incomplete RUN-only packaged log. Its original summary and failure logs were absent.
- `resumed-logs/`: resumed packaged result, manifest verification, interruption assessment and exact-commit hosted query (zero runs).
- `original-partial-runner/`: partial run environment and inspection listing; no completed checks log was present.
- `positive-artifact/` and `fully-rehashed-artifact/`: generated synthetic report/artifact bytes, including the forged pair rejected after both identities were repaired.
- `source-runner/`, `packaged-runner/`: assertion, schema, JDK and determinism evidence.
- `wrapper/`: 15 actual exit-code cases, a wrong-commit rejection, and rejection after report tampering with a repaired report ID and outer hash.
- `stages.json` and `summary.log`: 38 PASS stages, explicitly reconciled from 36 retained stages and two resumed stages. No uninterrupted full rerun is claimed.
- `build-and-output-hashes.log`: JAR, JDK executable/release and source/JAR report/artifact hashes.
- `SHA256SUMS`: SHA-256 of every other file in this evidence directory, using relative paths and exact stored bytes. Git attributes disable line-ending conversion here.

The original root is `C:\Users\prash\AppData\Local\Temp\codex-intra-origin-20261103\g2-admission-ci-3c774d2`; the resumed root is its sibling `g2-admission-resumed-3c774d2-20261111`. Full fixture trees and the JAR remain there. The successful source UUID is `336a38be2938467698e3913f61c9a5c8`; resumed packaged UUID is `557f7325ec3d46a3b1f273f9eca069b9`. Original partial UUID `ab602a4c29a446beab5efbc91e368828` remains untouched.

From the repository root, repeat wrapper validation against a matching generated synthetic fixture bundle with PowerShell 7:

```powershell
rtk proxy pwsh -NoProfile -File research/systems/g2-admission-validation-2026-11-11/verify-wrapper.ps1 -RepoRoot '<checkout>' -JarPath '<exact-JAR>' -FixtureRoot '<successful-run>/fixtures' -OutputRoot '<new-output>' -ExpectedCommit '3c774d2f2971c1ebe39d361e250057e9e872b971'
```

Set JAVA_HOME to the Microsoft JDK recorded in environment.log. The packaged test runner verifies Build-Commit before fixture generation; the retained wrapper driver also checks rejection of a deliberately wrong expected commit. Its positive fixture/build identity is supplied by that successful packaged run. The driver is validation evidence, not an admission or release authority.

[Completion report](../2026-11-09-g2-admission-validation.md) contains scope, audit, interruption diagnosis and integration limitations. Hashes and synthetic PASS do not establish source/use rights, issuer identity, completeness, market correctness, profitability, trading readiness or operational readiness.
