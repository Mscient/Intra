# User-supplied option-pricing benchmarks

The original `nifty_option_benchmarks.py` and JSON were supplied in `files (1).zip` and copied unchanged. The script was inspected as text and has not been executed. Its outputs are synthetic and are not an independent oracle.

The R11 material, `R11-OPTION-PRICER-VALIDATION.md` and `r11_reference.py`, was supplied separately and copied unchanged on 2026-11-04. It documents 22 BSM/Black-76 reference rows, high-precision formula calculations, Greek finite-difference comparisons, and no-arbitrage sweeps. The note reports that the Java implementation and a third-party pricing library were not compared. The Python script imports `mpmath` and `scipy`, invokes `main()` on import, and writes to `/mnt/user-data/outputs`; it has not been run here. Treat its values as an independently coded mathematical cross-check, not as third-party or market validation.

Both packs contain theoretical examples only; NIFTY-like cases are illustrative, not market observations. Textbook and public-library anchors listed in R11 still require source-level confirmation. Keep the artifacts separate from the Java runtime. The Java code's own bounds/parity/finite-difference CLI diagnostics are numerical consistency checks, not independent reference validation. Before any model values are used in accounting or risk, compare the R11 cases to Java and a maintained independent implementation, review conventions and tails/boundaries, and validate inputs against authorized synchronized option/underlying data.

## R11 Java pricer validation

This pack now contains an offline validation harness for the Java 21 European BSM and Black-76 pricers. It accounts for all 22 R11 call/put cases and checks price, delta, gamma, vega, theta, and rho. It does not access market data, connect to a broker, or use theoretical values for fills or accounting P&L.

### Inputs and provenance

- `R11-OPTION-PRICER-VALIDATION.md` is the supplied R11 note. Its expected-output table is the fixture's numeric oracle. Values are rounded as printed in that note; they are not full-precision market or exchange observations.
- `r11_reference_source.txt` is an inert text copy of the supplied Python source. The original script invokes its `main()` at module import time and writes an output file, so it must not be imported or casually executed.
- `extract_r11_fixtures.py` reads the source with `ast.parse`, extracts only the literal `CASES` assignment using `ast.literal_eval`, and parses the printed table from the note. It never imports or executes the supplied program.
- `r11_reference_cases.csv` is the checked-in, machine-readable input/output fixture. Every row carries a stable case ID, model, right, inputs, original time notation, provenance, expected outputs, explicit units in column names, and a tolerance per output.

The extractor derives each absolute tolerance as half one unit of the last printed decimal place plus `max(5e-13, abs(expected) * 5e-13)` for float64 arithmetic. This respects the precision of the supplied table instead of treating rounded display values as exact. The Java and QuantLib runners fail when an error exceeds that row-and-metric tolerance; the thresholds are not relaxed to accommodate the old Java CDF.

### Conventions

Fixture/reference Greeks are normalized to raw units: delta per underlying price unit; gamma per squared underlying price unit; vega per a 1.00 volatility change; theta per year; rho per a 1.00 rate change. In the Java API, vega and rho are reported per one percentage point and theta per 365-calendar day. The Java runner converts those three back to raw units before comparing them. Black-76's underlying is the futures price and its delta/gamma are forward-price derivatives.

The independent implementation is QuantLib-Python **1.44**, pinned by `requirements-quantlib.txt`. The runner uses QuantLib's `BlackCalculator` with the same discount factor and total standard deviation. For BSM it maps spot to forward `S*exp((r-q)T)` with discount `exp(-rT)`, then asks for spot delta/gamma and spot/carry theta/rho. For Black-76 it uses the supplied futures price and the same discount; delta/gamma are forward Greeks, theta holds futures price fixed, and rho is `-T * option_value` because the futures price is held fixed. Vega is `dV/dσ` per full volatility unit; theta and rho are annual/raw-rate derivatives. No percentage-point or daily rescaling is applied to the reference comparison.

### Run the Java 21 checks

From the repository root in PowerShell, set `JAVA_HOME` to a JDK 21 installation, then run:

```powershell
& .\Intra-Paper-System\validate-option-pricers.ps1
```

The script builds the production JAR in a task-specific external build folder, compiles the validation-only Java source in a temporary directory, runs the fixture and boundary/no-arbitrage checks, and removes the temporary validation classes. The harness is outside `src/main` and is not packaged into the simulator JAR. You can supply another fixture path as its `-Fixture` argument; the schema and exact 22 case IDs are enforced.

The runner checks missing, duplicate, and unexpected IDs; exact headers; finite inputs, expected values, tolerances, and Java results; then checks per-metric tolerances. Boundary coverage includes expiry, near-expiry ATM, deep ITM/OTM, and low-volatility ATM/ITM/OTM behavior. The R11 no-arbitrage grids cover BSM bounds, put-call parity, call-spread bounds, Black-76 bounds/parity, and Black-76 butterfly convexity.

### Run the independent QuantLib check

Use an isolated Python environment, separate from the simulator and runtime class path:

```powershell
python -m venv $env:TEMP\intra-quantlib-validation
& $env:TEMP\intra-quantlib-validation\Scripts\python.exe -m pip install -r .\research\systems\option-pricing-benchmark\requirements-quantlib.txt
& $env:TEMP\intra-quantlib-validation\Scripts\python.exe .\research\systems\option-pricing-benchmark\quantlib_crosscheck.py
```

The independent comparison is mandatory for this validation workflow. A missing dependency or version other than 1.44 is a hard error; it is never reported as a skipped check. The Python environment is only a validation tool and adds no Java/runtime dependency.

The R11 note and original Python source were supplied with the task. QuantLib provenance: [official 1.44 release](https://github.com/lballabio/QuantLib/releases/tag/v1.44) and [QuantLib-Python 1.44 package](https://pypi.org/project/QuantLib/1.44/). The revised normal CDF uses the Cephes erf/erfc rational approximation by Stephen L. Moshier, from [Netlib Cephes](https://netlib.org/cephes/).

### Numerical discrepancy found and corrected

The first comparison identified a real production issue: the prior Abramowitz–Stegun 7.1.26 normal-CDF approximation introduced a measurable price error in the large-notional, short-dated B03 case. Replaying that old approximation against QuantLib 1.44 gave maximum absolute errors across the R11 cases of `6.41166492926e-4` for price (B03C), `1.27e-4` for raw rho, and `9.00e-5` for annual theta; the corresponding maximum delta, gamma, and raw-vega errors were `7.11e-8`, `4.86e-17`, and `3.65e-11`. The Java pricers now share the Cephes erf/erfc CDF and density implementation. After the change, Java and QuantLib both fall within the rounding-derived tolerances of every printed R11 value. The Java runner's largest post-fix errors against the rounded fixture are reported by its command output. This change addresses numerical evaluation only; it does not change model conventions or connect prices to simulator accounting.
