# Synthetic external ledger fixture

These 22 independently authored normalized records match the synthetic two-contract `../portfolio-settlement` replay. The opening checkpoint is 2030-05-06 03:45 UTC and the final settlement/position checkpoint is 03:49 UTC. There are two full BUY fills, two signed settlement events, four fee totals, eight separate cash-flow records and four position checkpoints. No broker or account records are present.

The `itemized/` variant adds eight independently specified charge rows: two fill-model charges, three settlement charges and their three GST amounts. Its 30 rows require `ITEMIZED_AND_TOTAL`; the parent fixture uses `TOTAL_PER_EVENT`. Both require declared replay IDs, exact comparisons, explicit `tolerances=NONE` and caller policy provenance. The drill uses the itemized variant.

| Instrument | Trade notional cash | Settlement proceeds | Execution fee | Settlement fee | Final units |
|---|---:|---:|---:|---:|---:|
| F | −103 INR | 110 INR | 1 INR | 1.19 INR | 0 |
| O | −13 INR | 5 INR | 1 INR | 0.64 INR | 0 |

The manifest template is intentionally incomplete. `synthetic-external-ledger-drill.ps1` creates a fresh replay with the caller's JAR and replaces its run-ID/input-hash placeholders in a separate output directory. The normalized ledger is fixed; the script does not derive its economic amounts from replay outputs. All three CLI modes must pass. Repeated runs using the same JAR/input bytes produce the same audit hash.

```powershell
.\synthetic-external-ledger-drill.ps1 -JarPath <intra-paper-system.jar>
.\test-external-ledger-reconciliation.ps1 -JarPath <intra-paper-system.jar>
```

The acceptance runner also constructs synthetic split/partial fills, cancellations, corrections, reversals, short and zero-payoff settlement, mismatches, missing/unknown records, duplicate IDs, ambiguous keys, malformed inputs and byte tampering. It exercises actual verified single-contract full/partial-IOC replays and the portfolio v3 workflow. See the [schema and operating contract](../../docs/EXTERNAL-LEDGER-RECONCILIATION.md).
