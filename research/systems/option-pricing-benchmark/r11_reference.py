#!/usr/bin/env python3
"""R11 independent reference values for BSM (spot, continuous yield q) and Black-76.

Independence: written from the published closed-form formulas (Black 1976;
Merton 1973; Hull; Haug) using mpmath at 50 digits. Nothing here reads or
imports the Intra project. Analytic Greeks are verified against numerical
derivatives of the *price function* (mpmath.diff), and prices are cross-checked
against scipy.stats.norm in float64.

Conventions (explicit, so a fixture can be replayed):
  T       : year fraction, supplied directly (day-count is the CALLER's choice;
            cases that name days use days/365 purely as a test convention).
  r, q    : continuously compounded, decimal (0.065 = 6.5%).
  sigma   : annualised lognormal vol, decimal (0.14 = 14 vol points).
  Greeks  : RAW units = per 1.00 change of the input (spot/forward, vol, rate),
            theta per YEAR of calendar time (theta = dV/dt = -dV/dT).
            Scaled columns (per 1 vol pt, per calendar day, per 1bp) are
            derived by fixed divisors and labelled.
"""
import json, csv, itertools, sys
import mpmath as mp
from scipy.stats import norm as sp_norm
import math

mp.mp.dps = 50
N = lambda x: mp.ncdf(x)
phi = lambda x: mp.npdf(x)
def D(x):
    """Exact conversion: decimal strings/ints/floats via their shortest decimal repr; 'a/b' as exact rational."""
    if isinstance(x, mp.mpf):
        return x
    if isinstance(x, str) and "/" in x:
        a, b = x.split("/")
        return mp.mpf(a) / mp.mpf(b)
    return mp.mpf(str(x))


# ---------------------------------------------------------------- pricers
def bsm_price(cp, S, K, r, q, sig, T):
    S, K, r, q, sig, T = map(D, (S, K, r, q, sig, T))
    if T == 0 or sig == 0:  # limits: intrinsic of discounted forward
        fwd = S * mp.e ** (-q * T) - K * mp.e ** (-r * T)
        return max(fwd, 0) if cp == "C" else max(-fwd, 0)
    sd = sig * mp.sqrt(T)
    d1 = (mp.log(S / K) + (r - q + sig ** 2 / 2) * T) / sd
    d2 = d1 - sd
    if cp == "C":
        return S * mp.e ** (-q * T) * N(d1) - K * mp.e ** (-r * T) * N(d2)
    return K * mp.e ** (-r * T) * N(-d2) - S * mp.e ** (-q * T) * N(-d1)


def b76_price(cp, F, K, r, sig, T):
    F, K, r, sig, T = map(D, (F, K, r, sig, T))
    df = mp.e ** (-r * T)
    if T == 0 or sig == 0:
        return df * max(F - K, 0) if cp == "C" else df * max(K - F, 0)
    sd = sig * mp.sqrt(T)
    d1 = (mp.log(F / K) + sig ** 2 * T / 2) / sd
    d2 = d1 - sd
    if cp == "C":
        return df * (F * N(d1) - K * N(d2))
    return df * (K * N(-d2) - F * N(-d1))


# ---------------------------------------------------------------- analytic Greeks
def bsm_greeks(cp, S, K, r, q, sig, T):
    S, K, r, q, sig, T = map(D, (S, K, r, q, sig, T))
    sd = sig * mp.sqrt(T)
    d1 = (mp.log(S / K) + (r - q + sig ** 2 / 2) * T) / sd
    d2 = d1 - sd
    eq, er = mp.e ** (-q * T), mp.e ** (-r * T)
    common_theta = -S * eq * phi(d1) * sig / (2 * mp.sqrt(T))
    if cp == "C":
        delta = eq * N(d1)
        theta = common_theta - r * K * er * N(d2) + q * S * eq * N(d1)
        rho = K * T * er * N(d2)
    else:
        delta = -eq * N(-d1)
        theta = common_theta + r * K * er * N(-d2) - q * S * eq * N(-d1)
        rho = -K * T * er * N(-d2)
    gamma = eq * phi(d1) / (S * sd)
    vega = S * eq * phi(d1) * mp.sqrt(T)
    return dict(delta=delta, gamma=gamma, vega=vega, theta=theta, rho=rho, d1=d1, d2=d2)


def b76_greeks(cp, F, K, r, sig, T):
    F, K, r, sig, T = map(D, (F, K, r, sig, T))
    sd = sig * mp.sqrt(T)
    d1 = (mp.log(F / K) + sig ** 2 * T / 2) / sd
    d2 = d1 - sd
    df = mp.e ** (-r * T)
    price = b76_price(cp, F, K, r, sig, T)
    delta = df * N(d1) if cp == "C" else -df * N(-d1)
    gamma = df * phi(d1) / (F * sd)
    vega = F * df * phi(d1) * mp.sqrt(T)
    theta = r * price - df * F * phi(d1) * sig / (2 * mp.sqrt(T))   # F held fixed
    rho = -T * price                                                # F held fixed
    return dict(delta=delta, gamma=gamma, vega=vega, theta=theta, rho=rho, d1=d1, d2=d2)


# ---------------------------------------------------------------- independent Greek check (numerical derivatives of price)
def fd_bsm(cp, S, K, r, q, sig, T):
    f = lambda **kw: bsm_price(cp, kw.get("S", S), K, kw.get("r", r), q, kw.get("sig", sig), kw.get("T", T))
    S_, r_, s_, T_ = D(S), D(r), D(sig), D(T)
    return dict(
        delta=mp.diff(lambda x: f(S=x), S_),
        gamma=mp.diff(lambda x: f(S=x), S_, 2),
        vega=mp.diff(lambda x: f(sig=x), s_),
        theta=-mp.diff(lambda x: f(T=x), T_),
        rho=mp.diff(lambda x: f(r=x), r_),
    )


def fd_b76(cp, F, K, r, sig, T):
    f = lambda **kw: b76_price(cp, kw.get("F", F), K, kw.get("r", r), kw.get("sig", sig), kw.get("T", T))
    F_, r_, s_, T_ = D(F), D(r), D(sig), D(T)
    return dict(
        delta=mp.diff(lambda x: f(F=x), F_),
        gamma=mp.diff(lambda x: f(F=x), F_, 2),
        vega=mp.diff(lambda x: f(sig=x), s_),
        theta=-mp.diff(lambda x: f(T=x), T_),
        rho=mp.diff(lambda x: f(r=x), r_),
    )


# ---------------------------------------------------------------- scipy float64 cross-check of prices
def sp_bsm(cp, S, K, r, q, sig, T):
    sd = sig * math.sqrt(T)
    d1 = (math.log(S / K) + (r - q + sig * sig / 2) * T) / sd
    d2 = d1 - sd
    if cp == "C":
        return S * math.exp(-q * T) * sp_norm.cdf(d1) - K * math.exp(-r * T) * sp_norm.cdf(d2)
    return K * math.exp(-r * T) * sp_norm.cdf(-d2) - S * math.exp(-q * T) * sp_norm.cdf(-d1)


def sp_b76(cp, F, K, r, sig, T):
    sd = sig * math.sqrt(T)
    d1 = (math.log(F / K) + sig * sig * T / 2) / sd
    d2 = d1 - sd
    df = math.exp(-r * T)
    if cp == "C":
        return df * (F * sp_norm.cdf(d1) - K * sp_norm.cdf(d2))
    return df * (K * sp_norm.cdf(-d2) - F * sp_norm.cdf(-d1))


# ---------------------------------------------------------------- case table
# (id, model, S_or_F, K, r, q, sigma, T, T_text, provenance)
CASES = [
    ("B01", "BSM", 42, 40, 0.10, 0.0, 0.20, 0.5, "0.5 y", "textbook-style anchor (Hull-type S=42,K=40); published value to be confirmed by edition"),
    ("B02", "BSM", 100, 100, 0.0, 0.0, 0.20, 1.0, "1 y", "zero-carry ATM: call = S(2N(sigma*sqrt(T)/2)-1)"),
    ("B03", "BSM", 24000, 24000, 0.065, 0.012, 0.14, '30/365', "30/365", "ILLUSTRATIVE Nifty-scale inputs; not market data"),
    ("B04", "BSM", 24000, 23500, 0.065, 0.012, 0.16, '7/365', "7/365", "ILLUSTRATIVE; ITM call / OTM put"),
    ("B05", "BSM", 24000, 24500, 0.065, 0.012, 0.18, '2/365', "2/365", "ILLUSTRATIVE; OTM call / ITM put, short dated"),
    ("B06", "BSM", 100, 100, 0.05, 0.03, 0.30, 2.0, "2 y", "q>0 and r>0, long dated"),
    ("F01", "B76", 100, 100, 0.0, None, 0.20, 1.0, "1 y", "docs.rs black_76 quick-start: vol 0.20 <-> price 7.9656"),
    ("F02", "B76", 24100, 24000, 0.065, None, 0.14, '30/365', "30/365", "ILLUSTRATIVE; futures-option scale"),
    ("F03", "B76", 24100, 24500, 0.065, None, 0.18, '2/365', "2/365", "ILLUSTRATIVE; OTM call short dated"),
    ("F04", "B76", 100, 90, 0.05, None, 0.25, 0.5, "0.5 y", "ITM call, r>0 (discounting matters)"),
    ("F05", "B76", 100, 100, 0.05, None, 0.25, 0.5, "0.5 y", "ATM-forward, r>0: tests that discounting is applied to premium"),
]


def row_for(case, cp):
    cid, model, X, K, r, q, sig, T, Ttxt, prov = case
    if model == "BSM":
        px = bsm_price(cp, X, K, r, q, sig, T)
        g = bsm_greeks(cp, X, K, r, q, sig, T)
        fd = fd_bsm(cp, X, K, r, q, sig, T)
        sp = sp_bsm(cp, float(X), float(K), float(r), float(q), float(sig), float(D(T)))
    else:
        px = b76_price(cp, X, K, r, sig, T)
        g = b76_greeks(cp, X, K, r, sig, T)
        fd = fd_b76(cp, X, K, r, sig, T)
        sp = sp_b76(cp, float(X), float(K), float(r), float(sig), float(D(T)))
    maxrel = max(abs(g[k] - fd[k]) / max(abs(g[k]), mp.mpf("1e-12")) for k in fd)
    return dict(
        id=f"{cid}{cp}", model=model, cp=cp, underlying=X, strike=K, r=r, q=q, sigma=sig, T_years=float(D(T)),
        T_text=Ttxt, provenance=prov,
        price=float(px), delta=float(g["delta"]), gamma=float(g["gamma"]),
        vega_per_1_00=float(g["vega"]), theta_per_year=float(g["theta"]), rho_per_1_00=float(g["rho"]),
        vega_per_volpt=float(g["vega"] / 100), theta_per_calday=float(g["theta"] / 365),
        rho_per_1pct=float(g["rho"] / 100),
        d1=float(g["d1"]), d2=float(g["d2"]),
        price_50dp=mp.nstr(px, 30),
        scipy_price_absdiff=float(abs(px - D(sp))),
        greek_vs_fd_maxrel=float(maxrel),
    ), px, g


def main():
    rows, report = [], []
    for c in CASES:
        for cp in ("C", "P"):
            row, px, g = row_for(c, cp)
            rows.append(row)
    # parity checks per case
    parity = []
    for c in CASES:
        cid, model, X, K, r, q, sig, T, *_ = c
        T = float(D(T))
        C = next(x for x in rows if x["id"] == f"{cid}C")
        P = next(x for x in rows if x["id"] == f"{cid}P")
        if model == "BSM":
            rhs = X * math.exp(-q * T) - K * math.exp(-r * T)
        else:
            rhs = math.exp(-r * T) * (X - K)
        parity.append(dict(id=cid, C_minus_P=C["price"] - P["price"], rhs=rhs, absdiff=abs(C["price"] - P["price"] - rhs)))

    # boundary / no-arbitrage cases (computed at 50 dp, reported as floats / strings)
    bnd = []
    def add(name, model, cp, args, note):
        px = (bsm_price if model == "BSM" else b76_price)(cp, *args)
        bnd.append(dict(case=name, model=model, cp=cp, args=list(args), price=mp.nstr(px, 20), note=note))

    # expiry T=0: intrinsic
    add("T0_call_itm", "BSM", "C", (110, 100, 0.05, 0.02, 0.2, 0), "T=0 -> max(S-K,0)=10")
    add("T0_put_itm", "BSM", "P", (90, 100, 0.05, 0.02, 0.2, 0), "T=0 -> max(K-S,0)=10")
    add("T0_call_otm", "B76", "C", (90, 100, 0.05, 0.2, 0), "T=0 -> 0")
    add("T0_put_itm", "B76", "P", (90, 100, 0.05, 0.2, 0), "T=0 -> 10 (no discounting at T=0)")
    # sigma -> 0: discounted-forward intrinsic
    for nm, mdl, a in [
        ("sig0_BSM_call_itm", "BSM", (110, 100, 0.05, 0.02, 0, 0.5)),
        ("sig0_BSM_call_otm", "BSM", (90, 100, 0.05, 0.02, 0, 0.5)),
        ("sig0_B76_call_itm", "B76", (110, 100, 0.05, 0, 0.5)),
        ("sig0_B76_put_itm", "B76", (90, 100, 0.05, 0, 0.5)),
    ]:
        add(nm, mdl, "P" if "put" in nm else "C", a, "sigma=0 limit: discounted forward intrinsic")
    # very low vol: converges to the limit
    for s in ("1e-2", "1e-4", "1e-6", "1e-8"):
        add(f"lowvol_{s}_B76_call_ATMF", "B76", "C", (100, 100, 0.05, float(s), 0.5), "F=K: price ~ df*F*sigma*sqrt(T)/sqrt(2*pi) -> 0 linearly in sigma")
        add(f"lowvol_{s}_B76_call_ITM", "B76", "C", (110, 100, 0.05, float(s), 0.5), "-> df*(F-K) = 9.7531...; time value -> 0")
        add(f"lowvol_{s}_B76_call_OTM", "B76", "C", (90, 100, 0.05, float(s), 0.5), "-> 0 faster than any power of sigma (underflow region)")
    # tiny T
    for t in ("1e-4", "1e-8", "1e-12"):
        add(f"tinyT_{t}_B76_call_ATMF", "B76", "C", (100, 100, 0.05, 0.2, float(t)), "F=K: ~ F*sigma*sqrt(T)/sqrt(2*pi) -> 0 like sqrt(T)")
    # deep ITM/OTM
    add("deepOTM_BSM_call", "BSM", "C", (100, 1000, 0.05, 0.0, 0.2, 0.5), "price ~ 0 (below float64 relevance), delta ~ 0")
    add("deepITM_BSM_call", "BSM", "C", (1000, 100, 0.05, 0.0, 0.2, 0.5), "price ~ S - K e^{-rT}; delta ~ 1")

    # no-arbitrage sweeps (grid) in 50 dp
    viol = []
    grid_S = [60, 80, 100, 120, 160]
    grid_K = [80, 100, 120]
    grid_sig = [0.05, 0.2, 0.6]
    grid_T = [0.01, 0.25, 1.0]
    grid_r = [-0.01, 0.0, 0.06]
    grid_q = [0.0, 0.03]
    n_checks = 0
    for S, K, sig, T, r, q in itertools.product(grid_S, grid_K, grid_sig, grid_T, grid_r, grid_q):
        S, K, sig, T, r, q = map(D, (S, K, sig, T, r, q))
        C = bsm_price("C", S, K, r, q, sig, T); P = bsm_price("P", S, K, r, q, sig, T)
        lo_c = max(D(S) * mp.e ** (-q * T) - D(K) * mp.e ** (-r * T), 0)
        lo_p = max(D(K) * mp.e ** (-r * T) - D(S) * mp.e ** (-q * T), 0)
        hi_c = D(S) * mp.e ** (-q * T)
        hi_p = D(K) * mp.e ** (-r * T)
        par = C - P - (D(S) * mp.e ** (-q * T) - D(K) * mp.e ** (-r * T))
        n_checks += 5
        if not (lo_c - mp.mpf("1e-30") <= C <= hi_c + mp.mpf("1e-30")): viol.append(("bsm_call_bounds", S, K, sig, T, r, q))
        if not (lo_p - mp.mpf("1e-30") <= P <= hi_p + mp.mpf("1e-30")): viol.append(("bsm_put_bounds", S, K, sig, T, r, q))
        if abs(par) > mp.mpf("1e-30"): viol.append(("bsm_parity", S, K, sig, T, r, q))
        # call spread bound: 0 <= C(K1)-C(K2) <= df*(K2-K1) for K1<K2
        if K < 120:
            K2 = K + 20
            d = bsm_price("C", S, K, r, q, sig, T) - bsm_price("C", S, K2, r, q, sig, T)
            if not (-mp.mpf("1e-30") <= d <= (K2 - K) * mp.e ** (-r * T) + mp.mpf("1e-30")): viol.append(("bsm_spread", S, K, sig, T, r, q))
            n_checks += 1
    for F, K, sig, T, r in itertools.product(grid_S, grid_K, grid_sig, grid_T, grid_r):
        F, K, sig, T, r = map(D, (F, K, sig, T, r))
        C = b76_price("C", F, K, r, sig, T); P = b76_price("P", F, K, r, sig, T)
        df = mp.e ** (-r * T)
        lo_c = df * max(D(F) - K, 0); hi_c = df * D(F)
        n_checks += 3
        if not (lo_c - mp.mpf("1e-30") <= C <= hi_c + mp.mpf("1e-30")): viol.append(("b76_call_bounds", F, K, sig, T, r))
        if abs(C - P - df * (D(F) - K)) > mp.mpf("1e-30"): viol.append(("b76_parity", F, K, sig, T, r))
        # convexity in K
        Ks = [K - 10, K, K + 10]
        c3 = [b76_price("C", F, k, r, sig, T) for k in Ks]
        if c3[0] - 2 * c3[1] + c3[2] < -mp.mpf("1e-30"): viol.append(("b76_butterfly", F, K, sig, T, r))

    out = dict(rows=rows, parity=parity, boundary=bnd,
               noarb=dict(checks=n_checks, violations=viol,
                          grid=dict(S_or_F=grid_S, K=grid_K, sigma=grid_sig, T=grid_T, r=grid_r, q=grid_q)),
               env=dict(mpmath=mp.__version__, dps=int(mp.mp.dps)))
    json.dump(out, open("/mnt/user-data/outputs/r11_fixtures.json", "w"), indent=1)
    with open("/mnt/user-data/outputs/r11_fixtures.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r_ in rows:
            w.writerow({k: ("" if v is None else v) for k, v in r_.items()})

    # console summary
    print("max |analytic Greek - numeric derivative of price| (relative):", max(x["greek_vs_fd_maxrel"] for x in rows))
    print("max |mpmath - scipy float64| price abs diff:", max(x["scipy_price_absdiff"] for x in rows))
    print("max parity abs err (float64 rows):", max(p["absdiff"] for p in parity))
    print("no-arb checks:", n_checks, "violations:", len(viol))
    for x in rows:
        print(x["id"], f'{x["price"]:.8f}', f'd={x["delta"]:.8f}', f'g={x["gamma"]:.3e}', f'v={x["vega_per_1_00"]:.6f}',
              f'th/yr={x["theta_per_year"]:.6f}', f'rho={x["rho_per_1_00"]:.6f}')
    print("--- boundary")
    for b in bnd:
        print(b["case"], b["price"])


main()
