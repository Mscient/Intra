# Portfolio trial catalog templates

These files show the strict evaluator input shape. All identifiers and hashes in `catalog.example.json` are placeholders and will fail registration/run verification. Replace them with frozen registration and verified-artifact identities; use an independently preserved catalog hash. `split.example.csv` assigns ten illustrative synthetic sessions. It supplies no data, trial results, or trading-readiness evidence.

See [the evaluator guide](../../docs/PORTFOLIO-TRIAL-EVALUATION.md) for registration, capital, return, and provenance requirements.

`artifact_scope` defaults to `ALL_SPLIT_SESSIONS`. To evaluate final-session settlement in validation, explicitly set it to `DEVELOPMENT_VALIDATION_ONLY`, register a dataset containing all development/validation sessions and no holdout sessions, and generate that prefix replay with caller-supplied settlement events/costs. Keep the full frozen split, including its future holdout dates. Use the settlement-enabled parameter identity overload before replay. The two scopes are verified against exact session coverage and cannot silently substitute for each other.
