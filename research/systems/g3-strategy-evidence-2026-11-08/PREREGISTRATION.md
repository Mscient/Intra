# Registration, trial accounting, splits and methods

No new market trial registration, outcome, validation access, holdout freeze,
authorization or release occurred. This packet is an inventory, not a
preregistration for a strategy experiment. Local dates, file/Git hashes and hosted
publication cannot retrospectively prove pre-outcome custody.

## Exact available identities

[source-identities.csv](source-identities.csv) records exact documentary input
bytes and source revisions; copied card and companion hashes are also listed in
[SHA256.csv](SHA256.csv). The three card JSON files are retained without filling
their null approval or freeze fields. Their cost/variant/source companion hashes
must still match before review; snapshots do not create new versions/approvals.

| Required MM/RV trial binding | Current value |
| --- | --- |
| Owner-approved G1 card ID/version/hash and review | ABSENT for both slots |
| Strategy source revision and exact tested binary | ABSENT for candidates; software CI build identity is recorded separately in VALIDATION.md |
| Strategy parameters, features, variants/exclusions | UNFROZEN; no choice made |
| Instrument/contract universe, master, calendar/rules | UNFROZEN; no nominated authorized corpus |
| Dataset identity, rights, quality and source commitments | ABSENT; documentary synthetic quality hashes are not market datasets |
| Exact development/validation/holdout periods and split hash | ABSENT; no boundary chosen |
| Fill/latency/impact identity and calibration/sensitivities | ABSENT |
| Effective cost/settlement/funding/fixed/capital identities | ABSENT; G1-COST/v1 is a planning register with blank actual amounts |
| Risk capital, budgets, limits, margin source/config hash | ABSENT; private owner precommitment required |
| Benchmark, primary metric, inference, rejection/sensitivity rules | ABSENT for MM/RV / PENDING_METHOD_APPROVAL |
| Trial ledger/catalog/outcome chain and campaign family | No actual candidate family supplied; hashes null |
| Independent timestamp/hash anchor and search/exposure audit | UNVERIFIED / UNKNOWN |
| Holdout store/head/key/review/release/result identity | ABSENT; no freeze/authorization/evaluation attempted |

Nulls in [decision.json](decision.json) mean absent evidence, never a wildcard,
zero, accepted default or permission to run. The example catalog has two placeholder
rows, synthetic hashes and illustrative capital; its split is an example file.
Neither is a real registration or approved date boundary.

## Trial-family accounting, scoped honestly

| Population | Evidenced count / status |
| --- | --- |
| Requested candidate slots | 2 (task scope only) |
| Owner-approved MM/RV cards found in inspected evidence | 0 |
| Authored local signal cards / declared primary baseline variants | 3 / 3, WAVEA3_V1; unapproved |
| Declared Wave A confirmatory comparisons | 6: three rules × validation and final holdout |
| Declared Wave A secondary horizon outputs | 6: three rules × 5/30 minutes; descriptive |
| Declared bootstrap block-length settings / planned cost scenarios | 3 (2/5/10 sessions) / 4 (K0–K3), documentary, no market trials here |
| Actual G3 registrations/outcomes supplied | None; historical campaign count UNKNOWN, not asserted zero |
| Market runs/retries/generated/viewed outcomes in this session | 0 / 0 / 0 / 0 |
| Software qualification attempts | See VALIDATION.md; synthetic, separate from strategy-search accounting |
| Historical failures/retries/parameters/features/universe exclusions/views | UNKNOWN beyond the bound variant ledger and earlier smoke/gap disclosures |

No real trial ledger file was supplied in the inspected tracked evidence. Ledger
implementations and test-generated ledgers do not prove absence of private or
unlogged experiments. A campaign may span dataset/split revisions, seeds,
features/costs/exclusions and partial outcomes; the evaluator's complete-family
check covers only the submitted dataset/split family. Owner/reviewer must reconcile
the wider search before selection. Do not count retries as independent hypotheses
automatically, or delete unsuccessful registrations to satisfy complete coverage.

## Frozen methods that already exist

The main [portfolio evaluator](../../../Intra-Paper-System/docs/PORTFOLIO-TRIAL-EVALUATION.md)
uses sample-SD session Sharpe, descriptive sqrt(252) annualization, zero risk-free
return, raw skewness/kurtosis and unchanged raw-M DSR normal approximation.
N=1 and N=M are assumed fixed-dispersion sensitivity endpoints, not estimated
effective trial counts or universal bounds. R14 identifies negative-dependence,
dispersion, omitted-search and finite-sample limitations. No expanded R14-proposed
interior grid or fitted correlation correction is silently adopted here.

CSCV/PBO uses even 4–12 near-equal chronological blocks and every half/complement
combination, sorted trial-ID tie breaking IS, OOS midranks and strict-below-median
PBO. Complements can include later dates than selection slices; this is a relative
selection-instability diagnostic, not walk-forward inference or a profitability
probability. No block count is chosen for these absent candidate trials.

The frozen [HAC protocol](../../../Intra-Paper-System/docs/PORTFOLIO-SHARPE-HAC-PROTOCOL.md),
`INTRA_PORTFOLIO_SHARPE_HAC_V1`, preserves the existing point estimate and separately
reports original-session-frequency fixed-candidate SE. It uses Bartlett, lag
denominator T, c_T=sqrt((T-1)/T), multiplier 1, no prewhitening, and the full sorted
grid {0,max(1,floor(L0/2)),L0,2L0}, L0=floor(T^(1/5)). Operational floor T=60;
unavailable/numerically unsupported cases remain incomplete. No interval,
p-value, selection adjustment or profitability probability. Its protocol hash
normalizes CRLF/CR to UTF-8 LF without BOM; that identity is distinct from this
packet's exact-byte hashes. R14 source commit/blob remain those in the protocol.
Synthetic qualification covers only its three frozen processes.

Wave A separately specifies equal-weight complete-session directional index log
markouts, zero for complete no-signal sessions, stationary bootstrap mean block 5,
10,000 replicates, descriptive 2/10 sensitivities and two-sided 99.1667% intervals
for six signal comparisons (Bonferroni 5%). Exact dates/seed and development-only
adequacy review are pending. This signal-only specification does not define an
MM/RV net economic confidence bound or authorize market access.

Candidate net metric/benchmark, interval target/method/level/sidedness/adequacy,
holdout selection/report, capacity/uncertainty acceptance and precise 3× hurdle
are **PENDING_METHOD_APPROVAL**. The final plan's ≥3× round-trip cost and positive
net holdout lower bound remain unmet requirements; illustrative 4bp/12bp or
Nifty cost examples are not adopted. No arbitrary purge/embargo chosen: first
freeze actual feature/label/position overlap intervals and causal fit/state rules,
then document the exact necessary exclusions/gap. Existing flat boundaries do not
prove statistical independence.

## Holdout custody

The base evaluator excludes holdout financial parsing/scoring but ordinary full-
split artifacts may already expose them to an operator. Avoid opening even an
`identity`/full verifier on such artifacts without the appropriate access review.
Development/validation prefixes have distinct dataset identities; do not relabel
consumed holdout as validation or claim a prefix alone proves untouched custody.

The original checkout's one-shot controller design is preserved as a local,
uncommitted documentary snapshot. It binds catalog/code/dataset/split/protocol/
ledger hashes, Ed25519 custodian authorization and separate signed journal heads,
consumes before callback, and forbids rerun after start/failure/interruption.
Cached-result delivery is distinct from reevaluation. It is not integrated here.
Its callback is not a statistical scorer or protected data vault; external access,
keys, code replacement, multiple stores and matching backup rollback require a
canonical independent registry, protected deployment and fresh retained heads.
No actual keys, authorization token, sealed store or prior-access proof was supplied.
Custody and independent preregistration remain **UNVERIFIED**, blindness **UNKNOWN**.
