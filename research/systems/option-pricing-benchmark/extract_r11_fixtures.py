"""Safely extract R11's literal case inputs and printed table values.

This script AST-parses the archived reference source and calls ast.literal_eval
only on the CASES assignment. It never imports or executes the R11 source.
Run from this directory with: python extract_r11_fixtures.py
"""

from __future__ import annotations

import ast
import csv
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "r11_reference_source.txt"
NOTE = ROOT / "R11-OPTION-PRICER-VALIDATION.md"
OUTPUT = ROOT / "r11_reference_cases.csv"

HEADERS = [
    "case_id", "model", "right", "underlying", "strike", "rate", "dividend_yield",
    "volatility", "time_years", "time_text", "provenance", "price_per_underlying_unit",
    "delta_per_underlying_price_unit", "gamma_per_underlying_price_unit_squared",
    "vega_per_1_00_volatility", "theta_per_year", "rho_per_1_00_rate",
    "price_abs_tolerance", "delta_abs_tolerance", "gamma_abs_tolerance",
    "vega_abs_tolerance", "theta_abs_tolerance", "rho_abs_tolerance",
]


def read_cases() -> list[tuple]:
    module = ast.parse(SOURCE.read_text(encoding="utf-8"), filename=str(SOURCE))
    assignment = next(
        node for node in module.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "CASES" for target in node.targets)
    )
    cases = ast.literal_eval(assignment.value)
    if not isinstance(cases, list) or len(cases) != 11:
        raise ValueError("Expected exactly 11 literal R11 base cases")
    return cases


def read_expected_table() -> dict[str, list[str]]:
    lines = NOTE.read_text(encoding="utf-8").splitlines()
    start = lines.index("### 4.2 Expected outputs (raw units; C = call, P = put)")
    rows: dict[str, list[str]] = {}
    for line in lines[start + 1:]:
        if not line.startswith("|"):
            if rows:
                break
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if not cells or cells[0] in {"Id", "---"} or all(set(cell) <= {"-", ":"} for cell in cells):
            continue
        if len(cells) != 7:
            raise ValueError(f"Unexpected R11 expected-output row: {line}")
        case_id = cells[0]
        if case_id in rows:
            raise ValueError(f"Duplicate R11 output row: {case_id}")
        rows[case_id] = cells[1:]
    if len(rows) != 22:
        raise ValueError(f"Expected 22 R11 output rows, found {len(rows)}")
    return rows


def tolerance(printed: str) -> str:
    """Half a unit at the final printed decimal place plus a small float64 allowance."""
    value = Decimal(printed)
    rounding = Decimal(5).scaleb(value.as_tuple().exponent - 1)
    floating = max(Decimal("5e-13"), abs(value) * Decimal("5e-13"))
    return str(rounding + floating)


def main() -> None:
    expected = read_expected_table()
    out: list[dict[str, str]] = []
    seen: set[str] = set()
    for case in read_cases():
        base_id, model, underlying, strike, rate, yield_, volatility, time, time_text, provenance = case
        time_years = float(Decimal(time.split("/")[0]) / Decimal(time.split("/")[1])) if isinstance(time, str) else float(time)
        for right in ("C", "P"):
            case_id = f"{base_id}{right}"
            if case_id in seen:
                raise ValueError(f"Duplicate case ID in literal R11 inputs: {case_id}")
            seen.add(case_id)
            values = expected.pop(case_id, None)
            if values is None:
                raise ValueError(f"Missing R11 printed outputs for {case_id}")
            price, delta, gamma, vega, theta, rho = values
            out.append({
                "case_id": case_id,
                "model": model,
                "right": right,
                "underlying": str(underlying),
                "strike": str(strike),
                "rate": str(rate),
                "dividend_yield": "" if yield_ is None else str(yield_),
                "volatility": str(volatility),
                "time_years": format(time_years, ".17g"),
                "time_text": time_text,
                "provenance": provenance,
                "price_per_underlying_unit": price,
                "delta_per_underlying_price_unit": delta,
                "gamma_per_underlying_price_unit_squared": gamma,
                "vega_per_1_00_volatility": vega,
                "theta_per_year": theta,
                "rho_per_1_00_rate": rho,
                "price_abs_tolerance": tolerance(price),
                "delta_abs_tolerance": tolerance(delta),
                "gamma_abs_tolerance": tolerance(gamma),
                "vega_abs_tolerance": tolerance(vega),
                "theta_abs_tolerance": tolerance(theta),
                "rho_abs_tolerance": tolerance(rho),
            })
    if expected:
        raise ValueError(f"Unexpected R11 output rows without literal inputs: {sorted(expected)}")
    with OUTPUT.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=HEADERS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(out)
    print(f"Extracted {len(out)} R11 cases to {OUTPUT.name}; reference source was parsed as text only.")


if __name__ == "__main__":
    main()
