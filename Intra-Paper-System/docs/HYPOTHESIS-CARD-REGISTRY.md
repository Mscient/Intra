# Offline hypothesis-card identity and snapshots

G1 had a concrete gap: `ResearchTrialLedger.Trial` accepts an arbitrary `strategy_card_sha256`, while the Markdown strategy-card template does not validate its contents or preserve card versions. The new separate `evaluation/hypothesis` facility validates full specification fields and creates local snapshots. It **does not** add a strategy, acquire data, register trials, grant permissions, close G0/G1, release holdout, or change replay/DSR/PBO/HAC behavior.

The existing eight-field trial identity, append-only registration chain, outcome history and portfolio evaluation remain unchanged. The card's exact UTF-8 SHA-256 is directly compatible with `strategy_card_sha256`. Universe candidates, benchmark/metric definitions and IDs, cost version and file hash, variant family/ledger and source register are transitively bound in those card bytes. No new trial ledger, cost engine, book, pricer or evaluation implementation is introduced.

## Java 21 workflow

Set `JAVA_HOME` to an installed JDK 21; build using the existing `build.ps1`. The dedicated main class is included in the shared JAR without changing its manifest main class. Direct invocation works offline on any Java 21 system; the PowerShell wrapper and tests are Windows runners.

```powershell
$jar = 'C:\path-to-private-build\intra-paper-system.jar'
$cards = '..\research\systems\g1-foundations-2026-11-08'
$registry = 'C:\path-to-local-research-records\hypothesis-snapshots'
# Create an ordinary registry directory once, then use create-new publication.
New-Item -ItemType Directory -Path $registry
& "$env:JAVA_HOME\bin\java.exe" -cp $jar com.mscient.intrapaper.evaluation.hypothesis.HypothesisCardCli inspect "$cards\g1-s01-v1.card.json"
& "$env:JAVA_HOME\bin\java.exe" -cp $jar com.mscient.intrapaper.evaluation.hypothesis.HypothesisCardCli archive $registry "$cards\g1-s01-v1.card.json"
& "$env:JAVA_HOME\bin\java.exe" -cp $jar com.mscient.intrapaper.evaluation.hypothesis.HypothesisCardCli verify $registry G1-S01 1
./test-hypothesis-card.ps1 -JarPath $jar -OutputRoot 'C:\path-to-private-test-output'
```

`hypothesis-card.ps1 -JarPath $jar inspect <card>` is the equivalent wrapper. No network, dependency download, credentials or broker API is involved. Exit 0 means successful validation/identity check/publication, **not** approval. Exit 3 is schema, semantic, identity, usage or I/O failure. CLI failures print only a fixed diagnostic code, not caller prose or private paths.

Commands:

| Command | Arguments and meaning |
| --- | --- |
| `inspect` | `card.json`; checks card and all three companion hashes, prints identity/status and fixed false eligibility/permission/gate flags |
| `archive` | `existing-registry-directory card.json`; snapshots card plus companion bytes and SHA receipt at `card_id.vN/`, never updates an existing identity |
| `verify` | `registry card-id version`; validates all versions 1..N and predecessor links, card/companion bytes, identities and receipts |
| `check-trial` | `registry card-id version existing-trial-ledger trial-id`; verifies snapshot and existing full registration chain; checks card/strategy/version/declared variant equality, writes nothing |
| `schema` | `new-file.json`; emits the structural schema, refuses overwrite |

## Strict card contract

Normative structural schema: [`hypothesis-card-v1.schema.json`](../schemas/hypothesis-card-v1.schema.json), generated from the Java field definitions and checked byte-for-byte by focused tests. Unknown/missing/duplicate keys at every level, unsupported versions/enums/types, duplicate/empty candidate/variant arrays, malformed UTF-8, blank/padded/control/unpaired-surrogate strings and out-of-bounds files are rejected. Card and companion files each support 1..1,048,576 bytes; prose values support 1..16,000 UTF-16 units. Arrays have 1..256 unique entries. Paths are local single filenames, not URLs or parent traversal. Additional semantic rules beyond the structural schema enforce trimmed/control-free Unicode, actual canonical UTC instants and version/review consistency.

`schema=INTRA_HYPOTHESIS_CARD_V1` is the format version. `card_id` is stable, `card_version` is integer 1..1,000,000, and strategy version is separate. Identifiers match the existing 64-character ASCII ledger convention. Initial version requires `previous_card_sha256=null`; every later version requires a nonzero lowercase hash, exact prior archived version, and a nonblank `changelog`. Timestamp fields have explicit UTC roles: `recorded_at_utc` is authorship recording; `frozen_at_utc` is a **caller-declared** freeze instant only. Neither establishes independent chronology or preregistration.

`hypothesis`, `instruments`, `measurement`, `data`, `execution`, `economics`, `design`, and `decisions` contain required named fields covering mechanism/participant, exact signal/decision/horizon, contract/roll, metric/units/observation/benchmark, source/clock/session/gaps/sample/rights, fills/friction, costs/capital/fixed/time, full candidates/variants/exposure/splits/multiplicity, rejection/missing-data/stops/reviews. Most are explanatory single-line text so `UNKNOWN`, `UNRESOLVED`, `PENDING_OWNER_INPUT` and `PENDING_G2_DATA` can remain honest specifications; they are not runtime strategy parameters. Data rights only permit `UNRESOLVED`, `PENDING_SOURCE_REVIEW`, `CALLER_DECLARED_REVIEWED`. `decisions.gate_status` is always `INCOMPLETE`; the facility cannot write a closed gate or an approved entitlement. Free prose is still subject to human review, not natural-language truth certification.

`cost_model`, `variant_ledger`, `source_register` each require `id`, `version`, `file`, `sha256`. Exact companion bytes are read/hash-checked and copied into a snapshot. The cost attachment is a planning component register, not a runtime fee schedule. Card SHA is over original bytes, with **no** JSON reordering/whitespace normalization; any edit changes identity, consistent with the existing ledger. Research `.gitattributes` fixes LF for tracked bound files.

Statuses are `PROPOSED_PENDING_OWNER_SELECTION`, `PROPOSED_PENDING_REVIEW`, `DEFERRED`, `FROZEN_DECLARED`. Proposed/deferred cards have null freeze time. `FROZEN_DECLARED` requires five complete `CALLER_DECLARED` review records: owner selection, independent review, learning acknowledgment, private A15 attestation, G0 review. Each record must provide an opaque resolved ID, nonzero evidence SHA-256 and canonical UTC time no later than freeze. Pending/unresolved records have null ID/hash/time. Freeze cannot precede card recording. `APPROVED`/`VERIFIED` review statuses and additional boolean permission/approval/gate claims are rejected. These pointers are **not authenticated or opened**; private amounts/identity/account records stay outside Git and chat. Do not construct real frozen declarations without the actual required owner evidence.

All CLI card results contain `approval_authenticated=false`, `permission_approved=false`, `trial_eligible=false`, `g1_closed=false`, `market_evidence=false`, `independent_preregistration=UNVERIFIED`, including syntactically valid frozen declarations. Classifications distinguish authored `PLANNING_ONLY` specifications from `SYNTHETIC_PLUMBING_ONLY` test examples. Both are non-market evidence.

## Version preservation and trial boundary

Even a proposed snapshot refuses same-ID/same-version replacement. A correction creates version N+1 with changed bytes, predecessor hash and changelog; version N remains available. To freeze a previously snapshotted proposal, create a **new** version rather than modifying version 1. The create-new registry holds a local file lock, stages complete files with forced writes and publishes with an atomic directory move. Unsupported atomic moves fail closed and may retain a `.staging-*` directory; preserve it for review. This is not a power-loss durability guarantee. Directory reparse/host trust and off-host anchors remain operational responsibilities.

`check-trial` and `requireTrialBinding` reject proposed/deferred and synthetic cards. For a frozen declared planning specification they check identity only, still returning no trial eligibility. Eligibility requires actual G0/G1/G2 owner/data/execution evidence elsewhere. Existing legacy ledger callers can still supply arbitrary hashes; this opt-in validator is not a global enforcement hook and cannot authenticate their cards or prevent bypass. Existing trial/outcome/evaluation family definitions and parameter hashes are unchanged; do not alter them to omit variants.

Hashes/receipts detect local edits relative to supplied anchors; an operator could replace the entire registry and its receipts together. Independent reviewer custody/preregistration/exposure evidence is still needed. No holdout files or vault are read, created, unlocked or relabeled. The G1 research cards contain only pending split text, not held-out market data.

Focused checks include schema equality; every required root/section field; malformed/duplicate/unknown input; unresolved fields; exact-byte and locale/timezone hashes; bound metadata/companion changes; duplicate archive; silent frozen edit; new version and wrong/skipped predecessors; pending/fake/late reviews; synthetic/proposed trial separation; existing ledger binding; CLI create-new/error/false flags; and toy FIFO/partial/missed-IOC mechanics. All generated freezes/approvals are fictional software fixtures outside tracked files. The selected Java 21 CI matrix adds this source suite and a packaged repeat.
