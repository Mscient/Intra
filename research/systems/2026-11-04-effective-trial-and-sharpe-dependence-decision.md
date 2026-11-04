# Effective trial count and session Sharpe dependence

**Reviewed:** 2026-11-04 (Asia/Kolkata)  
**Scope:** methods for the offline DEVELOPMENT/VALIDATION evaluator only. No trading, profitability, or pass/fail threshold is inferred.

## Decision

Keep the current DSR result based on the raw number of preregistered trials. Add two explicitly labelled scenarios per trial: effective independent trial count `N_eff=1` and `N_eff=M`, where `M` is the raw comparison-family size. The first is the full-dependence endpoint and the second is the current independent-trial assumption. These scenario outputs bound the expected-maximum adjustment under the evaluator's approximation; neither is an estimate of actual dependence. Do not fit average trial correlation yet.

The evaluator continues to label session Sharpe annualization as unadjusted for serial dependence (`sqrt(252)`). Do not change PSR/DSR inference until a dependence method, lag/bandwidth rule, and independent validation plan are preregistered. A later small increment can report session-return autocorrelation and a Lo-style annualization sensitivity, then add a HAC delta-method uncertainty estimate after its bandwidth rule is reviewed.

The existing trial outcome ledger may be supplied to `statistical-evaluate` for verified hash/count/exposure provenance. It does not define an effective trial count or alter scoring.

## Methods and assumptions

### DSR trial multiplicity

Bailey and López de Prado (2014), Eq. 1–2, define DSR as a probabilistic Sharpe calculation at a selection-adjusted Sharpe hurdle. Their expected-maximum approximation depends on the dispersion of estimated Sharpes and the number `N` of independent trials. Appendix A.3 distinguishes `M` dependent trials from an implied independent count and proposes an average-correlation approximation. For an equal-weight family, the mean off-diagonal correlation is

`rho_bar = 2 * sum(i < j, rho_ij) / (M * (M - 1))`,

and the implied count is

`N_eff = rho_bar + (1 - rho_bar) * M`.

It maps `rho_bar=0` to `M` and `rho_bar=1` to `1`. It is a scalar approximation to the quadratic form of the correlation matrix, not a general measure of dependence. A highly clustered family can have the same average correlation as a diffuse family while having a different effective search structure. Nonlinear dependence is not captured. With negative average correlation the formula can exceed `M`; the positive-definite equicorrelation lower bound is `-1/(M-1)`, and noisy sample correlations may violate that bound. Do not silently clamp or round such an estimate and call it canonical.

The paper explicitly warns that correlation is only linear dependence and that, when `M` is large relative to sample length `T`, average-correlation estimates can be overfit and the matrix ill-conditioned; it discusses dimension reduction or information-theoretic alternatives rather than claiming the raw estimate is reliable. Guided/adaptive search and unlogged variants make the family definition incomplete, regardless of the correlation estimator.

### Session-return dependence

The current evaluator forms simple session returns as net session P&L divided by fixed analysis capital. Its sample Sharpe is `mean(r)/sd(r)` with a zero risk-free rate. Its `sqrt(252)` annualization is justified only under restrictive conditions such as IID session returns. Lo (2002), Eq. 19–20, derives time aggregation for stationary returns. For `q` periods, the ratio of aggregated to one-period Sharpe is

`SR_q = sqrt(q) * SR_1 / sqrt(1 + 2 * sum(k=1..q-1, (1-k/q) * rho_k))`,

where `rho_k` is lag-`k` return autocorrelation. Positive serial correlation generally reduces the IID annualized value; negative serial correlation can increase it. This is a time-series property and is distinct from correlation across strategy trials.

Ordinary PSR/DSR uncertainty uses a sample-length and return-moment expression that relies on the assumed sampling behavior of the return series. For serial dependence, Lo develops stationary-process asymptotics and a delta-method treatment of the Sharpe estimator. A HAC estimator can estimate the long-run covariance of a moment vector such as `(r_t, r_t^2)`; the delta method then maps that covariance to the Sharpe ratio. Newey–West's Bartlett-weighted HAC covariance is positive semidefinite by construction, but selecting a lag/bandwidth remains a material modeling choice and finite samples can make it noisy.

Block bootstrap methods resample dependent runs of observations and recompute the statistic. The stationary bootstrap uses geometrically distributed block lengths and circular continuation. It requires a preregistered expected block length, replicate count, and deterministic seed for replay; changing the block length can change results. Weak stationarity/short memory is still an assumption, and a bootstrap cannot repair structural breaks, inadequate data, or an invalid simulator.

## Choice comparison

| Method | Inputs | Benefit | Main failure / limit | Decision |
|---|---|---|---|---|
| Raw `M` plus `N_eff=1` / `M` sensitivity | Existing complete aligned trial returns and raw preregistered `M` | Transparent multiplicity scenarios; deterministic; no fitted dependence parameter | Does not identify actual dependence; scenarios are not confidence bounds | Implemented as diagnostic endpoints; raw-`M` result remains canonical output |
| Average-correlation `N_eff` | `T x M` aligned session-return matrix; complete trial family; stable sample correlation matrix | Directly follows DSR Appendix A.3 | Correlation is linear only; small `T`/large `M` ill-conditioning; adaptive search and clusters complicate interpretation; negative estimates can imply `N_eff > M` | Defer; no automatic clamp or single point estimate |
| Lo annualization | One ordered session series; chosen horizon `q`; autocorrelations through lag `q-1` | Makes time-aggregation assumption visible | Long-lag autocorrelation estimates are noisy when `q` is large relative to `T`; stationarity assumption | Next descriptive diagnostic/sensitivity, without performance gating |
| HAC/Newey–West Sharpe uncertainty | Ordered returns, moment vector, preregistered kernel/bandwidth and finite-sample convention | Asymptotic uncertainty robust to weak dependence and heteroskedasticity under conditions | Bandwidth-sensitive, asymptotic, not a drop-in replacement for all DSR components | Candidate next inference method after preregistration and independent numerical validation |
| Moving/stationary block bootstrap | Ordered returns; predeclared block scheme/length, replicates, seed | Recomputes nonlinear statistics while retaining local dependence | Block-length bias/variance tradeoff; nonstationarity and short samples remain; Monte Carlo error | Alternative cross-check; do not select after seeing a favorable result |

No observed-result-based thresholds or parameters are selected here. Use finite numeric checks and fail closed when an estimator is undefined; do not convert a numerical result into eligibility.

## What the diagnostics answer

- **DSR** adjusts a PSR-style statistic for a selection hurdle over a specified independent-trial count and non-normal return moments. It does not repair missing experiments, dependent session inference, bad data, incorrect execution/cost assumptions, or holdout leakage.
- **PBO/CSCV** estimates how often an in-sample-selected member of a complete synchronous trial family ranks below the OOS median over symmetric combinatorial partitions. Algorithm 2.3 requires a `T x M` matrix of synchronous observations. CSCV is not a forward forecast: a complement may contain dates later than its in-sample blocks. Strong autocorrelation plus too many small blocks can obscure time structure. PBO is not a profitability estimate or optimization target.
- **Chronological walk-forward** repeatedly fits/selects using only past data and evaluates a later segment. It directly tests the chosen rolling deployment protocol, but a small number of folds can be unstable and it still requires frozen choices, costs, and complete trial accounting.
- **One-shot sealed holdout** is a final untouched evaluation for one already-selected candidate. It can estimate that candidate's final out-of-sample result under its declared protocol; it cannot erase prior development selection or certify the simulator. This repository's ordinary run folders contain holdout artifacts and are not a sealed vault.

These methods answer different questions and should be reported separately.

## Current code support and missing evidence

The evaluator has complete DEVELOPMENT/VALIDATION session net-P&L series for every preregistered run in the selected dataset/split family, enforces a common session index, computes descriptive per-trial Sharpe/moments, raw-`M` DSR approximation, and chronological-block CSCV PBO, and excludes HOLDOUT P&L. The sensitivity output now evaluates the same DSR approximation at `N_eff=1` and `N_eff=M`; it does not estimate pairwise correlations.

Still absent are a complete guarantee of all attempted/viewed outcomes, a return-dependence estimator with predeclared bandwidth/block length, enough authorized real sessions to support inference, a physically/logically enforced one-shot holdout, validated costs/fills, and independent statistical review. Bar-level labels, purging, and embargo are not assumed: no observation-level label span is defined in this evaluator.

## Contract for a later dependence-aware increment

Prerecord the following before the affected results are inspected:

- estimator name/version and return basis (session net P&L / fixed capital);
- included partitions, horizon `q`, autocorrelation lag cap, HAC kernel and bandwidth or bootstrap block rule/length;
- finite-sample correction, missing-session handling, replicate count and seed if resampling;
- evaluation family identity: registration ledger hash, dataset/split hashes, run-index hash, trial count, common session dates;
- any risk-free rate (currently fixed at zero) and the convention for undefined/near-zero variance.

Report original-frequency Sharpe and IID annualized Sharpe unchanged for comparability, plus the chosen dependence-aware estimate, method parameters, session count, autocorrelation/HAC diagnostics, and failure/undefined reason. Do not emit a pass flag or modify `performance_claim_eligible=false`. Correlation-based effective `N` should remain a separate family-level diagnostic with `T`, `M`, full-matrix validity, and any sensitivity range; it must not be conflated with session serial dependence.

## Primary references

| Source | What was checked |
|---|---|
| Bailey & López de Prado (2014), “The Deflated Sharpe Ratio,” *Journal of Portfolio Management*; [full paper](https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf) | Eq. 1–2 and Appendix A.3: selection hurdle, independent `N`, average-correlation/effective-count approximation, and limits for large `M` relative to `T` |
| Lo (2002), “The Statistics of Sharpe Ratios,” *Financial Analysts Journal* 58(4), 36–52; [author-hosted paper](https://traders.berkeley.edu/papers/The-Statistics-of-Sharpe-Ratios.pdf) | IID versus stationary-return inference and time aggregation, including Eq. 19–20 |
| Newey & West (1987), “A Simple, Positive Semi-Definite, Heteroskedasticity and Autocorrelation Consistent Covariance Matrix,” *Econometrica* 55(3), 703–708; [NBER record](https://www.nber.org/papers/t0055), [DOI](https://doi.org/10.2307/1913610) | HAC covariance method and positive-semidefinite construction; the citation is not a Sharpe-specific formula |
| Politis & Romano (1994), “The Stationary Bootstrap,” *JASA* 89(428), 1303–1313; [journal/DOI record](https://doi.org/10.1080/01621459.1994.10476870), [author-hosted paper](https://users.ssc.wisc.edu/~behansen/718/Politis%20Romano.pdf) | Geometric block lengths and stationary resampling for weakly dependent stationary observations |
| Bailey, Borwein, López de Prado & Zhu (2015 revised), “The Probability of Backtest Overfitting”; [full paper](https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf) | Algorithm 2.3 and §5 limits: synchronous `T x M`, symmetric partitions, temporal dependence, full trial family and incorrect-model limits |

All equations and method claims above were checked against the linked primary papers. No NIFTY return data or strategy results were used.
