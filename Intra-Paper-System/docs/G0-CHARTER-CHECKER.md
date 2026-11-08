# G0 charter and evidence checker

The separate Java 21 entrypoint `com.mscient.intrapaper.g0.G0Checker` evaluates the
frozen S0 evidence pack. It connects to no account, broker or network, discovers no
credentials, submits no orders and changes no simulator/risk/strategy behavior.
It uses only its own package and Java standard libraries. URI parsing validates a
source reference; it never fetches it.

`PASS` means the supplied evidence-pack decisions are complete and consistent under
the versioned checker. `INCOMPLETE` means required decisions/evidence are missing,
uncertain, expired or not yet effective. `FAIL` means contradictory supplied evidence
or a declared constraint violation. FAIL takes precedence. Neither PASS nor software
verification grants rights, approves a rule interpretation, authenticates a person,
authorizes spending/access/trading, or closes G0. Actual closure requires human
authentication and independent review of the evidence described in the research pack.

Use Microsoft JDK **21.0.13.0.2+1-LTS**, pinned by the Windows CI installer. No Maven,
broker SDK or dependency download is required. From `Intra-Paper-System`:

```powershell
$jar = .\build-g0-charter.ps1 -OutputRoot "$env:TEMP\g0-build"
.\test-g0-charter.ps1 -JarPath $jar -OutputRoot "$env:TEMP\g0-checks"
.\g0-charter.ps1 -JarPath $jar run .\examples\g0-charter "$env:TEMP\g0-example-report"
# Expected exit 2: synthetic example deliberately retains owner/reviewer unknowns.
# Keep the printed digest independently; do not derive your trusted value from a suspect report.
.\g0-charter.ps1 -JarPath $jar verify .\examples\g0-charter `
    "$env:TEMP\g0-example-report\report.tsv" <retained-report-sha256>
```

Both the dedicated JAR (`java -jar`) and production JAR (`java -cp ...` using the
separate entrypoint) support run/verify. No command is added to the shared CLI.
Run exits: PASS 0, FAIL 1, INCOMPLETE 2. Schema/identity/usage/I/O rejection exits 3
with a fixed diagnostic that excludes paths, contents and exception messages.
Verify exits 0 only for the exact reconstruction, including INCOMPLETE/FAIL reports;
any failed verification exits 3. A valid incomplete report is not a passing gate.

The [schema contract](../schemas/g0-charter/README.md) and `G0Schema.java` define all
fields. Copy the synthetic pending example to a **private directory outside every
Git worktree**, with access restricted to the operator/reviewer. The example is
test data, not a template containing actual limits. Clear synthetic source/use
values before supplying an actual pack. Never put financial amounts, account IDs,
credentials, API keys, real static IPs, personal contact details or private contracts
in tracked files. The checker has no credential fields; use opaque role/product
IDs and keep their mapping in your separate private records. Keep originals and
owner values private; publish only manually reviewed, redacted summaries.

Reports omit owner values, free-text reasons, contact details and literal paths.
They contain input hashes and hashed canonical path identities. These fingerprints
can still reveal low-entropy inputs when guessed, so reports are private by default.
The tracked example and validation logs contain synthetic data only. The checker
does not impose filesystem ACLs or authenticate the local person preparing files.

The pack digest covers charter, owner, source and data bytes plus declared source
attachments and their canonical identities. Prepare a pending review, run once to
obtain that digest, then let the independent human examine those exact private
inputs and prepare the review/record. The second run evaluates the completed review.
Every source list, approval, record and report has a separate role: a hash identifies
bytes; only the human reviews authority/currentness/applicability/rights. V1 supports
unverified caller attestations only. It has no trusted key and accepts no claim of
verified signature. Qualified/independent YES and the reviewer record remain caller
statements requiring independent authentication outside the checker.

Assessment/freshness is dated, with no machine-clock fallback. Always choose a new
assessment date and re-review for actual use; replaying an old date cannot establish
current readiness. A review must refer to the exact pack on its assessment date.
Renaming/moving inputs changes canonical identity and requires a new report/review.
Implementation identity hashes all G0 class bytes. Build identity includes the
packaged JAR bytes and manifest Build-Commit; class-directory runs use a class digest.
The production build supplies exact commit identity only from a clean checkout.
Unknown/dirty builds are visibly identified, and do not represent validated releases.

Publication writes only the report into a unique staging directory, forces file
contents, and atomically moves the directory under a cooperating CREATE_NEW lock.
Existing targets/locks are refused. Output parent must already be an ordinary
directory; publication fails if atomic moves are unavailable. No overwrite fallback
exists. This verifies local Windows/NTFS publication, not power-loss durability,
hostile concurrent filesystem mutation, ACL isolation, or remote-storage behavior.

Focused checks run once against the standalone package and again against the
production JAR in the full selected Windows matrix. Each repeats three child JVM
runs/verifications under English/UTC, Turkish/Apia and Japanese/Kolkata and checks
identical report hashes. Generated artifacts stay outside the source checkout.
See the [G0 research pack](../../research/systems/g0-2026-11-08/README.md) and
[validation report](../../research/systems/g0-2026-11-08/VALIDATION.md).
