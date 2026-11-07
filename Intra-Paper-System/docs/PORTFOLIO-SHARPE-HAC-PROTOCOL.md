# Portfolio Sharpe HAC diagnostic protocol

**Protocol ID:** `INTRA_PORTFOLIO_SHARPE_HAC_V1`\
**Frozen:** 2026-11-07, before HAC implementation qualification runs.\
**Status:** engineering diagnostic; independent preregistration **UNVERIFIED** and prior exposure **UNKNOWN**.\
**Decision record:** user-approved protocol choices in the Codex conversation dated 2026-11-07.

This protocol adds a fixed-candidate, original-session-frequency HAC standard error to
the offline portfolio trial evaluator. It does not change its Sharpe point estimate,
DSR, trial-count sensitivity, or PBO formulas. It emits no confidence interval,
p-value, or probability of profitability. Every output remains
`performance_claim_eligible=false`. Synthetic qualification applies only to the
finite fixtures and processes defined below; it is not evidence of general coverage.

## Observation axis and population

One observation is each open exchange session in the validated dataset's hashed
session calendar, in chronological order. The registered split file binds each date
to a partition, and the validated dataset provenance binds the calendar file hash.
The axis fingerprint hashes the ordered `(session_date, partition)` sequence and
all distinct validated calendar hashes. Every registered DEVELOPMENT and
VALIDATION session contributes exactly one portfolio return, including sessions
with no trades. Existing replay evaluation must establish complete session and
instrument coverage; a missing, extra, duplicated, or reordered eligible session
is `INCOMPLETE` for the evaluation and blocks publication of the ordinary score
report. The tool only asserts completeness relative to the registered, hashed
calendar and split supplied to it; it cannot discover omitted or falsely declared
exchange sessions independently.

DEVELOPMENT and VALIDATION may be joined only because the split validator enforces
one continuous chronological partition sequence, and because the same registration,
strategy/configuration, validated dataset, split identity, and explicit starting
capital bind both partitions. This is a within-trial descriptive sampling diagnostic,
not evidence that the strategy or return process is stationary across the join.
Any future partition-specific regime concern requires a separate preregistered
analysis. HOLDOUT financial values are not read into the HAC calculation.

For trial `i`, with explicit fixed capital `C > 0`, session-end net portfolio P&L
`N_t`, and zero baseline before the first included session:

```text
x_t = (N_t - N_(t-1)) / C
```

Returns are simple and non-compounded. The risk-free session return is zero.

## Estimator

For `T` included sessions, use population moments `mu = mean(x)`,
`m2 = mean(x^2)`, `v = mean((x-mu)^2)`. The legacy sample-standard-deviation
Sharpe point estimate is unchanged. For the HAC calculation use
`z_t=(x_t,x_t^2)'`, globally centered `u_t=z_t-mean(z)`,
`d=(m2/v^(3/2), -mu/(2*v^(3/2)))'`,
`Gamma_k=(1/T) sum_(t=k+1..T) u_t u_(t-k)'`, and Bartlett weights
`w_k=1-k/(L+1)`. The long-run covariance is
`Omega_L=Gamma_0+sum_(k=1..L) w_k (Gamma_k+Gamma_k')`, with denominator `T`
for every lag. No prewhitening, covariance inversion, extra multiplier, or
degrees-of-freedom adjustment is used.

```text
c_T = sqrt((T-1)/T)
S_hat = c_T * mu / sqrt(v)
SE_HAC,L = c_T * sqrt((d' Omega_L d) / T)
```

The extra scaling multiplier is exactly `1`. `c_T` is a frozen finite-sample
reporting convention, not an exact correction. The reported HAC standard error is
in original-frequency Sharpe ratio units. It describes fixed-candidate sampling
uncertainty under regularity assumptions; it is neither selection-adjusted nor a
coverage guarantee.

An independent scalar implementation check forms

```text
psi_t = (x_t-mu)/sqrt(v)
        - mu*((x_t-mu)^2-v)/(2*v^(3/2))
```

and applies the same centering, lag denominators, Bartlett weights, and scaling to
`psi`. Matrix and scalar projected long-run variance calculations must agree within
the arithmetic tolerance below.

## Bandwidths and autocorrelation diagnostics

For `L0=floor(T^(1/5))`, evaluate the sorted unique grid
`{0, max(1,floor(L0/2)), L0, 2*L0}`. Do not clip a bandwidth. Any `L >= T`
is unavailable with a reason code. Report the complete grid and availability.
Report raw-return, squared-centered-return, and influence-series ACFs for lags
`1..max(available L)`, with `T-k` pair counts. ACFs use globally centered series
and denominator `sum_t (y_t-mean(y))^2`; zero-variance series have an undefined
ACF and an explicit reason. ACFs are descriptive and never screen or enable HAC.

## Sample and numerical policy

`T >= 60` is an operational floor selected for this implementation, not a theorem
or adequacy guarantee. Below it, report HAC `INCOMPLETE`, reason
`INSUFFICIENT_SESSIONS`, and null HAC estimates while retaining existing evaluation
outputs.

All input returns must be finite. Scale by maximum absolute return, compute means
and centered population variance with fixed-order compensated two-pass sums, and
compute lag products in fixed chronological order with compensated sums. Do not
clamp. Reject zero variance or any normalized population variance at or below
`64 * ulp(1.0)`, as a deterministic near-precision-limit guard. Reject nonfinite,
nonpositive, or `<= 64 * ulp(1.0) * max(1, sum_abs_projected_terms)` projected
long-run variance; report `NUMERIC_PRECISION_LIMIT`. This is an implementation
guard, not a statistical threshold. Reject nonfinite results. The scalar/matrix
cross-check tolerance is `2e-10 * max(1, abs(matrix), abs(scalar))` on normalized
projected long-run variance. No result is selected because it is more favorable.

## Preregistration, provenance, and output

Report independent preregistration status as `UNVERIFIED` and prior exposure as
`UNKNOWN`; local timestamps, Git history, registrations, and outcome ledger events
are not an independent timestamp/hash anchor. If an independently anchored record
is added later, it requires a separate schema/versioned input.

Bind into the deterministic report: protocol ID and exact protocol SHA-256,
estimator version, R14/DR-12 source review commit and source Git blob identifier,
analysis build commit, ordered trial/run/registration identities, outcome-ledger
provenance where supplied, catalog/dataset/split/artifact hashes, explicit capital,
validated session-axis hash, ordered included date/partition/return hash, resolved
bandwidths, and numeric policy. The canonical axis and return hashes use UTF-8
length-prefixed fields, ordered by session date, with decimals stripped of trailing
zeros and written as plain strings. Output has no volatile timestamp. Full artifacts
may be hash-verified, but HOLDOUT numeric values must remain unread. Every HAC result
is separately labeled; `sharpe_serial_dependence_adjusted` remains false for legacy
DSR/Sharpe outputs.

## Frozen synthetic qualification suite

The suite uses only synthetic data and fixed seeds; no project or market run data are
read. Seed expansion uses `java.util.SplittableRandom` and Box-Muller normal pairs in
the listed order. For each process, use 2,000 replications, `T=240`, discard the
first 1,000 generated observations, then retain the next 240. Replications are
independent and in ascending seed index. Replication `r` uses seed
`baseSeed XOR (0x9E3779B97F4A7C15 * (r+1))` with 64-bit wraparound. Report the empirical population standard
deviation of the protocol point estimate and the root-mean-square HAC SE for every
available bandwidth. Qualification is assessed only at the largest predeclared
bandwidth (the other grid points, especially `L=0`, remain reported diagnostic
comparators): the ratio must be in `[0.80, 1.20]` for each of the three processes.
The claim is only that these finite fixed-seed fixtures meet this broad engineering
comparison. A failed process blocks claiming synthetic qualification; no process
or bandwidth may be removed after observing results.

| ID | Process | Parameters and seed |
| --- | --- | --- |
| IID | `x=mu+sigma*Z` | `mu=0.0002`, `sigma=0.01`, seed `0x4949445f48414331` |
| AR1 | `x=mu+e`, `e_t=0.25*e_(t-1)+sigma*sqrt(1-0.25^2)*Z_t` | `mu=0.0002`, `sigma=0.01`, `e_0=0`, seed `0x4152315f48414331` |
| VOL | `x=mu+sigma_t*Z_t`, `h_t=0.000004+0.08*e_(t-1)^2+0.90*h_(t-1)`, `sigma_t=sqrt(h_t)`, `e_t=sigma_t*Z_t` | `mu=0.0002`, `h_0=0.0002`, seed `0x564f4c5f48414331` |

In all processes `Z` is a standard normal generated by Box-Muller from pairs of
uniforms in `(0,1]` produced by `SplittableRandom`; consume a pair per `Z` and
discard an unused partner. The sample-Sharpe point estimate uses the protocol's
`c_T`. Empirical and estimated standard deviations use compensated two-pass
population sums. The synthetic test has a separate fixed equation fixture
`x=(0,0.01,0,0.01)`, checking `S=sqrt(3)/2`, projected LRV `1` at `L=0`, `1/4`
at `L=1`, and SEs `sqrt(3)/4`, `sqrt(3)/8`; this tiny fixture does not qualify the
`T=60` floor.

Additional frozen acceptance checks: matrix/scalar agreement; missing, duplicated,
reordered and malformed session-axis rejection at the integration boundary;
no-trade session inclusion; deterministic bytes across repeated output; unchanged
DSR/PBO values; HOLDOUT sealed-string mutation invariance; null HAC output at `T<60`;
and zero/near-zero variance rejection. These verify implementation properties and
do not extend the Monte Carlo qualification claim.

## Method references and limits

The method proposal was reviewed in R14/DR-12, commit
`263e435f6daf3986267aae1e3d9b3ef45d3e707b`, source blob
`e56d0b9b3f3ccc56004303de871c45e008bc40bd`. Newey-West/Bartlett HAC estimation
and bandwidth selection are substantive modeling choices; this protocol freezes
one choice rather than claiming it is universally optimal. Serial dependence can
change Sharpe inference, but a HAC standard error does not correct trial selection,
nonstationarity, incomplete search history, or holdout reuse.

References: Andrews (1991), *Heteroskedasticity and Autocorrelation Consistent
Covariance Matrix Estimation*, https://personal.utdallas.edu/~d.sul/Econo2/andrews91.pdf;
Lo (2002), *The Statistics of Sharpe Ratios*, https://doi.org/10.2469/faj.v58.n4.2453.
