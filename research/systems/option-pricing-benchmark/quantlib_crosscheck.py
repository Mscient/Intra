"""Cross-check all R11 option values and Greeks using pinned QuantLib-Python.

Run with the isolated environment documented in README.md. A missing or wrong
QuantLib version is an error; the independent comparison is never skipped.
"""

from __future__ import annotations

import csv
import math
import sys
from pathlib import Path

EXPECTED_QUANTLIB_VERSION = "1.44"
EXPECTED_CASE_IDS = {
    f"{prefix}{right}"
    for prefix in ("B01", "B02", "B03", "B04", "B05", "B06", "F01", "F02", "F03", "F04", "F05")
    for right in ("C", "P")
}
EXPECTED_HEADERS = [
    "case_id", "model", "right", "underlying", "strike", "rate", "dividend_yield",
    "volatility", "time_years", "time_text", "provenance", "price_per_underlying_unit",
    "delta_per_underlying_price_unit", "gamma_per_underlying_price_unit_squared",
    "vega_per_1_00_volatility", "theta_per_year", "rho_per_1_00_rate",
    "price_abs_tolerance", "delta_abs_tolerance", "gamma_abs_tolerance",
    "vega_abs_tolerance", "theta_abs_tolerance", "rho_abs_tolerance",
]
NUMERIC_FIELDS = [
    "underlying", "strike", "rate", "dividend_yield", "volatility", "time_years",
    "price_per_underlying_unit", "delta_per_underlying_price_unit",
    "gamma_per_underlying_price_unit_squared", "vega_per_1_00_volatility",
    "theta_per_year", "rho_per_1_00_rate", "price_abs_tolerance", "delta_abs_tolerance",
    "gamma_abs_tolerance", "vega_abs_tolerance", "theta_abs_tolerance", "rho_abs_tolerance",
]


def load_quantlib():
    try:
        import QuantLib as ql
    except ImportError as error:
        raise SystemExit(
            "QuantLib is required for independent validation; install the pinned dependency with "
            "python -m pip install -r requirements-quantlib.txt"
        ) from error
    version = getattr(ql, "__version__", None)
    if version != EXPECTED_QUANTLIB_VERSION:
        raise SystemExit(f"QuantLib {EXPECTED_QUANTLIB_VERSION} is required; found {version!r}")
    return ql


def price(ql, row: dict[str, str], time: float | None = None,
          rate: float | None = None, volatility: float | None = None) -> float:
    model = row["model"]
    underlying = float(row["underlying"])
    strike = float(row["strike"])
    t = float(row["time_years"]) if time is None else time
    r = float(row["rate"]) if rate is None else rate
    sigma = float(row["volatility"]) if volatility is None else volatility
    q = float(row["dividend_yield"] or 0.0)
    forward = underlying if model == "B76" else underlying * math.exp((r - q) * t)
    discount = math.exp(-r * t)
    option_type = ql.Option.Call if row["right"] == "C" else ql.Option.Put
    return ql.blackFormula(option_type, strike, forward, sigma * math.sqrt(t), discount)


def quantlib_values(ql, row: dict[str, str]) -> dict[str, float]:
    model = row["model"]
    underlying = float(row["underlying"])
    strike = float(row["strike"])
    rate = float(row["rate"])
    q = float(row["dividend_yield"] or 0.0)
    sigma = float(row["volatility"])
    time = float(row["time_years"])
    forward = underlying if model == "B76" else underlying * math.exp((rate - q) * time)
    discount = math.exp(-rate * time)
    payoff = ql.PlainVanillaPayoff(
        ql.Option.Call if row["right"] == "C" else ql.Option.Put, strike
    )
    calculator = ql.BlackCalculator(payoff, forward, sigma * math.sqrt(time), discount)

    if model == "BSM":
        delta = calculator.delta(underlying)
        gamma = calculator.gamma(underlying)
    else:
        delta = calculator.deltaForward()
        gamma = calculator.gammaForward()

    # QuantLib returns raw vega (dV/dσ), per one full unit of volatility.
    vega = calculator.vega(time)

    # BlackCalculator theta uses the passed spot/forward and maturity; with the
    # BSM forward/discount inputs above it returns theta holding spot and yield
    # conventions fixed, while for B76 it holds the futures price fixed.
    theta = calculator.theta(underlying, time)
    # QuantLib's BlackCalculator rho uses spot/carry convention, matching BSM.
    # Black-76 instead holds the futures price fixed, so its rate derivative is
    # solely the discount-factor derivative: -T times premium.
    rho = calculator.rho(time) if model == "BSM" else -time * calculator.value()

    values = {
        "price": calculator.value(),
        "delta": delta,
        "gamma": gamma,
        "vega": vega,
        "theta": theta,
        "rho": rho,
    }
    for name, value in values.items():
        if not math.isfinite(value):
            raise ValueError(f"Non-finite QuantLib {name} for {row['case_id']}: {value}")
    return values


def main() -> int:
    ql = load_quantlib()
    fixture_path = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(__file__).with_name("r11_reference_cases.csv")
    if len(sys.argv) > 2:
        raise SystemExit("usage: python quantlib_crosscheck.py [r11_reference_cases.csv]")

    with fixture_path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, strict=True)
        if reader.fieldnames != EXPECTED_HEADERS:
            raise SystemExit("Fixture headers do not match the R11 validation schema")
        rows = list(reader)
    ids = [row["case_id"] for row in rows]
    if len(set(ids)) != len(ids):
        raise SystemExit("Duplicate case_id in fixture")
    if set(ids) != EXPECTED_CASE_IDS:
        missing = sorted(EXPECTED_CASE_IDS - set(ids))
        unexpected = sorted(set(ids) - EXPECTED_CASE_IDS)
        raise SystemExit(f"Fixture case IDs differ from R11; missing={missing}; unexpected={unexpected}")

    summaries = {}
    for row in rows:
        case_id = row["case_id"]
        if set(row) != set(EXPECTED_HEADERS) or any(value is None for value in row.values()):
            raise SystemExit(f"{case_id} has an incomplete or over-wide fixture row")
        expected_model = "BSM" if case_id.startswith("B") else "B76"
        expected_right = "C" if case_id.endswith("C") else "P"
        if row["model"] != expected_model or row["right"] != expected_right:
            raise SystemExit(f"{case_id} model/right identity does not match the R11 case ID")
        if not row["time_text"] or not row["provenance"]:
            raise SystemExit(f"{case_id} is missing time notation or provenance")
        for field in NUMERIC_FIELDS:
            raw = row[field]
            if field == "dividend_yield" and raw == "":
                if row["model"] == "BSM":
                    raise SystemExit(f"{case_id} BSM dividend_yield must be present")
                continue
            try:
                number = float(raw)
            except ValueError as error:
                raise SystemExit(f"{case_id} has invalid {field}: {raw!r}") from error
            if not math.isfinite(number):
                raise SystemExit(f"{case_id} has non-finite {field}: {raw!r}")
            if field.endswith("_abs_tolerance") and number <= 0.0:
                raise SystemExit(f"{case_id} must have a positive {field}")
        ql_values = quantlib_values(ql, row)
        fields = {
            "price": ("price_per_underlying_unit", "price_abs_tolerance"),
            "delta": ("delta_per_underlying_price_unit", "delta_abs_tolerance"),
            "gamma": ("gamma_per_underlying_price_unit_squared", "gamma_abs_tolerance"),
            "vega": ("vega_per_1_00_volatility", "vega_abs_tolerance"),
            "theta": ("theta_per_year", "theta_abs_tolerance"),
            "rho": ("rho_per_1_00_rate", "rho_abs_tolerance"),
        }
        summary = summaries.setdefault(row["model"], {name: 0.0 for name in fields} | {"cases": 0})
        for name, (expected_field, tolerance_field) in fields.items():
            expected = float(row[expected_field])
            tolerance = float(row[tolerance_field])
            error = abs(ql_values[name] - expected)
            summary[name] = max(summary[name], error)
            if error > tolerance:
                raise SystemExit(
                    f"{row['case_id']} {name} mismatch: QuantLib={ql_values[name]:.17g}; "
                    f"R11={expected:.17g}; abs_error={error:.3e}; tolerance={tolerance:.3e}"
                )
        summary["cases"] += 1

    print(f"INDEPENDENT LIBRARY: QuantLib {ql.__version__} (QuantLib-Python)")
    for model in ("BSM", "B76"):
        summary = summaries.get(model)
        if summary is None:
            raise SystemExit(f"Missing model fixture family: {model}")
        print(
            f"{model} QuantLib: cases={summary['cases']}; "
            f"max_abs_error price={summary['price']:.3e} delta={summary['delta']:.3e} "
            f"gamma={summary['gamma']:.3e} vega_raw={summary['vega']:.3e} "
            f"theta_year={summary['theta']:.3e} rho_raw={summary['rho']:.3e}"
        )
    print(f"QUANTLIB CROSS-CHECK PASSED: cases={len(rows)}; version={ql.__version__}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
