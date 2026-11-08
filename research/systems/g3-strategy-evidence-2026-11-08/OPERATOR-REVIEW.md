# Operator and independent-review handoff

This guide describes the existing tools and required future sequence. It neither
supplies inputs nor authorizes collection, forward paper or orders. Do not execute
the market workflow until the dependencies in README.md are closed. This review
needs no owner choice to finish its documentary/software verification.

1. Human owner and reviewers close actual G0/G1 prerequisites. Approve and version
   the MM/RV cards, instrument rules, entire campaign/search/exposure accounting,
   immutable evaluation protocol and private capital/cost/risk decisions. Reuse
   the separate G1 card validator when its increment is reviewed/integrated;
   do not treat card status text as authenticated approval.
   No risk limit may be relaxed after viewing results; a version change cannot
   rescue a failed held-out evaluation or authorize reuse of its outcomes.
2. Nominate exact already-authorized datasets and permitted purposes/periods.
   Reuse intended-use preflight/coverage and dataset/feed/master/calendar checks
   with real source/right records. Preserve gaps/duplicates/quarantine and clock
   limitations; no interpolation, current-contract backfill or index-to-P&L mapping.
3. Freeze strategy source/tested JAR/config, complete eight-field registrations,
   split bytes, benchmark/metric/method/rejection, fill/cost/settlement/risk
   attachments and capacity/sensitivity rules before validation outcomes. Preserve
   external anchor/receipts or explicitly keep UNVERIFIED. Maintain the existing
   outcome ledger for every start/production/view/failure/abandonment, with unknown
   historic exposure disclosed. Bind source/build review beyond arbitrary card hashes.
4. Development uses the shared Java production replay/risk/accounting APIs only.
   Existing CLI SMA is a harness; a reviewed strategy adapter is still required
   for actual approved candidates. Bar/L1/queue model assumptions must retain their
   grades. Keep signal versus fill markouts, spread/impact/fees/net and capital
   distinct; publish all intents/partials/misses/cancels and joint-leg failures.
5. Run the existing development/validation evaluator on the frozen complete family.
   Prefer predeclared prefix artifacts that carry no holdout rows. Its minimum two
   trials is a computational contract; do not manufacture variants or a candidate
   to meet it. A genuine one-trial diagnostic/decision integration needs separately
   reviewed scope; the evaluator alone never grants G3 eligibility.
6. Before holdout, integrate/review the already-authored one-shot controller and
   deploy independent custody, canonical family registry, separate pinned keys,
   protected data/code and current independent heads. Freeze selection without
   holdout choice. Only the authorized reviewed callback may inspect committed
   secret bytes and generate the frozen report once. Start/failure/interruption
   consumes; delivery retrieves cached bytes. Never create another store/request
   to choose a candidate or universe on the same holdout.
7. Independent reviewer reproduces allowed outputs on the exact build and inputs,
   checks semantics beyond hashes, cost completeness/uncertainty/risk/capacity,
   confirms all preregistered thresholds and records QUALIFIES_FOR_NEXT_PAPER_STAGE,
   REJECTED or INCOMPLETE per candidate. All-fail is a valid completed negative G3
   only with sufficient evidence. G3 approval alone grants no forward paper/live
   operation; G4 readiness and separate G5 human authority remain required.

## Existing packaged workflows

From the repository root, full selected Java 21 validation uses the exact clean
revision and a fresh output directory outside the checkout:

```powershell
.\.github\scripts\test-offline-ci.ps1 -OutputRoot <new-local-evidence-root> -ExpectedCommit <clean-full-commit>
```

This installs nothing locally and needs the already pinned Microsoft JDK
21.0.13.0.2. Hosted setup uses the repository's pinned JDK installer. See
[CI guide](../../../Intra-Paper-System/docs/WINDOWS-JAVA21-CI.md).

Existing synthetic focused commands from `Intra-Paper-System`:

```powershell
.\test-portfolio-replay-engine.ps1
.\test-portfolio-risk-replay.ps1
.\test-portfolio-trial-evaluation.ps1
```

Existing production JAR workflows, only with appropriately approved inputs:

```text
java -jar <jar> portfolio-run <inputs.csv> <portfolio.properties> <new-runs-root>
java -jar <jar> portfolio-replay-verify <inputs.csv> <portfolio.properties> <run-folder>
java -cp <jar> com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskReplayRunner run <inputs.csv> <portfolio.properties> <limits.csv> <instruments.csv> <samples.csv> <new-output-root>
java -cp <jar> com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationCli evaluate <registered-ledger> <catalog> <allowed-runs-root> <frozen-even-block-count> <new-output.json> <outcome-ledger>
```

Risk-enabled regeneration uses the risk runner's `verify` workflow, not ordinary
portfolio replay verification; see its guide. Review cost/settlement scope and
source rights separately. Do not invoke full artifact accounting verification on
sealed holdout payloads before custody authorization. The holdout control currently
has a local API design, no integrated production CLI; do not invent an unlock command.

## Packet integrity versus trust

SHA256.csv binds exact retained documentary bytes, excluding itself, validation
logs and VALIDATION.md (reported separately). source-identities.csv binds inspected
upstream source bytes and revisions; it does not request opening upstream data.
`.gitattributes` retains snapshots/manifest bytes without checkout conversion.
Confirm the manifest against an independently retained expected digest before
using it as an anchor. This packet currently has no such independent custody.

From this packet directory, compare the listed documentary bytes (only these
public/synthetic packet files; this command never opens upstream market data):

```powershell
$packetRoot = (Get-Location).Path
$packetPrefix = $packetRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
foreach ($row in (Import-Csv -LiteralPath SHA256.csv)) {
    $file = [IO.Path]::GetFullPath((Join-Path $packetRoot $row.path))
    if (-not $file.StartsWith($packetPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Manifest path leaves packet directory.'
    }
    if ($row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) {
        throw "Packet byte mismatch: $($row.path)"
    }
}
```

The snippet assumes the reviewed manifest/layout and ordinary files in this
packet. It is an operator recipe, not an untrusted-path ingestion service. Missing
files fail `Get-FileHash`; compare the manifest itself with the expected digest
before trusting a newly supplied manifest. Follow the source-identities rows to
review original committed paths; snapshot links retain original document-relative
context and are not rewritten, to preserve exact upstream bytes.

Hashes detect changes relative to supplied expected bytes. They cannot authenticate
owner/source rights, establish preregistration time, discover omitted trials or
verify market correctness. A matching replacement manifest/packet is outside that
claim. Document any later approval as a new version; do not edit these snapshots
to make nulls/statuses appear approved.
