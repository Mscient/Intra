# Offline Nifty futures research workflow

This workflow connects the existing ResearchTrialLedger and ResearchOutcomeLedger, strict chronological split plan, validated portfolio CSV loader, causal replay/accounting engine, v3 manifests/verifiers, session-return evaluator and signed one-shot holdout controller. It supports one or more individually dated NIFTY futures. No new registry, simulator, broker route or data acquisition service is introduced. Profitability is unproven.

## Build and commands

Use the installed JDK 21, and retain the exact tested JAR. A dirty checkout is allowed for diagnostic work: its Build-Commit remains unknown-dirty, while the actual JAR SHA-256 is frozen and enters both trial and replay identities. Rebuilding changes code identity and requires a new workspace/family registration; it cannot alter a frozen trial.

From Intra-Paper-System:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
rtk proxy node test-offline.mjs --java-home $env:JAVA_HOME --output-root build/research-checks
$env:INTRA_PAPER_BUILD_DIR = Join-Path $PWD 'build/research-checks'
.\nifty-research.ps1 register .\local-research .\examples\nifty-research\sma-a.json
.\nifty-research.ps1 register .\local-research .\examples\nifty-research\sma-b.json
.\nifty-research.ps1 register .\local-research .\examples\nifty-research\empty.json
.\nifty-research.ps1 validate .\local-research sma-a
.\nifty-research.ps1 evaluate .\local-research sma-a
.\nifty-research.ps1 evaluate .\local-research sma-b
.\nifty-research.ps1 evaluate .\local-research empty
.\nifty-research.ps1 report .\local-research .\local-reports
.\nifty-research.ps1 verify-report .\local-reports\<report-id>
.\nifty-research.ps1 replay-verify .\local-research sma-a
~~~

The wrapper invokes com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflow by classpath. Direct invocation uses java -cp <jar> <class> followed by exactly the same arguments. Commands return 0 on success and 1 on invalid inputs, denied access, or verification failure. Every attempt uses a create-new evaluation receipt. Repeated evaluation cannot overwrite a result or failure; replay-verify reruns the same frozen development/validation inputs and compares output bytes. Existing single-contract and portfolio-run commands retain their diagnostic behavior; they do not automatically inherit this workflow's access gate.

## Frozen plan and preregistration

See examples/nifty-research/sma-a.json for the exact INTRA_NIFTY_RESEARCH_PLAN_V1 contract. Unknown keys, duplicate JSON keys, invalid identifiers, nonpositive capital, partial walk-forward specifications, and conflicting file commitments are rejected. Paths resolve against the plan directory. prefix_files and holdout_files map every admitted path to its exact SHA-256, including their input CSV, bars, run properties, instrument master, complete calendar, rules and cost schedule; supplied permission records/manifests must be included when applicable. The shared portfolio settings file and original research card are separately hashed. The holdout map is a public commitment supplied by a preparer: registration does not read any holdout file. It is not a proof of independent secrecy or data authenticity.

Registration captures raw prefix bytes and settings before strategy validation. Thus an invalid strategy parameter can be registered, attempted, rejected and retained with MECHANICAL_FAILURE_NO_OUTCOME. Inputs missing at registration cannot be frozen; repair the input declaration before attempting a strategy. Registry identities and strategy-version/variant pairs are immutable. A successful registration followed by a persistence problem remains in the registry; reports reject missing frozen cards instead of dropping the trial. A new workspace is an organizational boundary, not permission for another look at the same holdout.

The canonical frozen card commits the trial ID/version/variant, original card hash, source-file maps, settings, exact code artifact, full split, fixed capital, selection rule and walk-forward counts. The existing ledger's strategy_card_sha256 hashes this canonical card. Dataset identity uses INTRA_NIFTY_ADMITTED_DATA_V1 over the full raw prefix input map; parameter identity uses INTRA_NIFTY_FROZEN_PARAMETERS_V1 over settings and actual code bytes. These domains are explicit extensions for this workflow, distinct from older portfolio catalog dataset/parameter fingerprints. Replay artifacts retain their existing validated-dataset and executable-model fingerprints, and reports reconcile both identities. Do not feed the workflow's raw-commitment catalog into the older artifact evaluator; the workflow report verifies the mapping.

One workspace contains one complete data/split family. Every registered card must appear in its report, even if unattempted, failed, interrupted or empty. A result is recorded as produced only after verified artifacts exist. Generating a report records its first view for each attempt; repeated report generation does not add duplicate views. Separate result delivery logs holdout access and views. Operator-maintained logs cannot prove all experiments or outside file reads were recorded.

## Sessions, hypothesis and leakage

The entire split must declare nonempty DEVELOPMENT, VALIDATION and HOLDOUT periods in chronological, non-overlapping blocks. The supplied public calendar must cover every calendar date across that full span, and every OPEN date must occur once in the split. Prefix bars must contain exactly the development/validation dates; holdout bars must contain exactly the holdout dates. Missing full sessions, partial sessions, duplicated/reordered timestamps, reassigned sessions, boundary mismatch and undeclared dependencies fail closed. Full-session quality overrides never authorize simulation through gaps.

NIFTY_SESSION_SMA_RESEARCH_V1 is a fixed long-only intraday SMA hypothesis, implemented by wrapping the existing portfolio SMA. Its lookbacks reset each session. It decides to exit at the penultimate completed bar, using the known calendar, and executes at the next/final bar open. The final completed bar cannot create a carried intent. The existing engine provides immutable completed history and open-only next-frame fill inputs. No parameter fitting reads validation or holdout data. Development and validation share one auditable prefix replay, with daily flat boundaries and separate partition summaries.

REQUIRE_FLAT, CANCEL_AT_SESSION_BOUNDARY, contiguous complete BAR_START data, and a dated source-attributed execution-cost schedule are required. Each input must validate as the same dated NIFTY FUTURE, never an index execution series, stitched expiry, or option. Costs require BUY and SELL coverage even for a zero-trade trial. Expiry settlement and overnight exposure are intentionally outside this intraday hypothesis; their existing separate replay workflows remain available. Cost values, calendars, market rules, source truth, rights and tariff completeness still need independent review.

## Reports and verification

A report folder is named by SHA-256 of report.json. manifest.json binds the exact report, canonical recipe and snapshot registration/outcome ledgers. The recipe retains every frozen trial card and evaluation receipt. Atomic publication requires the report output root to be on the same volume as the workspace. Reports link each result to its registration sequence/hash/time, raw input commitment, split, parameters, exact JAR, validated replay dataset, run ID, build metadata, cost schedule, run-manifest and every output hash. No result-producing registration may silently lose its artifact.

Per-session rows include partition, gross/net P&L changes, costs, fixed-capital return, fill/entry/completed-position counts, turnover, sampled exposure, maximum gross notional and checkpoint drawdown. Aggregate and partition summaries retain sample sizes, gross/net amounts, costs, mean, sample deviation, nullable session and annualized Sharpe. Drawdown is peak-to-trough account net P&L at bar-open/bar-close checkpoints, initially zero; it is also scaled to explicitly supplied starting capital. Turnover is absolute executed notional; completed positions count returns to flat, not arbitrary fill rows. Exposures are instrument checkpoint counts and gross notional, not continuous intrabar measures. Adverse tick slippage is embedded in execution prices; a separately observed spread/impact cost is unavailable.

The existing shared DSR sensitivity/chronological-block CSCV is included only for a complete synchronous family with the required sample and defined variances. Otherwise diagnostics are null with the exact limitation. Single-trial and raw-family effective-count endpoints remain assumptions. No estimated effective trial count, dependence-adjusted confidence interval, performance probability or multiple-comparison confidence claim is invented. Undefined zero-variance or short-sample Sharpe is null; empty trials remain visible. Failed/unattempted families cannot select a holdout candidate.

Walk-forward counts are frozen decimal integer strings: training, validation and evaluation sessions must all be positive, or all zero to disable. Each rolling window advances by its evaluation length, exposes all dates, selects the largest preceding validation net P&L with ascending trial-ID tie break, and scores only the later evaluation period. Training is descriptive fixed-hypothesis warmup, not a hidden fitting algorithm. Windows use development/validation sessions only. Insufficient complete windows produce an explicit limitation. CSCV is a separate instability diagnostic; its nonchronological complements are not forward prediction evidence.

verify-report checks exact report layout and hashes, snapshot ledgers, every registered card/receipt, source commitments, validated session coverage, actual JAR, executable strategy/cost identity, replay manifests/output hashes, and recomputed report bytes. Original local prefix source inputs and the exact code artifact are required for semantic verification. replay-verify additionally regenerates and byte-compares replay outputs. Hashes detect changes against retained anchors; wholesale replacement of all local anchors is not authenticated evidence.

## Holdout access

The selected trial is fixed by MAX_VALIDATION_NET_PNL_TIE_TRIAL_ID. Selection requires complete family results, including valid zero-trade outcomes. freeze verifies the selection report and current complete registry/outcome audit, writes a create-new gate intent, builds the existing strict complete-family catalog and signed protocol, then invokes PortfolioHoldoutReleaseController.freeze. Failures/no-result rows use explicit deterministic placeholder run references only for the controller's public catalog; they are never executable results, and incomplete families cannot reach freeze. New registrations and development/validation evaluation are denied after the gate intent, including after holdout delivery.

~~~powershell
.\nifty-research.ps1 freeze .\local-research .\local-reports\<report-id> .\custodian-public.der .\journal-public.der .\journal-private.der
# A custodian separately signs the exact frozen Receipt using the existing Authorization.sign API.
.\nifty-research.ps1 authorize .\local-research .\authorization.token .\custodian-public.der .\journal-public.der .\journal-private.der
.\nifty-research.ps1 holdout .\local-research holdout-evaluation-1 .\custodian-public.der .\journal-public.der .\journal-private.der
.\nifty-research.ps1 read-holdout .\local-research delivery-1 .\custodian-public.der .\journal-public.der .\journal-private.der
.\nifty-research.ps1 verify-holdout .\local-research verify-1 .\custodian-public.der .\journal-public.der .\journal-private.der
.\nifty-research.ps1 status .\local-research status-1 .\custodian-public.der .\journal-public.der .\journal-private.der
~~~

These are dedicated research-signing keys, not account/broker credentials. Production custodian and journal keys must be independently controlled as described in ONE-SHOT-HOLDOUT-RELEASE.md. The increment does not create or read production keys during development. The synthetic drill generates temporary test keys and clearly states that local signing is not independent custody.

Only the controller's durably logged EVALUATING callback opens and verifies the committed holdout files, checks the same dated instrument/calendar, and runs the selected frozen strategy once. Result bytes link the trial, opened evaluation ID, selected report and existing verified run artifacts, and include holdout metrics. Consumed success, failure or interruption cannot evaluate again. Delivery retries use the same saved hash-verified result. read-holdout and verify-holdout also verify its linked run artifacts and recompute metrics from the committed local holdout inputs without running the strategy again; the existing controller logs this result access. Interrupted EVALUATING states require consume-interrupted with the original evaluation ID and the same key arguments; they cannot resume. Before authorization or after consumption, holdout calls are denied and audited where the store is writable.

Independent canonical family registry, external fresh audit heads, actual secret data custody, prior-access reconciliation and reviewed deployment remain open. A researcher with raw files, signing keys or permission to replace the entire local store can bypass local controls or create another workspace. This workflow does not claim blindness or performance eligibility. Signal markouts, synthetic replay plumbing and dated-contract execution diagnostics remain separate evidence classes.

## Offline acceptance

NiftyResearchWorkflowTest runs in the comprehensive test-offline.mjs suite. It covers registration before validation, duplicates/identities, failed/unattempted/empty trials, view states, chronological coverage/boundaries, instruments/cost sources, changed admitted inputs, reproducibility, report tampering/missing artifacts, walk-forward limits, early/repeated holdout denial, consumed failures and no tuning after freeze. Existing replay leakage tests, split/evaluator tests and signed controller recovery/tampering tests also run.

The synthetic-nifty-research.mjs drill executes separate JVM commands against that same tested JAR and generated fictional files: registration, validation, prefix evaluation, report generation/verification, deterministic replay verification, freeze, early denial, synthetic authorization, one holdout evaluation, result delivery, repeated denial and post-holdout tuning denial. Its command/exit/count/hash ledger is diagnostic evidence only.

## Separate remaining gates

- Data rights and retention: real admitted scope and historical dated-contract history need independent authorization; no data was acquired.
- Market rules/calendar/instruments: source authenticity and applicable dated rules remain unverified by fixture validation.
- Costs/fills/margin: tariffs, charge completeness, realistic spread/impact/queue behavior and account margin are separate open evidence.
- Operations: no qualifying operating sessions, broker reconciliation, route or live controls are established by this workflow.
- Strategy performance: adequate market-derived samples, independently frozen selection/custody and review remain open; profitability is unproven.
