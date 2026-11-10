# Offline G2 evidence admission

Overall **G2 remains INCOMPLETE**. This command joins local evidence and permission declarations into a deterministic admission sidecar. It does not authorize access to a dataset. Obtain independent source/use authorization before opening any real file, including for hashing. Only repository synthetic fixtures were used to develop and validate this increment.

The report identifies the exact evaluated bytes and configured checks. `PASS` is available only for explicitly synthetic fixtures. `CALLER_DECLARED` evidence always carries `UNAUTHENTICATED_EVIDENCE` and remains INCOMPLETE (or FAIL if a check fails). Setting a classification is a caller assertion, not an authenticity test. Hashes cannot establish issuer identity, rights, completeness, correct market semantics, profitability, or readiness. All approval/readiness flags remain false.

## Run and verify

Use Microsoft JDK 21.0.13.0.2 and the normal production build. The build records `unknown-dirty` for an uncommitted checkout; it must not be presented as a tested commit. A clean build embeds the full `Build-Commit` in `META-INF/MANIFEST.MF`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
$env:INTRA_PAPER_BUILD_DIR = 'C:\path\to\new-build'
.\build.ps1
.\g2-admission.ps1 -JarPath 'C:\path\to\new-build\intra-paper-system.jar' verify 'C:\path\to\bundle\admission.json'
.\g2-admission.ps1 -JarPath 'C:\path\to\new-build\intra-paper-system.jar' run 'C:\path\to\bundle\admission.json' 'C:\path\to\new-report'
.\g2-admission.ps1 -JarPath 'C:\path\to\new-build\intra-paper-system.jar' verify-artifact 'C:\path\to\bundle\admission.json' 'C:\path\to\new-report'
```

| Outcome | Exit | Meaning |
| --- | --- | --- |
| PASS | 0 | All configured synthetic checks passed; G2 remains open. |
| FAIL | 2 | At least one configured failure; other unknowns remain in the report. |
| INCOMPLETE | 3 | Missing/unverified evidence or caller-declared authenticity. |
| Rejected input/artifact | 65 | Malformed, inaccessible, unsafe or changing inputs; no valid admission is established. |
| Usage error | 64 | Invalid command or argument count. |
| Verified artifact | 0 | Stored bytes reproduce, including a stored FAIL/INCOMPLETE report. Inspect its status; verification is not admission approval. |

`run` publishes `report.json` and `artifact.json` even for a well-formed FAIL/INCOMPLETE result. It recomputes before publication, writes and forces new files in a sibling staging directory, and uses an atomic directory move with no fallback. Existing output directories are rejected. Output must be outside the input bundle. An exception or failed atomic move never produces an accepted artifact; an OS crash may leave a staging directory for manual inspection. Directory-entry power-loss durability is not certified.

## Input contract and fixtures

The versioned schemas are in [schemas/g2-admission](../schemas/g2-admission/). Runtime semantic validation is authoritative and also enforces joins, bounds, permission order, unique identities, date relationships, Windows path restrictions and deterministic recomputation. JSON Schema validation by itself is insufficient. The report embeds the existing production permission decision; its semantic validity is checked by recomputing that production checker.

All three schemas resolve their definitions locally without fetching external references. The runner uses PowerShell `Test-Json` for three positive and three negative structural checks alongside the Java semantic and artifact checks. Use current PowerShell 7 with `Test-Json`; validation here uses 7.6.9.

Generate fresh, local synthetic fixtures with:

```powershell
.\test-g2-admission.ps1 -OutputRoot .\build\g2-admission-source
.\test-g2-admission.ps1 -JarPath 'C:\path\to\clean\intra-paper-system.jar' -ExpectedCommit '<full-40-character-commit>' -OutputRoot .\build\g2-admission-jar
```

Each invocation creates a unique directory containing `environment.log`, `checks.log`, fixtures, valid artifacts, negative artifacts and separate-process comparison logs. The valid template is `fixtures/valid-input/admission.json`. Synthetic raw/lifecycle bytes come from `examples/feed-audit/inputs/complete`. The normalized/mapping/calendar files, processing files and permission declarations are generated test data, not market/reference evidence or approved candidates. The fixture labelled CALLER_DECLARED also contains only synthetic bytes and exercises the INCOMPLETE boundary.

There are 9 mandatory resource roles: RAW_INPUT, LIFECYCLE_INPUT, NORMALIZED_DATA, TOKEN_MAPPING, SESSION_CALENDAR, DECODER, SCHEMA, BUILD and FEED_EVIDENCE. Additional FEED_REFERENCE resources bind every schedule, policy, provenance, decoder reference, mapping or quote file followed by the feed audit. Required roles occur once; FEED_REFERENCE may repeat. The generated valid fixture uses 12 resources. Each has a unique logical ID and relative path, contiguous order, exact lowercase SHA-256, explicit byte bound, observation time and three permission-document paths plus an explicit as-of date.

Every resource uses the existing `PermissionPreflight.check` before inspecting its dataset path. Its permission manifest must match the resource role, classification, hash and required calendar-date coverage. Raw/lifecycle source and product match capture identity. The as-of date must equal the assessment UTC date, and the intended use must include `AGGREGATE_METRICS`. Source/use declarations, including storage, retention, output and sharing terms, are checked by the existing checker; physical storage-policy enforcement and authenticity remain external responsibilities. Frozen bytes pass the existing `withAllowedDataset` guard before any feed audit or event checks use them. Denied/incomplete resources are not opened, and any such resource withholds feed re-audit.

The decoder/schema/build IDs and hashes must name their admitted resource bytes. Those files are never executed. The replay dataset ID must match the normalized permission manifest, and its normalized ID/hash must match the resource. Version labels remain explicit caller metadata. This sidecar does not reconstruct the raw-to-normalized transform or certify that the declared processing build produced it.

Feed re-audit runs against a temporary copy containing only admitted bytes. Undeclared references fail even if matching files exist in the original directory. It verifies the existing artifact exactly, joins the full relative raw/lifecycle paths and hashes, source/product/channel/connection/subscription/timezone, decoder ID, schedule identity/revision and required interval. Single-scope feed evidence is supported. The report retains every feed input hash. No implicit filename-only join or default calendar is used.

Each event binds an explicit resource, ordinal, byte offset, positive byte length and SHA-256 of that exact range. Event IDs and resource/ordinal pairs are unique; observation order and future/skew/age limits are checked. Domain labels cover capture, decode, normalization, mapping, session, permission and replay. These are byte references: ordinals, event types and domain labels are not validated as a universal semantic journal. `event_semantics_verified`, `normalized_projection_verified`, and `mapping_calendar_authority_verified` remain false. Unknown times/identities/hashes stay null or explicitly UNKNOWN and block PASS. No timestamps, prices or missing evidence are inferred.

Assessment time and age/skew limits are explicit inputs. A report is valid only for that stated assessment and policy, not forever. Reverification uses the same assessment, never the wall clock. Renewed assessments require new manifests and outputs. The tool does not authenticate observation timestamps or policy values.

## Filesystem and determinism

The manifest is strict UTF-8 JSON, at most 1 MiB. There are at most 64 resources, each at most 16 MiB and with at most 64 MiB total declared bounds. Reports are capped at 16 MiB. Relative paths exclude dot components, absolute/UNC paths, alternate data streams, device names and trailing-dot aliases; case aliases are rejected across resource paths. All ancestors are checked for links/reparse points, and canonical paths must remain inside the bundle. Permissions are never weakened when canonicalization fails.

The execution account must be able to traverse ordinary ancestors and resolve their real paths. This workstation's managed sandbox allows some file reads/writes but denies Java `toRealPath`; the same 27 baseline checks succeeded outside that sandbox. The user-local JDK path was also inaccessible there. Validation used the installed Program Files JDK with ordinary filesystem access. This does not establish a universal need for administrator rights. Do not replace canonical-path checks with lexical checks to work around this restriction.

Use an exclusively controlled, frozen input directory and trusted local scratch/output storage. Metadata is rechecked after evaluation, resource content is frozen after permission checks, and publication recomputes. Protection against a hostile actor concurrently replacing filesystem ancestors, trusted-anchor custody, signatures, authenticated release and OS power loss is outside this contract. Temporary admitted bytes are deleted on normal completion; a crash may leave local scratch files, so lawful retention controls must cover scratch storage too.

Reports use canonical sorted JSON, stable ordered arrays, explicit assessment times, relative paths and code fingerprints. Source and JAR test modes compile tests separately from production classes. A report fingerprints production classes; the validation logs additionally record the full JAR hash and exact Build-Commit. Recompilation with another JDK or code revision can change fingerprints and deliberately prevent reuse of old feed/admission artifacts. Regenerate the entire synthetic bundle for a different build.

The [gap matrix](G2-ADMISSION-GAPS.md) and [validation report](../../research/systems/2026-11-09-g2-admission-validation.md) distinguish software from external evidence. No network, account, broker, SmartAPI, WebSocket, cloud, order, real data, pricing, accounting, replay, risk, settlement or statistical formula functionality is added here.
