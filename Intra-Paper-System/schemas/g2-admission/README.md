# G2 admission v1 schemas and synthetic fixtures

G2 remains INCOMPLETE. These schemas describe a bounded local admission sidecar; they do not authorize data access.

| File | Contract |
| --- | --- |
| input.schema.json | Admission manifest, identities, limits, ordered resources, permissions and byte-bound events. |
| report.schema.json | Deterministic result with explicit unknowns and all readiness/authority flags false. |
| artifact.schema.json | Version, code identity, report identity and exact report hash. |

Use PowerShell 7 with Test-Json. Definitions resolve locally; no external schema retrieval is needed. Java runtime semantics remain authoritative for joins, dates, bounds, path preflight, permission-before-resource reads and full artifact reconstruction. A schema-valid file is not necessarily admitted.

From Intra-Paper-System with JAVA_HOME set to Microsoft JDK 21.0.13.0.2:

```powershell
rtk proxy pwsh -NoProfile -File ./test-g2-admission.ps1 -OutputRoot '<new-source-output>'
rtk proxy pwsh -NoProfile -File ./test-g2-admission.ps1 -JarPath '<clean-production-JAR>' -ExpectedCommit '<full-commit>' -OutputRoot '<new-packaged-output>'
```

Each successful invocation prints a UUID directory. Its fixtures/valid-input/admission.json is the generated template; valid-artifact/report.json and artifact.json are corresponding outputs. Twelve synthetic resources include all nine mandatory roles and three feed references. Permission declarations and normalized/reference/processing bytes are fictional. CALLER_DECLARED negative fixtures also contain only synthetic bytes.

The runner checks all three positive schemas and rejects an extra input property, a true g2_closed flag and a malformed report digest. Retain schema-checks.log, checks.log, environment.log and runner-summary.log. Fresh fixtures are required when production class fingerprints change; editing hashes in old artifacts does not make them valid.

For one structural check:

```powershell
Test-Json -Json (Get-Content -Raw '<run>/fixtures/valid-input/admission.json') -SchemaFile './schemas/g2-admission/input.schema.json'
```

The [operating guide](../../docs/G2-ADMISSION.md) defines exits, safe output placement, permission/control-input responsibilities and filesystem limits. [Exact-commit validation](../../../research/systems/2026-11-09-g2-admission-validation.md) records the 2026-11-11 completion and retained evidence. Never substitute real files without independent authorization to inspect them, including for hashing.
