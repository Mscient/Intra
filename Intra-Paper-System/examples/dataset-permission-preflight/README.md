# Fictional permission preflight examples

These files are synthetic software fixtures. Every source, product, instrument, owner, reviewer, approval, evidence reference, and evidence digest is fictional. `synthetic.csv` is a tiny hash-verification payload, not a normalized market replay dataset. None of these files confers real data rights.

| Permission file | Assessment date | Expected result |
|---|---|---|
| `permission-evidence.json` | 2026-11-07 or 2026-11-08 | ALLOWED under the supplied fictional declarations. |
| `permission-evidence.json` | 2026-11-09 | DENIED: expired use permission. |
| `missing-reviewer.permission.json` | 2026-11-07 | INCOMPLETE: missing reviewer evidence reference. |
| `contradictory.permission.json` | 2026-11-07 | DENIED: both ALLOW and DENY records are supplied. |

All examples use `manifest.json` and `intended-use.json`. The declared retention interval is 7–9 November inclusive, exactly three days. Effective simulation/backtest rights end on 8 November. Retention after use is an explicitly declared survival right ending on 9 November.

Run from `Intra-Paper-System`:

```powershell
$jar = .\build-dataset-intended-use-preflight.ps1
.\synthetic-dataset-intended-use-drill.ps1 -JarPath $jar
.\test-dataset-intended-use-preflight.ps1 -JarPath $jar
```

The drill checks and reproduces all four outcomes plus dataset-byte verification, using ten separate CLI JVM invocations. The acceptance suite additionally generates scope, expiry, retention, sharing, malformed/missing evidence, mutation, deterministic-output, and before-use callback scenarios. See the [guide](../../docs/DATASET-INTENDED-USE-PREFLIGHT.md).

The manifest and its bindings use exact UTF-8/LF bytes; preserve the line endings. Changing the manifest requires rebinding the intended use and every permission record. Rebinding real inputs is a new owner/reviewer action; do not “repair” real evidence hashes to make a check pass.
