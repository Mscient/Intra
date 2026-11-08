# Offline historical candle ingestion v1

This standalone Java 21 subsystem plans requests, inspects explicit local files,
normalizes synthetic candles, and reproduces hash-bound diagnostic bundles. It
contains no HTTP client, SmartAPI authentication, broker action, cloud integration,
or deletion scheduler. Existing replay outputs and the older saved-response importer
are unchanged. This command is the permission boundary for its own callers; it does
not intercept other importers or replay commands.

**Angel One ingestion is blocked in v1.** The existing
[intended-use preflight](DATASET-INTENDED-USE-PREFLIGHT.md) and
[permission coverage checker](DATA-PERMISSION-COVERAGE.md) explicitly do not
authenticate provider permissions. ALLOWED/SCOPE_MATCH declarations cannot satisfy
the requested verified provider-rights gate. The service invokes these checkers for
diagnostics, records their decision identities, and returns PERMISSION_DENIED before
opening the supplied raw path. There is no force/skip/default-allow flag. G0 and G2
remain open/incomplete; every bundle records `permission_approved=false` and
`production_eligible=false`. No real permission evidence is bundled or invented.

## Build and commands

Set `JAVA_HOME` to an installed JDK 21. From `Intra-Paper-System`:

```powershell
$env:INTRA_PAPER_BUILD_DIR = Join-Path $env:TEMP 'historical-package'
./build.ps1
$jar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
$sample = './examples/historical-ingestion'
$output = Join-Path $env:TEMP 'historical-bundles'
New-Item -ItemType Directory -Force -Path $output | Out-Null

# Planning only: no acquisition or authentication, for either source identity.
./historical-ingestion.ps1 -JarPath $jar plan `
  --request "$sample/request.json" --policy "$sample/policy.json" `
  --mapping "$sample/mapping.csv" --out "$output/plan-v1"

# The committed fixture is fictional, explicitly SYNTHETIC_CANDLES.
./historical-ingestion.ps1 -JarPath $jar ingest `
  --request "$sample/request.json" --policy "$sample/policy.json" `
  --raw "$sample/candles.json" --mapping "$sample/mapping.csv" `
  --master "$sample/master.csv" --calendar "$sample/calendar.csv" `
  --out "$output/ingest-v1"

# Recompute from the same explicit input files and the same retained JAR.
./historical-ingestion.ps1 -JarPath $jar verify --operation ingest `
  --request "$sample/request.json" --policy "$sample/policy.json" `
  --raw "$sample/candles.json" --mapping "$sample/mapping.csv" `
  --master "$sample/master.csv" --calendar "$sample/calendar.csv" `
  --out "$output/ingest-v1"

./test-historical-ingestion.ps1 -OutputRoot (Join-Path $env:TEMP 'historical-source-tests')
./test-historical-ingestion.ps1 -JarPath $jar -OutputRoot (Join-Path $env:TEMP 'historical-jar-tests')
```

The independent production-JAR entrypoint is
`java -cp <intra-paper-system.jar> com.mscient.intrapaper.ingestion.HistoricalIngestionCli`.
It does not invoke the replay CLI. `verify --operation plan` takes the same inputs
as `plan`. All flags consume exactly one argument. Duplicate and unknown flags are
errors, except repeated `--evidence-file` described below. There are no defaults for
request metadata, capture time, policy or output. `--mapping`, `--master`, and
`--calendar` may be omitted to diagnose INCOMPLETE coverage. `--raw` is required for
ingest/verify-ingest, including a blocked provider request. Output parents must
already exist. Use short temporary build paths on Windows to avoid JDK classpath
limits with deeply nested worktrees.

| Result | Exit |
| --- | --- |
| PASS_PLANNED / PASS_SYNTHETIC, including exact verification | 0 |
| REJECTED / PERMISSION_DENIED, including reproduced blocked diagnostics | 2 |
| INCOMPLETE, including reproduced incomplete diagnostics | 3 |
| Verification byte/file-set/recomputation mismatch | 4 |
| Invalid command arguments | 64 |
| Unsafe path, command or atomic publication failure | 74 |

`complete.json` means that a diagnostic bundle is atomically visible. It does not
mean the data passed validation. Only read `status` with the exit code; a reproduced
denial remains denied. PASS_SYNTHETIC means the fictional records passed the stated
software checks, not that any source permission or trading gate passed.

## Strict request and policy

Structural JSON Schemas and the fictional inputs are under
[`schemas/historical-ingestion`](../schemas/historical-ingestion/) and
[`examples/historical-ingestion`](../examples/historical-ingestion/).
Runtime semantics are also mandatory. Every request field is required; unknown
fields, duplicate keys, malformed UTF-8/BOM, unsupported versions, and secret-like
content are rejected. Inputs are bounded to 16 MiB each. IDs are short ASCII logical
identifiers, not paths. Source tokens are single digit strings (1–18 characters),
not credentials. The request names exactly one dated contract identity.

The v1 canonical derivative boundary is `exchange=NFO`, with master rows identifying
`exchange=NSE, segment=NFO`. Other exchanges are rejected. Intervals reuse
`IntervalSupport`: ONE_MINUTE, FIVE_MINUTE, FIFTEEN_MINUTE, THIRTY_MINUTE, ONE_HOUR,
ONE_DAY. An interval must also occur once in the policy for NFO. Other SmartAPI
intervals are intentionally unsupported until the canonical dataset contract can
represent them. The example policy permits only ONE_MINUTE and FIVE_MINUTE.

`from`/`to` carry explicit offsets, seconds, and a year of four digits; timestamps
without offsets are rejected. Internal and plan boundaries are `[start,end)` in UTC.
Range endpoints must be whole minutes, span a positive integral number of candles,
and span at most 366 days. At most 10,000 windows are planned. Each configured maximum
window must be a positive multiple of its interval. Windows are adjacent and bounded
by the caller's requested end. Dated mapping coverage is checked throughout the
entire range, including mapping transitions, not just at request endpoints. A
resolved token identifying another contract rejects the plan; missing, invalid or
overlapping mappings yield nonzero INCOMPLETE. No current mapping is substituted.

The policy records `policy_id`, `effective_date`, `review_by`, source URL, declared
evidence status, boundary convention, pacing, concurrency and per-interval window
limits. Its exact bytes are hashed into the plan and manifest. The source URL is
the public documentation locator `https://smartapi.angelone.in/docs`; the command
does not open it. The bundled 120-second/3600-second window limits and 2000 ms pace
are deliberately conservative **illustrative owner configuration**, not asserted
provider limits. `OWNER_CONSERVATIVE_UNVERIFIED` must remain explicit. Pacing is
configurable from 1000 to 3600000 ms, with one concurrent request, and is only
recorded: the planner does not sleep or execute requests. Review/replace the policy
after obtaining dated provider documentation; do not treat it as permanent truth.
The explicit assessment date must be within the policy's validity interval.

The future authorized connector must establish the provider's inclusive/exclusive
endpoint behavior before translating these internal windows into HTTP parameters.
The plan is not an assertion that every requested expired F&O contract has history.

## Adapter, references and normalization

The isolated `SmartApiCandleAdapterV1` accepts only
`{"status":true,"message":"SUCCESS","errorcode":"","data":[...]}`. Each row is
exactly `[timestamp, open, high, low, close, volume]`. Bare arrays, additional response
fields, provider errors, nonnumeric prices, and malformed rows are rejected. Prices
are positive locale-independent BigDecimal values, at most 32 digits of precision
and an absolute scale of 10. Non-finite numbers are invalid JSON. Volume is a
nonnegative signed 64-bit integer or explicit null; null stays unknown. Open interest
is absent in this adapter and remains blank. OHLC must be internally consistent.
No out-of-range candle is admitted and no malformed input value is echoed.

The request freezes `captured_at`, `assessment_date`, `timestamp_role` (BAR_START or
BAR_END), and `timezone=Asia/Kolkata`. Nothing defaults to the clock or machine zone.
Capture must not follow the assessment date in UTC. Timestamp semantics are caller
declarations; explicit offsets alone do not prove whether a provider candle denotes
its opening or closing time. Neither timestamp roles nor missing rows are repaired.

The pipeline reuses:

- `DatedInstrumentMappingRegistry`: exact archived exchange/token/UTC mapping and
  mapping row IDs/canonical hash. See [mapping schema](INSTRUMENT-MAPPING-REGISTRY.md).
- `InstrumentMasterCsvReader` and `InstrumentMasterSnapshot`: the existing dated
  CSV, using explicit `master_snapshot_id`, `master_retrieved_at` and
  `master_effective_from`. An added overload avoids constructing replay settings.
  Stable ID, symbol, type, expiry, strike and option right must agree with mapping.
- `SessionCalendarCsvReader`: explicit OPEN/CLOSED entries for every requested local
  date, using the existing first/last bar timestamp and regime/reference fields.
  `calendar_id` and `calendar_basis` declare input identity and evidence limits.
- `Bar`: the existing canonical OHLC, positive-price, derivative and tick-size checks.
  `normalized.csv` uses exactly the existing `BarsCsvReader` column order and UTC
  timestamps. It is a bars input, not a complete replay configuration or approval.

Calendar bounds are inclusive expected **bar timestamps** for the declared interval
and timestamp role, not independently inferred exchange open/close times. Every
expected timestamp within the requested half-open range is enumerated on that grid.
Both input candles and enumerated expected timestamps are limited to 100,000.
Missing calendar dates/grid validity or instrument coverage are INCOMPLETE, including
reference checks at missing candle timestamps. The existing calendar reader requires
at least one OPEN entry; a file containing only closed dates is INCOMPLETE in v1.
Supplied calendars are not authenticated as exchange-authoritative; actual source and
review evidence remain open. The synthetic calendar is an invented three-minute
session and is not an NSE schedule.

Exact duplicates (same UTC timestamp and candle values), conflicting duplicates,
row-order regressions, unexpected grid timestamps, and session violations are
reported. Duplicates and disorder reject the dataset; no sorting or deduplication
silently changes the input. Missing expected candles yield INCOMPLETE with explicit
timestamps. Valid mapped rows may still be normalized when the only defect is
missing candles. Any row-level reference uncertainty withholds the entire normalized
file; invalid records also withhold it. Never interpret a gap as zero volume, a flat
price, a holiday, or no trading. For larger session definitions the caller must
supply supported explicit schedules; this v1 format does not infer intraday breaks.

## Permission diagnostic inputs

Optional provider diagnostic flags are `--preflight-manifest`, `--intended-use`,
`--permission-evidence`, `--coverage-dataset`, `--coverage-intent`, and
`--coverage-register`. Each takes one explicit local JSON path using the existing
checker schemas. Synthetic mode rejects all permission flags to prevent mixing
fictional and provider evidence. Provider mode is denied regardless of these inputs:
v1 has no authenticated permission-result capability.

The preflight runs on frozen document snapshots and records its decision ID. The
coverage checker runs on supplied declarations only when safe to do so; otherwise
it records the existing checker's missing-input INCOMPLETE result. Its report hash
is retained. Evidence files referenced by the coverage register must each also be
explicitly named with `--evidence-file`; no other references are opened. This boundary
supports UTF-8 text evidence only; binary evidence cannot pass its safety checks. Coverage
dataset `files` must be empty at this blocked ingestion boundary, so it cannot read
market bytes before authorization. Use the independent coverage command for a full
already-authorized dataset assessment. Nothing here treats those diagnostics as
proof of rights or as approval of the ingestion request's scope. Original input
hashes are retained; evidence contents, party identifiers and references are not
published. Sensitive evidence remains in its separately controlled owner process.

## Publication and reproduction

Bundles are flat directories with `schema_version=historical-ingestion-manifest/v1`:

- `manifest.json`: request identity and frozen metadata, exact input hashes,
  permission decision ID, parser/software versions, compiled-class-tree fingerprint,
  production JAR Build-Commit (or SOURCE_CLASSES), status, file hashes and fixed gates.
- `plan.json`, `validation.json`, and for ingestion `permission.json` when the
  permission assessment completed and later strict parsing did not reject the run.
- `raw-<sha256>.json`: original unmodified response bytes only after the gate and
  strict adapter pass. Secret-bearing/malformed/rejected adapter inputs are never
  retained; their raw input hash can still appear in diagnostics. Reference files
  are represented by exact hashes, not copied to the bundle.
- `normalized.csv` when rows meet canonical normalization requirements; the manifest
  separately identifies its SHA-256. `raw_record_hash` hashes canonical JSON for the
  corresponding six-element source row, including the original timestamp string.
- `complete.json`: version and SHA-256 of the final manifest. The manifest's `files`
  map hashes every payload file, excluding itself and this outer completion marker.

Synthetic/provider gate reports always retain false permission/production flags.
A rejected malformed run may have only manifest, validation and completion files.
Provider denial intentionally has no raw hash because the raw file was not opened.
Hashes prove byte identity, not authenticity, legal rights or signatures.

All diagnostic JSON is sorted-key UTF-8 with LF and deterministic array order. CSV
uses UTF-8/LF. No output contains the output path, clock time, random ID, mtime or
temporary directory name. Exact raw input bytes are the deliberate exception to
format normalization. Identical bytes, declared metadata, operation and executable
produce identical bundle bytes after relocation. Different compiler class bytes or
JAR Build-Commit intentionally change software provenance; retain the exact JAR and
all explicit inputs for verification. Builds made with uncommitted changes report
`unknown-dirty`; clean production builds bind the committed implementation.

`verify` reruns the entire operation from explicit current snapshots, compares every
expected byte, and rejects missing/extra files, changed raw/normalized/reference
inputs, forged self-consistent hash chains, or software identity changes. It opens
only the fixed expected artifact filenames, never follows paths embedded in a
manifest, and does not write into the verified directory. Artifact reads are capped
at 128 MiB. Input snapshots make each run coherent; use trusted stable local files
to prevent hostile input races and retain the matching set yourself.

Publication uses an exclusive sibling lock, a fresh sibling staging directory,
forced file writes and one ATOMIC_MOVE; there is no non-atomic fallback. Existing
identical bundles are accepted, different existing contents are never overwritten.
Failed staging is cleaned and cannot create a complete target. A retained empty
lock file is coordination, not a completed bundle. Application-level immutability
does not prevent an external editor changing a file; verification detects changes.
Atomic visibility is not a power-loss durability guarantee for directory metadata.

URI/UNC/device/traversal paths, alternate streams and detected symlink/reparse
ancestors are rejected. Mounted remote drives, malicious concurrent filesystem
changes and compromised executables are outside the local-path guarantee. No
source URL or schema URL is fetched. Run on trusted ordinary local storage.
Recognized authentication markers/JWTs are rejected; closed response schemas also
reject unexpected string/header fields. Arbitrary secrets disguised as ordinary
logical identifiers cannot be authenticated by a parser; never supply them.

## Retention and future integration

Retention is explicit in every request. For ANGEL_ONE, `retention_status` must be
`OWNER_PROPOSED_UNVERIFIED`, including the proposed `retention_days=20`. Synthetic
retention uses SYNTHETIC_TEST_ONLY and supplies no provider evidence. This service
does not calculate or enforce a real deletion schedule, delete data, choose AWS/GCP,
or choose a region. Temporary parser/publication scratch cleanup is not retention
enforcement. Diagnostic and derived artifacts also need reviewed retention scope.

A later connector/storage integration must first establish authenticated provider
permission and owner/reviewer approval bound to the exact source/product/dated
instruments, acquisition, simulation/replay/backtest purposes, raw and transformed
storage, retention start/deadline, backups and derived outputs, and any sharing.
Cloud storage additionally requires the selected provider, region, compute/backup
locations, access controls and deletion evidence. Add an authenticated verifier and
scope binding to this gate before enabling real ingestion; do not replace it with
an approval boolean or interpret public API access as a license. The transport must
then supply frozen response bytes plus capture/request metadata to this adapter,
with current reviewed provider windows/pacing and dated references. Expired-contract
availability, timestamp semantics and calendar authority require separate evidence.
None of those gates is closed by this implementation or its synthetic tests.
