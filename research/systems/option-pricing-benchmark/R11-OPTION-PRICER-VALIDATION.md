# R11 — Independent validation benchmarks for BSM and Black–76 pricers

Prepared 2026-11-04. Research findings only. No exchange rules and no live-trading recommendations are included; items that could not be established are marked **UNRESOLVED**.

## 0. Scope and limits of this pass

- **`research/systems/NEXT-RESEARCH-REQUESTS.md` (R11) was not read.** The file is not linked from the README or the implementation tracker, and the fetch tool would only open URLs that had appeared in earlier results. The "benchmark gaps listed in R11" are therefore **not reviewed**. What I did read: the repo README and `IMPLEMENTATION-TRACKER.md`, which say the standalone BSM helper is "not independently validated", with no futures-option pricing, no volatility surface and no replay integration.
- **The Java implementation was not inspected.** These fixtures are independent of it by construction; no comparison against it has been run.
- **No third-party pricing library was available offline** (QuantLib and py_vollib were missing, and the sandbox had no network). The reference values are therefore *my own* high-precision implementation of the published closed forms, not an off-the-shelf library. That is independent of the project, but it is one implementer's reading of the formulas. Section 8 lists the cross-check to run on your machine to close this gap.

## 1. Method (what "independent" means here)

1. Prices computed with `mpmath` 1.3.0 at 50 decimal digits from the closed-form formulas in §3. All inputs enter as exact decimals or exact rationals (`30/365` is a rational, not a float).
2. **Analytic Greeks are checked against numerical derivatives of the price function** (`mpmath.diff`). The maximum relative disagreement across all 22 option rows is 2.2e-49. This validates the Greek formulas against the price formulas without relying on any other Greek implementation.
3. Prices cross-checked in float64 with `scipy.stats.norm` (scipy 1.18.1): maximum absolute difference 2.8e-12 (the largest values are about 568, so this is float64 rounding).
4. Put–call parity and no-arbitrage sweeps at 50 digits (§6).
5. Two external anchors, recorded in §2.

Reproduce: `python3 r11_reference.py` regenerates `r11_fixtures.json` and `r11_fixtures.csv`. Needs `mpmath` and `scipy`.

## 2. External anchors and references

| Anchor | Source | What it confirms | Status |
|---|---|---|---|
| F=K=100, T=1, r=0, σ=0.20 → call 7.9656 | `black_76` Rust crate docs, quick-start example (docs.rs/black-76); fetched this session | Matches fixture **F01C = 7.96556746** (price/vol pair, ATM, zero rate) | Confirmed at 4 decimals |
| Black-76 call/put definitions, discounted-forward form | Wikipedia, "Black model" (en.wikipedia.org/wiki/Black_model), fetched via search snippet | Structure `e^{-rT}(F N(d1) − K N(d2))`; underlying replaced by a discounted futures price | Snippet only; formula body was not in the retrieved text |
| Generalized BSM with cost-of-carry b: b=r gives Black–Scholes 1973, b=r−q gives Merton 1973 with yield q, b=0 gives Black 1976 | Haug, *Option Pricing Formulas*, as excerpted in TradingView script descriptions (e.g. `tradingview.com/script/ecJOXGMw-BSM-OPM-1973-w-Continuous-Dividend-Yield-Loxx`) | The b-parameterisation and the BSM-to-Black-76 mapping | **Secondary excerpt only; book page/equation number UNRESOLVED** |
| S=42, K=40, r=10%, σ=20%, T=0.5 → call ≈ 4.76, put ≈ 0.81 | Recalled from Hull, *Options, Futures, and Other Derivatives* | Consistent with fixture B01 (4.75942 and 0.80860) | **Not retrieved this session; edition, chapter and example number UNRESOLVED.** Do not cite it until confirmed in your edition |
| Original papers | Black & Scholes, *J. Political Economy* 81(3), 1973; Merton, *Bell J. Econ. & Mgmt Sci* 4(1), 1973; Black, *J. Financial Economics* 3, 1976 | Primary derivations | Bibliographic references from memory; page-level equation locations UNRESOLVED |

**Open item:** none of the sources above gives a published *Greek* table. Greeks are validated only by the derivative check in §1.2. A published numerical Greek example (Hull or Haug) would be a useful extra anchor.

## 3. Conventions and formulas

| Item | Convention used in these fixtures |
|---|---|
| Time `T` | Year fraction supplied directly. Cases written `n/365` use calendar days ÷ 365 **only as a test convention**; the correct day-count for any real expiry is **UNRESOLVED** |
| Expiry instant | Not modelled: `T` is an input. Time-of-day of expiry and the session calendar are **UNRESOLVED** |
| Rates `r`, yield `q` | Continuously compounded, decimal (0.065 = 6.5%). Rate source and tenor are **UNRESOLVED** |
| Volatility | Annualised lognormal σ, decimal (0.14 = 14 vol points), over the same `T` |
| Delta | ∂V/∂S (BSM) or ∂V/∂F (Black-76), per 1.00 of the underlying's price |
| Gamma | ∂²V/∂S² (or ∂²V/∂F²) |
| Vega | ∂V/∂σ **per 1.00 of σ** (raw). Divide by 100 for per vol point |
| Theta | ∂V/∂t = −∂V/∂T **per year** of calendar time, other inputs held fixed (for Black-76, F held fixed). Divide by 365 for per calendar day |
| Rho | ∂V/∂r **per 1.00 of r** (raw). Divide by 100 for per 1 percentage point |
| Premium | Paid at inception and discounted as in the formulas below. Futures-style premium margining is a different model and is not covered |

**BSM (spot, continuous yield q):**
d1 = [ln(S/K) + (r − q + σ²/2)T] / (σ√T), d2 = d1 − σ√T
C = S e^{−qT} N(d1) − K e^{−rT} N(d2); P = K e^{−rT} N(−d2) − S e^{−qT} N(−d1)
Δ_C = e^{−qT}N(d1), Δ_P = −e^{−qT}N(−d1); Γ = e^{−qT}φ(d1)/(Sσ√T); Vega = S e^{−qT}φ(d1)√T
Θ_C = −S e^{−qT}φ(d1)σ/(2√T) − rK e^{−rT}N(d2) + qS e^{−qT}N(d1)
Θ_P = −S e^{−qT}φ(d1)σ/(2√T) + rK e^{−rT}N(−d2) − qS e^{−qT}N(−d1)
ρ_C = KT e^{−rT}N(d2), ρ_P = −KT e^{−rT}N(−d2)

**Black-76 (forward/futures F, premium paid at inception):**
d1 = [ln(F/K) + σ²T/2] / (σ√T), d2 = d1 − σ√T
C = e^{−rT}[F N(d1) − K N(d2)]; P = e^{−rT}[K N(−d2) − F N(−d1)]
Δ_C = e^{−rT}N(d1), Δ_P = −e^{−rT}N(−d1); Γ = e^{−rT}φ(d1)/(Fσ√T); Vega = F e^{−rT}φ(d1)√T
Θ = rV − e^{−rT}Fφ(d1)σ/(2√T) (F fixed); ρ = −T·V (F fixed, so ρ is discounting only)

## 4. Benchmark cases

Cases B03–B05 and F02–F03 use Nifty-*scale* numbers purely for magnitude. **They are illustrative, not market data**, and the 6.5% rate and 1.2% yield are placeholders, not sourced inputs.

### 4.1 Inputs

| Case | Model | S or F | K | r | q | σ | T |
|---|---|---|---|---|---|---|---|
| B01 | BSM | 42 | 40 | 0.10 | 0 | 0.20 | 0.5 |
| B02 | BSM | 100 | 100 | 0 | 0 | 0.20 | 1 |
| B03 | BSM | 24000 | 24000 | 0.065 | 0.012 | 0.14 | 30/365 |
| B04 | BSM | 24000 | 23500 | 0.065 | 0.012 | 0.16 | 7/365 |
| B05 | BSM | 24000 | 24500 | 0.065 | 0.012 | 0.18 | 2/365 |
| B06 | BSM | 100 | 100 | 0.05 | 0.03 | 0.30 | 2 |
| F01 | Black-76 | 100 | 100 | 0 | — | 0.20 | 1 |
| F02 | Black-76 | 24100 | 24000 | 0.065 | — | 0.14 | 30/365 |
| F03 | Black-76 | 24100 | 24500 | 0.065 | — | 0.18 | 2/365 |
| F04 | Black-76 | 100 | 90 | 0.05 | — | 0.25 | 0.5 |
| F05 | Black-76 | 100 | 100 | 0.05 | — | 0.25 | 0.5 |

### 4.2 Expected outputs (raw units; C = call, P = put)

| Id | Price | Delta | Gamma | Vega /1.00 | Theta /yr | Rho /1.00 |
|---|---|---|---|---|---|---|
| B01C | 4.75942239 | 0.77913129 | 4.996267e-02 | 8.813415 | -4.559092 | 13.982046 |
| B01P | 0.80859937 | -0.22086871 | 4.996267e-02 | 8.813415 | -0.754174 | -5.042543 |
| B02C | 7.96556746 | 0.53982784 | 1.984763e-02 | 39.695255 | -3.969525 | 46.017216 |
| B02P | 7.96556746 | -0.46017216 | 1.984763e-02 | 39.695255 | -3.969525 | -53.982784 |
| B03C | 437.41743220 | 0.55062014 | 4.103335e-04 | 2719.668086 | -2988.207336 | 1050.202675 |
| B03P | 333.19971151 | -0.44839405 | 4.103335e-04 | 2719.668086 | -1724.235444 | -911.889603 |
| B04C | 567.89264508 | 0.84286715 | 4.516754e-04 | 798.314588 | -4365.326274 | 377.058719 |
| B04P | 44.13902802 | -0.15690274 | 4.516754e-04 | 798.314588 | -3127.662960 | -73.064750 |
| B05C | 8.92552010 | 0.06437085 | 3.935137e-04 | 223.558880 | -3753.254174 | 8.416301 |
| B05P | 501.77907678 | -0.93556340 | 3.935137e-04 | 223.558880 | -2449.302329 | -125.782469 |
| B06C | 17.42528892 | 0.58422822 | 8.449450e-03 | 50.696699 | -4.099444 | 81.995065 |
| B06P | 13.73257736 | -0.35753632 | 8.449450e-03 | 50.696699 | -2.400551 | -98.972418 |
| F01C | 7.96556746 | 0.53982784 | 1.984763e-02 | 39.695255 | -3.969525 | -7.965567 |
| F01P | 7.96556746 | -0.46017216 | 1.984763e-02 | 39.695255 | -3.969525 | -7.965567 |
| F02C | 434.80374709 | 0.54628321 | 4.071081e-04 | 2720.822997 | -2288.972009 | -35.737294 |
| F02P | 335.33656911 | -0.44838857 | 4.071081e-04 | 2720.822997 | -2295.437375 | -27.561910 |
| F03C | 16.87635900 | 0.10953810 | 5.837495e-04 | 334.403046 | -5491.473069 | -0.092473 |
| F03P | 416.73391862 | -0.89010580 | 5.837495e-04 | 334.403046 | -5465.482327 | -2.283474 |
| F04C | 12.52410934 | 0.73454284 | 1.741470e-02 | 21.768373 | -4.815888 | -6.262055 |
| F04P | 2.77101022 | -0.24076707 | 1.741470e-02 | 21.768373 | -5.303543 | -1.385505 |
| F05C | 6.86930060 | 0.52200146 | 2.192458e-02 | 27.405722 | -6.507965 | -3.434650 |
| F05P | 6.86930060 | -0.45330845 | 2.192458e-02 | 27.405722 | -6.507965 | -3.434650 |

Full-precision values (price at 30 digits, scaled Greeks, d1/d2) are in `r11_fixtures.csv` / `.json`. **Suggested fixture tolerances** are a recommendation, not a derived result: 1e-9 relative on price and Greeks for double-precision implementations, with a looser absolute floor where values are near zero.

**Designed discriminators** (each catches a specific implementation mistake):
- **B02 / F01** — zero carry: call equals put, so a missing or misplaced discount factor or a wrong sign convention shows up immediately.
- **F05** — r > 0 with F = K: the call and put are equal only if the premium is discounted correctly. Note the deltas still differ (0.522 vs −0.453) because of `e^{−rT}`.
- **B06 vs B01** — q ≠ 0 separates spot-forward from `S` in d1 and in delta's `e^{−qT}`.
- **F-series rho** — Black-76 rho is −T·V (discounting only). A BSM-style rho applied to a futures option gives a different number.
- **Theta, B-series vs F-series** — BSM theta carries `−rK e^{−rT}N(d2)` and `+qS…` terms; Black-76 theta carries `+rV`. Do not reuse one for the other.

## 5. Boundary behaviour (50-digit results)

| Case | Inputs | Expected | Note |
|---|---|---|---|
| Expiry, ITM call (BSM) | S=110, K=100, T=0 | 10 | max(S−K, 0) |
| Expiry, ITM put (BSM) | S=90, K=100, T=0 | 10 | |
| Expiry, OTM call (B76) | F=90, K=100, T=0 | 0 | |
| Expiry, ITM put (B76) | F=90, K=100, T=0 | 10 | no discounting at T=0 |
| σ→0, ITM call (BSM) | S=110, K=100, r=.05, q=.02, T=.5 | 11.374490509575219 | S e^{−qT} − K e^{−rT} |
| σ→0, OTM call (BSM) | S=90 | 0 | |
| σ→0, ITM call/put (B76) | F=110 or 90, K=100, r=.05, T=.5 | 9.753099120283327 | e^{−rT}·\|F−K\| |
| Low vol, ITM (B76) | F=110, K=100, r=.05, T=.5; σ = 1e-2…1e-8 | 9.753099120283327 at every σ | time value is zero to ≥ 20 digits |
| Low vol, ATMF (B76) | F=K=100, same r, T | σ=1e-2: 0.275129273; 1e-4: 2.751298465e-3; 1e-6: 2.751298465e-5; 1e-8: 2.751298465e-7 | Linear in σ: ≈ e^{−rT}Fσ√T/√(2π) |
| Low vol, OTM (B76) | F=90, K=100 | σ=1e-2: 7.1e-52; **σ=1e-4: 6.2e-482113** | **Underflows to 0.0 in double** well before σ=1e-4 |
| Tiny T, ATMF (B76) | F=K=100, r=.05, σ=.2; T=1e-4, 1e-8, 1e-12 | 0.0797880438, 7.97884560e-4, 7.97884561e-6 | ∝ √T |
| Deep OTM call (BSM) | S=100, K=1000, T=.5 | 3.2e-58 | |
| Deep ITM call (BSM) | S=1000, K=100, T=.5 | 902.469008797 | |

**What these imply for tests (observations, not a review of your code):**
- At T=0 and σ=0 the closed forms divide by zero. A correct implementation needs an explicit branch to the intrinsic limit, or an explicit rejection. Which of the two the project wants is a design decision and is **UNRESOLVED**.
- Greeks exactly at T=0 or σ=0 are not determined by the formulas: delta is a step function at F=K and gamma is unbounded there. Expected Greek values at those points are **UNRESOLVED** beyond the limits away from the strike (delta → 0 or ±discount factor, gamma → 0, vega → 0).
- For deep-OTM and low-σ cases, tests should assert "≥ 0 and ≤ tolerance", not equality with a double-precision number, since the true value is below 1e-300.
- Any ITM price below the discounted intrinsic value by more than rounding is a lower-bound violation. The low-vol ITM row is a good regression test for catastrophic cancellation: implementations that compute N(−d) as `1 − N(d)` in double can drift.
- Invalid inputs (S, F, K ≤ 0; σ < 0; T < 0): expected behaviour is a policy choice, **UNRESOLVED**.

## 6. No-arbitrage and consistency checks (50-digit, tolerance 1e-30)

**5,805 checks, 0 violations.** Parity on the 11 benchmark cases also passes in float64 (max |error| 1.6e-12).

| Check | Applied to | Grid |
|---|---|---|
| Call lower bound max(S e^{−qT} − K e^{−rT}, 0) and upper bound S e^{−qT}; put bounds max(K e^{−rT} − S e^{−qT}, 0) and K e^{−rT} | BSM | S ∈ {60, 80, 100, 120, 160}; K ∈ {80, 100, 120}; σ ∈ {.05, .2, .6}; T ∈ {.01, .25, 1}; r ∈ {−.01, 0, .06}; q ∈ {0, .03} |
| Put–call parity C − P = S e^{−qT} − K e^{−rT} | BSM | same |
| Call-spread bound 0 ≤ C(K) − C(K+20) ≤ 20 e^{−rT} | BSM | same, K ∈ {80, 100} |
| Call bounds e^{−rT}max(F−K, 0) ≤ C ≤ e^{−rT}F | Black-76 | F ∈ {60…160}; K, σ, T, r as above |
| Parity C − P = e^{−rT}(F − K) | Black-76 | same |
| Butterfly convexity C(K−10) − 2C(K) + C(K+10) ≥ 0 | Black-76 | same |

Not covered: American exercise, discrete dividends, calendar-spread monotonicity across expiries, or anything involving a surface.

## 7. Compact fixture table

The machine-readable form is `r11_fixtures.csv` (one row per option, columns: id, model, cp, underlying, strike, r, q, sigma, T_years, T_text, provenance, price, delta, gamma, vega_per_1_00, theta_per_year, rho_per_1_00, vega_per_volpt, theta_per_calday, rho_per_1pct, d1, d2, price_50dp, scipy_price_absdiff, greek_vs_fd_maxrel). `r11_fixtures.json` adds `parity`, `boundary` and `noarb` sections. The §4.1 inputs plus §4.2 outputs are the same data in compact form.

Note for fixture authors: `T_years` for `n/365` cases is the float64 rounding of the exact rational. If your implementation takes day counts rather than year fractions, feed it the integer day count and the divisor the case names.

## 8. Closing the independence gap (to run on your machine)

I could not run a second, third-party implementation here. A quick way to do it, with any one of:
- QuantLib (`AnalyticEuropeanOption` for BSM with a dividend yield; `blackFormula` for Black-76 with a manual discount factor), or
- `py_vollib` (`black`, `black_scholes_merton`) with its Greeks modules, or
- R `RQuantLib`/`fOptions`.

Then compare all 22 rows of the CSV, and watch conventions: library Greeks often report vega per 1 vol point, theta per day, and rho per 1 point, so convert before comparing. A mismatch beyond ~1e-9 on price is worth investigating, not tolerating.

## 9. What would still be needed before model values are used in replay

None of the following is resolved by this work.

1. **Synchronised underlying data.** A spot, futures or forward price at the same timestamp as each option observation. Whether the right forward is a listed futures price or a put–call-parity implied forward is **UNRESOLVED**, and the choice changes every d1.
2. **Rate input.** A named, dated source and tenor-matching rule for `r` (and whether it is continuously compounded in that source). Sensitivity: rho in §4.2 is large for longer expiries.
3. **Dividend/carry input.** For the spot-BSM path, a documented yield or an implied-forward approach. A flat continuous `q` is only an approximation to discrete dividends.
4. **Expiry definition.** Exact expiry timestamp, day-count, and holiday/session calendar feeding `T`. Intraday replay makes this first-order: for the 2/365 cases (48 hours), every 4 hours of expiry-time error changes `T` by about 8%.
5. **Option quote history.** Bid/ask, last, and sizes with timestamps, so model value can be compared to executable prices rather than only to last trades. Stale-quote and crossed-quote rules.
6. **Implied volatility inputs.** Either per-strike IVs from observed quotes (with a stated inversion method and its tolerances) or a fitted surface. Needs at least: strikes × expiries snapshots at replay times, a rule for strikes without quotes, and a documented interpolation/extrapolation policy.
7. **Surface arbitrage checks.** Butterfly (convexity in strike) and calendar (total variance non-decreasing in T) conditions on whatever surface is used. The §6 checks are the single-slice building blocks.
8. **Surface dynamics assumption.** How IV moves when the underlying moves intraday (sticky strike, sticky moneyness, or something else), since Greeks-based P&L attribution depends on it. This is a modelling assumption to pre-register, not a fact to look up.
9. **Model-versus-observed error budget.** A pre-registered tolerance for |model − observed mid| (in price and in IV points) by moneyness and time-to-expiry, evaluated on authorized data, before any replay result relies on model values.
10. **Applicability check.** Whether Black-76 or BSM is the appropriate model for each instrument in replay is an instrument-specification question (exercise style, underlying, settlement, premium style). It was not researched here and is **UNRESOLVED**.

## 10. Separation of concerns

- **Research findings:** §§1–6 (formulas, reference values, boundary and no-arbitrage behaviour).
- **Exchange rules (expiry time, lot size, settlement, margining, fees):** deliberately not addressed; those need exchange documents and belong in a separate note.
- **Live-trading recommendations:** none. These values are theoretical benchmarks and are not evidence that any model price is executable or profitable.

## 11. Next assignment (R12)

R12 (DSR/PBO and chronological holdout methodology) was not started. It would also need the unread `NEXT-RESEARCH-REQUESTS.md` to see the current implementation and open items.
