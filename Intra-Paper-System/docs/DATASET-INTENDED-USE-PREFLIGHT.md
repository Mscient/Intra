# Dataset permission and intended-use preflight

`com.mscient.intrapaper.permission.preflight.PermissionPreflight` is a separate offline Java 21 checker for the open G2 data-rights gate. It evaluates an explicit dataset manifest, intended-use declaration, and owner-supplied permission evidence set. It returns **ALLOWED**, **DENIED**, or **INCOMPLETE** with field-specific reason codes. Both DENIED and INCOMPLETE must stop a research run.

**ALLOWED means only that complete caller declarations match under this checker.** A reviewer identity, approval declaration, evidence reference, or hash is an evidence pointer, not proof that the underlying contract exists, is authentic, is valid, or grants those rights. The owner and a qualified reviewer must supply and approve the real evidence through an appropriate private process. The checker never fetches references, opens contracts, acquires datasets, contacts providers, or changes candidate approval status. Every decision contains `production_approved=false`, `contract_validity_verified=false`, and `g2_closed=false`. This implementation cannot close G2.

## Build and run

No dependencies, accounts, downloads, or simulator are required; only JDK 21. The standalone build retains its classes and JAR under a fresh local build directory. The repository's normal full build also includes the checker.

The full build uses a compiler argument file to avoid the Windows process command-line length limit as the source tree grows.

```powershell
$jar = .\build-dataset-intended-use-preflight.ps1
$example = '.\examples\dataset-permission-preflight'

# Declaration-only assessment. The date is explicit and never defaults to the clock.
.\dataset-intended-use-preflight.ps1 -JarPath $jar check `
  "$example\manifest.json" "$example\intended-use.json" "$example\permission-evidence.json" `
  2026-11-07 .\build\declared-permission.json

# Also verify an already-authorized local file's exact bytes, only after rights pass.
.\dataset-intended-use-preflight.ps1 -JarPath $jar check-dataset `
  "$example\manifest.json" "$example\intended-use.json" "$example\permission-evidence.json" `
  2026-11-07 .\build\dataset-permission.json "$example\synthetic.csv"
if ($LASTEXITCODE -ne 0) { throw 'Dataset preflight blocked research use.' }

# Reproduce a declaration-only report against all current inputs and checker bytes.
.\dataset-intended-use-preflight.ps1 -JarPath $jar verify `
  "$example\manifest.json" "$example\intended-use.json" "$example\permission-evidence.json" `
  2026-11-07 .\build\declared-permission.json

.\test-dataset-intended-use-preflight.ps1
.\test-dataset-intended-use-preflight.ps1 -JarPath $jar
.\synthetic-dataset-intended-use-drill.ps1 -JarPath $jar
```

Direct entry point: `java -jar <standalone-jar> <arguments>` or `java -cp <full-jar> com.mscient.intrapaper.permission.preflight.PermissionPreflightCli <arguments>`. For assessments and verification, exits are 0 ALLOWED, 2 DENIED, 3 INCOMPLETE, 4 failed report reproduction, 64 usage error, and 74 command/publication error. **Only an assessment's exit 0 may pass the gate.** `schema` exports documentation and its successful exit is not permission.

Reports are UTF-8/LF JSON. Existing identical reports are accepted; a different existing report is never overwritten. Use a distinct report path per assessment. A failed/partial publication cannot pass reproduction. Reports must not alias inputs. The command prints a compact status and decision ID; details are in the report. Missing/unreadable inputs generate INCOMPLETE with null hashes, never invented hashes. A publication failure stops the command with a nonzero exit.

## Strict input contracts

The three [JSON Schemas](../schemas/dataset-intended-use-preflight/) are generated from the same field definitions used by the runtime checker. Export one with `schema dataset-manifest`, `schema intended-use`, or `schema permission-evidence`. All listed fields are mandatory; unknown fields, duplicate JSON keys, wrong types, nulls, blanks, unsupported enum values, malformed UTF-8, BOMs, invalid calendar dates, duplicate set members, and empty sets block permission. Documents are limited to 1 MiB, sets to 256 members, and an evidence set to 64 records. No wildcards or fuzzy matching exist. Schema validation is necessary; cross-document and semantic checks below are also mandatory.

Bare placeholder identifiers/references such as UNKNOWN, UNSPECIFIED, ANY, ALL, AMBIGUOUS, TBD, and NONE are rejected regardless of case. Literal NONE is supported only in the explicitly defined no-output/no-sharing fields. Other identifiers still require meaningful owner/reviewer interpretation; matching strings cannot prove their truth.

| Input | Explicit declarations |
|---|---|
| Dataset manifest | `schema_version=1`, `dataset_id`, `dataset_sha256`, `classification` SYNTHETIC or CALLER_DECLARED, `candidate_status=CANDIDATE`, `source_id`, `product_id`, `data_type`, full dated `instruments`, inclusive `data_start`/`data_end`. |
| Intended use | `schema_version=1`, `use_id`, exact `manifest_sha256`; source/product/type/instruments/data dates; `purposes`; `use_start`/`use_end`; `storage_locations`; `retention_start`/`delete_by`; `derived_outputs`; `sharing_mode`, `sharing_recipients`, `shared_material`. |
| Permission evidence set | `schema_version=1`, `evidence_set_id`, matching `classification`, `completeness` COMPLETE/INCOMPLETE/UNKNOWN, and nonempty `records`. COMPLETE is the owner's assertion that all applicable records have been supplied. |
| Each permission record | Unique `record_id`, exact `manifest_sha256`, `effect` ALLOW/DENY, `record_state` ACTIVE/REVOKED/AMBIGUOUS; full source/product/type/instruments/data dates; `purposes`; `effective_from`/`effective_through`; `storage_locations`; `max_retention_days`, `retain_until`; `derived_outputs`, `sharing_modes`, `sharing_recipients`, `shared_material`; `owner`, `owner_approval`, `owner_approval_reference`; `reviewer`, `review_status`, `review_reference`, `reviewed_on`; `evidence_reference`, `evidence_sha256`. |

IDs are exact, case-sensitive ASCII logical identifiers, not paths or lookup queries. Data types and instruments have no inferred taxonomy: use dated contract identifiers rather than a generic underlying to represent a futures/options contract. Storage IDs must identify every intended storage, compute, backup, and geographic environment under the owner's reviewed naming scheme. The checker matches declarations, not actual machine configuration. Keep evidence references redacted and logical; no reference is dereferenced. Hashes are lowercase SHA-256 of exact bytes. The dataset hash describes one frozen local file; evaluate separate manifests for separate files/products, and require every decision to pass. Do not combine differently licensed files under one ambiguous scope.

`purposes` explicitly enumerates SIMULATION and/or BACKTEST. Live trading and other unmodeled uses are unsupported. Derived outputs are NONE, AGGREGATE_METRICS, CHARTS, SIGNALS, MODEL_PARAMETERS, or TRANSFORMED_DATA. Sharing modes are NONE, INTERNAL, EXTERNAL, or PUBLIC. Sharing material additionally permits RAW_DATA. Recipient IDs must explicitly identify the recipient or reviewed audience. No sharing hierarchy is inferred: PUBLIC does not silently grant INTERNAL. In an intended use, NONE sharing requires exactly `["NONE"]` recipients and material; NONE cannot be mixed with actual outputs/material/recipients. Shared derived material must also be listed among intended derived outputs.

## Deterministic matching and conflict policy

1. Parse and hash each document from the same bounded byte snapshot. Missing or malformed evidence produces INCOMPLETE. `schema_version` is the string `"1"`; versions are never guessed.
2. Both intended use and every permission record must name the exact manifest hash. Any change to the manifest, including whitespace, invalidates old bindings. Source/product/type identifiers must agree exactly. Intended instruments/dates must fit the manifest; **every permission record must cover the entire frozen dataset**, even if the intended calculation uses a subset.
3. Every requested purpose, location, derived-output type, sharing mode, recipient, and shared-material type must be explicitly present in every record. There are no defaults, inherited clauses, unions of partial grants, or “latest record wins” rules. All supplied records are declared applicable to this dataset. Wrong-scope records block permission instead of being silently discarded.
4. All dates are literal ISO calendar dates, inclusive. The explicit assessment date must fall inside both the intended use interval and each permission's effective interval. The effective interval must cover storage from `retention_start` through `use_end`. Review must be on/before the assessment and use start. A review after use begins cannot retroactively authorize it. Sub-day rights are unsupported; the owner/reviewer must not round narrow timestamp rights into wider calendar-day permission.
5. `retention_start <= use_start <= use_end <= delete_by`. Count retained days inclusively: `days_between(retention_start, delete_by) + 1`. The count must not exceed `max_retention_days`, and deletion must be on/before `retain_until`. These restrictions apply to the declared data, copies/backups, and outputs. Post-use storage past `effective_through` is permitted only within this explicitly supplied survival/deletion scope; it is not inferred from a simulation right. More complex derivative/backup/legal-hold retention terms need reviewer resolution into a supported conservative declaration.
6. Owner and reviewer approvals must both be APPROVED; PENDING is INCOMPLETE, REJECTED is DENIED. AMBIGUOUS records and incomplete/unknown evidence sets are INCOMPLETE. REVOKED and DENY records are DENIED. Mixed ALLOW/DENY records produce CONTRADICTORY_RECORDS. Duplicate IDs and reversed date intervals are DENIED. Differing ALLOW restrictions are applied conjunctively; they never expand the scope.
7. Any evaluated denial takes precedence over incompleteness; otherwise every required validation and comparison must succeed for ALLOWED. If an input fails schema validation, dependent comparisons are skipped and remain incomplete; a valid-looking fragment of that input never authorizes use. Input snapshots are rechecked before returning a decision. Changed inputs fail closed.

## Before-use enforcement and stale decisions

`check` assesses declarations; it does not open the dataset. `check-dataset` additionally hashes a local dataset only after declaration permission passes. A dataset byte mismatch is DENIED. Its report records `dataset_verification=EXACT_BYTES` and the observed hash; other reports record DECLARATION_ONLY or UNVERIFIED. A changed local file after this command is not protected by a prior report. Rerun against frozen inputs immediately before each run, use a trusted current assessment date, retain the resulting decision ID with the research artifact, and never use an old exit code or status string as a perpetual grant.

For a Java caller, `PermissionPreflight.withAllowedDataset(manifest, use, permissions, asOf, frozenDatasetBytes, researchCallback)` enforces the preflight and exact dataset hash **before invoking research code**. It gives the callback a private snapshot of the bytes that matched the manifest. DENIED, INCOMPLETE, changed inputs, and hash mismatches throw without invoking the callback. The caller must already be authorized to possess those bytes and must supply the actual intended behavior and current assessment date. The callback contract is suitable for parsers that consume immutable bytes; do not reopen a mutable dataset path instead.

This standalone command/API is an explicit gate for its callers, not a universal interception layer for every historical replay command. It does not modify the separate `DataPermissionChecker`/`PermissionAdmission` integration being developed in this workspace, nor treat that checker's result as a substitute for these three inputs. A research orchestrator adopting this contract must gate **every** dataset before use and preserve frozen input identity across subsequent parsing. The checker cannot enforce physical deletion, prevent a caller bypass, detect withheld records, validate the truth of scope labels, or validate the legal interpretation supplied by a reviewer.

Each decision binds the exact manifest, intended-use, and evidence-set hashes; the version string and its SHA-256; the SHA-256 fingerprint of sorted checker/schema/serializer/CLI and strict-parser class bytes; the explicit assessment date; observed dataset hash when checked; and all reasons. `decision_id` is SHA-256 of the sorted-key UTF-8/LF payload before inserting `decision_id`. There is no clock, random ID, machine path, file timestamp, or environment-dependent ordering in the report. Identical bytes/date/code reproduce after relocation and across standalone source/JAR use. Different compiler-generated class bytes intentionally change identity. Hashes establish content identity, not authenticity or signatures.

`verify` fully reruns the declaration assessment and compares every report byte. A stale/tampered report, changed intended use, changed evidence record, changed assessment date, or changed checker fails reproduction. A faithfully reproduced DENIED/INCOMPLETE report retains its blocking exit code. Use `verify-dataset` with the same argument list as `check-dataset` to reproduce a dataset-verified report against the frozen local file as well.

## Synthetic acceptance

The [fictional example bundle](../examples/dataset-permission-preflight/) grants both simulation and backtest use for 7–8 November 2026, with three inclusive retention days and deletion on 9 November. Its reviewer names, approvals, and evidence digest are deliberately fictional. They must not be copied into real-data evidence.

The test runner retains generated scenario inputs, reports, a reference decision, and an acceptance log. Checks cover valid in-scope permission, source/product/type/instrument and historical-date mismatches, exact expiry boundaries, missing intended-use rights, retention days/deadlines, locations, derived outputs and sharing, changed manifest and dataset bytes, conflicting/reordered/duplicate records, missing reviewer evidence, pending/rejected review, every missing schema field, strict malformed inputs, report tampering/staleness, deterministic relocation, immutable outputs, and blocked research callbacks. No real data or private terms are involved. These are software acceptance checks, not G2 evidence approval.
