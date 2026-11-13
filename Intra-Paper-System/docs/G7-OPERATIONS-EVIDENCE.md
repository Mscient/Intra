# Proposed G7 offline operations evidence contract

G7 is an unadopted extension to the original **G0–G6** roadmap. It is an offline
diagnostic tool for reviewing a future operating period against an exact,
previously approved envelope. Owner adoption and separate human authority would
be required before G7 could become a project gate. This implementation supplies
neither. Every result retains `g7_closed=false`, `operation_authorized=false`, and
`scale_authorized=false`.

No real operational evidence was supplied for this implementation. The
[examples](../examples/g7-operations/README.md) and focused tests are synthetic.
They cannot close G7 or any G0–G6 gate, establish readiness, release a halt, or
authorize operation or scaling. See the [proposal note](../../research/systems/G7-OPERATIONS-PROPOSAL.md)
and [validation record](G7-OPERATIONS-VALIDATION.md).

## Commands and deterministic bytes

Use Microsoft OpenJDK **21.0.13.0.2**, with `JAVA_HOME` set to its local
installation, and PowerShell 7. The standalone builder compiles all current main
sources into fresh classes, without annotation processing or downloaded
dependencies. It packages the allowlisted existing verifiers in the same JAR.
It sorts entries, fixes ZIP dates to the Git commit date, and checks source/Git
inputs before and after building. Dirty builds are explicitly `unknown-dirty`.

```powershell
Set-Location Intra-Paper-System
.\build-g7-operations.ps1 -OutputRoot build/g7-validation/standalone
# Use the exact JAR path printed by the builder.
.\g7-operations.ps1 -Command explain -JarPath $g7Jar
.\g7-operations.ps1 -Command evaluate -JarPath $g7Jar -Dossier $dossier -Output $newReport
.\g7-operations.ps1 -Command verify -JarPath $g7Jar -Dossier $dossier -Output $newReport
.\g7-operations.ps1 -Command append -JarPath $g7Jar -Dossier $dossier -History $oldHistory -Event $draft -Output $newGeneration
```

The wrapper accepts only the full named options shown above, each followed by a
nonempty value. Option names are case-sensitive as shown; command values are
lowercase. Missing, duplicate, unknown, abbreviated, case-mismatched or extra
arguments produce redacted `FAIL` JSON and exit 1; they never echo supplied
values or paths. The complete argument shape is checked inside the diagnostic
error boundary before opening paths. The wrapper verifies the pinned JDK release
and captures launcher errors, releasing only G7 JSON with false authority flags.

If access to the pinned JDK's `release` is denied, run the same verified toolchain
under its authorized Windows identity. Do not skip vendor/version verification,
substitute another JDK or weaken its ACL. The 2026-11-11 validation diagnosed a
sandbox-account ACL denial; the normal owner could read the same file and execute
Microsoft `21.0.13.0.2`. Details are in the validation record.

The direct entry point, including in the production `intra-paper-system.jar`, is
`com.mscient.intrapaper.ops.governance.G7Cli`:

```text
evaluate <dossier> <new-output> [selected-history.json]
verify   <dossier> <report-output> [latest-history.json]
explain  [dossier]
append   <dossier> <old-history.json> <draft-event.json> <new-output>
```

Only these commands and exact arities are accepted. A single ordinary local JAR
is required; mixed origins for G7 and its reused verifier entry points are
rejected. Unpackaged classes are unsupported. No command reads credentials,
opens a network connection, contacts a broker/account/feed/cloud service,
submits or cancels orders, operates a kill switch, resumes trading, changes
limits or settings, or grants capacity. Replayed simulated executions stay
inside the existing offline verifier.

`evaluate` publishes exactly `report.json` and `artifact.json` in a new directory.
`append` publishes exactly a new `history.json` generation. A nonzero diagnostic
exit can accompany a successfully published report/history; inspect its status.
Rejected input/publication produces a redacted `g7-error/v1` and no new completed
output. JSON uses sorted object keys, stable array order, exact numbers, UTF-8
without a BOM and one final LF. History must already have this canonical form.
The manifest's explicit `as_of` controls freshness; the wall clock is never an
implicit input. The report records the loaded JAR hash and its own manifest's
`Build-Commit`. Verification requires the same code artifact and build identity.

## Status precedence and human actions

The only evaluation statuses are, in descending precedence:

| Status | Meaning | Required human action | Exit |
| --- | --- | --- | --- |
| `FAIL` | Known inconsistency, anomaly, malformed input or rejected history/publication. | Preserve originals, investigate, record the incident/correction, follow the applicable separately controlled stop/escalation procedure and obtain independent disposition. | 1 |
| `BLOCKED` | Earlier-gate prerequisite, authentication, material drift, incident, requalification or revocation prevents reliance. | Obtain genuine original evidence and separately authenticated human decisions; complete the configured earlier-gate requalification. | 3 |
| `INCOMPLETE` | Missing, stale, excluded, partial or unknown evidence. | Obtain original coverage or explicitly retain the gap. Never fill it with zero, a default or a healthy inference. | 2 |
| `ELIGIBLE_FOR_HUMAN_REVIEW` | Reserved for a future separately qualified authentication implementation. **Unavailable in v1.** | Even future eligibility would require a separate human decision; it would confer no operating or scale authority. | 0 reserved |

An earlier-gate authentication block is always present in this checkout. A
clean-looking synthetic dossier therefore remains `BLOCKED`; an anomaly raises
it to `FAIL`. Missing periods remain `INCOMPLETE` findings even when a stronger
overall status applies. No artifact's own `PASS`, matched hash, AI review,
signature or caller assertion can make eligibility available.

`verify` exit **0** means exact local recomputation of report, publication
metadata, selected history prefix, input sizes/hashes and artifact bytes. It does
not mean the diagnostic passed. Exit **3** retains terminal revocation. Exit
**1** rejects changed bytes, invalid suffix decisions or unsafe input. Status and
all authority flags remain visible in verification output.

## Strict versioned inputs

The [published schemas](../schemas/g7/v1/) are exported from the executable
`G7Schema` definitions. Every fixed object rejects unknown properties and
requires every declared property; unknown values use explicit `null`. Arrays
retain order. The executable semantic checks also enforce canonical UTC Instant
spelling, chronology, unique identities/references, exact field sets and path
rules. A generic JSON Schema validator alone is insufficient for those checks.

| Contract | Required meaning |
| --- | --- |
| `manifest.json`, `g7-manifest/v1` | Review identity/scope, interval, explicit as-of, synthetic/claimed-operational classification, designated owner/reviewer, approved envelope, exact G0–G6 declarations and owner rules. |
| `g7-gate-decision/v1` | Exact original gate/decision identity, scope, validity, envelope hash, declared completeness, source-artifact hashes and declared signer/identity/authority/qualification/independence records. It is a binding declaration, not a trusted approval adapter. |
| `g7-envelope/v1` | Artifact IDs for strategy, source revision, build, configuration, parameters, route, instruments, capacity, risk limits, costs, data source, market rules, hosting and operating procedure. All domains are required; unknown IDs are null. |
| `g7-configuration/v1` | Exact daily configuration, portfolio settings and per-instrument dataset configurations. Original linked files are compared against these members before re-performance. |
| `g7-rules/v1` | Prospective owner registration, cadence, review lifetime, per-category freshness, escalation evidence, declared severity mapping and scoped change/incident/exception triggers with stop/action/requalification paths. No default cadence, freshness, severity or stop rule exists. |
| `bundle.json`, `g7-bundle/v1` | Complete listed artifact inventory, ordered scheduled intervals, observed or explicit missing-period records, active changes, separately classified estimates and capacity claims. |
| `g7-input-artifact/v1` | Immutable ID, exact portable relative path, SHA-256, byte size, format, category, classification, source/provenance, observation time, period, coverage/completeness and untrusted signature declaration. |
| `g7-scheduled-period/v1`, `g7-period/v1` | A complete explicit interval partition and original observations/evidence. Exclusions require reasons and remain incomplete. Missing periods contain null observations/envelope/adapter inputs and an empty evidence list. |
| `g7-change/v1` | Before/after identities, exact domain/time/period, approval references and matching history event. Changes cannot amend the frozen approved envelope. |
| `history.json`, `g7-history/v1`, `g7-event/v1` | Canonical ordered hash chain binding registration, schedule, evidence/period/change payloads, incidents, stops, recovery, corrections, bundle finalization, decisions and revocation. |
| `g7-decision/v1` | Designated role/actor, exact scope/time/expiry, reviewed input hashes/head/report, qualifications/independence/identity/authority evidence and the owner's exact preceding reviewer-event hash. |
| `g7-normalized-ledger/v1`, `g7-reconciliation/v1` | Exact internal/external declared streams, original raw/normalization references, coverage, completeness and counts. No source authentication or automatic normalization is supplied. |
| `g7-normalized-record/v1` | Ordered exact source record IDs/times, nullable order/instrument/prior IDs and stream-specific exact values. |
| `g7-publication/v1` | Exact report/history sizes/hashes/count/head, hashed input inventory, immutable dossier-history anchor, build/code identity and false authority flags. |

Every listed artifact is checked for its exact path, size and hash; absent bytes
are reported explicitly. Extra/unregistered files, duplicate/case aliases and
hard-link aliases are rejected. Hashes identify **bytes only**. They cannot prove
supplier identity, source authenticity, custody, who viewed evidence or when,
reviewer qualifications/independence, executed rights, or completeness against
omitted evidence. Ed25519 verification checks a signature under its **supplied,
unprovisioned key**; it authenticates neither a person nor signing time.

## Coverage, drift and existing verifiers

The approved envelope hash includes every domain's artifact ID reference, exact
path hash, size and content hash. Original G0–G6 decisions must match those exact
identities and scope, precede the interval and remain unexpired at as-of. Missing,
expired, incomplete, mismatched or unauthenticated decisions cannot be repaired
by this review.

Owner/reviewer identity, authority, qualification and independence records,
including those referenced by original gate decisions, use the owner's explicit
`review_freshness_seconds` as their maximum declared observation age at `as_of`.
Missing age or policy remains unknown; stale records produce
`ROLE_EVIDENCE_STALE`. This is a document-freshness check, not a qualification or
identity authentication adapter. Decision expiry remains a separate check.

Observed domain identities are compared with the frozen approved envelope. Any
strategy/build/parameter/cost/source/instrument/rule/route/hosting/risk/procedure
or capacity change requires its configured human requalification path. Unlogged
drift fails. Logged drift stays blocked even when an approval artifact is
supplied; G7 cannot modify the original envelope. Equality of caller declarations
does not observe a running environment or authenticate settings.

All scheduled periods and every required category must have explicit original
coverage. Categories include raw input/feed, orders/acknowledgements/executions/
cancellations/corrections, positions/fees/cash/margin/settlement/reconciliation,
risk, incidents/recovery/operator, daily operations and replay. Empty activity
requires an explicit source record with complete coverage and zero count;
absence or null never means zero. Stale and partial artifacts stay incomplete.
Statistical estimates and capacity claims remain separately classified and
cannot become operating observations or scaling authority.

| Adapter used from this checkout | Re-performance and limits |
| --- | --- |
| `DailyOpsEvidenceChecker` | Rechecks original daily evidence and propagates its configured findings, exact session/boundary/configuration bindings and incident-history gaps. Caller evidence does not authenticate operations. |
| `FeedAudit` | Rechecks original raw/lifecycle journals, decoder/schedule/policy references, exact G7 interval and declared intended scope IDs. Gaps, excluded windows and unknown thresholds stay findings. Actual source/instrument/hosting/route authenticity is unavailable. |
| `PortfolioReplayArtifactVerifier` and `PortfolioReplayDeterminismVerifier` | Recheck output integrity, original input/configuration dependencies and deterministic replay, using the existing accounting/settlement calculations. Reproducibility is software evidence, not complete account activity. |
| `PortfolioRiskAudit.verify` | Reperforms the exact original risk audit with its original replay, limits, source/age, margin-proxy and budget inputs. Existing breach/incomplete diagnostics remain visible even if re-performance inputs are missing. Proxies are not authenticated account margin/risk. |
| `PortfolioSettlementAudit.verify` | Reperforms original replay/settlement/dated-cost bytes. Explicit `NOT_MODELED` costs stay incomplete and never become zero fees. Source truth and real settlement completeness remain unauthenticated. |
| External ledger | **No existing external-account/broker-ledger verifier is present.** G7 performs only exact comparison of declared normalized streams; raw-source/normalization/source identity and account completeness need independent review. |

Before a legacy reader runs, all JSON file references and property/CSV linked
dependencies are restricted to registered local relative files. Verifiers receive
a frozen local copy and do not re-open uncontrolled source paths. Missing original
dependencies produce `ADAPTER_INPUTS_MISSING`; malformed or mismatched originals
produce `ADAPTER_REJECTED`. A status or output hash alone is never called
re-performance.

Normalized reconciliation compares each declared ordered stream exactly, including
IDs, times, strings, counts and unknowns. Decimal strings such as `0.00` and `0.0`
remain different. There is no tolerance, imputation, row sorting, invented fee
schedule or replacement accounting method. Invalid corrections, duplicate/reordered
records, self-comparison and exact mismatches fail; absent streams/windows/counts/
values remain incomplete. Matching streams do not prove normalization or omitted
account coverage.

Normalized corrections must retain the prior record's order and instrument IDs.
Repeated correction of the same prior record and field fails; provide a new
retained source generation for subsequent financial corrections. This limited
comparison does not implement broker correction accounting.

## Immutable history and human decisions

The first event registers the exact manifest bytes and scope. Each event has the
next zero-based sequence, a unique event ID, canonical occurred/recorded times,
the previous canonical event's SHA-256 and its exact subject/payload binding.
Times are caller declarations; the chain is not a trusted timestamp service.
Reordered/duplicate events, malformed signatures, wrong links/subjects and future
or regressing chronology are rejected. Artifact, period and change registration
retain their complete record payloads. Artifact registration also binds the exact
review scope and the artifact's declared period. A final `BUNDLE` event binds the exact
inventory/period/change bytes before decisions.

`append` requires a complete next draft with its correct sequence/link. It
validates and evaluates the extended generation before publication. It never
rewrites the supplied history, supplies a human signature, changes an artifact or
edits a dossier. Maintain each immutable dossier/report/history generation
separately. Before decisions, newly added evidence requires a retained new dossier
generation with the same immutable manifest, all old artifacts and the original
history anchor, plus the new inventory/records/events and final bundle binding.
Verification of an old report uses its original dossier, not the newer inventory.

`CORRECTION`/`SUPERSESSION` appends a new event that names the prior event and
its exact hash, retains its logical kind/scope/period, explains the response and
binds different immutable subject bytes. Only evidence, period, change or incident
records can be corrected. The same prior event cannot be corrected twice; a
subsequent correction must reference the latest correction. Prior events and
artifacts remain mandatory. Corrections do not clear original incident findings.
An in-place period edit mismatches history. A wholly forged/rechained dossier or
an omitted/forked history has no external trusted anchor and cannot be authenticated.

Incident evidence requires its scoped declared policy handling and human response.
Stops/recovery must reference the exact incident, scope and period; recovery needs
preceding stop evidence. A recovery assertion without resolution evidence fails.
Even consistent stop/resolution records remain blocked for qualified independent
recovery review. Such records are evidence of a claim, never an incident closure,
halt release or permission to resume.

Independent and owner decisions bind the deterministic **pre-decision** report,
all input hashes, and its history head, avoiding circular hashes. The owner also
binds the exact preceding reviewer event. Expiry is explicit, must remain current
at as-of, and cannot exceed the owner's declared review lifetime. Wrong/replayed
role, actor, scope, reviewed bytes or ordering fails; expired decisions and all
reservations/rejections stay blocked. Correct signatures remain unauthenticated.
New invalid decision bindings are rejected before append publication.

Decision/revocation suffixes seal evidence history. Later evidence requires a new
review identity. Revocation names the exact earlier registration/decision event
hash, is terminal and cannot be replayed or followed by a new decision. `verify`
can check a retained decision/revocation suffix against the original reviewed
prefix; any later evidence addition invalidates reliance on the old report.

## Findings, privacy and filesystem boundary

Findings retain the affected period/artifact references, diagnostic status,
declared policy/severity references, exact evidence hashes/sizes, policy reference,
manifest/bundle hashes and required human actions/procedure references. Reviewed
`severity` stays null because policy authentication is unavailable. Applicable
declared labels are retained as hashed references, and unmapped labels stay null.
All policy/reviewer/source authentication remains false in
this revision. Look up hashed references in the private dossier; reports do not
copy artifact filenames, actor/account IDs, free-form incident/reservation text,
credentials, private limits, values or absolute source paths. Raw history remains
private and is not bundled into public report output.

Use ordinary local directories on the JDK's local volume. UNC/device/URI paths,
other or mapped drives, traversal, alternate streams, symlinks/junctions/reparse
ancestors, special files, unsafe Windows reserved names, case aliases and linked
inputs/outputs are rejected. Artifact paths use portable ASCII relative segments,
forward slashes, at most 240 characters and at most 128 per segment. No empty,
`.`/`..` or trailing-dot segment is permitted. Bounds are 16 MiB per file, 128 MiB
per dossier, 1,024 files and 2,048 visited entries; JSON depth/node/number bounds
also apply. Binary artifacts are not misrepresented as UTF-8 text.

Snapshots hold shared file locks, record metadata and hashes, and check every
source/directory/frozen/external history/draft input again immediately before
publication. Windows Java can return null file keys; those stay unknown. Creation/
modified times, open locks, exact-byte checks and `Files.isSameFile` alias checks
are separate safeguards, not trusted file identity authentication.

Publication uses a cooperating parent-directory lock, new staging directory,
forced file bytes, a final input/output/ancestor check and a same-directory atomic
move. Existing outputs are rejected, and injected pre-publication failures remove
only owned staging. A filesystem that cannot perform the atomic move fails; there
is no partial-copy fallback. The lock file remains in the output parent and is
not evidence. The tested boundary is ordinary local Windows/NTFS with exclusive
control of dossiers and output parents. Java locks/metadata/rename checks do not
defeat privileged or noncooperating mount/ancestor/destination replacement,
restored metadata, complete-history forgery, disk/power loss or untrusted storage.
They do not supply a global single-writer history/custody service. Keep that
boundary explicit; no operational authority follows from these local checks.
