# G2 admission continuation and validation — 2026-11-09

**G2 remains INCOMPLETE.** This continues the pre-existing 11 untracked admission files on `codex/g2-remaining-engineering-2026-11-09`; no work was reset or restarted. Initial HEAD and local `origin/main` were `7d6f6c56424b4e50903121eaf8ee491a9ce15607`. A read-only `git ls-remote origin refs/heads/main` confirmed the same GitHub main revision. The sandbox network attempt failed; the permitted retry read Git metadata only. No push, merge or hosted workflow was requested.

The audit read admission implementation/artifacts/CLI/runner/schemas/tests, production permission and feed-audit contracts, the readiness inventory, final WP2–WP5 plan references and repository session/tracker conventions. The earlier verbatim remaining-work prompt and transcript were not available as repository files; the current user requirements and inventory define the reconciliation. See [gap matrix](../../Intra-Paper-System/docs/G2-ADMISSION-GAPS.md).

## Changes and audit findings

The starting 27 checks passed outside the sandbox, but omitted substantive bindings. Fixed declaration-only processing/event hashes, a permission as-of date independent of assessment, permission source/product/role/classification/coverage/output joins, basename-only raw/lifecycle joins, undeclared feed references, unbounded permission dataset hashing in this caller, forgeable publication input and Windows path aliases. Existing permission guards and v1 journals were preserved. Tests and production classes now compile separately, making code fingerprints comparable across source/JAR modes. New checks and documentation preserve the authenticity and semantic limits.

Changed components are the four admission Java classes, synthetic Java suite, two PowerShell runners, three schemas and schema attributes, G2 source/JAR selection in the offline CI runner, operating/gap guides, readiness and implementation trackers, CI guide, session and this report. No pricing, accounting, replay, risk, settlement, statistical formula, source candidate or network/order implementation changed.

## Environment and baseline reproduction

Windows; PowerShell 7; Microsoft OpenJDK `21.0.13.0.2+1-LTS`, implementor version `Microsoft-15780113`; `javac 21.0.13.0.2`; JDK at `C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot`. The configured user-local JDK could not be accessed inside the managed sandbox. The Program Files copy reports the exact CI-pinned vendor/version.

Ordinary sandbox source execution compiled, then failed in existing `AuditInputs.safe` / `WindowsPath.toRealPath` on its own newly generated synthetic `feed/evidence.json`. The same baseline suite with ordinary filesystem access passed 27 checks. This is a sandbox canonicalization/traversal restriction, not evidence that symlink/junction guards should be removed. Administrator rights are not established as a general requirement. Final validation uses ordinary filesystem access; guards remain enabled.

Baseline logs are retained under the worktree's ignored `Intra-Paper-System/build/`: `g2-admission-resume-baseline.log`, `g2-admission-resume-baseline-programfiles.log`, and `g2-admission-resume-baseline-elevated.log`; the successful baseline fixture directory is `g2-admission-resume-baseline-elevated/cdf376742105431580c9da2bcd25f6cb`. The first strengthened run retained 66 passing checks, cross-JVM identity and junction rejection under `g2-admission-audit-checks/1201bca44d2341c5bb89a336face52d0`. These are development results, not final production-JAR acceptance.

## Final validation record

Completed on **2026-11-11** against implementation commit **`3c774d2f2971c1ebe39d361e250057e9e872b971`**, on the existing branch. This completion changes documentation and retained validation evidence only; the production sources, schemas, build scripts and runtime test runners are unchanged from that commit. The final documentation/index commits are not claimed as newly built JAR revisions.

| Validation | Result and scope |
| --- | --- |
| Source admission | PASS: 66 Java assertions, three positive and three negative schema checks, separate-JVM byte comparison and junction rejection. Retained exact-commit source run. |
| Production-JAR admission | PASS: 66 Java assertions, the same six schema checks, separate-JVM byte comparison and junction rejection. Resumed on 2026-11-11. |
| Source/JAR output comparison | PASS: complete report.json and artifact.json bytes match across source and packaged modes. |
| G2 contracts | PASS: 365 checks in each of source and production-JAR modes. |
| G2 reproducibility | PASS: 42 complete replay artifact files plus normalized journal byte-identical across UTC/en-US and Pacific/Apia/tr-TR JVMs. |
| Selected offline matrix | All **38 stages** have PASS evidence: **21 source suites and 17 build/package/drill/reproducibility/manifest stages**. This reconciles 36 retained completed stages with two resumed stages; it is not a new uninterrupted full-matrix run. |
| PowerShell wrapper | PASS: 15 cases cover exits 0/2/3/64/65, paths with spaces, publication, overwrite rejection, and verification of stored PASS/FAIL/INCOMPLETE reports. Stored FAIL/INCOMPLETE artifacts correctly verify with exit 0 without changing their decision. |
| Tamper and build negatives | PASS: modified report rejected with 65; changing report content and repairing both its report ID and outer SHA-256 still reaches deterministic-recomputation rejection. Wrong ExpectedCommit rejected before fixture execution. |
| Hosted CI | No exact-commit run found: public GitHub Actions API returned total_count=0 at 2026-11-11T06:43:45Z. No workflow was dispatched, and no hosted success is claimed. |

The matrix includes risk gateway/process recovery, portfolio replay/risk/accounting/audits/evaluation, daily operations, intended-use preflight, permission coverage, incident drills, feed audit, G0, hypothesis cards, mapping, sequence, synchronized frames, kill recommendations, G2 contracts and admission. Individual results and their provenance are in [stages.json](g2-admission-validation-2026-11-11/stages.json) and [summary.log](g2-admission-validation-2026-11-11/summary.log). Expected FAIL/INCOMPLETE outputs from negative fixtures are successful rejection tests, not failed matrix stages.

### Interrupted-run diagnosis and preservation

The original matrix root is `C:\Users\prash\AppData\Local\Temp\codex-intra-origin-20261103\g2-admission-ci-3c774d2`. Its `logs/g2-admission-packaged.log` contains only a RUN line (last written 2026-11-10T21:12:13Z), and both `logs/summary.log` and `logs/failure.log` are absent. Inner run `ab602a4c29a446beab5efbc91e368828` retains environment, compiled tests and partial fixtures, but no checks/schema/runner-summary log. No prior validation or Java process remained at inspection. The run was interrupted/terminated without a retained exit code; the cause and exit are unknown, and it cannot be classified as a completed PASS or a demonstrated assertion failure.

All 36 preceding stages have explicit `RESULT=PASS EXIT_CODE=0` and exact source identity. The partial packaged environment records the same Build-Commit and JAR hash now observed. The original directory was left untouched. Only the incomplete packaged stage and final manifest verification were resumed under sibling `g2-admission-resumed-3c774d2-20261111`. No successful stage was rerun merely to manufacture a fresh summary. Older results using `2fbb9fd` are historical and do not validate `3c774d2`.

### Tested package and retained evidence

- Build-Commit: `3c774d2f2971c1ebe39d361e250057e9e872b971` (exactly one manifest field).
- Production JAR SHA-256: `5d094cb8d787d4b3fbaa8b055c98ddeb3b1e9abe806e53a6af7bae1b197672c7`.
- Runtime/compiler: Microsoft OpenJDK `21.0.13.0.2+1-LTS`, Microsoft-15780113, x86_64, PowerShell `7.6.9`, Windows; executable/release hashes are retained.
- Source/JAR report SHA-256: `18e4af3659dc8b931597ac37e53cfda7a8deb7761ddb1152c62393b093989ff8`.
- Source/JAR artifact SHA-256: `2a6f5829e1142cbac72c042217a78373e2617c9d8b02294f80ebfed7fbf4f07e`.

[The evidence pack](g2-admission-validation-2026-11-11/README.md) includes verbatim original and resumed logs, runner schema/environment evidence, wrapper commands and results, stage provenance and SHA256SUMS. Full synthetic fixture trees and the production JAR remain in the original/resumed local roots. Copied evidence bytes are preserved by scoped Git attributes. Hashes identify observed bytes, not a trusted release signature or issuer identity.

### Retained Git state and integration implications

The retained Git snapshot identifies `3c774d2`, tracking `origin/main`, with origin `https://github.com/Mscient/Intra.git`, stored `origin/main=6c398f1e8d090a527f413d713dc7d294e411c548`, merge base `7d6f6c56424b4e50903121eaf8ee491a9ce15607`, and divergence 3 ahead / 4 behind before documentation commits. It does not contain proof of a successful fetch. The supplied continuation context records a fetch failure because `github.com` could not resolve. The stored tracking ref is not claimed fresh; no fetch was attempted in the 2026-11-13 review.

Local commits are `fe71fd6` (admission implementation), `2fbb9fd` (local schema validation) and `3c774d2` (exact Build-Commit runner fix). Remote-only commits are `ea76170` (G5 readiness), `61e7b29` (G5 session index), `33e948e` (G5 dependency inspection), and merge `6c398f1`. G5 adds production classes and overlaps the offline runner, workflow/tracker/index documentation. This branch's results do not validate that combined code or its additional G5 stages; integration must preserve both G2 and G5 checks and obtain a fresh clean combined-build validation. No rebase, merge, reset or push was performed.

### Current semantic and preflight audit

The production checker evaluates permission documents, then joins role/hash/classification, required date coverage, source/product for raw/lifecycle, explicit assessment date and aggregate-output intent before inspecting each resource path. Denied or incomplete resources remain unopened; tests use missing denied/incomplete files to establish this ordering. Bounded immutable snapshots then pass withAllowedDataset before downstream use. Feed re-audit sees only admitted copies; undeclared references cannot be read from the original directory. Missing/denied resources withhold feed re-audit.

Permission documents, the admission manifest and existing artifact metadata are control inputs that must already be lawful for the operator to inspect. This is not an OS-wide access guard or an authenticity service. Lexical path rules, ancestor link/reparse checks, canonical containment, size limits, control-input rechecks, sorted reasons and canonical JSON remain enabled. A frozen, exclusively controlled bundle is required; hostile concurrent filesystem replacement and power-loss durability remain outside the contract.

PASS remains synthetic/configured only. Capture/provider/process labels and event ordinals/types are assertions; bound byte ranges do not establish domain truth. Normalized transformation, authoritative mapping/calendar content, source clocks, authenticity and complete market delivery remain unverified. The inventory and gap matrix now distinguish the implemented admission sidecar from the older test-only quality record.

### Continuation review — 2026-11-13

The requested checkout was preserved at `3c774d2`: 98 staged G2 documentation/evidence files and no unstaged or untracked changes. The admission, permission, path, schema and readiness contracts were reviewed again. Permission decisions and scope/date/hash/output joins precede resource path inspection; bounded frozen bytes precede downstream use. No production code, schema or existing runner change was needed. Control inputs require prior operator authorization, and hostile concurrent ancestor replacement remains outside the contract.

All 85 original evidence checksums were directly verified. The retained logs contained 1,340 CRLF terminators and 118 LF terminators, with no actual trailing spaces or tabs. Scoped `whitespace=cr-at-eol` now recognizes retained carriage returns while `-text` preserves evidence bytes; space/tab checking remains enabled. The original logs, hashes, interrupted runner and package were left unchanged.

The [continuation verification record](g2-admission-review-2026-11-13/README.md) checks stage provenance, original/resumed log equality, source/packaged output bytes, the actual JAR manifest and hash, and installed JDK executable/release hashes. The tested build remains `3c774d2`, Microsoft JDK `21.0.13.0.2+1-LTS`; this documentation revision is not a newly tested package. Current review PowerShell is `7.6.10`; the retained test environment was `7.6.9`. Hosted CI has no recorded exact-build run, and the retained zero-run query is historical. No new hosted validation, push, merge or rebase occurred. G2 remains INCOMPLETE; no real data was accessed.

## Remaining limits

No real data was accessed. The CALLER_DECLARED negative fixture also uses synthetic bytes. No account, credentials, broker, SmartAPI, WebSocket, cloud or order path was used. GitHub main identity was the only remote read.

Hashes bind bytes and deterministic reconstruction; they do not establish issuer identity, permission, completeness or correct market semantics. This sidecar cannot certify normalized transformations, reference authority, source time, universal event semantics, private owner inputs, independent human review, trusted operational recovery or a G4 campaign. Synthetic PASS cannot close G2. No permission, market-correctness, profitability, trading-readiness or operational-readiness claim follows.
