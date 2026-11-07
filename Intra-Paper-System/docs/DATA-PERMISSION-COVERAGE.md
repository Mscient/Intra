# Offline data-permission evidence coverage

This standalone Java 21 tool compares a dataset and an intended use with explicit,
caller-supplied evidence claims. It does not interpret a contract, grant rights,
authenticate evidence, or approve production use. **It does not close G2.** Real
permissions still require owner-provided evidence and qualified review.

Every report contains `permission_approved=false`, `production_eligible=false`,
`legal_approval=false`, `evidence_authenticated=false`, and `g2_closed=false`.
`SCOPE_MATCH` is only an evidence-scope comparison. Hashes establish file identity,
not the authenticity of a provider, reviewer, contract, entitlement, or permission.

## Build and run

No network, Maven, package download, database, or credentials are needed. Set
`JAVA_HOME` to an installed JDK 21. From `Intra-Paper-System` in PowerShell:

```powershell
$jar = ./build-data-permission-coverage.ps1
./test-data-permission-coverage.ps1 -JarPath $jar
$sample = './examples/data-permission-coverage'
$out = './build/coverage-reports'
./data-permission-coverage.ps1 -JarPath $jar check `
    "$sample/dataset.json" "$sample/intent.json" "$sample/register.json" $out
$report = Get-ChildItem -LiteralPath $out -Filter '*.json' | Select-Object -First 1
./data-permission-coverage.ps1 -JarPath $jar verify `
    "$sample/dataset.json" "$sample/intent.json" "$sample/register.json" $report.FullName
```

The build prints its new, uniquely named JAR path and retains build files. It
compiles only the checker and the existing `StrictJsonParser`. The existing
project build also includes the checker without changing the shared CLI.
`test-data-permission-coverage.ps1` without `-JarPath` compiles all project main
sources using a javac argument file and tests the checker against those classes.
Add `-RunRelevantChecks` (without `-JarPath`) to also compile all existing tests,
package the full project, and run the dataset frame, replay artifact, risk audit,
settlement audit, and daily-ops schema/artifact regressions. Logs and synthetic
outputs remain under a fresh run directory.

On any Java 21 platform, the entrypoint can also be invoked directly:

```text
java -jar data-permission-coverage.jar check dataset.json intent.json register.json reports
java -jar data-permission-coverage.jar verify dataset.json intent.json register.json reports/<report_sha256>.json
```

The bundled example contains only synthetic assertions and synthetic file bytes.
Expected `check` status is `SCOPE_MATCH`; it provides no actual permission.

| Command/result | Exit code |
| --- | --- |
| check: SCOPE_MATCH | 0 |
| check: OUT_OF_SCOPE | 1 |
| check: INCOMPLETE | 2 |
| verify: schema, identity and recomputation all agree (including INCOMPLETE reports) | 0 |
| Usage, publication I/O, or verification failure | 3 |

The runner should be invoked as its own PowerShell script/process when a caller
needs to handle nonzero exit codes. CLI diagnostics contain fixed codes and
hashes only, never input paths, evidence contents, or exception messages.

## Versioned input contracts

The normative structural schemas are in
[`schemas/data-permission-coverage`](../schemas/data-permission-coverage).
`CoverageSchemas` generates them from the same definitions used at runtime;
focused tests check byte-for-byte agreement. Semantic rules below are also
enforced in Java. The schemas use JSON Schema draft 2020-12.

All fields are required, including explicit empty arrays and permitted nullable
fields. Unknown fields, duplicate JSON keys, duplicate set members, unknown enum
values, malformed UTF-8, and unsupported versions are rejected. No field has a
permissive default. Identifiers are case-sensitive ASCII tokens (letters, digits,
underscore, dot, hyphen; maximum 128 characters). Use opaque or pseudonymized
identifiers for sensitive parties and records. `*` is never an identifier wildcard.

All dates are ISO `YYYY-MM-DD` calendar dates. All intervals include a literal
`"boundary":"[start,end)"`, an inclusive `start` and an exclusive `end`, with
`start < end`. Version 1 accepts no other boundary convention. It uses `LocalDate`
and the declaration's `assessment_date`, never the machine clock or timezone.

### Dataset: `data-permission-dataset/v1`

| Field | Meaning |
| --- | --- |
| `dataset_id`, `dataset_version` | Stable dataset identity and version |
| `provider_id`, `product_id` | Declared source/provider and product identifiers |
| `data_types` | Nonempty set: BARS, TRADES, QUOTES, DEPTH, INSTRUMENT_MASTER, SETTLEMENT, MARGIN |
| `instruments`, `date_scope` | Explicit instruments/contracts, each covering the entire half-open date interval |
| `files` | Nonempty set of `{file_id,path,sha256}`; IDs and paths must be unique |
| `storage_locations`, `compute_locations`, `backup_locations` | Declared existing locations; storage and compute are nonempty, backups may be explicitly empty |

Paths are local, relative to the manifest's directory. A content-addressed artifact
can be referenced by its local file path and its expected SHA-256; the tool does
not fetch or interpret an artifact registry. It hashes bytes without parsing the
dataset's rows or asserting their accuracy or coverage. Manifest dates and
instrument identity remain caller assertions.

### Intent: `data-permission-intent/v1`

`policy_version` must be `declared-coverage/v1`. `request_id` is stable, and
`dataset_id`/`dataset_version` must match the manifest. The declaration includes
`contracting_party_id`, `assessment_date`, `use_period`, `purpose`, `display_mode`,
`instruments`, `date_scope`, all three location arrays, `retention_days`, `deletion`,
and `derived_outputs`.

Purposes are RESEARCH, BACKTEST, SIMULATION, INTERNAL_ANALYSIS, or EDUCATION.
Display modes are DISPLAY or NON_DISPLAY. There is no unreviewed `OTHER` purpose;
a new enumerated purpose requires a schema/policy revision.

Location tokens have the form `local:<opaque-id>` or `remote:<opaque-id>`. Matching
is exact; no country, cloud region, legal jurisdiction, or network topology is
inferred. The checker tests the **union** of manifest and intent locations,
including backups. An empty backup array explicitly declares no backup action;
it cannot hide a backup listed by the manifest.

`assessment_date <= use_period.start` is required. The use period describes active
use. `retention_days` is an integer from 0 to 36500, starting at the exclusive end
of active use and covering all copies, backups, and retained derived outputs.
The effective permission interval must cover active use **and** that trailing
retention period. `DELETE_AT_END` requires zero retention; alternatively declare
`DELETE_AFTER_RETENTION`. The checker assesses this declaration; it does not
observe or perform deletion. Different per-copy lifetimes require separate,
fully scoped declarations or a future schema revision.

`derived_outputs` requires all four booleans: `retained`, `shared`, `published`,
and `distributed`. Each becomes an exact YES/NO request for the corresponding
permission term, including explicit NO when an action is not requested.

Intent instruments and dates must exactly equal the complete manifest scope.
A subset must be represented by its own accurate dataset manifest. A smaller
intent cannot make a partially covered full dataset appear covered.

### Register: `data-permission-register/v1`

`records` is a set (possibly empty). Each record has `record_id`, positive integer
`revision`, `state` (ACTIVE, REVOKED, SUPERSEDED), `claims`, `evidence`,
`revocation_ref`, `superseded_by`, `amends`, and `conflicts_with`.

`claims` includes provider, product, and contracting-party IDs, `permitted_scope`,
`prohibited_scopes`, `effective_period`, `terms`, and `retention`.

A selector is exactly `{ "all": false, "values": ["explicit-value"] }` or the
explicit broad declaration `{ "all": true, "values": [] }`. `all=true` with values
is ambiguous and rejected. Empty permitted values with `all=false` mean missing
permission, never universal permission. An empty prohibited selector declares no
listed prohibition. Broad declarations are reported with `explicit_broad_scope`.

A scope is `{instruments: selector, all_dates: boolean, dates: [intervals]}`.
Explicit `all_dates=true` requires an empty dates array; otherwise the dates must
be supplied to establish coverage. Overlapping intervals inside a scope are
ambiguous and rejected; adjacent intervals are allowed. `prohibited_scopes` is an
explicit **exception list subtracted from permitted scope**. Every exception
needs complete instrument and date selectors; an empty/unknown selector inside
an exception produces INCOMPLETE. This exception semantics is part of v1, not an
inferred interpretation of contract language.

`terms` requires each dimension below with both `permitted` and `prohibited`
selectors. Overlap between those selectors is contradictory and produces
INCOMPLETE, including broad selectors that overlap prohibited values.

| Dimension | Values |
| --- | --- |
| `data_types` | Dataset data-type enum |
| `purposes` | Intent purpose enum |
| `display_modes` | DISPLAY, NON_DISPLAY |
| `storage_locations`, `compute_locations`, `backup_locations` | Exact location tokens |
| `deletion` | DELETE_AT_END, DELETE_AFTER_RETENTION |
| `derived_retention`, `sharing`, `publication`, `distribution` | YES, NO |

`retention.permitted_max_days` is an inclusive maximum (or null for unknown).
`prohibited_from_days` is an inclusive prohibited threshold (or null for no
declared threshold). Overlapping permitted/prohibited retention ranges are
contradictory. All other retention numbers use the same 0..36500 bounds as intent.

`evidence` requires a nonblank `reference`, `reviewer_id`, `reviewer_role`,
`review_date`, `reviewed_fields`, and `claims_sha256`. `reviewed_fields` must list
all 18 report dimensions exactly. `claims_sha256` hashes the canonical claims
object, binding the asserted review to those exact fields. `path` and `sha256`
may be null structurally to represent unavailable evidence, but a missing local
evidence file or hash yields INCOMPLETE. A future review date also yields
INCOMPLETE. A `reviewed=true` field is rejected as unknown; reviewer labels and
hashes cannot authenticate anything.

All relation references are `{record_id,revision}`. Dangling and self references
are rejected. Any revocation assertion invalidates a record. Declared conflict
references make the register incomplete. Amendments must be complete replacement
records, not patches: an old SUPERSEDED record must identify one ACTIVE
replacement that reciprocally `amends` it and has the same party/provider/product.
The old record is excluded from coverage; its evidence must still verify, but its
historical effective period may be expired. Multiple or unresolved replacements,
unresolved amendments, and supersession cycles yield INCOMPLETE. Revocation does
not disappear through replacement. Disjoint records may share an ID with distinct
revisions; overlapping revisions remain ambiguous unless explicitly replaced.

## Decisions and report contract

| Status | Meaning |
| --- | --- |
| SCOPE_MATCH | Every required dimension has explicit coverage in one usable record for each complete instrument/date cell |
| OUT_OF_SCOPE | Well-formed evidence expressly excludes the request or its enumerated bounds/identifiers do not cover it |
| INCOMPLETE | Required inputs, fields, evidence, or review assertions are missing, malformed, ambiguous, expired, revoked, conflicting, or unverifiable |

INCOMPLETE takes precedence over OUT_OF_SCOPE in a mixed result. Both retain
individual reasons and covered/uncovered/conflicting cells. Invalid evidence
anywhere in the supplied register prevents SCOPE_MATCH, even if another record
would match. Supply a deliberately scoped register; there is no implicit
"latest wins" or "allow wins" policy.

For every manifest instrument, the checker splits its date interval at all
permission/exclusion boundaries. Every resulting cell is reported, with exact
start/end dates, status, reason codes, record tokens/revisions, and all 18
dimension results. Disjoint complete records can cover adjacent dates or distinct
instruments. The checker never constructs a complete permission by combining
unrelated dimension fragments. More than one applicable record for a cell yields
CONFLICTING_RECORDS or AMBIGUOUS_OVERLAP, even if both seem permissive. Effective
use periods are not stitched across records.

Top-level `dimensions` aggregate cell results. `coverage` is the complete
covered/uncovered/conflicting partition; a matching dimension in an uncovered
cell is not a complete scope match. `record_findings` records evidence/lifecycle
problems. Common codes include MISSING_PERMISSION_RECORD, IDENTIFIER_MISMATCH,
TERM_NOT_PERMITTED, EXPLICIT_PROHIBITION, EXPLICIT_SCOPE_PROHIBITION,
SCOPE_NOT_PERMITTED, MISSING_PERMITTED_TERM, MISSING_SCOPE, EXPIRED_RECORD,
REVOKED_RECORD, SUPERSEDED_WITHOUT_REPLACEMENT, DATA_HASH_MISMATCH,
EVIDENCE_HASH_MISMATCH, and REVIEWED_CLAIMS_HASH_MISMATCH. Structural errors use
INVALID_OR_MISSING_DATASET/INTENT/REGISTER. See the Java implementation for the
complete code vocabulary; codes and enum values contain no caller text.

## Canonicalization, identity and verification

Inputs are strict UTF-8 JSON, at most 8 MiB each. Schemas bound arrays at 1000
entries unless otherwise specified. Work is limited to 10000 instrument/date
cells and 10000 cell-times-record comparisons. Excess complexity produces an
INCOMPLETE report. Report reads are bounded at 128 MiB. Data/evidence files are
stream-hashed without an 8 MiB data-size limit.

Canonical JSON uses sorted object keys, no insignificant whitespace, lowercase
SHA-256, locale-independent numbers, and one final LF. All input arrays are sets
and sorted recursively by their canonical JSON representation for canonical input
and claims hashing. Duplicates are rejected, not silently discarded. String
case and Unicode content are preserved. There is no wall-clock creation time.

Each input has `raw_sha256` (exact bytes) and `canonical_sha256` (normalized JSON).
Reordering input rows leaves decisions and canonical input hashes unchanged, but
necessarily changes physical input hashes and therefore the full report identity.
Repeated checks of identical bytes and implementation produce byte-identical
reports across tested locales/timezones. Invalid/missing files may have null hashes.
`file_hashes` carries declared and observed data/evidence hashes; no evidence
contents or paths are copied into reports.

`report_sha256` is SHA-256 of canonical report JSON **without** its own hash field,
including the final LF. Publication writes `<report_sha256>.json` with that field
inserted. A lock, staged file, forced file write, and atomic move prevent partial
publication among cooperating writers. Identical existing artifacts are reused;
different bytes at an existing address fail. No non-atomic fallback is used.
The lock file is retained and carries no evidence. Output failures return exit 3.

`verify` validates the strict report schema, filename, canonical bytes, report
hash, all input/evidence identities, and full recomputation. Provenance binds the
executable class bytes (including nested classes and the shared JSON parser) and
the runtime schemas. Changed decisions with recomputed hashes, changed provenance,
or changed inputs fail verification. Compiler changes may change class identities;
retain the matching JAR for later verification. Verification demonstrates
reproducibility, never authenticity. This is not signed or independently attested
provenance; replacement of the trusted executable is outside its guarantees.

Report IDs are domain-separated SHA-256 tokens of input identifiers, e.g.
SHA-256(UTF-8(`permission-record:` + record_id)); revisions are retained. The
domains are `dataset`, `request`, `instrument`, `permission-record`, and `data-file`.
Owners can map tokens locally. No raw provider, product, party, reviewer, evidence
reference, record ID, or file path is emitted. Tokens are deterministic and
guessable for low-entropy IDs; they are not anonymization. Use opaque input IDs
and protect input files and reports with appropriate filesystem access controls.

## Guarantees and limits

The checker reuses the project's strict JSON parser and follows its explicit
input hashing and atomic artifact conventions. It adds no shared CLI command and
does not alter replay, evaluation, broker, permission records, or research records.

Only local files are opened. URI/UNC paths, traversal, symlinks and detected
reparse ancestors are rejected. The application makes no network requests and
does not resolve evidence references. Run against stable local files in a trusted
directory: mounted remote drives, filesystem races, hostile concurrent writers,
hardware failures, and compromised executables are outside these guarantees.
Atomic visibility does not promise power-loss durability of directory metadata.

Version 1 deliberately rejects ambiguity rather than interpreting legal text.
It has no contract parser, entitlement service, provider contact, legal inference,
rights inferred from public availability or download success, reviewer
authentication, retention enforcement, or production gate. All committed fixtures
are synthetic. Owner-provided permission evidence and qualified review remain
necessary; this tool does not close G2.
