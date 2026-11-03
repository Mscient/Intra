#!/usr/bin/env python3
"""
Reproducible benchmarks for European index-option pricing (NIFTY 50 options).

Models
------
1. BSM with continuous dividend yield q (Merton 1973)  -> options on a SPOT index
       C = S e^{-qT} N(d1) - K e^{-rT} N(d2)
       P = K e^{-rT} N(-d2) - S e^{-qT} N(-d1)
       d1 = [ln(S/K) + (r - q + sigma^2/2) T] / (sigma sqrt(T)),  d2 = d1 - sigma sqrt(T)

2. Black-76 (Black 1976)                               -> options on a FORWARD/FUTURES price F
       C = e^{-rT} [F N(d1) - K N(d2)]
       P = e^{-rT} [K N(-d2) - F N(-d1)]
       d1 = [ln(F/K) + sigma^2 T / 2] / (sigma sqrt(T)),  d2 = d1 - sigma sqrt(T)

Bridge:  Black-76 with F = S e^{(r-q)T} reproduces BSM-with-q EXACTLY, provided F is the
forward for the OPTION's own expiry T.  It does NOT hold if F is a futures contract that
expires on a different date than the option (e.g. weekly option priced off the monthly future).

Only the standard library is required; scipy (if present) is used as an independent cross-check.
Run:  python3 nifty_option_benchmarks.py
"""
import json
import math
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")


def N(x: float) -> float:
    """Standard normal CDF via erf."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def n(x: float) -> float:
    """Standard normal PDF."""
    return math.exp(-0.5 * x * x) / math.sqrt(2.0 * math.pi)


# ----------------------------------------------------------------------------- models
def bsm(S, K, T, r, q, sigma):
    """BSM with continuous dividend yield. Returns (call, put)."""
    sd = sigma * math.sqrt(T)
    d1 = (math.log(S / K) + (r - q + 0.5 * sigma ** 2) * T) / sd
    d2 = d1 - sd
    c = S * math.exp(-q * T) * N(d1) - K * math.exp(-r * T) * N(d2)
    p = K * math.exp(-r * T) * N(-d2) - S * math.exp(-q * T) * N(-d1)
    return c, p


def black76(F, K, T, r, sigma):
    """Black-76 on a forward/futures price F. Returns (call, put)."""
    sd = sigma * math.sqrt(T)
    d1 = (math.log(F / K) + 0.5 * sigma ** 2 * T) / sd
    d2 = d1 - sd
    df = math.exp(-r * T)
    return df * (F * N(d1) - K * N(d2)), df * (K * N(-d2) - F * N(-d1))


def bsm_greeks(S, K, T, r, q, sigma):
    sd = sigma * math.sqrt(T)
    d1 = (math.log(S / K) + (r - q + 0.5 * sigma ** 2) * T) / sd
    d2 = d1 - sd
    eq, er = math.exp(-q * T), math.exp(-r * T)
    return {
        "delta_call": eq * N(d1),
        "delta_put": -eq * N(-d1),
        "gamma": eq * n(d1) / (S * sd),
        "vega_per_1.00": S * eq * n(d1) * math.sqrt(T),
        "theta_call_per_year": (-S * eq * n(d1) * sigma / (2 * math.sqrt(T))
                                - r * K * er * N(d2) + q * S * eq * N(d1)),
        "rho_call_per_1.00": K * T * er * N(d2),
    }


def forward_from_parity(C, P, K, T, r):
    """Forward implied by European put-call parity: C - P = e^{-rT}(F - K)."""
    return K + math.exp(r * T) * (C - P)


def implied_vol_bsm(price, S, K, T, r, q, is_call=True, lo=1e-4, hi=5.0):
    f = lambda s: bsm(S, K, T, r, q, s)[0 if is_call else 1] - price
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if f(mid) > 0:
            hi = mid
        else:
            lo = mid
    return 0.5 * (lo + hi)


# ------------------------------------------------------------------------ time to expiry
def year_fraction(now: datetime, expiry_date, close=time(15, 30), basis=365.0):
    """ACT/365 year fraction from `now` (IST) to expiry date at `close` IST.
    NOTE: the close time is a modelling choice. Since 3 Aug 2026 the NSE derivatives session
    ends 15:40 while the index close (the settlement price) comes from the 15:15-15:35
    closing auction. This function makes the choice explicit rather than hard-coding it."""
    exp_dt = datetime.combine(expiry_date, close, tzinfo=IST)
    return (exp_dt - now).total_seconds() / (basis * 86400.0)


# --------------------------------------------------------------------------------- checks
def check(name, got, want, tol):
    ok = abs(got - want) <= tol
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: got {got:.6f}, want {want:.6f} (tol {tol})")
    return ok


def main():
    all_ok = True
    out = {}

    # ---- A. textbook anchor (Hull, Options Futures & Other Derivatives: S=42,K=40,r=10%,sigma=20%,T=0.5)
    print("A. Textbook anchor (no dividends): S=42 K=40 r=0.10 sigma=0.20 T=0.5")
    c, p = bsm(42, 40, 0.5, 0.10, 0.0, 0.20)
    all_ok &= check("call ~ 4.76", c, 4.7594, 5e-4)
    all_ok &= check("put  ~ 0.81", p, 0.8086, 5e-4)
    out["hull_anchor"] = {"call": c, "put": p}

    # ---- B. NIFTY-style benchmark grid (synthetic inputs; NOT market quotes)
    print("\nB. NIFTY-style European benchmarks (synthetic inputs, continuous compounding)")
    cases = [
        # label,                 S,      K,     T(days), r,    q,     sigma
        ("ATM 30d",             25000, 25000, 30,  0.0600, 0.0130, 0.150),
        ("OTM-call / ITM-put 7d", 25000, 25500, 7,  0.0600, 0.0130, 0.140),
        ("ITM-call / OTM-put 180d", 25000, 24000, 180, 0.0600, 0.0130, 0.170),
        ("Deep OTM call 3d",     25000, 26000, 3,   0.0600, 0.0130, 0.130),
    ]
    rows = []
    for label, S, K, d, r, q, sg in cases:
        T = d / 365.0
        c, p = bsm(S, K, T, r, q, sg)
        F = S * math.exp((r - q) * T)
        c76, p76 = black76(F, K, T, r, sg)
        g = bsm_greeks(S, K, T, r, q, sg)
        # parity: C - P = S e^{-qT} - K e^{-rT}
        par = (c - p) - (S * math.exp(-q * T) - K * math.exp(-r * T))
        # forward round-trip
        F_back = forward_from_parity(c, p, K, T, r)
        # IV round-trip
        iv = implied_vol_bsm(c, S, K, T, r, q, True)
        print(f"  {label}: S={S} K={K} T={d}d r={r:.2%} q={q:.2%} vol={sg:.1%}")
        print(f"     BSM(q)   call={c:12.6f} put={p:12.6f}")
        print(f"     Black-76 call={c76:12.6f} put={p76:12.6f}  (F={F:.4f})")
        all_ok &= check("  BSM(q) == Black-76(F=S e^{(r-q)T}) call", c76, c, 1e-8)
        all_ok &= check("  BSM(q) == Black-76(F=S e^{(r-q)T}) put ", p76, p, 1e-8)
        all_ok &= check("  put-call parity residual", par, 0.0, 1e-8)
        all_ok &= check("  forward recovered from parity", F_back, F, 1e-6)
        all_ok &= check("  implied vol round-trip", iv, sg, 1e-8)
        rows.append({"label": label, "S": S, "K": K, "days": d, "r": r, "q": q, "sigma": sg,
                     "call": c, "put": p, "forward": F, "greeks": g})
    out["grid"] = rows

    # ---- C. finite-difference check of delta / gamma / vega
    print("\nC. Finite-difference Greeks check (ATM 30d)")
    S, K, T, r, q, sg = 25000, 25000, 30 / 365, 0.06, 0.013, 0.15
    h = 1.0
    cu, _ = bsm(S + h, K, T, r, q, sg)
    cd, _ = bsm(S - h, K, T, r, q, sg)
    c0, _ = bsm(S, K, T, r, q, sg)
    g = bsm_greeks(S, K, T, r, q, sg)
    all_ok &= check("delta (central diff)", (cu - cd) / (2 * h), g["delta_call"], 1e-6)
    all_ok &= check("gamma (central diff)", (cu - 2 * c0 + cd) / h ** 2, g["gamma"], 1e-6)
    cv_u, _ = bsm(S, K, T, r, q, sg + 1e-4)
    cv_d, _ = bsm(S, K, T, r, q, sg - 1e-4)
    all_ok &= check("vega  (central diff)", (cv_u - cv_d) / 2e-4, g["vega_per_1.00"], 1e-3)

    # ---- D. why Black-76 on the WRONG futures contract mis-prices a weekly option
    print("\nD. Weekly option (7d) priced with Black-76 on the MONTHLY future (35d) vs its own forward")
    S, K, r, q, sg = 25000, 25000, 0.06, 0.013, 0.14
    T_opt, T_fut = 7 / 365, 35 / 365
    F_own = S * math.exp((r - q) * T_opt)
    F_monthly = S * math.exp((r - q) * T_fut)
    c_right, p_right = black76(F_own, K, T_opt, r, sg)
    c_wrong, p_wrong = black76(F_monthly, K, T_opt, r, sg)
    print(f"  own-expiry forward  F={F_own:.2f}  call={c_right:.4f} put={p_right:.4f}")
    print(f"  monthly future      F={F_monthly:.2f}  call={c_wrong:.4f} put={p_wrong:.4f}")
    print(f"  forward error = {F_monthly - F_own:.2f} index points; "
          f"call error = {c_wrong - c_right:+.4f}, put error = {p_wrong - p_right:+.4f}")
    out["weekly_vs_monthly_future"] = {
        "F_own": F_own, "F_monthly": F_monthly, "call_right": c_right, "call_wrong": c_wrong,
        "put_right": p_right, "put_wrong": p_wrong}

    # ---- E. NSE's published (dividend-free) formula vs BSM(q): size of the q effect
    print("\nE. NSE-spec formula (no q, r=MIBOR) vs BSM with q  -- effect of ignoring dividend yield")
    S, K, T, r, sg = 25000, 25000, 30 / 365, 0.06, 0.15
    c_nse, p_nse = bsm(S, K, T, r, 0.0, sg)
    c_q, p_q = bsm(S, K, T, r, 0.013, sg)
    print(f"  q=0     call={c_nse:.4f} put={p_nse:.4f}")
    print(f"  q=1.3%  call={c_q:.4f} put={p_q:.4f}")
    print(f"  difference call={c_nse - c_q:+.4f} put={p_nse - p_q:+.4f} index points")
    out["no_q_vs_q"] = {"call_noq": c_nse, "call_q": c_q, "put_noq": p_nse, "put_q": p_q}

    # ---- F. time-to-expiry convention sensitivity
    print("\nF. Time-to-expiry convention (expiry Tue 2026-11-10 close; valuation Tue 2026-11-03 10:00 IST)")
    now = datetime(2026, 11, 3, 10, 0, tzinfo=IST)
    exp = datetime(2026, 11, 10).date()
    S, K, r, q, sg = 25000, 25000, 0.06, 0.013, 0.14
    for lab, close in (("15:30", time(15, 30)), ("15:40", time(15, 40))):
        T = year_fraction(now, exp, close)
        c, p = bsm(S, K, T, r, q, sg)
        print(f"  close={lab}: T={T:.8f}y ({T*365:.5f} d) call={c:.4f} put={p:.4f}")
        out.setdefault("expiry_close_convention", {})[lab] = {"T": T, "call": c, "put": p}

    # optional cross-check with scipy
    try:
        from scipy.stats import norm
        S, K, T, r, q, sg = 25000, 25000, 30 / 365, 0.06, 0.013, 0.15
        d1 = (math.log(S / K) + (r - q + 0.5 * sg ** 2) * T) / (sg * math.sqrt(T))
        d2 = d1 - sg * math.sqrt(T)
        c_sp = S * math.exp(-q * T) * norm.cdf(d1) - K * math.exp(-r * T) * norm.cdf(d2)
        print("\nG. scipy cross-check")
        all_ok &= check("call (math.erf vs scipy.norm)", bsm(S, K, T, r, q, sg)[0], c_sp, 1e-9)
    except ImportError:
        print("\nG. scipy not installed; cross-check skipped")

    with open("nifty_option_benchmarks.json", "w") as fh:
        json.dump(out, fh, indent=2)
    print("\nALL CHECKS PASSED" if all_ok else "\nSOME CHECKS FAILED")
    raise SystemExit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
