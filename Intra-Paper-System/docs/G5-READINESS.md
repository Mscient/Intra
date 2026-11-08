# G5 live-pilot readiness and decision records — offline V1

G5 remains closed to live activity. This software has no broker adapter, feed
connector, authentication client, account access or order route. Every report has
`g5_closed=false`, `order_egress_enabled=false` and `live_authorized=false`.
Here `g5_closed=false` means the evidence gate has not been fulfilled. Even a
future `ELIGIBLE_FOR_HUMAN_DECISION` report would only invite a separate human
decision; it would never enable deployment or orders.

## Current records inspected

Base: `origin/main` at `7d6f6c5`. The root README, implementation tracker, G0
owner/assumption and source registers, G2 readiness inventory/source-use map, G3
current-state decision and daily-operations operating guide were inspected.
G0 is OPEN; G1 and G2 INCOMPLETE; G3 INCOMPLETE with both required MM and slower-RV
slots NOT TESTABLE; G4 not started; G5 closed. Unrelated uncommitted G4/custody and
external-ledger implementations in the original checkout were excluded.

The G0 source register includes current-dated SEBI/NSE candidates, replacement
circulars, data-use restrictions, unresolved account/route applicability, a broker
static-IP announcement and a segment/IOC wording conflict. The SmartAPI public
contract text was inaccessible in that review. No current service contract or
executed owner-specific licence was established. This increment makes no external
requests and acquires no new rule, broker or data evidence. Historical plan
session counts, durations, amounts and limits are not imported as defaults.

## Local inputs and privacy

Use the established private input mechanism: an ordinary private directory outside
tracked source, or ignored `data/private/`. Keep the whole real dossier, original
evidence, salted configuration, decisions, archives, reports and history there.
Never place private values, account identifiers, keys, contacts or source documents
in tracked fixtures, issues, logs or session reports. Public references must be
opaque `ref-` plus 32 hexadecimal digits, with private mappings held separately.
Signing private keys are never accepted or stored by this application.

`schemas/g5/g5-v1.schema.json` defines the dossier and `$defs/evidence`,
`$defs/decision`, `$defs/event` and `$defs/private`. Java additionally enforces
exact requirement names, enums, relationships, canonical event bytes, timestamps,
path rules, sizes, hashes and cryptographic checks. Unknown/duplicate JSON fields,
malformed UTF-8/JSON and unsafe paths are rejected. Inputs are limited to 1,024
files, 16 MiB per file and 128 MiB per tree. Paths are relative with ordinary
ancestors; traversal, symlinks, NTFS junctions and case-colliding filenames fail.

`dossier.json` binds eight exact identity files: build JAR, configuration, strategy
card, strategy identity, implementation, authorized dataset, protocol and trial
family. Each has a file row whose ID matches the identity key. The JAR is inspected
for its exact `Build-Commit` and is never executed. Strategy identity bytes for a
card are `strategy_id + LF + strategy_version + LF`; the card verifier checks that
digest and requires `FROZEN_DECLARED`. All evidence repeats the eight identities.

Scope references cover exact proposed activity/instruments, broker route class,
data source, hosting/network, operating policy and stop/recovery rules. Their
source documents must explicitly describe the chosen instruments, settings,
route, hosting jurisdiction, primary/standby static-IP requirements, duties,
absence/deputy arrangements, stop triggers, reconciliation and recovery rules.
Opaque IDs or hashes do not establish those documents' truth or completeness.

The separately supplied private JSON includes an unpredictable nonce, an opaque
reference and policy reference, owner-chosen required session count, required
pre-open streak, maximum evidence age, pilot start/end and all supported risk
limits. Capital, recurring spend, pilot loss, position, notional, margin, order
rate and session loss must each be explicit; zero is accepted and enables nothing.
Currency and measurement-policy references must explain units, horizons, gross/net
fees, liquidity, loss rules and refresh obligations. Preserve broader strategy
settings and operational thresholds in the exact configuration/policy evidence.
No numeric field is printed in a report. The salt protects the private-input hash
against guessing; keep salted whole configurations and reports private too.

## Requirements and verifier reuse

Each requirement uses its exact `Gx_name` key and expected claim. All rows are
required; sessions may have multiple unique rows. Verifiers consume a frozen local
snapshot. They retain their existing arithmetic and trust limits. Their outputs
are never copied into G5 reports; only diagnostic success/rejection is retained.

| Gate | Required evidence |
| --- | --- |
| G0 | charter; authority; budgets; account_scope; data_rights |
| G1 | gate; card; implementation; trial_protocol |
| G2 | gate; permissions; replay; risk_audit; settlement_audit; dataset_build; quality; cost_margin; reconciliation; limitations |
| G3 | gate; decision; registration; statistics; holdout; independent_review |
| G4 | gate; session; feed_audit; drill; reconciliation; severe_incidents |
| G5 | scope; exact_configuration; instruments; broker_route; data_source; hosting; static_ip; current_rule_review; operator; reviewer; stop; recovery; private_limits; operating_policy |

Every verifier dependency must be in the dossier's file bindings. Nested properties
are decoded before path checks, even when given a different filename extension.
G0's existing review is bound to its canonical source location: G5 therefore calls
G0 at that location and checks that the complete source tree is unchanged against
the captured snapshot afterward. Other verifiers read the temporary frozen copy.
Concurrent untrusted writers and hostile JVM instrumentation are outside the local
filesystem trust boundary; this software still cannot authorize a live action.

Argument paths are relative to the dossier tree, with the following exact order:

| Verifier | Arguments |
| --- | --- |
| G0 | charter directory |
| HYPOTHESIS_CARD | card file, including its local bound attachments |
| PERMISSION | manifest, intended-use file, permissions file |
| PORTFOLIO_REPLAY | input manifest, settings, saved run directory |
| RISK_AUDIT | audit directory, run directory, input manifest, settings, risk directory |
| SETTLEMENT_AUDIT | audit directory, run directory, inputs, settings, settlement events, costs |
| REGISTRATION | registered trial ledger |
| TRIAL_EVALUATION | registration ledger, trial catalog, runs root, evaluation settings JSON, retained statistical report, outcome-exposure ledger |
| DAILY_OPS | daily-operations manifest and its local attachments |
| FEED_AUDIT | feed-audit manifest and its local attachments |
| DOCUMENT | no arguments; requires independent semantic review |

The evaluation-settings schema contains only its version and the explicit frozen
`chronological_block_count`; it inherits the existing evaluator's supported values
and has no default. Statistical verification re-runs the existing analyzer with
outcome-ledger verification and demands exact report bytes. It retains the existing
holdout financial exclusion and supplies no economic eligibility or independent
custody attestation.

The permission checker is declaration-only; its ALLOWED status proves no licence.
The existing G0/card/G3/daily/drill/registration facilities cannot authenticate
humans, source truth or actual sessions. G3 eligibility, custody/holdout, external
reconciliation and actual operational drills therefore require separate authentic
evidence and review. No missing verifier becomes a PASS. Current synthetic drills
are not actual drills and the application never starts one while checking a dossier.

## Deterministic commands

Set `JAVA_HOME` to Microsoft JDK 21.0.13.0.2. Build with
`build-g5-readiness.ps1 -OutputRoot <private-output>`; the standalone JAR reuses the
production compilation and has `G5Cli` as its entrypoint. The production JAR also
supports `java -cp <jar> com.mscient.intrapaper.g5.G5Cli`.

```text
check ROOT PRIVATE_OR_DASH AS_OF_UTC RETAINED_HISTORY_HEAD report NEW_REPORT
verify ROOT PRIVATE_OR_DASH AS_OF_UTC RETAINED_HISTORY_HEAD REPORT RETAINED_REPORT_HASH
explain ROOT PRIVATE_OR_DASH AS_OF_UTC RETAINED_HISTORY_HEAD
record ROOT EVENT_JSON RETAINED_HISTORY_HEAD AS_OF_UTC ATTACHMENT_OR_DASH
```

Use `-` for an absent private file or attachment. UTC times are explicit, whole
seconds. The frozen assessment time is caller-supplied and is displayed; the
checker does not attest current wall-clock time. Exact verification must use the
same time, inputs, history anchor and checker JAR. A different checker binary
changes `checker_identity` and cannot reproduce an old report. `g5-readiness.ps1`
provides a separate PowerShell wrapper with `-Command` and `-Arguments`.

`check` publishes a new canonical JSON report without overwriting any existing
destination. `verify` recomputes all hashes, signatures, verifier results and report
bytes, requiring both an externally retained report hash and byte-identical
reconstruction. `explain` emits that report with every evidence row, source
identity, finding and required owner/reviewer action. No sensitive downstream
exception text is printed.

Status precedence is FAIL, BLOCKED, INCOMPLETE, then ELIGIBLE_FOR_HUMAN_DECISION.
FAIL denotes contradictory/tampered evidence or verifier rejection; BLOCKED denotes
an unmet gate, stale/expired approval, missing authority/permission, severe incident,
unreviewed source or unestablished authenticity. INCOMPLETE denotes insufficient
input/verification detail. Exit codes: eligible 0, FAIL 1, BLOCKED/INCOMPLETE 2,
schema/history/path/publication rejection 3. `verify` returns 0 for reproduction
of a blocked report; reproduction is not approval. `explain` returns 0 when a
valid diagnostic report is emitted.

V1 intentionally has no authenticated source/identity enrollment adapter. A valid
Ed25519 signature yields `VALID_KEY_POSSESSION_ONLY`. Caller keys, caller assertions,
local attestations, hashes, AI reviews and synthetic fixtures cannot establish
actual operations or independent review. Consequently this version cannot issue
ELIGIBLE_FOR_HUMAN_DECISION from supplied local evidence. That status is reserved
for a separately reviewed future authenticity integration, with no deployment or
order capability. There is no flag, test key, environment switch or boolean to
bypass this limit.

## Immutable decisions and history

Decision files are `decisions/reviewer.json` and `decisions/owner.json`. Each binds
the exact dossier bytes and exact proposed build, actor role/key, qualification and
independence evidence references, decision time and expiry. Sign the canonical
ASCII JSON without `signature`, followed by LF, using Ed25519; then add the base64
signature. Public keys are Ed25519 X.509 encodings with their exact SHA-256.
Matching IDs or distinct keys alone do not prove organizational independence.

`record` retains the dossier, its available exact bound inputs and optional attachment under content-addressed
`archive/<sha256>.bin`, then appends `history/00000001.json`, etc. The caller supplies
canonical event contents. Events contain their sequence, unique ID, previous exact
event hash, dossier/build, actor, time, attachment and correction reference. Genesis
uses a zero previous hash; retain every returned head separately. All event types
are schema-enumerated. A SUPERSEDED event must reference an earlier uncorrected
creation/evidence/supersession event; prior bytes remain mandatory. Evidence
addition, review and owner decisions must retain their exact attachments. Reviews
follow recorded checks, and owner decisions follow recorded reviews. Recorded
events never prove these were independently authenticated actions.

The effective state is reconstructed on every invocation:
`DRAFT -> EVIDENCE_CHECKED -> INDEPENDENTLY_REVIEWED -> OWNER_DECISION_RECORDED`.
Each step requires proven prerequisites. Local synthetic signatures cannot advance
it. Any changed dossier input invalidates both decisions; expiry and revocation
produce BLOCKED. Revocation is latched within that history. Restart executes only
fresh diagnostics and cannot resume any live behavior.

Publication uses a forced temporary file and an atomic, non-overwriting hard link.
Unsupported filesystems fail closed. A failed append may leave an unreferenced
archive attachment, never a partial committed event. A stale writer lock requires
custodian investigation; it is not automatically removed or bypassed. This proves
local process-level publication, not power-loss durability, external custody or
protection against an attacker rewriting both files and caller-retained anchors.

## Real evidence still needed

The owner must supply deliberate private inputs, scope/account decisions, exact
strategy selection/freeze, stop/resume/deputy authority and operational policy.
Broker/rights reviewers must establish current route applicability and acceptance,
effective rules, hosting/static-IP arrangements and executed exact-use data rights.
Independent humans must review authentic G0–G3 sources, cost/margin, reconciliation,
statistical decisions and holdout custody. Operations must supply the owner-required
actual complete session campaign, authentic scheduled pre-open streak, daily
reconciliations, measured actual drills and closed severe incidents. Then separate
owner and independent reviewer decisions must bind the final dossier/build.

No profitability, legal-compliance or trading-readiness claim follows from this
software. Synthetic acceptance and local/hosted software CI never close a gate.
