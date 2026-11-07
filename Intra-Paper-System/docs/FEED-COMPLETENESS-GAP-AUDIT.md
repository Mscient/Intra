# Offline Feed Completeness and Gap Audit

This standalone Java 21 command compares caller-supplied captured evidence with an explicit activity schedule. It creates no connections, obtains no data, routes no orders, and repairs nothing. `PASS` means only that no configured anomaly was detected under the supplied schedule and policy. It does **not** mean the feed is complete. A sequence gap is not proven packet loss. Silence is not evidence of a quiet market.

All schedules, policies, identities, decoder declarations, mapping provenance, and source evidence remain caller-supplied and unauthenticated. Hashes establish consistency with supplied bytes, not authenticity. G2/G4 remain open pending relevant real evidence and independent review.

## Commands

From `Intra-Paper-System`, with `JAVA_HOME` set to a JDK 21 installation:

```powershell
$env:INTRA_PAPER_BUILD_DIR = Join-Path $env:TEMP 'feed-audit-package'
.\build.ps1
$jar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
$evidence = '.\examples\feed-audit\inputs\complete\evidence.json'
$artifact = Join-Path $env:TEMP 'feed-audit-complete-result' # must not already exist
.\feed-completeness-audit.ps1 -Mode verify -Evidence $evidence -JarPath $jar
.\feed-completeness-audit.ps1 -Mode run -Evidence $evidence -Artifact $artifact -JarPath $jar
.\feed-completeness-audit.ps1 -Mode verify-artifact -Evidence $evidence -Artifact $artifact -JarPath $jar
.\test-feed-completeness-audit.ps1
.\test-feed-completeness-audit.ps1 -JarPath $jar
.\synthetic-feed-audit-drill.ps1 -JarPath $jar
```

The Java entrypoint is `com.mscient.intrapaper.audit.feed.FeedAuditCli`. Positional Java modes are `run evidence.json new-directory`, `verify evidence.json`, and `verify-artifact evidence.json directory`. `verify` validates inputs and evaluates policy without publishing; it does not assert a clean audit when anomalies exist. `verify-artifact` rechecks hashes and schema and recomputes the entire report using the supplied inputs and executing code.

Exit codes: `0` PASS (or valid artifact), `2` FAIL, `3` INCOMPLETE, `64` usage, `65` rejected input/artifact or publication failure. FAIL and INCOMPLETE runs still publish their diagnostic reports. Invalid schema, identity, journal framing, hashes, or optional quote reconciliation rejects the run without publishing an audit. `INCOMPLETE` takes precedence over FAIL; FAIL rows remain visible when some checks cannot be evaluated.

## Versioned input contract

Machine-readable Draft 2020-12 schemas are in `schemas/feed-audit/`. Java validates fields directly without fetching those schemas or using a network validator. It additionally enforces cross-file, interval, hash, and identity constraints. Unknown fields, duplicate JSON keys, unknown versions, non-UTF-8 text, invalid enums, negative thresholds, noninteger numeric values, ambiguous part order, and malformed timestamps are rejected. Text must be nonblank, trimmed, and free of control characters. Optional fields use explicit null except policy fields, whose absence deliberately produces INCOMPLETE.

Every file reference has exactly `path` and `sha256`. Hashes are lowercase SHA-256 of exact file bytes. Paths are relative to the evidence manifest's directory, use `/`, and contain only letters, digits, `_`, `-`, and `.` within components. Absolute paths, traversal, links/reparse points, URLs, and directories are rejected. No reference is fetched. Every referenced file is hash-checked and copied to a private temporary snapshot before use. Consumers discard all accumulated state if a journal fails anywhere, including at its tail.

### Schedule (`feed-audit-schedule/v1`)

| Field | Meaning |
| --- | --- |
| `schedule_id`, `revision` | Caller-stable schedule identity and explicit revision; both nonempty strings. |
| `provenance` | Local file reference containing the caller's basis for this schedule. Its bytes are verified and bound. |
| `interval_rule` | Exactly `[start,end)`. |
| `scopes` | Nonempty collection of explicit, uniquely identified scopes. |
| `scope_id` | Join key used by policy, evidence, and report rows. |
| `source`, `product`, `channel` | Explicit source and product/channel identity; no inference from filenames. |
| `connection_id`, `subscription_id` | Exact journal identity. |
| `exchange`, `token`, `instrument_id` | Explicit token and stable instrument identity. |
| `session_date`, `timezone` | ISO date and Java/IANA zone ID. No system-local timezone is used. |
| `session_start`, `session_end` | ISO offset timestamps within the named date; end can be next local midnight. Offset must be valid for the zone, including DST. |
| `expected_types` | Unique array of `DATA`, `QUOTE`, `TRADE`. An empty array means no data-type expectation; it does not assert no trading occurred. |
| `heartbeat` | `NONE` or `LIFECYCLE`. The latter expects actual connection-level lifecycle HEARTBEAT records. |
| `windows` | Exact partition of the explicit session; see below. |

Each window has `window_id`, `kind` (`ACTIVE`, `CLOSED`, `PAUSE`, `EXCLUDED`), `start`, `end`, and `reason`. Windows must be positive, disjoint, contiguous, and collectively cover the session. Row order has no meaning; timestamps determine order. Closures, planned pauses, market exclusions, and session exclusions must be explicit. An ACTIVE window needs at least one event type or a lifecycle heartbeat expectation. There are no default hours, holidays, heartbeats, inferred sessions, or implicit exclusions. The existing bar session calendar uses first/last bar boundaries and is deliberately **not** converted into this activity schedule.

### Observed evidence (`feed-audit-evidence/v1`)

Top-level `schedule` and `policy` are verified file references. `bindings` supplies exactly one entry for every schedule scope. Each binding contains:

| Field | Meaning |
| --- | --- |
| `scope_id` | Exact schedule join key. |
| `raw`, `lifecycle` | Nonempty arrays of `{order,file}`. File is a verified reference; order is an explicit contiguous zero-based acquisition ordinal. Header-only journals are valid observations of zero messages. |
| `decoder` | `AUDIT_EVENT_V1` or `CANONICAL_QUOTE_V1`; no decoder guessing. |
| `decode_reference` | Hash-checked local declaration of the decoder and its supported event semantics. |
| `timestamp_roles` | Exact constants shown in examples and schema: event `DECLARED_EVENT_UTC`, receive `CAPTURE_RECEIVE_UTC`, lifecycle `LIFECYCLE_OBSERVED_UTC`, monotonic `JOURNAL_ORDER_DIAGNOSTIC_ONLY`. |
| `mapping` | Null or verified existing dated instrument mapping CSV. |
| `quotes` | Null or verified canonical quote journal for an exact cross-check with decoded canonical raw payloads. |

Raw files use the repository's `INTRARAW` version-1 binary framing and hash chain. Lifecycle files use the existing version-1 lifecycle journal and enum vocabulary. Optional quotes use the existing version-1 canonical quote journal. All original validators run. No caller-provided `verified=true` flag can bypass them. File-part ordinal then physical one-based journal row defines acquisition order. Receive/event timestamp sorting never hides an order regression. Sorting times is used only to measure wall-clock coverage after order diagnostics have been recorded.

Raw source, connection, and subscription must exactly match the declared stream. Lifecycle source/connection and all present subscription IDs must be declared. Connection-level lifecycle events have no subscription ID. A lifecycle file may include multiple explicitly declared subscriptions on the same connection. Multiplexed token scopes on one source/product/channel/connection/subscription/session must use identical raw/lifecycle parts, decoder, and mapping. Sequence diagnostics use the whole raw stream; activity and event-time diagnostics use the matching instrument. Shared journal hashes appear once in the report.

`AUDIT_EVENT_V1` reads strict UTF-8 JSON already present inside captured payloads. It does not convert vendor packets. Its exact schema is `feed-audit-event/v1`: `product`, `channel`, `exchange`, `token`, `instrument_id`, `type`, `event_time`. Type is DATA/QUOTE/TRADE; event time is UTC with `Z` or null. Source, receive time, and sequence remain raw-capture metadata. Product/channel must match the declaration. Unknown tokens/instruments produce anomaly rows. Malformed/unsupported payloads produce DECODE_FAILURE; malformed journal framing rejects the entire input.

`CANONICAL_QUOTE_V1` reads the existing canonical quote encoding. Payload source, receive UTC, and monotonic time must match capture metadata. The caller explicitly declares product/channel and exactly one token binding for the quote's stable instrument on that stream; the canonical payload itself has no token/channel. These identity assertions remain unauthenticated. An optional normalized journal must match **all** decoded raw quotes exactly, including sequence, timestamps, prices, and sizes; mismatches reject the input. The normalized quote sequence is not substituted for raw source sequence.

Only those two decoders are supported in v1. Arbitrary vendor payloads require an independently reviewed offline decoder in a future version; do not relabel or rewrite raw captures to force acceptance. A decode reference supplies provenance, not executable code and not proof of protocol correctness.

The optional dated mapping uses the existing strict CSV schema in `docs/INSTRUMENT-MAPPING-REGISTRY.md`. Resolve token/exchange at **event time** against half-open validity intervals. Unknown mappings and changed instrument identities are separate failures. No receive-time fallback is used. Missing event time makes the event-time/staleness checks and requested mapping resolution INCOMPLETE. CSV source references remain declarations; no external provenance is fetched or authenticated.

### Policy (`feed-audit-policy/v1`)

Top-level fields are `policy_id`, `revision`, `provenance`, and `rules`. Each rule has an exact `scope_id` and applies throughout that scope's declared session. There are no wildcard or inherited thresholds. Exactly one matching rule is needed; missing or multiple rules produce INCOMPLETE. Every value declaration contains `value`, `unit`, and `provenance` (caller explanation/reference covered by the policy file hash).

| Rule field | Explicit value and behavior |
| --- | --- |
| `max_event_gap` | Nonnegative integer MILLISECONDS; maximum receive-time silence per expected event type within each active window. |
| `stale_threshold` | Nonnegative integer MILLISECONDS; maximum age measured as receive minus event time, plus aging until the next same-type receipt or active-window end. |
| `clock_skew` | Nonnegative integer MILLISECONDS; allowed **future event time relative to capture receive time**. No subtraction from stale age or expansion of schedule/lifecycle boundaries. |
| `heartbeat_gap` | Nonnegative integer MILLISECONDS; required when LIFECYCLE heartbeat is expected; null otherwise. |
| `sequence_reset` | ENUM: `REJECT`, `ALLOW_ZERO_AFTER_RECONNECT`, or `INCOMPLETE`. |
| `sequence_domain` | ENUM: `RAW_STREAM` or `NOT_OBSERVED`; explicit declaration of the source counter's availability/domain. |

Missing/null fields, values, units, or provenance make that check INCOMPLETE. Missing numeric policy never acquires a default. Bad types, unknown units, negative values, and unknown enums are malformed and rejected. A zero threshold is a caller choice and is not converted into a default. Integer values must fit signed 64-bit range.

Numeric thresholds use strict `>` comparisons, so equality passes. Whole-window absence fails an expected type/heartbeat even if the configured maximum gap is larger than that window. Leading and trailing silence are checked; pauses reset the silence/staleness window. Event and receive times are checked independently against `[start,end)`; an event exactly at end belongs outside that window. No skew is used to move events inside scheduled windows. Receive/event regressions in acquisition order are flagged even within skew allowance: allowance governs future-event comparison, not order.

For RAW_STREAM, jumps greater than one report SEQUENCE_GAP; a repeated counter reports DUPLICATE_SEQUENCE; an unseen lower nonzero counter reports OUT_OF_ORDER_SEQUENCE. A lower counter equal to zero is a reset candidate. ALLOW_ZERO_AFTER_RECONNECT permits that candidate only when a connection-established event exists after the preceding receipt and no later than the current receipt. A legal permitted reset is INFO; disconnect/inactivity findings still apply independently. Other reset origins require an updated explicit policy/schema, not guessing. Missing raw counters under RAW_STREAM are INCOMPLETE. NOT_OBSERVED disables sequence assertions explicitly; it says nothing about packet loss.

## Report and evidence semantics

`report.json` binds exact hashes of the evidence manifest, schedule, policy, journals, mapping, and provenance/decoder files. It records schedule identity/revision, full scope identities, audit version, status, limits, and sorted rows. Every row names its scope, optional window, severity, reason code, UTC interval/point, journal `{file,row}` references, and detail. An empty evidence list means a schedule/policy/absence assertion, not an invented observed event. Scope/window IDs join to the hash-bound schedule. OBSERVED_MESSAGE rows preserve the original event time in detail and receive time in the row, plus the payload hash. Gap rows reference adjacent observed rows when available. INACTIVE_SUBSCRIPTION rows cite the last relevant state transition, or no row when state was never observed.

Core reason families are WINDOW_OBSERVATION / NO_MESSAGES_OBSERVED / EVENT_GAP; SCHEDULE_EXCLUSION; INACTIVE_SUBSCRIPTION / SUBSCRIPTION_REJECTED / UNEXPLAINED_DISCONNECT / LIFECYCLE_TRANSITION_ANOMALY; HEARTBEAT_ABSENT; EVENT_TIME_REGRESSION / RECEIVE_TIME_REGRESSION / MONOTONIC_REGRESSION / CLOCK_SKEW_EXCEEDED; SEQUENCE_GAP / SEQUENCE_RESET / DUPLICATE_SEQUENCE / OUT_OF_ORDER_SEQUENCE; DUPLICATE_PAYLOAD; STALE_MESSAGE / STALE_INTERVAL; EVENT_TIME_OUT_OF_WINDOW / RECEIVE_TIME_OUT_OF_WINDOW; DECODE_FAILURE / UNKNOWN_INSTRUMENT_OR_TOKEN / MAPPING_FAILURE / MAPPING_CHANGED; and POLICY_UNRESOLVED / EVENT_TIME_UNAVAILABLE / SEQUENCE_UNAVAILABLE / LIFECYCLE_TIMELINE_UNCERTAIN. The report schema enumerates the full vocabulary.

Lifecycle reconstruction begins with unknown/inactive state and requires connection establishment, authentication, and subscription acknowledgement. Disconnect invalidates subscriptions until reacknowledgement. Data receipts never create lifecycle state. A disconnect in an ACTIVE interval is unexplained by the supplied schedule; detail codes describe the observation and do not excuse a gap. Backward lifecycle UTC time makes interval reconstruction INCOMPLETE; the original order is retained and uncertain intervals are not reconstructed. A trailing active state is only the last observed state through the scheduled boundary, not proof the connection remained healthy.

Stale intervals record when the last observed event's age would exceed the supplied threshold until the next receipt/window end. Their left endpoint is the equality boundary (strict exceedance occurs immediately after it); their right endpoint is excluded. These are diagnostic intervals, not generated quote values. Event counts, continuing connections, and heartbeats never prove data completeness or market inactivity. Identical payload bytes are flagged as duplicates without asserting why they occurred.

## Artifact integrity and determinism

Publication stages `report.json` and `artifact.json` in a sibling temporary directory, forces both files, and uses an atomic directory move. Unsupported atomic moves fail; there is no non-atomic fallback. The output must be a new directory outside the evidence bundle. Existing results are preserved. This is atomic visibility, not a promise of power-loss durability on every filesystem.

`artifact.json` hashes the exact report bytes. Code identity is SHA-256 over a canonical sorted map of every `com/mscient/intrapaper/**/*.class` path and its exact class hash in the executing production class directory or JAR. This includes audit code, parsers, journal readers, mapping, and transitive project helpers. It excludes JAR timestamps/manifest packing and separately compiled test classes. The version is also bound. Verification needs the same compiled class bytes; a changed build is rejected. Retain the tested JAR and JDK identity alongside evidence.

Reverification uses the original input bundle and executable code. Merely rehashing a modified report cannot pass deterministic recomputation. It rejects extra files, unknown fields, noncanonical output, and differing rows/hashes/code. It is not a signature: an actor replacing every input and producing a new valid audit can create a different internally consistent artifact.

Output JSON is UTF-8 without BOM, LF terminated, with sorted object keys and deterministic row order. There are no wall-clock run timestamps, absolute paths, random IDs, locale formatting, or machine-local timezone conversions in reports. The same bytes and code produce identical output bytes. Reordering schedule/mapping/policy/binding rows leaves semantic findings unchanged but changes exact input-file hashes and therefore the full report; this distinction is intentional. Raw acquisition order is evidence and must not be rearranged.

The implementation snapshots inputs and holds journals/report rows in memory; provision disk/memory for the caller's bounded offline evidence set. It does not stream an unlimited live feed, infer missing identity, synthesize bars, forward-fill quotes, reconstruct lost packets, or certify upstream journal capture completeness.

## Validation

The focused runner checks source compilation and packaged execution with Java 21. The synthetic drill exercises CLI run, verify, and verify-artifact across PASS/FAIL/INCOMPLETE scenarios and rejection cases. All test fixtures and repeated output artifacts are retained in its reported directory. The Windows CI runner includes the focused source suite, packaged suite, and synthetic drill, in addition to existing suites. The focused suite also invokes existing raw-capture verification, feed-lifecycle analysis, and raw/lifecycle reconciliation on the synthetic scenarios. No real source evidence or independent review is supplied by these tests.
