# G0 strict schemas, version 1

`G0Schema.java` defines the normative fixed keys and exact TSV column headers.
Each of the five files starts with `g0-<name>-v1`, then its header, then rows.
Names are `charter`, `owners`, `sources`, `data`, `review`. Encoding is strict UTF-8,
no BOM/CR/NUL, final LF, no blank rows. Cells cannot have surrounding whitespace,
control characters, tabs or newlines. Maximum file size 8 MiB, 4,096 rows, 4,096
characters per cell. IDs and set members match `[A-Za-z][A-Za-z0-9_.-]{0,127}`.
The total pack is bounded to 64 MiB and 256 snapshots; oversized packs reject.
Set cells use `;`; duplicate members, keys, rows and identical source rule/URL/section
references reject. Unknown keys/columns/enums/versions reject. Missing decision rows
and blank values remain INCOMPLETE; an assessment date is necessary to evaluate.
Literal UNKNOWN, UNRESOLVED, PENDING_OWNER_INPUT, NOT_SUPPLIED, TBD, TBC,
UNDECIDED, TO_BE_DECIDED and PLACEHOLDER owner values
remain unknown rather than becoming attestations, limits or permissions.
Unknown charter values and reviewer qualification also block completion. A missing
or non-date assessment cannot be evaluated and rejects. The two pending example
registers have narrowly scoped Git whitespace attributes because terminal tabs
encode empty final cells; the strict parser still rejects whitespace inside cells.

Dates use valid ISO `YYYY-MM-DD` from 2000 onwards. `valid_until` is inclusive.
Assessment dates are explicit, never taken from the machine clock. An expired or
not-yet-effective source is INCOMPLETE; impossible chronology rejects or FAILs.
Publication/effective dates left unknown block CURRENT evidence in V1. The annual
budget period is `start/end`, with exclusive end exactly one year later, containing
the assessment date. Monetary decisions are INR decimal amounts, zero permitted
only as an explicit attestation, up to 12 integer and 2 decimal digits; no defaults,
exponents or placeholders. Risk capital, spend, and loss are distinct decisions.

Owner states: PENDING_OWNER_INPUT, UNRESOLVED, OWNER_ATTESTED,
CONFIRMED_WITH_EVIDENCE, NOT_APPLICABLE_WITH_RATIONALE. V1 cannot authenticate
CONFIRMED_WITH_EVIDENCE and keeps it INCOMPLETE. Mandatory decisions cannot be
waived; assumption N/A needs an attested rationale. Evidence kinds are NONE or
CALLER_ATTESTATION. Every row has a reason. Current attested decisions have dates.

Sources require a supported G0 rule ID, frozen ruleset identity, explicit authority
and applicability decisions, title/publisher/HTTPS URL, dates and pinpoint/requirement.
CURRENT_EVIDENCE requires PRIMARY_ACCEPTED_BY_REVIEWER and APPLICABLE or
NOT_APPLICABLE_WITH_RATIONALE, with current referenced bytes. These labels are
caller decisions, never checker-authenticated authority or legal sufficiency.
CONTRADICTORY fails; UNRESOLVED blocks. Each required rule must have current
applicability evidence. `data_source` binds a rights source to the exact data product
ID, not just a provider domain. Multiple products require distinct rights rows.

Data rows bind the exact charter use IDs to one source/product and rights-source ID.
RECORDED needs every intended and allowed dimension: purpose, instruments, storage
(including backups/compute), retention days, sharing, publication, deletion. Every
intended set must be contained in its allowed set; requested retention cannot exceed
the recorded maximum. Owner data declarations must equal the combined intended
source/purpose/storage/sharing/publication/deletion sets, and `data_retention` must
equal sorted `use_id:days` pairs. Instruments must be within owner scope.
Included and excluded instrument sets must be disjoint. Unknowns
block; DENIED or mismatches fail. No wildcard or implicit unlimited retention exists.

Review requires APPROVE_EVIDENCE_PACK, distinct assigned reviewer, qualified and
independent YES with qualification description, CALLER_ATTESTATION, authentication
UNVERIFIED, dates and pack digest. There is no signature verification key or verified
signature state. The separately hashed record has `g0-review-record-v1`, `key<TAB>value`,
then decision, reviewer, qualified, qualification, independent, evidence_kind,
authentication, reviewed, valid_until, pack_sha256 in exactly that order. It must
match the review fields byte-for-byte. Review occurs on the frozen assessment date.
Reviewer qualifications/independence remain assertions for human authentication.

Evidence attachments are explicitly named ordinary files in `evidence/`, with
lowercase SHA-256. No absolute/traversal names or symlinks/reparse ancestors.
Files are read once into bounded snapshots used for both parsing and hashing.
Only declared inputs are read; credentials and accounts are never discovered.

Reports use `g0-report-v1`: fixed provenance lines, sorted input identities/hashes
and sorted finding rows. FAIL > INCOMPLETE > PASS. Reports contain no owner values,
contact descriptions or paths; canonical absolute file identities are hashed.
They remain private fingerprints, especially for low-entropy financial inputs.
An independently retained report digest and byte reconstruction validate reports.
Changing canonical location, input bytes, source bytes, implementation class bytes
or build identity requires a new review/report. Verification authenticates none of
the human assertions. The operating guide describes publication and exit codes.
