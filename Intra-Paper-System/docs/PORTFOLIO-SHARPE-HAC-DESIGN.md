# Portfolio Sharpe HAC diagnostic: method extraction and decisions

Status: **DESIGN ONLY; protocol decisions pending; implementation not started.**
Recorded 2026-11-06 before implementation or inspection of real financial outcomes.
This local note does not establish independently anchored preregistration.

Source: R14/DR-12 review on `codex/r14-dr12-methods-review-20261105`,
commit `263e435f6daf3986267aae1e3d9b3ef45d3e707b`,
`research/systems/2026-11-05-r14-dr12-statistical-methods-review.md`,
Git blob `e56d0b9b3f3ccc56004303de871c45e008bc40bd`, especially sections
4.3, 7, 8 and 10. The review calls this a proposal, not an adoption decision.

Code inspected: checkout `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`,
including committed settlement/evaluation integration `c1ed5c6`.
The latest reviewed local integration branch, `codex/local-integration-reviewed-20261106`
at `07e5483`, has the same production evaluator and a stronger sealed-holdout
checkpoint test. The current checkout is the inspected production base; no branch
switch, merge, staging, or changes to existing uncommitted work are needed.

## Extracted estimator and conventions

One observation is one verified, complete exchange session. Chronologically join
DEVELOPMENT then VALIDATION for each registered trial. Existing return construction
is `x_t = (N_t - N_previous_session) / C`, where `C > 0` is explicit fixed capital
in INR, `N` is net portfolio P&L including applicable settlement fees, and
`N_before_first_session = 0`. Returns are simple, dimensionless fractions; the
current risk-free session return is explicitly zero. No compounding or annualization
enters the HAC estimator. HOLDOUT financial values are not numerical inputs.

For `T` observations, let `mu = mean(x)`, `m2 = mean(x^2)`, and
`v = mean((x-mu)^2) > 0` (population-denominator variance, computed stably).
The following quantities are plug-in estimates:

```text
z_t = (x_t, x_t^2)'
u_t = z_t - mean(z)                       # global centering at every lag
d = (m2 / v^(3/2), -mu / (2*v^(3/2)))'
Gamma_k = (1/T) * sum[t=k+1..T] u_t u_(t-k)'
w_k = 1 - k/(L+1)                        # Bartlett kernel
Omega_L = Gamma_0 + sum[k=1..L] w_k * (Gamma_k + Gamma_k')
c_T = sqrt((T-1)/T)
S_hat = c_T * mu / sqrt(v)                # existing sample-SD point estimate
SE_HAC,L = c_T * sqrt((d' Omega_L d) / T)
```

All lag covariances use denominator **T**, not `T-k`. No covariance matrix inverse
is required. Section 7 proposes no prewhitening and extra HAC multiplier **1**;
do not introduce `T/(T-4)` or another degrees-of-freedom adjustment.
The `c_T` scaling is a finite-sample reporting convention, not an exact correction.
At `L=0` this SE generally differs from the legacy PSR/DSR SE.

An independent scalar check uses the estimated influence series:

```text
psi_t = (x_t-mu)/sqrt(v) - mu*((x_t-mu)^2-v)/(2*v^(3/2))
```

Apply the same global centering, lag denominator, Bartlett weights and SE scaling
to `psi`. Report ACFs of returns, squared centered returns, and influence values
over an explicitly frozen lag range, with pair counts and undefined status for
zero-variance ACF inputs. Do not use an ACF significance screen to enable HAC.

The result is uncertainty of an **original-frequency, fixed-candidate session
Sharpe**, in the same dimensionless ratio units as that Sharpe. It is neither
trial-count sensitivity nor a selection-adjusted probability. Required assumptions
are a stable positive population variance, suitable moments/dependence for a joint
moment-process CLT, and consistently estimable long-run covariance. Finite fourth
moments alone, or positive semidefiniteness of the estimate, does not establish
interval coverage. Pooling partitions requires a recorded regime justification.

## Decisions required before implementation

| Choice | Review's exact position / required decision |
| --- | --- |
| Estimator conventions | Adopt or explicitly revise the equation above, Bartlett kernel, `c_T`, no prewhitening and multiplier 1. These are proposed conventions, awaiting adoption. |
| Bandwidth | Candidate for synthetic qualification: `L0=floor(T^(1/5))`; sorted deduplicated grid `{0, max(1,floor(L0/2)), L0, 2*L0}`. No adopted optimal rule. `L=0` is a no-lag comparator; `L>=T` is unavailable and must not be clipped. Approve this grid or supply another rule; freeze ACF lags too. |
| Minimum observations | No statistical minimum is chosen. Arithmetic needs `T>=2` and `0<=L<T`. Existing legacy CSCV needs `T>=2*blocks`; this is not HAC adequacy. Specify minimum history and any additional lag/sample constraint. |
| Numerical conventions | Stable centered variance is required, but near-zero thresholds, tolerances, exact summation algorithm and runtime policy remain unchosen. Specify units/scale behavior; do not silently clamp a nonpositive projected variance. |
| Session axis / gaps | Freeze an authoritative exchange-session axis and its hash, closure/gap policy, eligible no-trade accounting, partition join and pooling rationale. A list of split dates alone cannot establish that all gaps are exchange closures. Unsupported irregular sessions must not be silently compressed or padded. |
| Interval | No level, sidedness, interval method or adequacy rule is chosen. Explicitly choose SE-only, or preregister a method and confidence level. The proposed two-sided normal interval is `S_hat +/- z_(1-alpha/2)*SE_HAC`; it is asymptotic fixed-candidate sampling uncertainty, not post-selection coverage or a probability of profitability. |
| Preregistration | Supply protocol identity, an independent timestamp/hash anchor and prior-exposure disclosure; otherwise explicitly label independent preregistration unverified. The local creation timestamp and input hashes cannot prove registration preceded outcomes. |

An unanswered required setting prevents an inferential run. Missing values remain
missing; no hidden defaults, observation imputation, lag clipping or favorable
scenario selection is allowed. Unsupported/incomplete cases must fail closed or
report `INCOMPLETE` with reason codes and null unavailable numerical fields.

## Integration and validation contract

Add a separately reported HAC diagnostic fed only by the existing verified included
session rows. Keep `SessionReturnStatistics` formulas and all DSR/PBO/sensitivity
results unchanged, together with `performance_claim_eligible=false` and the existing
offline diagnostic classification. A separate HAC result must not relabel legacy
DSR as serial-dependence adjusted. Preserve the split-aware holdout reader; the
HAC component receives no holdout financial fields or run-directory access.

Bind protocol bytes/hash, estimator version, source memo hash, analysis code
identity, chosen and resolved settings, ordered trial/run identities, registration
and outcome provenance, catalog/dataset/split/artifact hashes, fixed capital,
and a canonical included-session/return hash into the output. Hash full artifacts
for integrity without numerically interpreting holdout payloads. Holdout mutations
may change provenance hashes but must leave every included statistic unchanged.
Keep volatile timestamps outside any deterministic payload hash.

Before new tests run, freeze synthetic process definitions, deterministic seeds or
explicit fixtures, tolerances and qualification claims in the adopted protocol.
Required focused checks are matrix/scalar equation agreement; independent
homoskedastic returns; autocorrelated returns; volatility clustering with small
raw-return ACF and nonzero mean; insufficient history; zero/near-zero variance;
missing/duplicate/reordered/malformed sessions and unsupported irregular axes;
repeat output across locale/timezone changes; unchanged DSR/PBO for identical
inputs; and mutation of all holdout financial fields to nonnumeric sealed strings.

The review's exact arithmetic fixture, used only to test the equation, is
`x=(0,0.01,0,0.01)`, `T=4`, `S_hat=sqrt(3)/2`, `psi=(-1,1,-1,1)`.
Projected long-run variance is 1 at `L=0` and 1/4 at `L=1`; the SEs are
`sqrt(3)/4` and `sqrt(3)/8`. This tiny fixture does not justify a real-data minimum.
Coverage validation requires a separately frozen simulation budget and acceptance
tolerances; arithmetic or deterministic tests alone do not establish coverage.

## Existing baseline check

On 2026-11-06, tracked production sources and the existing
`PortfolioTrialEvaluationTest` compiled with Microsoft JDK `21.0.13.0.2+1-LTS`;
all **161** existing synthetic checks passed. Compiler outputs, synthetic fixture
directories and logs were isolated under
`C:\Users\prash\AppData\Local\Temp\codex-hac-baseline-74d441134e064d1e9cb57f2bd3eca50b`.
No HAC implementation, new HAC test, coverage experiment or real-data evaluation
has run. An initial compile including unrelated untracked operations-evidence
sources failed with four missing-method errors in `DailyOpsEvidenceChecker.java`;
that existing work was left untouched. This baseline result applies to tracked
sources, not to compilation of every local work-in-progress file.
