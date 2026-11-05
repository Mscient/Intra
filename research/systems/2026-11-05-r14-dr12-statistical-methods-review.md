# R14 / DR-12: independent statistical-methods review

**Author:** Codex, independent methods review for this task

**Date / source access:** 2026-11-05, Asia/Kolkata

**Status:** Research recommendation for review; no implementation or performance acceptance decision.

**Code baseline:** `9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c` in an isolated branch, `codex/r14-dr12-methods-review-20261105`. Uncommitted work in the supplied checkout was excluded.

**Boundary:** Source code, documentation, papers, and invented arithmetic fixtures only. No run folders, market data, evaluation outcomes, or holdout outcomes were opened; no evaluator was run and no Java was changed.

## 1. Recommendation and changes to the previous decision

Retain the current raw-family-count DSR approximation as a clearly labelled legacy diagnostic. Keep trial-count sensitivity separate from uncertainty caused by dependent session returns. The next increment should be a preregistered, independently checked **original-frequency Sharpe HAC diagnostic**, with return and squared-return autocorrelations, evaluated first on synthetic processes. Do not replace the DSR denominator, estimate a canonical effective trial count, change PBO scoring, or assign a performance pass threshold in that increment.

The [existing decision](2026-11-04-effective-trial-and-sharpe-dependence-decision.md) correctly separates the two kinds of dependence and defers a fitted effective count. This review adds the following qualifications; it does not repeat that decision as evidence:

| Previously reviewed position | Independent finding / implication |
|---|---|
| Counts 1 and raw `M` are sensitivity endpoints. | Confirmed as code behavior. They bound a **chosen scenario grid with fixed dispersion**, not selection bias under arbitrary dependence or an incomplete search history. A negative-correlation counterexample appears in §3. |
| Average correlation gives `N_eff = rho_bar + (1-rho_bar) M`. | Verified in DSR Appendix A.3, Eq. (8). Its preceding dominant-eigenvalue approximation is material, and neither expression identifies the distribution of the maximum. Cluster and dispersion counterexamples appear in §3. |
| Noisy correlations can violate the equicorrelation lower bound. | Needs correction: **any valid positive-semidefinite correlation matrix** satisfies the average-correlation bound. Ordinary complete-case sample correlation preserves it, even when singular. Pairwise deletion or incoherent estimation can break it. |
| A large `M/T` and ill-conditioning prevent average-correlation estimation. | Too broad. Computing the average requires no matrix inversion. High-dimensional rank deficiency is expected; the more serious issues are estimation uncertainty, nonstationarity, the wrong correlation target, and the heuristic's interpretation. |
| Lo annualization is the natural next step before uncertainty. | Useful as a scaling diagnostic, but it does not resolve sampling uncertainty. Zero return ACF can coexist with dependent volatility. Include squared-return/moment dependence and qualify HAC directly. |
| The evaluator has a complete family of returns. | It **requires** complete submitted returns relative to one registration-ledger dataset/split family. Source inspection establishes this validation rule, not the existence, adequacy, or completeness of actual research history. |
| Chronological blocks implement CSCV. | Broadly confirmed, with consequential conventions: near-equal rather than necessarily equal blocks, strict negative logits, and deterministic IS tie-breaking. See §6. |

All proposed changes below are a handoff to a separate code task. Existing fields `trial_dependence_estimated=false`, `sharpe_serial_dependence_adjusted=false`, and `performance_claim_eligible=false` accurately describe the reviewed implementation.

## 2. What the current evaluator actually calculates

Code citations below identify the reviewed commit, not a moving branch. `S` denotes population session Sharpe; `M` denotes the number of trial columns; `N` denotes an assumed independent-trial count; `T` denotes session observations. None of these counts is interchangeable.

| Code location at the baseline | Observed behavior and review consequence |
|---|---|
| [OfflineEvaluationAnalyzer, lines 221-306](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/OfflineEvaluationAnalyzer.java#L221) | Verifies registration and optional outcome chains; requires every registration sharing dataset/split hashes, one indexed run per trial, common capital and session dates. Outcome events affect provenance, not membership or scores. |
| [Return construction, lines 381-460](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/OfflineEvaluationAnalyzer.java#L381) | Checks partition/session coverage, completeness, and flat partition boundaries. Concatenates DEVELOPMENT and VALIDATION; divides net session P&L by fixed capital. HOLDOUT P&L is excluded from the statistics. This is not an access-control boundary: ordinary files still contain it. |
| [SessionReturnStatistics, lines 51-103](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/SessionReturnStatistics.java#L51) | Sorts trial IDs; computes sample-standard-deviation Sharpe, observed cross-trial Sharpe dispersion, raw-`M` expected maximum, and one-trial/raw-count PSR evaluations. Multiplies by `sqrt(252)` for the descriptive annualized value. |
| [Moment and SE definitions, lines 176-226](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/SessionReturnStatistics.java#L176) | Variance for Sharpe uses `T-1`; skewness and raw kurtosis use central moments normalized by `T`; SE uses `T-1`. These plug-in conventions are not unbiased small-sample moment estimates. Zero variance or invalid SE fails; zero cross-trial dispersion is permitted. |
| [CSCV, lines 106-173](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/SessionReturnStatistics.java#L106) | Enumerates every half-block subset, selects maximum IS Sharpe, scores its OOS rank, and counts `lambda < 0`. A zero-variance fold aborts the evaluation. |
| [ResearchOutcomeLedger, lines 36-63 and 285-345](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/ResearchOutcomeLedger.java#L36) | Records `ATTEMPT_STARTED`, `OUTCOME_PRODUCED`, `OUTCOME_VIEWED`, `MECHANICAL_FAILURE_NO_OUTCOME`, and `ABANDONED`, with attempt/trial identity. Aggregates exposure states; prior exposure is not erased by later failure/abandonment. |
| [PortfolioTrialEvaluationAnalyzer, lines 178-180](https://github.com/Mscient/Intra/blob/9a5ef1a4b3ac71c8fd889f3bfdc0dd18af86db8c/Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/portfolio/PortfolioTrialEvaluationAnalyzer.java#L178) | Calls the same shared statistics implementation. This review covers those shared formulas, not the portfolio accounting or its uncommitted changes. |

For trial `i`, define excess session return `x_it = net_pnl_it / C - f_t`, with `C > 0` fixed and `f_t = 0` in current code. With `m_ki = T^-1 sum_t (x_it - mean_i)^k`, the implemented conventions are

\[
s_i^2=\frac{T}{T-1}m_{2i},\quad
\widehat S_i=\frac{\bar x_i}{s_i},\quad
\widehat\gamma_{3i}=\frac{m_{3i}}{m_{2i}^{3/2}},\quad
\widehat\gamma_{4i}=\frac{m_{4i}}{m_{2i}^{2}}.
\]

Kurtosis is **raw**, so the normal reference is 3, not 0. No compounding or time-varying invested-capital return is implied. The multiplier 252 is a reporting convention, not evidence of 252 observed or independent sessions.

The legacy calculation can be written

\[
\widehat e_i^2=
\frac{1-\widehat\gamma_{3i}\widehat S_i+
 (\widehat\gamma_{4i}-1)\widehat S_i^2/4}{T-1},
\qquad \widehat{PSR}_i(s_0)=\Phi\!\left(\frac{\widehat S_i-s_0}{\widehat e_i}\right).
\]

This agrees structurally with PSR Eq. (11), using original-frequency quantities ([S2, §3, p. 9][psr]), and with DSR's PSR component ([S1, Eq. (2), pp. 8-9][dsr]). It is an asymptotic normal approximation with estimated moments, not an exact finite-sample probability or a Bayesian posterior probability of profitability.

**Accounting gap.** Registered variants, executed attempts, produced outcomes, viewed outcomes, and undeclared experiments are different populations. A deterministic retry need not be a new hypothesis; a changed seed, cost choice, sample window, or partial outcome that influenced a decision can expand the search. Current code counts registrations in the selected family even if a history says “no outcome”; indeed an unrun registration prevents the required complete run index. It cannot include an unregistered attempt or link searches across revised dataset/split hashes. Outcome events cannot prove that unlogged views never happened. A later campaign policy must reconcile these populations without deleting unfavorable/partial outcomes or automatically counting every retry as independent.

## 3. Trial multiplicity: exact source formula and independent critique

### 3.1 Raw count and the expected-maximum approximation

DSR Eq. (1) models independent, identically distributed normal **Sharpe estimates** across trials. Eq. (2) uses the zero-null expected-maximum hurdle; Appendix A.1 derives the large-`N` approximation. Define

\[
a(N)=(1-\gamma_E)\Phi^{-1}(1-1/N)
 +\gamma_E\Phi^{-1}(1-1/(eN)),\qquad \gamma_E\approx0.5772156649.
\]

The code uses

\[
d_S^2=\frac{1}{M-1}\sum_i(\widehat S_i-\overline{\widehat S})^2,
\quad h(N)=d_S a(N),\quad
DSR_i(N)=\widehat{PSR}_i(h(N)),
\]

with `N=M`; its one-trial scenario sets `h(1)=0` separately. The mean of observed trial Sharpes is used to center dispersion, not added to the zero-null hurdle. [S1, Eq. (1)-(2), pp. 7-9; Appendix A.1, p. 12][dsr].

**Our numerical check:** for independent unit normals, the exact two-trial expected maximum is `1/sqrt(pi) = 0.5641895835`; the approximation gives `a(2) = 0.5197553443`. Thus it underestimates this particular small-`N` hurdle. This does not invalidate its large-`N` derivation, but “conservative” is not a guaranteed finite-sample property. At `N=1` the inverse-normal expression is undefined; extending it continuously toward 1 produces a negative/divergent approximation, not the one-trial limit. Do not feed an arbitrary fitted real `N` near 1 into the existing routine. A later exact independent-normal integration or interpolation policy needs its own review.

For fixed nonnegative `d_S` and positive `e_i`, increasing integer `N >= 2` raises the hurdle and lowers the reported DSR. Therefore `{1,M}` brackets the **declared integer scenarios**, with the separate one-trial definition. It is not a confidence interval. It also cannot compensate for unknown omitted searches or uncertain dispersion. If all observed Sharpes coincide, `d_S=0`, all scenarios coincide even for many registrations.

### 3.2 What Appendix A.3 proposes

Appendix A.3 relates a dominant-eigenvalue approximation to average pairwise correlation and then sets

\[
\bar\rho=\frac{2}{M(M-1)}\sum_{i<j}C_{ij},\quad
\lambda_{\max}\approx1+(M-1)\bar\rho,\quad
N\approx M+1-\lambda_{\max}
\approx\bar\rho+(1-\bar\rho)M.                 \tag{A.3 / 8}
\]

It cautions about linear dependence, correlation estimation, and dimensionality, mentioning PCA conditioning and information-theoretic alternatives without specifying a complete replacement estimator. This is a heuristic, not an effective-sample-size formula for a sample mean. [S1, Appendix A.3, Eq. (7)-(8), p. 14][dsr].

The following are this review's mathematical checks and implications, not additional claims attributed to the paper:

1. **Average correlation loses search geometry.** With four Gaussian trial scores `(Z1,Z1,Z2,Z2)`, where `Z1,Z2` are independent standard normals, `rho_bar=1/3`, so Eq. (8) gives 3. The actual maximum is the maximum of only two draws. A four-variable equicorrelation matrix with the same `rho_bar=1/3` has a different maximum distribution. For 100 columns made of two independent clusters of 50 duplicates, Eq. (8) gives 51, although there are only two distinct random scores. Neither clustering nor general nonlinear dependence is recovered by this scalar.
2. **The quadratic form is not generally determined by an average.** For an equicorrelation matrix, `w' C w = (1-rho)||w||^2 + rho(1'w)^2`. It equals `||w||^2[1+(M-1)rho]` for the equal-weight direction, not arbitrary weights. With negative equicorrelation the equal-weight eigenvalue is not the largest. The interpolation in A.3 must not be promoted to a universal spectral identity.
3. **Negative correlation defeats a raw-`M` upper-bound claim.** For two scores `(Z,-Z)`, `E max = E|Z| = sqrt(2/pi) = 0.7978845608`, above the independent two-draw value `0.5641895835`, at equal marginal variance. This is a counterexample to independence being the worst possible dependence structure; it is not a calibration experiment for the whole plug-in DSR estimator.
4. **Matrix validity is distinct from sampling uncertainty.** Since `1' C 1 = M + M(M-1)rho_bar >= 0` for a valid correlation matrix, `rho_bar >= -1/(M-1)`; also `rho_bar <= 1`. The heuristic can therefore range up to `M+1` under valid negative correlation. Complete-case sample correlations obey this bound apart from rounding, even when `M >= T` makes the matrix singular. A bound violation is a construction/numerical problem, not ordinary sampling noise. No silent clamp, nearest-PSD repair, shrinkage, or rounding should masquerade as the original estimate.
5. **Dispersion and count cannot be calibrated separately by assumption.** Consider Gaussian score errors `Y_i = sigma[sqrt(rho) Z0 + sqrt(1-rho) Zi]`, `0 <= rho < 1`. If `A_M` is the exact independent unit-normal expected maximum, then `E max_i Y_i = sigma sqrt(1-rho) A_M`, while the expected sample cross-sectional variance is `sigma^2(1-rho)`. The observed-dispersion factor already reflects common dependence in this model. Reducing `N` as well can discount the selection hurdle again. This is not proof that the present estimator is calibrated; it shows why simply adding Eq. (8) to its existing dispersion estimate is not justified.
6. **The correlation target matters.** Same-session return correlation `Corr(x_it,x_jt)` generally differs from correlation of the estimated Sharpe errors. The latter depends on joint return/variance moments and cross-lag covariances. If `psi_it` is the Sharpe influence quantity in §4, then asymptotically `T Cov(S_hat_i,S_hat_j)` involves `sum_k Cov(psi_it,psi_j,t-k)`, not just the zero-lag return matrix. Adaptive search further invalidates treating a selected family as fixed independent draws. An aligned return matrix alone does not settle either issue.

**Decision:** Keep raw `M` as the named legacy assumption, not an asserted upper bound. A preregistered integer grid `{1} union {2^j: j>=1, 2^j<=M} union {M}` provides transparent interior scenarios without fitting `N`. Each row must retain the same `d_S`, SE convention, and data. Counts beyond `M` for missing-history stress would be a separate declared search-size assumption, not an estimated effective count. Defer correlation-derived `N` until its target, family, dispersion calibration, and synthetic failure tests are reviewed.

## 4. Session dependence: scaling and uncertainty are different estimands

### 4.1 Assumptions and a primary-source disagreement

The ordinary moment SE follows from a CLT for mean and variance under IID returns with suitable finite moments. The original PSR paper nevertheless extends its claim to stationary/ergodic returns by invoking Opdyke; it also says serially conditioned processes are not explicitly addressed ([S2, p. 4; §2.4, Eq. (8), p. 8][psr]). Ledoit and Wolf specifically dispute Opdyke's general time-series variance formula ([S6, Remark 3.1, p. 853][lw]). This review does not adopt stationarity and ergodicity alone as sufficient for the implemented SE. The covariance derivation below displays the omitted terms directly. Opdyke's original article was not independently inspected here; the disagreement is reported with that limit.

Required assumptions for the proposed inference include a stable estimand, positive population variance, a CLT for the joint moment process, and a consistently estimable long-run covariance. Finite fourth moments are necessary for the usual squared-return variance; time-series CLTs/HAC consistency additionally require appropriate moment and dependence conditions. Merely finding finite sample kurtosis does not establish population moment existence. Serial uncorrelatedness is weaker than independence; stationarity is weaker than short memory. Structural breaks, near-unit roots, persistent long memory, and rare-event-dominated short samples can defeat the approximation.

For session NIFTY strategy returns, date order, no-trade sessions, costs, expiry effects, gaps, and any changes in strategy/risk policy must be declared. These are possible modeling concerns, not observed properties of a series in this review. A split boundary with flat positions does not eliminate statistical dependence or a change in return distribution. Pooled DEVELOPMENT/VALIDATION inference needs its own stationarity justification.

### 4.2 Lo-style time aggregation

For covariance-stationary excess returns with autocovariance `gamma_k`, define an **additive** `q`-session return. Directly expanding its variance gives

\[
\operatorname{Var}\left(\sum_{t=1}^{q}x_t\right)
=q\gamma_0+2\sum_{k=1}^{q-1}(q-k)\gamma_k,
\quad D_q=1+2\sum_{k=1}^{q-1}(1-k/q)\rho_k,
\quad S_q=\frac{\sqrt q\,S}{\sqrt{D_q}}.
\]

This is Lo's Eq. (19)-(20); his aggregation Eq. (16) explicitly ignores compounding ([S3, pp. 40-41][lo]). Additive fixed-capital P&L matches this target. Compound wealth, changing capital, or a varying risk-free convention requires a different definition. IID implies `D_q=1`; zero autocovariances suffice for this variance scaling, even if returns are not IID.

**Implications for this evaluator:** Lo scaling changes the horizon of the reported ratio, not automatically its SE. Estimating `S_q` adds uncertainty from estimated autocorrelations; multiplying an IID SE by an estimated scaling factor ignores that uncertainty. Estimating all 251 lags for `q=252` is unavailable when `T<=251` and can be very noisy even when technically possible. If lags above a cap `K` are set to zero, label the result `truncated_acf_scaling_scenario(q,K)`, not the full Lo estimate. Report every declared horizon/cap; do not select the favorable one. Nonpositive or nonfinite aggregate variance is undefined, not a large Sharpe.

### 4.3 HAC/Newey-West uncertainty for original-frequency Sharpe

Here is an explicit proposed one-series delta-method contract, derived for this review from the moment approach in [S3, Appendix A, Eq. (A5)-(A9), pp. 46-47][lo] and [S6, §3, Eq. (1)-(5), pp. 851-852][lw]. Let

\[
z_t=(x_t,x_t^2)',\quad\theta=(\mu,m_2)',\quad m_2=E[x_t^2],\quad
v=m_2-\mu^2>0,\quad g(\theta)=\mu/\sqrt v,
\quad d=\nabla g=(m_2/v^{3/2},-\mu/(2v^{3/2}))'.
\]

With `Gamma_k = Cov(z_t,z_(t-k))`,

\[
\Omega=\Gamma_0+\sum_{k=1}^{\infty}(\Gamma_k+\Gamma_k'),\quad
\sqrt T\{g(\widehat\theta)-S\}\Rightarrow N(0,d'\Omega d).
\]

Use globally centered `u_t = z_t - mean(z)` and the **fixed denominator `T`**:

\[
\widehat\Gamma_k=T^{-1}\sum_{t=k+1}^{T}u_tu_{t-k}',\quad
\widehat\Omega_L=\widehat\Gamma_0+
\sum_{k=1}^{L}\left(1-\frac{k}{L+1}\right)
(\widehat\Gamma_k+\widehat\Gamma_k').
\]

These Bartlett weights and the positive-semidefinite construction follow [S4, Eq. (3)-(5), Theorem 1, pp. 704-705][nw]. Positive semidefiniteness is an algebraic property, not proof of accurate coverage. The original consistency theorem requires additional moment/mixing conditions and growing, controlled bandwidth (Theorem 2, p. 705); choosing `L` remains substantive.

To match the current point estimate's sample SD, set `c_T=sqrt((T-1)/T)`, so `S_hat=c_T g(theta_hat)`, and propose

\[
SE_{HAC,L}=c_T\sqrt{\widehat d'\widehat\Omega_L\widehat d/T}.
\]

This is an explicitly chosen finite-sample convention, not an exact finite-sample correction. It does **not** equal the legacy PSR formula for every finite sample when `L=0`. Do not copy the four-moment Sharpe-difference correction `T/(T-4)` in S6 into this two-moment, one-series calculation. Record any extra multiplier; the proposal uses 1.

An equivalent scalar check uses the influence quantity

\[
\psi_t=\frac{x_t-\mu}{\sqrt v}
-\frac{\mu}{2v^{3/2}}\{(x_t-\mu)^2-v\}.
\]

Apply the same HAC formula to estimated `psi_t`, then multiply the SE by `c_T`. Under IID sampling its population variance is `1-gamma_3 S+(gamma_4-1)S^2/4`; under dependence its long-run variance adds all lag covariances of `psi`. This explains why adjusting only the mean's SE, using a return-ACF “effective T,” or inspecting only raw return ACF is insufficient in general. Conditional heteroskedasticity can enter through squared-return dependence. When `mu=0` some variance-component effects vanish at first order, which is not a general exemption.

Use a stable centered calculation of `v`; avoid cancellation in a naive `mean(x^2)-mean(x)^2`. A singular moment covariance need not fail: no inverse is needed. A nonpositive projected variance cannot support a normal SE/interval and must be reported as such. For a later interval, `S_hat +/- z_(1-alpha/2) SE_HAC` requires a preregistered confidence level and adequacy rule; neither is selected here.

**No automatic DSR-HAC hybrid.** Replacing `e_i` by `SE_HAC` while retaining an estimated `h(N)` does not establish selection-adjusted calibration. Even before accounting for selection, the variance of a difference contains `Var(S_hat_i)+Var(h)-2Cov(S_hat_i,h)`. Both statistics share observations and search choices. A fixed-candidate HAC interval also ceases to be a selection-adjusted interval when one picks the largest result among many reported candidates.

## 5. Implementable choices and their failure modes

| Method / question | Required data and assumptions | Small-sample / failure behavior | Reproducibility and validation |
|---|---|---|---|
| Raw `M` plus declared `N` scenarios: sensitivity of the existing selection hurdle | Complete accounting for the declared family, its original-frequency Sharpe dispersion, and per-trial moments. These summaries suffice for DSR arithmetic; aligned raw returns are needed to establish their comparability, not to evaluate the formula itself. | Small-`M` approximation error; dispersion collapse; omitted searches; heterogeneous score distributions. Raw `M` is not a universal worst case. | Fixed integer grid, special `N=1`, fixed dispersion; check independent-normal integration and duplicate/negative-correlation cases. |
| Average-correlation `N`: possible trial redundancy | Common `T x M` return matrix, defined correlation target, frozen family, treatment of constants/missingness, and a justified model connecting it to score maxima. | Small `T`, unstable correlations, clustering, negative correlation, adaptive search. Singular matrix alone is not undefined average correlation. | Pin centering, window, estimator and any shrinkage. Record matrix/hash, eigen diagnostics, `rho_bar`, raw real-valued `N`, and unsupported cases. Never silently repair/clamp. |
| Lo scaling: additive horizon conversion | One ordered series; stable second moments; chosen `q`; all `q-1` covariance lags or an explicitly truncated scenario. | Noisy long lags, missing sessions, policy/regime shifts. A computable adjustment is not a confidence statement. | Fix ACF denominator and horizon/cap; compare with direct population AR(1) covariance sums. |
| Bartlett HAC: uncertainty of session Sharpe | One ordered series; stable positive variance; moment-process CLT and consistently estimable long-run covariance; lag rule/kernel fixed in advance. | Bias/variance tradeoff in bandwidth; heavy tails, short samples, persistent dependence, and nonstationarity; intervals may under-cover. | No random seed needed. Pin summation and finite-sample conventions; compare matrix/scalar implementations and simulate coverage. |
| Moving block bootstrap: distributional uncertainty | Ordered observations, fixed block length `ell`, stationary short-memory regime, valid statistic on resamples. | `ell=1` erases dependence; long blocks leave few effectively distinct pieces. Ordinary moving blocks underrepresent boundary observations; short/constant resamples can make Sharpe undefined. | Freeze overlapping-start rule, truncation, `B`, RNG/version, seed, quantile/interval method, and invalid-replicate policy. Check explicit index fixtures and coverage across lengths. |
| Stationary bootstrap: distributional uncertainty | Same substantive requirements; geometric lengths with `p=1/ell`, circular continuation. | Random block lengths do not establish stationarity of the original data; circular wrap creates an artificial end/start adjacency. Long memory and breaks remain problematic. | Fix start/continuation algorithm, `p`, `B`, RNG/seed and index hashes. Report block-length sensitivity and Monte Carlo error separately from data uncertainty. |

For moving blocks, the resampling population is the overlapping vectors `(x_j,...,x_(j+ell-1))`, `j=1,...,T-ell+1`; concatenate sampled blocks and truncate to `T`. For the stationary method, start uniformly at an observed index; continue to the next circular index with probability `1-p`, otherwise restart uniformly. Thus `P(length=k)=p(1-p)^(k-1)`. These constructions and their distinction are inspected in [S5, §§1-2, pp. 1303-1305][sb]. Its consistency results concern stationary weakly dependent processes with conditions on block growth and moments; resampling is not assumption-free.

For **joint** trial statistics, resample the same session-index blocks across all columns. Independently resampling each trial destroys contemporaneous cross-trial dependence. If the target is performance of an entire selection/refit procedure, that procedure must be replayed inside each appropriately designed resample. Resampling an already selected winner estimates a different, conditional quantity. A null test also needs a specified null construction; the fraction of ordinary bootstrap Sharpes above zero is not automatically a calibrated test or DSR.

Ledoit-Wolf's Sharpe-specific proposal is a **studentized circular block bootstrap for a difference of two Sharpes**, not a proof that any percentile interval or geometric-block bootstrap is calibrated for a selected winner ([S6, §3.2.2, pp. 854-855][lw]). Its reported simulations motivate a later cross-check; they do not guarantee performance on this application's sample. Studentization requires a replicate-specific SE and a specified covariance estimator. Bootstrap percentile, basic, and studentized intervals must not be interchanged under one output name.

Report `T/ell` as an indicative number of blocks, not a universal effective sample size. With `B` conditionally independent replications, an estimated bootstrap tail fraction has Monte Carlo SE approximately `sqrt(p_hat(1-p_hat)/B)`; this measures simulation noise only. More replications cannot recover absent sessions. Quantile error needs an order-statistic/binomial quantile interval or another predeclared calculation; the tail-fraction formula is not a quantile SE.

## 6. DSR, PBO, walk-forward, and a sealed holdout

| Procedure | Question it can address | What it cannot establish / additional requirements |
|---|---|---|
| PSR / DSR | Relative to a fixed hurdle, PSR gives a moment-based normal-approximation score. DSR substitutes a model-based expected-maximum hurdle for an assumed search family. | Neither verifies execution assumptions, a complete search, serial-independence assumptions, future regime stability, or a posterior probability of profit. A HAC SE alone does not repair selection. |
| PBO via CSCV | For the supplied family and partition/selection protocol, how often does the IS-selected trial rank poorly against the same family OOS? | It is a relative-rank selection diagnostic, not absolute return/utility, a chronological forecast, or a substitute for an unseen final test. It cannot reconstruct an unrecorded adaptive search or refit a strategy merely by slicing already computed returns. |
| Chronological walk-forward | How did a frozen selection/refit/update rule perform on successive later periods using only information available at each origin? | Tuning the rule after inspecting folds creates another search. Overlapping training windows and test horizons do not yield independent replications. Freeze fit length, origins, refit schedule, costs, and state/warm-up boundaries. |
| One-shot sealed holdout | How does one already frozen candidate/protocol evaluate on the reserved period after selection is finished? | One period gives limited precision and regime coverage. It does not validate the simulator, recover earlier leakage, or allow repeated candidate testing. Its sampling uncertainty can itself require a dependence method. |

Algorithm 2.3 uses a synchronous performance matrix, even equal row partitions, all half-partition combinations, IS selection and complementary OOS rank/logit. With `r_c` an increasing OOS rank, `omega_c=r_c/(M+1)` and `lambda_c=log(omega_c/(1-omega_c))`; the paper's Eq. (2.5) integrates the empirical logit distribution through zero. Sections 4-5 discuss block/trial granularity, temporal structure, model validity, and completeness. [S7, pp. 11-13 and 21-24][pbo].

**Specific code qualifications, derived from §2:**

- The code allows even block counts 4 through 12 and only requires `T >= 2*blocks`, potentially as few as 8 sessions. This is an arithmetic guard, not statistical adequacy. `C(12,6)=924` overlapping combinations do not create 924 independent histories. Do not attach an IID binomial CI using this combination count, including by copying the paper's heuristic precision discussion on p. 22.
- Blocks use `floor(b*T/blocks)` boundaries. For `T=10`, four blocks have lengths `(2,3,2,3)`; some half-block splits contain 4 versus 6 sessions. This differs from literal equal-size CSCV. Require divisibility or preregister a documented unequal-block variant and expose actual IS/OOS sizes; do not silently drop sessions to achieve divisibility.
- IS ties choose the first trial in sorted `trial_id` order; OOS ties receive midranks; only `lambda<0` is counted. For identical columns every logit is zero and current PBO is zero. That says the strict-below event never occurred, not that selection has predictive content. The paper's printed through-zero probability and strict-below implementation differ when mass exists at zero. Preserve and report negative/zero/positive counts and tie conventions before deciding on a future definition.
- “Chronological blocks” describes contiguous source slices. A training subset can contain dates later than its testing subset. Preserving order within concatenated slices does not make the protocol forward-only. A common permutation can also leave marginal Sharpe/DSR unchanged while changing serial diagnostics.
- Selecting the most favorable PBO block count, DSR scenario, HAC lag, bootstrap length, or walk-forward layout is itself another search choice. The report must show the full preregistered set.

Tashman's rolling-origin framework distinguishes advancing origins, parameter recalibration, and fixed versus expanding windows ([S8, §§3.2, 4.2, 4.4, pp. 439-442][wf]). The proposed project rule is to choose/fit on past data, evaluate the next declared session segment once, then update only on schedule. A single aggregate of existing DEVELOPMENT/VALIDATION returns is not evidence that this procedure occurred.

**Purging/embargo:** no bar-level label span is defined by this evaluator. Do not import an arbitrary purge/embargo duration. If a future design uses overlapping outcome labels, future-derived features, or cross-fold positions, first record each observation's information/label interval and fit/state boundary. Then remove actual information overlaps and justify any gap from those intervals. This is a design requirement conditional on a specified learning problem, not a feature claimed to exist now.

**One-shot custody proposal (not implemented):** place holdout bytes in a store unreadable by the ordinary research/evaluator account, with separate permission/key custody. Hash and freeze candidate, inputs, methodology, cost assumptions, report fields, and analysis code before release. An independent custodian authorizes one immutable request; atomically reserve/consume it, and log every execution attempt and any produced/viewed performance output against a trusted audit anchor. A crash after partial output consumes the exposure; retry is allowed only under a predeclared identical-request/no-output failure rule with evidence. Renaming a request, deleting a local log, or copying files cannot reset exposure. Release one predetermined report, with no parameter selection after viewing. These are recommended controls, not guarantees provided by the current hash chain or ordinary run directory.

## 7. A small preregisterable next increment

Register a **diagnostic-only sidecar specification** before new affected outcomes are inspected. Previously viewed developmental results must be disclosed as such; a later timestamp cannot retrospectively preregister them.

1. Freeze the session-return definition, declared campaign/family, authorized DEVELOPMENT/VALIDATION dates, gap policy and immutable input hashes. Supply only approved evaluation rows to the sidecar; it should not require access to a directory containing holdout results. Export/access controls would be a separate implementation.
2. Preserve the legacy `S_hat`, raw-`M` DSR, and IID `sqrt(252)` values for comparability. If adding the integer scenario grid from §3, reuse its fixed dispersion and legacy SE. Keep all scenarios visibly separate from the session-dependence outputs.
3. Compute original-frequency HAC SE by §4.3, with Bartlett kernel, no prewhitening, no extra degrees-of-freedom multiplier, and sample-SD point-estimate scaling `c_T`. Add ACFs of `x`, `(x-mean(x))^2`, and `psi` over a frozen lag range. ACFs describe evidence; no ACF significance screen should switch IID/HAC modes.
4. **Concrete candidate for synthetic qualification:** let `L0(T)=floor(T^(1/5))`; report the deduplicated grid `{0, max(1,floor(L0/2)), L0, 2*L0}`. Label `L=0` a no-lag covariance comparator, not a dependence correction. This is this review's transparent candidate rule, not a claimed optimal Newey-West rule for NIFTY. Each positive grid rule grows more slowly than `T^(1/4)`, consistent with the bandwidth-rate restriction in S4's original theorem, but this alone does not satisfy its other conditions or guarantee adequate finite-sample coverage. Unsupported `L>=T` is unavailable, not silently clipped.
5. For descriptive scaling, preregister additive horizons `q in {5,20}` with the full required lags, returning unavailable when they cannot be computed. Keep the existing IID 252 multiplier explicitly unadjusted. A 252-horizon dependence estimate, an ACF truncation scenario, confidence level/interval, or different lag rule requires an explicit protocol choice before use. No confidence level or strategy acceptance threshold is selected by this memo.
6. Qualify the candidate implementation on the fixed synthetic plan in §9. Review coverage and failure behavior before choosing a real-data adequacy rule. A later stationary/circular/moving-block comparison can be preregistered as a cross-check; it is not required to expand this first code increment into an unvalidated “DSR correction.”

The first increment reports estimation sensitivity and limitations. Its scope should exclude fitted `N`, a joint selection-adjusted inference claim, changed PBO event definitions, and performance gating. Any such extension requires a separate methods decision and independent reviewer. No evidence here establishes that enough authorized real sessions are available.

## 8. Proposed input/output contract for a later code task

Names are proposed, not existing Java/CLI fields. A strict JSON manifest plus a canonical long-form CSV can represent the data without serializing a large matrix into JSON. Use UTF-8, LF, defined numeric formatting, stable ordering and hashes. Hash the exact manifest and data bytes; distinguish absence from zero. An unresolved required choice prevents an inferential run rather than triggering a hidden default.

### 8.1 Preregistration / input manifest

| Group | Required fields and validation |
|---|---|
| Protocol identity | `schema_version`, `protocol_id`, `registered_at_utc`, `analysis_code_commit`, `estimator_version`, `source_memo_sha256`, `protocol_sha256`, `prior_outcome_exposure_disclosure_ref`. A future claim of preregistration requires a trusted timestamp/hash anchor; a local clock alone is insufficient. |
| Campaign and family | `search_campaign_id`, `family_definition`, `selection_rule`, `trial_ids_sorted`, `registration_ledger_sha256`, `dataset_sha256`, `split_plan_sha256`, `run_index_sha256`, `trial_to_run_bindings`, `registered_family_count`, `submitted_series_count`. Identity hashes do not by themselves certify complete search accounting. |
| Exposure provenance | `outcome_ledger_sha256` nullable, `outcome_ledger_status`, `registered_count`, `attempt_count`, `produced_attempt_count`, `viewed_attempt_count`, `mechanical_failure_no_outcome_count`, `abandoned_attempt_count`, `unlogged_history_status`, `retry_equivalence_rule`, `partial_outcome_policy`. Counts are scoped to an explicit campaign and distinguish events/attempts/trials. |
| Return basis | `frequency="session"`, `return_basis="net_pnl/fixed_capital"`, positive decimal `analysis_capital_inr`, `risk_free_mode`, `risk_free_series_sha256` if applicable, `currency`, `cost_model_sha256`, `capital_policy_sha256`, `aggregation="additive"`, `iid_periods_per_year=252`. Reject mixtures of fixed-capital and compounded returns. |
| Session axis | `included_partitions`, `session_calendar_id`, `session_index_sha256`, `first_session`, `last_session`, `T`, `gap_policy`, `zero_activity_policy`, `partition_join_policy`, `regime_pooling_rationale`. Exact common dates; no duplicate/missing trial-session pairs. Market closures are not synthetic zero-return days; a no-trade eligible session is a genuine zero net return only when its accounting confirms that. |
| Moment/numeric conventions | `sd_ddof=1`, `central_moment_denominator="T"`, `kurtosis="raw"`, `risk_free_subtraction_order`, `stable_variance_algorithm`, `numeric_tolerance_policy`, `near_zero_variance_policy`, `summation_order`, `numeric_runtime_version`. Nonfinite input fails. Tolerances address arithmetic, not performance. |
| Trial-count scenarios | `N_scenarios` exact sorted integer list, `N1_hurdle=0`, `dispersion_basis="observed_sample_sd_trial_sharpes"`, `dispersion_held_fixed=true`, `expected_max_method_version`. Optional future correlation fields: `correlation_target`, `window`, `estimator`, `missingness_policy`, `shrinkage_policy`, `real_N_handling`; all disabled in the first increment. |
| Session dependence | `hac_kernel="Bartlett"`, `hac_bandwidth_rule`, `hac_lags_resolved`, `hac_gamma_denominator="T"`, `hac_extra_multiplier=1`, `prewhitening=false`, `sharpe_scaling="c_T"`, `acf_lags`, `acf_denominator="T"`, `aggregation_horizons`, `aggregation_lag_caps`, `aggregation_tail_policy`. Record the rule and resolved values. |
| Adequacy and intervals | `min_sessions_policy`, `lag_to_sample_policy`, `assumption_review_ref`, `interval_method` nullable, `confidence_level` nullable, `interval_sidedness`, `unsupported_case_policy`. Resolve adequacy before inferential use; a computational minimum is not a statistical minimum. `performance_pass_threshold` must be absent in this task. |
| Optional bootstrap | `scheme`, `block_length_rule`, `block_lengths_resolved`, `stationary_restart_probability`, `B`, `rng_algorithm`, `rng_version`, `master_seed`, `replicate_seed_derivation`, `resample_scope`, `joint_column_indices=true`, `wrap_policy`, `truncate_policy`, `interval_method`, `studentization_method`, `quantile_rule`, `invalid_replicate_policy`. Disabled unless fully specified. Proposed sensitivity around a separately chosen base `ell0`: `{1,max(1,floor(ell0/2)),ell0,2*ell0}`, with 1 labelled IID comparator; do not choose the best result. |
| Separate validation protocols | `cscv_block_count`, `cscv_size_policy`, `is_tie_rule`, `oos_tie_rule`, `median_event_rule`; walk-forward `fit_window`, `origins`, `test_horizon`, `refit_schedule`, `state_reset_rule`, `label_span_policy`; holdout `custody_protocol_id`, `frozen_candidate_sha256`, `release_request_id`, `consumption_policy`. These are distinct protocols, not one combined confidence score. |

Canonical return rows:

```text
trial_id,run_id,session_ordinal,session_date,partition,net_pnl_inr,analysis_capital_inr,risk_free_session_return,session_complete
```

Rows must be ordered by trial ID then session ordinal. Dates and ordinals must map to one declared exchange-session axis. The reader computes `x_it`; it does not trust independently supplied return and P&L columns that can disagree. Allow only DEVELOPMENT/VALIDATION rows. A missing trial outcome is never imputed as zero. Calendar gaps that are not exchange closures require the frozen gap policy; do not compress them into adjacent observations unnoticed.

### 8.2 Outputs

| Level | Fields / interpretation |
|---|---|
| Report | `schema_version`, `protocol_sha256`, all input/code hashes, `created_at_utc`, `deterministic_payload_sha256`, `status`, `failure_codes[]`, `limitations[]`, `T`, `M`, session range/index hash, exposure summaries. Keep wall-clock metadata outside the deterministic payload hash. |
| Legacy per trial | `trial_id`, `mean_session_excess_return`, `sample_sd`, `sharpe_session`, `sharpe_iid_annualized`, `skewness`, `raw_kurtosis`, `legacy_dsr_raw_M`. Retain the original method labels and flags. |
| Trial-count scenario rows | `N_assumed`, `expected_max_hurdle_session`, `dispersion_used`, `legacy_se_used`, `dsr_scenario`, `status`. `trial_dependence_estimated=false`; `universal_upper_bound_claim=false`. A scenario table is not an uncertainty interval. |
| Session dependence rows | `method`, `L`, `lag_rule`, `omega_11`, `omega_12`, `omega_22`, `projected_long_run_variance`, `se_sharpe_session`, `iid_comparator_se`, `acf_return[]`, `acf_squared_centered_return[]`, `acf_influence[]`, valid pair counts, `status`, diagnostics. Keep method-specific adjustment flags; never change the legacy DSR flag because a separate HAC diagnostic exists. |
| Horizon rows | `q`, `K_used`, `tail_policy`, `D_q`, `scaling_factor`, `sharpe_additive_q`, `status`, `interval=null` unless separately preregistered. Do not label a truncated result full-lag Lo annualization. |
| Optional bootstrap | `scheme`, `ell`, `p`, `B_requested`, `B_valid`, invalid counts by cause, RNG/seed/version, resampled-index hash, selected quantiles, interval method/level, Monte Carlo diagnostics. No silent removal of invalid replicates or favorable rerun with a new seed. |
| CSCV / walk-forward / holdout | Preserve separate reports. CSCV should expose block boundaries, IS/OOS sizes, total combinations, negative/zero/positive logits, tie counts and winner IDs. Walk-forward needs each origin, fit/test dates and model hashes. This sidecar contains no holdout returns, scores, or outcome-derived diagnostics. |
| Eligibility | `performance_claim_eligible=false`, `selection_adjusted_inference_validated=false`, `holdout_outcomes_accessed=false` for this proposed sidecar's restricted input scope. No pass/fail performance field. |

Proposed statuses distinguish `COMPUTED_DIAGNOSTIC`, `UNDEFINED`, `INSUFFICIENT_HISTORY`, `ASSUMPTIONS_UNSUPPORTED`, `INVALID_INPUT`, and `NOT_REQUESTED`. Reasons include `ZERO_RETURN_VARIANCE`, `NEAR_ZERO_VARIANCE`, `NONFINITE_MOMENT`, `LAG_OUT_OF_RANGE`, `MISSING_SESSION`, `INCOMPLETE_FAMILY`, `NONPOSITIVE_PROJECTED_VARIANCE`, `NONPOSITIVE_AGGREGATE_VARIANCE`, `UNSPECIFIED_GAP_POLICY`, `INVALID_CORRELATION_MATRIX`, `BOOTSTRAP_UNDEFINED_REPLICATE`, and `INTERVAL_NOT_PREREGISTERED`. No software diagnostic can establish stationarity or population moment existence from a finite sample; those assumptions also need a recorded review.

## 9. Exact fixtures and a future qualification plan

These fixtures are mathematical examples with no market interpretation. Small vectors test arithmetic, not inferential adequacy.

| Fixture | Exact input / expected result |
|---|---|
| Independent-normal maximum | Two independent standard normal scores: exact expected maximum `1/sqrt(pi)=0.5641895835477563`; Eq. (1) approximation `0.5197553442805939`. `N=1` must use hurdle zero explicitly. |
| Antithetic scores | `(Z,-Z)` for standard normal `Z`: expected maximum `sqrt(2/pi)=0.7978845608028654`, exceeding the independent two-score maximum at the same marginal variance. |
| Duplicate clusters | Correlation matrix with two diagonal `2 x 2` all-one blocks and zero off-block entries: eigenvalues `(0,0,2,2)`, mean off-diagonal correlation `1/3`, heuristic `N=3`, only two distinct Gaussian scores. Matrix singularity does not invalidate the average. |
| HAC arithmetic | `x=(0,0.01,0,0.01)`, `T=4`: mean `0.005`, population variance `0.000025`, sample-SD Sharpe `sqrt(3)/2`; estimated influence values `(-1,1,-1,1)`. With denominator `T`, influence `Gamma0=1`, `Gamma1=-3/4`. Bartlett `L=0` gives SE `sqrt(3)/4=0.4330127018922193`; `L=1` gives SE `sqrt(3)/8=0.21650635094610965`, with projected long-run variance `1/4`. These are the proposed delta-method convention, not exact sampling SEs. |
| Population Lo scaling | AR(1) autocorrelation `rho_k=phi^k`, `q=2`: multiplier `sqrt(2/(1+phi))`. For `phi=(-0.5,0,0.5)`, expected multipliers `(2,sqrt(2),sqrt(4/3))`. Verify arbitrary `q` with the direct covariance sum. |
| CSCV ties and sizes | Two identical columns `(0,0.01,0,0.01,0,0.01,0,0.01)`, four blocks: six combinations, all OOS logits zero, current strict-negative PBO zero and cross-trial Sharpe dispersion zero. Separately, `T=10`, four blocks yields lengths `(2,3,2,3)` and IS sizes 4, 5, or 6. |
| Moving-block indices | Input IDs `(a,b,c,d,e)`, `ell=2`, one-based sampled starts `(2,4,1)`: concatenation `(b,c,d,e,a,b)`, truncated output `(b,c,d,e,a)`. Apply identical indices to every trial column. |
| Stationary-block indices | Input IDs `(a,b,c,d,e)`, `p=1/2`, initial index 4. Transition uniforms `(0.9,0.8,0.1,0.9)` mean continue, continue, restart, continue; specify restart index 2. Output `(d,e,a,b,c)`, including circular wrap. Define uniform endpoints and RNG draw order before random generation. |
| Undefined / identity checks | Constant returns fail Sharpe; a constant resample is counted invalid; missing session/extra HOLDOUT row fails input. Positive rescaling leaves Sharpe/SE unchanged at zero risk-free return. A common time permutation preserves legacy marginal moments but generally changes ACF/HAC. Negative scaling flips Sharpe and preserves its SE. |

**Performed in this review:** scratch Python 3.13.2 / NumPy 2.5.4 calculations checked the maximum constants, independent-normal quadrature, duplicate-cluster eigenvalues/count, AR(1) scaling values, and HAC matrix-versus-scalar projected variances (1 and 1/4) and resulting SEs. Equations and PBO tie/block conclusions were checked against source code; the Java evaluator was not executed. The bootstrap index examples are explicit hand calculations. No runtime/integration or coverage validation is claimed.

**Proposed future qualification, not performed:** freeze synthetic sample lengths `{64,256,1024}`, original-frequency population Sharpes `{0,0.25}`, and the lag grid in §7. Include IID Gaussian returns; stationary Gaussian AR(1) with coefficients `-0.5,0.5,0.9`; finite-fourth-moment heavy tails; and a persistent two-state volatility process with independent signs, to separate return ACF from variance dependence. Include deliberately unsupported infinite-fourth-moment and deterministic-regime-break examples, plus constants, gaps, rounding-sensitive variance, and sparse nonzero returns. Fully specify innovation distributions, stationary initialization, burn-in, scale, PRNG, seed derivation, replication count, and all analytic targets before running the qualification suite.

For multiplicity, use fixed families with `{2,8,32}` columns, independence, equicorrelation, unequal clusters, duplicates, negative pairs, and a prespecified adaptive-search simulation. Compare oracle and estimated dispersion separately. This isolates the two factors instead of crediting count adjustment for a change in dispersion.

An independent implementation should compare matrix and scalar HAC, analytic cases, SE bias, distribution of studentized errors, interval coverage over a preregistered set of nominal levels, invalid-output rates, and sensitivity to lag/block rules. Freeze numerical tolerances and qualification acceptance rules before those experiments. Coverage levels and Monte Carlo tolerances are methodology choices, not strategy profitability thresholds. Do not choose the implementation by whichever real return series yields the most favorable interval. Archive reproducible seeds/index hashes; parallel execution must not change a replicate's random stream.

## 10. Decisions still requiring review

| Open decision | Needed resolution / owner |
|---|---|
| Search-family scope | Research owner defines the campaign across dataset/split revisions, related strategies, seeds, costs, partial outcomes and retries. An exposure-event count is not automatically an independent-trial count. |
| Trial-dependence estimand | Statistical reviewer chooses whether to study return correlation, Sharpe-error correlation, or the maximum's distribution, and how dispersion is estimated jointly. Eq. (8) alone is insufficient. |
| Bandwidth and adequacy | Independent reviewer qualifies or replaces the candidate `T^(1/5)` lag rule, minimum history, lag/sample constraints, moment assumptions and treatment of regimes. The proposal is not validated on NIFTY sessions. |
| Scaling / interval target | Owner preregisters additive horizons, whether full long-lag annualization is supportable, confidence level/sidedness, and interval method. No pass threshold is chosen here. |
| Finite-sample conventions | Confirm sample-SD scaling, HAC multiplier, numerical tolerances, and how unsupported cases are reported. Resolve rather than conceal differences from legacy PSR at `L=0`. |
| Bootstrap scope | Choose scheme, block rule, replicate budget, null/studentization design, joint columns, and invalid-replicate treatment. Confirm whether the target is a fixed candidate or an entire search/selection procedure. |
| CSCV definition | Decide equal-size requirement, tie/zero-logit event, deterministic tie-breaking, and whether exposing fold diagnostics warrants a versioned report. Any formula change is a separate code task. |
| Forward validation and holdout | Define causal fit/update/state rules; define label spans only if the learning design needs them. Assign independent holdout custody and specify crash/retry/exposure consumption; no control is implemented by this memo. |
| Adoption / independent signoff | Review the methods and synthetic validation plan before implementation. A future claim of dependence-adjusted DSR or post-selection coverage requires a new derivation and validation, not a renamed flag. |

The research deliverable closes the requested critique and contract handoff. Method adoption, implementation, sufficient authorized history, complete exposure evidence, and sealed-holdout enforcement remain unresolved. No performance conclusion follows from this review.

## 11. Primary-source register and access limits

Pinpoints refer to printed page numbers in the linked version; for S1/S2/S7 these are the manuscript's own page numbers. **FT-section** means the full text was accessible and the identified relevant sections were inspected directly, including visual equation checks; it does not claim every page of the paper was read. References support the attributed methods; the repository-specific critiques, counterexamples, schema and proposed controls are this review's analysis.

| ID | Bibliographic identity / stable link | Pinpoints and verified scope | Access status |
|---|---|---|---|
| S1 | Bailey, D. H. and López de Prado, M. (2014), *The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting and Non-Normality*, Journal of Portfolio Management 40(5), 94-107. [Author-hosted manuscript, version 2014-07-31][dsr]; SSRN 2460551. | Eq. (1), p. 7; Eq. (2), pp. 8-9; Appendix A.1, p. 12; Appendix A.3, Eq. (7)-(8), p. 14. Normal score-maximum approximation and the specific effective-count heuristic. | FT-section. Equations viewed in PDF images, including A.3. |
| S2 | Bailey, D. H. and López de Prado, M. (2012), *The Sharpe Ratio Efficient Frontier*, Journal of Risk 15(2), 3-44. [Author-hosted manuscript, July 2012][psr]; SSRN 1821643. | Introduction p. 4; §§2.3-2.4, pp. 6-8; Eq. (8) and §2.5, p. 8; §3, Eq. (11), p. 9. Non-normal moment SE, PSR and its stationarity claim. | FT-section. Live PDF page numbering checked locally; claim about Opdyke is reported critically, not adopted. |
| S3 | Lo, A. W. (2002), *The Statistics of Sharpe Ratios*, Financial Analysts Journal 58(4), 36-52. [Academic-hosted full text][lo]. | Non-IID discussion pp. 39-40; Eq. (16), (19)-(20), p. 40; Appendix A, Eq. (A5)-(A9), pp. 46-47. Additive scaling and moment-based non-IID inference. | FT-section. Formula pages inspected visually. |
| S4 | Newey, W. K. and West, K. D. (1987), *A Simple, Positive Semi-Definite, Heteroskedasticity and Autocorrelation Consistent Covariance Matrix*, Econometrica 55(3), 703-708. DOI [10.2307/1913610](https://doi.org/10.2307/1913610); [academic-hosted journal text][nw]. | Eq. (3)-(5), Theorems 1-2, pp. 704-705. Bartlett covariance construction, PSD and consistency conditions. | FT-section, scanned pages visually read. NBER record/PDF returned 403; conclusions use the accessible journal text, not the NBER abstract. |
| S5 | Politis, D. N. and Romano, J. P. (1994), *The Stationary Bootstrap*, JASA 89(428), 1303-1313. DOI [10.1080/01621459.1994.10476870](https://doi.org/10.1080/01621459.1994.10476870); [academic-hosted journal text][sb]. | §§1-2, pp. 1303-1305; §3, pp. 1305-1306. Fixed moving-block comparison, geometric lengths/circular continuation and stationary-bootstrap assumptions. | FT-section, scanned pages visually read. Original stationary-bootstrap paper; no universal Sharpe coverage theorem inferred. |
| S6 | Ledoit, O. and Wolf, M. (2008), *Robust Performance Hypothesis Testing with the Sharpe Ratio*, Journal of Empirical Finance 15, 850-859. DOI [10.1016/j.jempfin.2008.03.002](https://doi.org/10.1016/j.jempfin.2008.03.002); [author's university-hosted journal text][lw]. | §3, Eq. (1)-(5), pp. 851-852; Remark 3.1, p. 853; §3.2.2, pp. 854-855. Joint moment inference, critique of general-stationary IID variance, and studentized circular resampling. | FT-section. Remark 3.1 inspected visually. Repository mirror ZORA was access-denied; university publication link supplied accessible full text. |
| S7 | Bailey, D. H., Borwein, J. M., López de Prado, M. and Zhu, Q. J. (2015 revised), *The Probability of Backtest Overfitting*. [Author-hosted manuscript, 2015-02-27][pbo]. | Algorithm 2.3, pp. 11-12; Eq. (2.5), p. 13; §4, pp. 21-22; §§5.1-5.2, pp. 23-24. CSCV construction and design/application limitations. | FT-section. Rank event and limitations viewed in PDF images. |
| S8 | Tashman, L. J. (2000), *Out-of-Sample Tests of Forecasting Accuracy: An Analysis and Review*, International Journal of Forecasting 16(4), 437-450. DOI [10.1016/S0169-2070(00)00065-0](https://doi.org/10.1016/S0169-2070(00)00065-0); [full text from author's profile][wf]. | §2, p. 438; §3.2, p. 439; §§4.2-4.4, pp. 440-442. Origin, refitting/window choices and test contamination. | FT-section. Relevant full-text sections read; no trading-strategy theorem claimed. |
| S9 | Künsch, H. R. (1989), *The Jackknife and the Bootstrap for General Stationary Observations*, Annals of Statistics 17(3), 1217-1241. DOI [10.1214/aos/1176347265](https://doi.org/10.1214/aos/1176347265). | Bibliographic attribution only, cross-checked in S5/S6 references. | **Metadata/reference only:** Project Euclid full/PDF endpoints did not expose readable text in this session. Moving-block mechanics here are verified in S5's primary-text comparison; Künsch's original proofs were not reviewed. |

Opdyke (2007), *Comparing Sharpe Ratios: So Where Are the p-Values?*, Journal of Asset Management 8(5), 308-336, was identified through S2/S6 references only. No independent full-text verification or resolution of its claimed theorem is asserted. No secondary blog or software implementation supplies the statistical conclusions. No copyrighted paper is bundled with the deliverable.

[dsr]: https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf
[psr]: https://www.davidhbailey.com/dhbpapers/sharpe-frontier.pdf
[lo]: https://traders.berkeley.edu/papers/The-Statistics-of-Sharpe-Ratios.pdf
[nw]: https://users.ssc.wisc.edu/~behansen/718/NeweyWest1987.pdf
[sb]: https://users.ssc.wisc.edu/~behansen/718/Politis%20Romano.pdf
[lw]: https://www.econ.uzh.ch/dam/jcr:ffffffff-935a-b0d6-0000-00007214c2bc/jef_2008pdf.pdf
[pbo]: https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf
[wf]: https://www.researchgate.net/profile/Len-Tashman/publication/223319987_Out-of-sample_tests_of_forecasting_accuracy_An_analysis_and_review/links/5745ceec08ae9f741b430de3/Out-of-sample-tests-of-forecasting-accuracy-An-analysis-and-review.pdf
