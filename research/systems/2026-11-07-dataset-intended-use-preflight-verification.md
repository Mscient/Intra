# Dataset permission and intended-use preflight verification

Date: 2026-11-07. Session: `7365db1b-c2b1-4672-be43-5c398e68c753`.

Implemented the separate offline Java 21 checker in `com.mscient.intrapaper.permission.preflight`, three strict generated JSON Schemas, standalone build/CLI/test/drill runners, fictional input examples, and the [operating guide](../../Intra-Paper-System/docs/DATASET-INTENDED-USE-PREFLIGHT.md). It does not modify the concurrently developed `DataPermissionChecker` / `PermissionAdmission` adapter. Existing unrelated work was preserved.

The checker evaluates explicit source/product/data type, dated instruments and data/use intervals, simulation/backtest rights, storage, inclusive retention/deletion limits, derived outputs, sharing modes/recipients/material, owner and reviewer approval pointers, and permission effective dates. Missing/ambiguous declarations cannot authorize use. All applicable records must independently cover the requested scope; denials, revocations, contradictions, duplicate IDs, and mismatched bindings block permission. Reports bind exact input hashes, version and class-byte fingerprints, assessment date, reasons, and optional observed dataset hash. The guarded immutable-byte callback API never invokes research code on DENIED, INCOMPLETE, changing inputs, or a dataset-hash mismatch.

## Executed validation

Microsoft JDK `21.0.13.21-hotspot`, Java 21 target, Windows local workspace; no network or data acquisition. The sandbox allowed compilation but blocked javac classpath resolution during test compilation. The source and packaged acceptance commands were therefore run with the requested sandbox escalation.

| Check | Observed result |
|---|---|
| Standalone source acceptance | 300 checks passed. |
| Standalone JAR acceptance | Same 300 checks passed. |
| Synthetic CLI drill | Ten separate JVM check/reproduction invocations passed: ALLOWED, missing-reviewer INCOMPLETE, conflicting-record DENIED, expired DENIED, and exact dataset-byte verification. |
| Determinism | Source, standalone JAR, full-project JAR, repeated runs, relocation, and alternate locale/timezone reproduced the same reference decision bytes. |
| Before-use enforcement | Valid bytes reached the research callback; denied/incomplete evidence and changed dataset bytes did not. Dataset-file reading was skipped when declared permission failed. |
| Full project compilation/package | Passed after replacing the main build's long source command line with a javac argument file. The argument file is removed before packaging and is absent from the JAR. |
| PowerShell syntax | All four new runners and the edited main build parsed. |

Acceptance includes every missing schema field, wrong source/product/type/instrument/data dates, exact inclusive expiry boundaries, missing intended-use rights, retention conflicts, storage/output/sharing conflicts, changed manifest, contradictory/reordered/duplicate records, incomplete evidence sets, missing reviewer evidence, pending/rejected/revoked review, placeholder scope IDs, malformed UTF-8/JSON and duplicate keys, stale/tampered reports, and immutable output publication.

No unrelated simulator behavioral regression is claimed from the full compile. No hosted CI result, commit, push, contract review, production approval, or G2 closure is claimed.

## Retained identities

- Standalone JAR SHA-256: `ca403d93ede1bd018f895471791bc4c2ed46613f778f225ab6416866b2955123`.
- Full-project JAR SHA-256: `fc4f6c66a0b161cda81a6df3cb3906ac743e1c81bbe69c50cbdf8af31109c960` (`Build-Commit: unknown-dirty`).
- Checker class-byte fingerprint: `e59c2f2f36efb4292d3b758e7a0604aa9e1bafb8376875ca42cfc317a4bdf724`.
- Reference decision ID: `7561b6cbd4d14d861166e07f9abf715fc33252bbae28d9291c7de8fcb13f2211`.
- Reference decision-file SHA-256: `7200968069dc47b8f094ba4c21023588d230c5b0a0edd7f70710927b0dcc42c2`.
- Dataset-verified decision ID: `75e5112f9307340689ba283d4d1fb7459c43f585e7b25f16dbb50f2543de8592`.

Source evidence is under `Intra-Paper-System/build/dataset-permission-checks/15be4e8f000047f8a73f62b8cef0fd8a/`; packaged evidence under `build/dataset-intended-use-packaged-checks/1cf23f7d0b0545279725c75c29708b2f/`; standalone JAR under `build/dataset-intended-use-preflight/6f407e31cff743e1bfcca8b03b97a9cb/`; drill reports under `build/dataset-intended-use-drill/3e9a1d008ace4130a8b08050291f114b/`. Build folders are ignored local artifacts. Selected deterministic reports and logs are retained in [the synthetic verification bundle](dataset-intended-use-preflight-2026-11-07/).

The owner and qualified reviewer must supply and approve real permission evidence. The checker assesses declared scope and content identity; references/hashes do not prove contract validity or authenticity. It does not acquire data, inspect private terms, validate physical storage/deletion, detect withheld records, or intercept every legacy replay command. Adopting research callers must use the explicit preflight/guard, current assessment date, and frozen dataset bytes. All candidates remain unpromoted and G2 remains open.
