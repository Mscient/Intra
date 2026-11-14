# Offline portfolio trial evaluation

The dedicated `com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationCli` evaluates a complete registered family of verified multi-instrument portfolio replay artifacts, including explicit final-session settlements. It uses the existing registration/outcome ledgers and shared session-return Sharpe, DSR sensitivity, and chronological-block CSCV definitions. It emits `INTRA_PORTFOLIO_TRIAL_EVALUATION_V2` JSON with artifact scope and included/excluded settlement counts. Every report and trial sets `performance_claim_eligible=false`.

## Entrypoint and workflow

Build with JDK 21 using the existing `build.ps1`. The JAR retains its shared CLI main class; invoke the evaluator by classpath, or use the dedicated wrapper:

```powershell
$env:INTRA_PAPER_BUILD_DIR = Join-Path $PWD 'build'
.\build.ps1
.\portfolio-trial-evaluate.ps1 -TrialLedger .\trials.ledger -TrialCatalog .\catalog.json -RunsRoot .\portfolio-runs -ChronologicalBlocks 4 -OutputJson .\portfolio-evaluation.json
# Optional registration-bound exposure provenance:
.\portfolio-trial-evaluate.ps1 -TrialLedger .\trials.ledger -TrialCatalog .\catalog.json -RunsRoot .\portfolio-runs -ChronologicalBlocks 4 -OutputJson .\portfolio-evaluation-with-history.json -OutcomeLedger .\outcomes.ledger
```

Equivalent direct invocation:

```powershell
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
& $java -cp $jar com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationCli evaluate .\trials.ledger .\catalog.json .\portfolio-runs 4 .\portfolio-evaluation.json .\outcomes.ledger
& $java -cp $jar com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationCli identity .\portfolio-runs\<64-character-run-id>
```

`evaluate` requires a registration ledger, catalog, runs root, even block count from 4 through 12, and a new output path; the outcome ledger is optional. The output parent must exist. Validation finishes before writing output, and an existing output is never overwritten. `identity` verifies an existing artifact and prints its strategy, dataset, parameter, run, and manifest hashes; this inspection is **after replay**, so it cannot establish preregistration timing.

Register before generating or inspecting outcomes. The existing `ResearchTrialLedger.register(Path, Trial)` accepts the portfolio identities through its existing eight-field trial type. The single-contract `trial-ledger-register` shared CLI command is not a portfolio registration command. For programmatic portfolio registration:

```java
// dataset has been validated; config, strategy, fillModel, and costs are frozen.
String parameters = PortfolioTrialIdentity.parameterConfigSha256(
        config, strategy, fillModel, costs); // costs: Optional<PortfolioExecutionCostSchedule>
var trial = new ResearchTrialLedger.Trial(trialId, strategy.id(), strategyVersion, variantId,
        strategyCardSha256, dataset.datasetsSha256(), split.splitFileSha256(), parameters);
var registration = ResearchTrialLedger.register(trialLedgerPath, trial);
// Only now replay and publish using the existing portfolio engine/writer.
```

The catalog is assembled after artifacts are available, using the previously frozen registration and split. Starting capital is an explicit fixed normalization input; it is not inferred from the account P&L or claimed to be an actual account balance. Preserve the catalog, ledgers, split, and their hashes independently. The evaluator does not append registrations/outcome events, rerun strategies, seal files, or grant evidence gates.

For settlement-enabled registration, use the overload that accepts both frozen settlement inputs:

```java
String parameters = PortfolioTrialIdentity.parameterConfigSha256(
        config, strategy, fillModel, executionCosts, settlementEvents, settlementCosts);
// Optional<PortfolioSettlementEvents> and Optional<PortfolioSettlementCostSchedule>;
// the event instrument set must exactly match config.settlementInstruments().
```

The original four-argument helper remains valid for settlement-disabled runs. It rejects an enabled policy without supplied settlement inputs.

## Strict catalog and identity contract

The UTF-8 JSON catalog requires root keys `schema` and `trials`, with optional `artifact_scope`. Schema remains `INTRA_PORTFOLIO_TRIAL_CATALOG_V1`; other unknown, duplicate, or missing keys are rejected. The catalog supports up to 16 MiB and requires at least two trials for evaluation. Each trial has exactly these fields:

| Field | Meaning |
| --- | --- |
| `trial_id`, `strategy_id`, `strategy_version`, `variant_id` | Exact existing registration identifiers |
| `strategy_card_sha256` | Exact registered frozen research-card hash |
| `dataset_sha256` | Existing portfolio `validated_datasets_sha256` fingerprint |
| `split_plan_sha256` | SHA-256 of the exact registered split CSV bytes |
| `parameter_config_sha256` | Portfolio parameter identity described below |
| `registration_record_sha256` | Exact verified registration-chain record hash |
| `run_id` | Existing 64-character portfolio replay fingerprint and folder name |
| `run_manifest_sha256` | SHA-256 of the published `run-manifest.properties` bytes |
| `split_plan_csv` | Split file path, relative to the catalog directory or absolute |
| `starting_capital_inr` | Positive plain decimal **JSON string**, e.g. `"100000"` |

Identifiers and hashes use the existing ledger restrictions; run IDs and hashes are lowercase 64-character hex. Capital cannot contain signs, exponents, grouping, whitespace, or currency symbols. Capital is normalized for comparison, and every member of a comparison family must use the same capital. Each trial and run ID must appear exactly once.

The comparison family is **all registrations sharing the dataset and split-plan hashes**, across strategy IDs/versions. Every such registration must have exactly one catalog run. Registrations from other families are counted but not scored. Outcome history cannot remove registered failures, unseen trials, or variants without an artifact from the family; a missing family artifact fails evaluation.

The split reuses `EvaluationSplitPlan.loadForEvaluation`: CSV headers must be exactly `session_date,partition`, dates must be canonical YYYY-MM-DD and unique, and DEVELOPMENT, VALIDATION, and HOLDOUT must all be nonempty and contiguous in chronological date order. Physical CSV row order is immaterial to assignment validation; its exact bytes still determine the registered hash. All trials must share the same complete assignments. See `examples/portfolio-trial-evaluation/catalog.example.json` and `split.example.csv`; their hashes and run IDs are placeholders, not registered trials.

Portfolio dataset identity deliberately uses the existing `INTRA_VALIDATED_PORTFOLIO_DATASETS_V1` fingerprint, rather than the single-contract trial dataset hash. The evaluator reconstructs that fingerprint from verified `datasets.csv` provenance. It also reconstructs the strategy, fill-model, and replay fingerprints from the frozen manifest metadata.

Portfolio parameter identity uses the domain `INTRA_PORTFOLIO_TRIAL_PARAMETERS_V1`. Encoding is four-byte big-endian UTF-8 byte length followed by bytes for the domain, a four-byte field count, then each key/value pair in this fixed order:

1. `strategy_id_base64`, `strategy_fingerprint_sha256`, `strategy_parameters_base64`
2. `fill_model_id_base64`, `fill_model_fingerprint_sha256`, `fill_model_parameters_base64`
3. `execution_cost_schedule_enabled`, `execution_cost_schedule_id_base64`, `execution_cost_schedule_source_file_sha256`
4. `max_abs_lots_by_instrument_base64`, `pending_intent_boundary_policy`, `position_boundary_policy`, `require_contiguous_frames_within_session`

Settlement-disabled registrations preserve this V1 identity exactly. Settlement-enabled registrations use domain `INTRA_PORTFOLIO_TRIAL_PARAMETERS_V2` and append, in order: `settlement_policy`, `settlement_instruments`, `settlement_events_source_file_sha256`, `settlement_cost_schedule_id_base64`, and `settlement_cost_schedule_source_file_sha256`. Changes to supplied settlement values, source references, targets, cost inputs, or policy therefore cannot silently reuse a registration identity.

Values use the unchanged canonical portfolio manifest encoding. This freezes strategy/fill parameters, per-instrument limits, optional dated costs, and boundary policies. Split/card/dataset are separate registration hashes. Starting capital is catalog provenance, separate from the replay parameter identity. Strategy version, variant, and card are external registration declarations: the old replay format does not embed them or prove their relation to reviewed source code.

## Aggregate session returns and coverage

For each trial, take the final account checkpoint of each registered session: **BAR_CLOSE**, or the last **SETTLEMENT** snapshot when that session has explicit settlement events:

```text
N_t = sum over instruments(realized_gross_pnl_inr + unrealized_pnl_inr - fees_inr)
delta_N_t = N_t - N_previous_session
r_t = delta_N_t / C_0
N_before_first_session = 0
C_0 = explicit fixed starting_capital_inr
cumulative_simple_return = sum(DEVELOPMENT/VALIDATION delta_N_t) / C_0
```

Amounts and divisions use `BigDecimal` with DECIMAL128 for returns; statistical observations are finite Java doubles. There is no compounding, capital/margin inference, cash-flow adjustment, risk-free deduction, portfolio-notional denominator, or implicit reset of cumulative P&L. Fees are already subtracted in net P&L. Returns may reflect leverage; the capital input is not a solvency or margin check.

`equity.csv` repeats the account totals on each instrument row. The evaluator verifies full instrument coverage and equality of those totals, reconciles included totals to instrument components, and counts the account checkpoint **once**. Paired BAR_OPEN/BAR_CLOSE snapshots must have increasing timestamps, common intervals, contiguous intraday frames, and counts matching every instrument's validated bars and complete sessions. Every dataset's `final_bar_close_timestamp_utc` must equal the actual last BAR_CLOSE timestamp, including in sealed holdout artifacts. Terminal settlement snapshots must match the declared events' timestamps, exact identities, source hashes, and signed residual quantities; same-time events share one snapshot. Only declared positions may become flat. The session end is updated after the last event, so proceeds and charges enter returns once. Snapshot dates use the common dataset timezone. Sessions spanning local midnight and mixed timezone IDs are unsupported.

`artifact_scope` is an explicit catalog-wide choice:

- `ALL_SPLIT_SESSIONS` (default): artifacts must contain every and only registered split session, including HOLDOUT. Since replay settlement is terminal, any settlement occurs in the final holdout session and is excluded from evaluated returns.
- `DEVELOPMENT_VALIDATION_ONLY`: artifacts must contain every and only DEVELOPMENT/VALIDATION session, ending at the last validation session. The frozen split must still declare nonempty future HOLDOUT sessions; no holdout artifacts may be included. This scope allows terminal settlement P&L and charges to enter validation returns without opening the holdout. Missing included sessions, partial prefixes, or an unexpected holdout row fail. Declare this scope before the run; do not relabel observed holdout outcomes as validation.

To use a prefix, set `"artifact_scope": "DEVELOPMENT_VALIDATION_ONLY"` in the catalog and generate the registered dataset/replay using only the frozen development/validation dates. Full-split and prefix datasets have different byte-bound identities. Scope itself is bound by the catalog hash and emitted in the report; complete comparison-family and common dataset/split/capital requirements still apply.

Each included partition must be flat immediately before its first session and at its final checkpoint, after any settlement; full-split mode checks the holdout's structural flat boundaries too. `REQUIRE_FLAT` additionally requires every observed session to end flat. Overnight positions within a partition under `ALLOW_OVERNIGHT` contribute their mark-to-market changes to the next session. Strategies and pending-intent state are not reset by this evaluator.

Only DEVELOPMENT and VALIDATION numerical P&L enters session returns and scores. Prefix mode performs full artifact accounting verification after confirming no holdout rows are present. Full-split mode uses integrity checks plus split-aware validation: holdout timestamps, identities, quantities, and event/charge links are inspected, but holdout equity, settlement values/P&L, charge rates, and charge amounts are not numerically parsed, scored, or emitted. This structural holdout check does not certify held-out accounting. Use the full replay verifier separately when authorized to inspect that partition. Existing replay artifacts expose holdout performance to an operator; these files are not a one-shot holdout vault.

## Statistics, provenance, and limits

`SessionReturnStatistics` is shared with the existing single-contract evaluator. Sample session-return standard deviation uses denominator T-1; daily/session Sharpe is mean divided by that deviation. Annualized Sharpe multiplies by sqrt(252), with zero risk-free rate. Skewness and raw kurtosis use unadjusted population central moments. Exactly zero variance, invalid DSR variance, nonfinite observations, and zero-variance CSCV folds fail closed.

For raw family count M, sample dispersion of trial Sharpes sigma_S, and Euler's constant gamma:

```text
S_star(M) = sigma_S * [(1-gamma) Phi^-1(1-1/M) + gamma Phi^-1(1-1/(M*e))]
S_star(1) = 0
SE(S) = sqrt((1 - skewness*S + ((raw_kurtosis-1)/4)*S^2) / (T-1))
DSR(M) = Phi((S - S_star(M)) / SE(S))
```

The raw-M approximation remains `deflated_sharpe_probability`. Sensitivity reports the same approximation at N_eff=1 and N_eff=M. These are assumed endpoints, not an estimated effective count, fitted dependence correction, confidence interval, rejection threshold, or performance gate. Normal/inverse-normal numerical approximations are inherited unchanged.

CSCV concatenates DEVELOPMENT and VALIDATION observations chronologically, splits them into near-equal contiguous blocks, and enumerates every half-block/complement combination (4–12 even blocks, at least two sessions per block). In-sample selection maximizes Sharpe; exact ties select ascending `trial_id`. Out-of-sample cross-sectional ties use midranks; PBO is the fraction of selected winners strictly below the OOS median. OOS scores exactly at the median do not count as below.

Reports include evaluator/run build metadata, catalog and ledger file hashes, final registration-chain hash, registration sequence/record hashes, all trial identities, pinned run-manifest and output-file hashes, complete registered/catalog/analyzed/family counts, included per-session changes/returns, and per-trial summaries. Build metadata can be `unknown` or `unknown-dirty`; it is not source authentication. Supplied outcome history must verify its full chain, event transitions, and registration bindings. Its file/final-chain hashes, event counts, and overall/family exposure-state trial counts are emitted; omitted history is explicitly `supplied=false`. Provenance inputs are checked for changes while being read.

Hashes and operator-maintained chains detect changes relative to the supplied anchors; they do not authenticate a data source, prove every experiment/view was logged, independently prove preregistration timing, or prevent wholesale replacement of artifacts, catalog, and ledgers together. Verification does not replay frozen source files or certify rights, completeness beyond supplied validated provenance, actual fills, tariffs, margin, accounting economics, or strategy-version review.

Sharpe annualization and DSR assume sampling conditions that are not established here. Trial correlation, serial dependence/HAC/bootstrap uncertainty, purging/embargo, chronological walk-forward validation, and independently reviewed sealed holdout custody remain open. A separate [one-shot release controller](ONE-SHOT-HOLDOUT-RELEASE.md) now freezes public input hashes, checks signed authorization, logs access attempts, and consumes one custody-adapter invocation; it does not seal these existing replay files or certify blindness. CSCV complements can contain later dates than their selection blocks; PBO is a selection-instability diagnostic, not a forward forecast or a profitability probability. Short samples, near-zero variance, and dependent variants can make diagnostics unstable.

Existing G0/G1/G2 and downstream strategy/execution/risk/operations evidence gates remain open. This evaluator has no gate override and grants no strategy-performance, paper-trading, or live-trading readiness claim.

Method references already reviewed in this project: [DSR/PBO method audit](../../research/systems/2026-11-04-dsr-pbo-audit.md), [effective-trial and Sharpe-dependence decision](../../research/systems/2026-11-04-effective-trial-and-sharpe-dependence-decision.md), Bailey & Lopez de Prado (2014), *The Deflated Sharpe Ratio*, Eq. 1–2/Appendix A.3, and Bailey et al. (2015 revised paper), *The Probability of Backtest Overfitting*, Algorithm 2.3. No new independent method certification is claimed.

## Focused validation

The separately reported Sharpe HAC increment is currently at the design stage.
See [the method extraction and required protocol decisions](PORTFOLIO-SHARPE-HAC-DESIGN.md)
for the pinned R14/DR-12 equation, candidate bandwidth grid, unresolved adequacy
and interval conventions, and the planned synthetic checks. No HAC output is
implemented or independently preregistered by that note.

Run `test-portfolio-trial-evaluation.ps1` under JDK 21. It compiles production/test sources and exercises real synthetic, complete two-instrument replay artifacts. Coverage includes all registration identities, run identity versus registration, strict catalog/capital handling, missing/duplicate sessions, chronological partitions, flat boundaries, complete-family and unrelated registration counts, optional outcome history, locale/timezone/input-order determinism, holdout P&L exclusion, checksum and rehashed artifact tampering, ledger tampering, undefined statistics, and create-new CLI output. Settlement checks cover input-bound V2 identities, both artifact scopes, exact-once charges, sealed holdout financial fields, and final-bar metadata/checkpoint agreement even after output hashes and manifest pins are refreshed. Existing replay, execution-cost, and artifact-verifier checks run in the same focused script. Synthetic checks are implementation evidence only.
