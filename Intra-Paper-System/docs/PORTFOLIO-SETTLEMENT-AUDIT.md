# Offline portfolio expiry settlement audit

`PortfolioSettlementAuditCli` reconstructs a completed multi-instrument portfolio replay and produces a separate cash settlement diagnostic. It does not change `PortfolioReplayCsvRunner`, the accounting ledger, replay artifacts, or `IntraPaperCli`. All values, dates, methods, references, and charge terms come from the caller. No exchange rules, expiry adjustments, rates, broker executions, account activity, network calls, or order submissions are inferred or created. This closes the implementation gap for a post-run portfolio settlement reconciliation; G2 source validation and operational evidence remain open.

## Commands and prerequisites

Build with JDK 21 using `build.ps1`. Preserve the exact resulting JAR and original replay inputs. Semantic verification requires the same replay build identity, as enforced by the existing deterministic replay verifier, and the exact audit JAR bytes. An old replay made by a different build fails closed; this command does not override its identity or claim cross-build equivalence. Artifact-only verification works without original inputs or the original build.

```powershell
$jar = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar'
.\portfolio-settlement-audit.ps1 -JarPath $jar audit .\portfolio-runs\<replay-id> .\portfolio-inputs.csv .\portfolio.properties .\settlement-events.csv .\settlement-audits
.\portfolio-settlement-audit.ps1 -JarPath $jar audit .\portfolio-runs\<replay-id> .\portfolio-inputs.csv .\portfolio.properties .\settlement-events.csv .\settlement-audits .\settlement-costs.csv
.\portfolio-settlement-audit.ps1 -JarPath $jar verify .\settlement-audits\<audit-id> .\portfolio-runs\<replay-id> .\portfolio-inputs.csv .\portfolio.properties .\settlement-events.csv .\settlement-costs.csv
.\portfolio-settlement-audit.ps1 -JarPath $jar verify-artifact .\settlement-audits\<audit-id>
```

Omit the final cost argument in `verify` when no costs were used. Direct invocation is `java -cp $jar com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAuditCli` followed by the same mode and arguments. Exit code 0 means the requested verification completed; 1 means rejected inputs, mismatched reports, publication failure, or invalid arguments. A successful result remains diagnostic and performance-ineligible.

The replay input manifest, settings, bars, per-instrument run properties, dated references, calendars, and any indirect inputs retain their existing validation contract. Importantly, the existing single-contract dataset validator requires a legacy `settlement.file` when the final dataset session equals expiry. For such completed portfolio runs, preserve that file as part of the original validated input set. Portfolio replay does not apply its settlement, but its hash is already in replay provenance. The new audit does **not** use that legacy option underlying-close value to derive a payoff: it uses the separate `CASH_OPTION_PAYOFF` event. The test fixture demonstrates this distinction. No prerequisite is bypassed and no replay outputs are rewritten.

## Settlement events

Exact ordered header:

```csv
instrument_id,expiry,settlement_date,settlement_method,settlement_value_per_unit_inr,source_id,source_reference
```

Illustrative caller-owned rows, with synthetic IDs and dates (not an exchange settlement assertion):

```csv
instrument_id,expiry,settlement_date,settlement_method,settlement_value_per_unit_inr,source_id,source_reference
SYN-FUT,2030-05-06,2030-05-06,CASH_FUTURES_FINAL_PRICE,106,fixture,synthetic future final cash price
SYN-OPTION,2030-05-06,2030-05-06,CASH_OPTION_PAYOFF,0,fixture,synthetic caller cash payoff
```

| Column | Contract |
| --- | --- |
| `instrument_id` | Exactly one ID from the validated portfolio. IDs identify the full dated contract, including kind, expiry, strike and right where applicable. One event maximum per instrument. |
| `expiry` | Exact `yyyy-MM-dd` date from that contract's validated bars/reference. |
| `settlement_date` | Caller date in the inclusive interval `[expiry, final replay session date]`. No holiday or settlement lag rule is inferred. |
| `settlement_method` | `CASH_FUTURES_FINAL_PRICE` for FUTURE or `CASH_OPTION_PAYOFF` for OPTION only. |
| `settlement_value_per_unit_inr` | Positive futures final cash price or non-negative option cash payoff, in INR per replay unit. Zero option payoff is supported for long and short positions. |
| `source_id` | Non-empty logical caller ID. |
| `source_reference` | Non-empty caller attribution, which may be a document reference or URL. Recorded verbatim after CSV unescaping; never fetched or authenticated. |

Logical IDs match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. All cells must be trimmed and free of control characters. These inputs use strict UTF-8, exact unquoted headers, ordinary CSV quoting/escaped quotes, and no blank rows, BOM, extra columns, multiline fields, or trailing malformed records. LF and CRLF are accepted, but their exact bytes yield different input identities. An optional final newline is accepted. Dates must be valid four-digit-year ISO dates. Numbers must be plain signed decimal text, without exponents, at most 12 fractional places, at most 64 characters, and magnitude below `10^30`.

Every open position with expiry reached by the final replay session date needs exactly one matching event. Events for unknown contracts, wrong expiry/kind/method/date, already-flat positions, or future expiry are rejected, as are duplicates and missing or unused events. A header-only file is valid when all expired positions are flat or all open positions are unexpired. Non-expiring open positions remain marked and retain their signed units in the report.

The cutoff is the synchronized frame's validated **session date**, not the UTC date or today's date. The mark is the final completed BAR_START bar's close. Its timestamp must equal final bar start plus the declared interval and the final snapshot must be BAR_CLOSE. No future price, underlying-derived option payoff, intrabar exercise rule, or settlement timestamp is introduced. In the current validated dataset model bars cannot be after contract expiry, so a reached expiry ordinarily equals the final session date; a later settlement-date event cannot extend this replay's cutoff. Missing or mismatched marks and positions fail closed.

## Optional dated settlement cost schedule

This audit reuses `SettlementCostSchedule.load` and its non-overlapping, inclusive date intervals and source-attributed terms. Exact header:

```csv
effective_from,effective_through,contract_kind,component_code,basis,rate,gst_bps,gst_on_charge,source_reference
```

Illustrative terms only; rates below are arbitrary fixture values:

```csv
effective_from,effective_through,contract_kind,component_code,basis,rate,gst_bps,gst_on_charge,source_reference
2030-05-06,2030-05-06,FUTURE,FIXTURE_FIXED,FIXED_PER_SETTLEMENT,0.005,1800,true,fixture
2030-05-06,2030-05-06,OPTION,FIXTURE_UNITS,PER_SETTLED_UNIT,0.5,0,false,fixture
```

`effective_from` is required; blank `effective_through` means unbounded. Both endpoints are inclusive (unlike the portfolio execution-cost schedule's half-open convention). Reversed ranges and overlap for the same contract-kind/component pair are rejected. Components match `[A-Z0-9][A-Z0-9_]{0,63}`. Kinds are FUTURE/OPTION. Rates and GST bps must be non-negative, obey the decimal limits above, and `gst_on_charge` must be exactly `true` or `false`. Cost `source_reference` follows the established schedule's logical-reference restriction: it cannot contain `/`, `\`, `?`, `#`, `@`, or be `.`/`..`; use a caller ID for a separately retained document. No rate or GST treatment is supplied by the program.

For every settled event, **each component declared anywhere in the supplied schedule for that contract kind** must have one covering entry on the settlement date. Missing kind coverage or a gap for even one declared component rejects the audit. This checks completeness against the caller's declared component set, not legal completeness. Represent a deliberately inactive component with an explicit dated zero rate. Unused historical/future schedule rows and rows for another kind are permitted and hashed; supplying a schedule when no positions settle is rejected.

Supported bases:

| Basis | Calculation base | Unrounded charge |
| --- | --- | --- |
| `FIXED_PER_SETTLEMENT` | 1 for this non-flat event | base × rate |
| `PER_SETTLED_UNIT` | absolute signed position units | base × rate |
| `BPS_OF_SETTLEMENT_NOTIONAL` | absolute units × supplied cash value per unit | base × rate / 10,000 |

Option notional in this audit means caller cash payoff, not underlying value. `PER_EXERCISED_OPTION_UNIT` and `BPS_OF_OPTION_INTRINSIC` are rejected throughout the supplied schedule: their semantics would require exercise/intrinsic assumptions that this audit does not make. Compatible future calculations are checked against the existing calculator. The audit adapts the calculation to long signed-unit magnitudes and zero option payoff without modifying the shared schedule. It does not load ordinary execution schedules as settlement costs or add exercise STT/SEBI rules automatically.

Bps division uses scale 12, HALF_UP. Round each charge to scale 2, HALF_UP. If `gst_on_charge=true`, the GST taxable base is that rounded charge; otherwise it is zero. GST = taxable base × supplied GST bps / 10,000 at scale 12 HALF_UP, then round to scale 2 HALF_UP. Sum rounded charge + rounded GST for each component, then sum components. Cost lines record inputs, effective dates, absolute units, calculation base, rate, multiplication product, pre-paise charge, rounded charge, GST flag/rate/base/product, pre-paise GST, rounded GST, line total and source reference. `charge_product_inr` and `gst_product_inr` are the multiplication intermediates before bps division (the rate determines their dimensional interpretation). Decimal display strips insignificant trailing zeros without changing values.

## Accounting and reconciliation

Let `q` be final signed replay units, `m` the final close mark, and `s` the supplied per-unit cash value:

```text
difference per unit = s - m
signed marked value = q * m
signed cash settlement value = q * s
settlement adjustment = q * (s - m)
instrument final net P&L = instrument replay net P&L + adjustment - settlement costs
portfolio final net P&L = replay final net P&L + sum(adjustments) - sum(settlement costs)
```

For a short future with `q=-2`, mark 104 and cash price 106, adjustment is `-4`. For a long option with `q=2`, premium mark 14 and supplied cash payoff 0, adjustment is `-28`. If replay portfolio net is 12 and settlement costs are 2.26, final net is `12 - 4 - 28 - 2.26 = -22.26`. These are arithmetic examples, not approved trading assumptions.

The independent execution cash-flow check starts at zero, subtracts `signed execution units × execution price` for each execution, and separately sums execution fees. Before settlement, `cash flow + signed marked value - execution fees` must match each replay position's realized plus unrealized P&L less fees, and summed positions must match the replay snapshot aggregates. Signed units are independently accumulated with exact long arithmetic. After settlement, `cash flow + signed cash settlement value - execution fees - settlement costs` must equal the adjustment formula. Flat/unexpired rows use their existing mark instead. Settlement reports transfer settled gross P&L to post-settlement realized gross P&L and show zero post-settlement unrealized P&L/position; unexpired rows retain both. This is a report accounting adjustment, not a fake execution, order, broker cash receipt, or mutation of the replay ledger.

P&L arithmetic uses exact `BigDecimal` addition/subtraction/multiplication. The audit does not compute P&L from rounded average entry prices or round settlement adjustments to paise. The existing ledger's basis-allocation/average-display conventions remain unchanged. Signed position magnitude `Long.MIN_VALUE`, long addition overflow, monetary intermediate magnitude `>=10^30`, precision above 64 digits, or scale outside `[-30,24]` rejects rather than wraps or silently rounds. Missing marks and any final replay/settlement reconciliation mismatch reject publication.

## Sidecar files, identities and verification

Output is a separate `<output-root>/<SHA256-of-manifest>/` directory. Publishing inside the saved replay folder, including a resolved alias, is rejected. All calculations and verifications finish before staging; files are written with CREATE_NEW in a sibling temporary directory, verified there, and renamed with ATOMIC_MOVE. There is no non-atomic fallback. Same-ID publication is guarded by an exclusive file lock; an existing destination is integrity-verified and reused. Failed staging is cleaned; lock files remain in the output root for safe future lock reuse. This is atomic local publication, not a power-loss durability or hostile concurrent-writer guarantee.

Fixed layout:

- `settlements.csv`: one sorted row per instrument, including full dated contract identity, final session/mark timestamp/price, status, event value/method/dates/sources, before/settled/after signed units, execution cash flows, signed marked/settlement values, replay realized/unrealized P&L and execution fees/net, per-unit difference, adjustment, settlement costs, post-settlement realized/unrealized P&L and final net.
- `settlement-cost-lines.csv`: present only when costs were supplied; sorted instrument rows and schedule component order, with the inputs/intermediates described above.
- `inputs.csv`: sorted `input,sha256` bindings for exact portfolio manifest/settings, replay manifest and outputs, replay/dataset/frame identity, all validated indirect input hashes, event file bytes, optional cost bytes, and audit build artifact.
- `summary.properties`: fixed ordered diagnostic flags, replay and audit build IDs, exact build/event/cost hashes, final session date, instrument/settlement counts, replay final net, adjustments, settlement costs, and final net. Omitted costs show `settlement_cost_status=NOT_MODELED` with a zero arithmetic deduction; that zero does not establish absence of actual charges.
- `settlement-manifest.properties`: exact LF UTF-8 rows, no comments or blanks. First `schema_version=1`, then `classification=OFFLINE_PORTFOLIO_SETTLEMENT_AUDIT`, then `settlement_costs_enabled=true|false`, then SHA-256 rows in this order: settlements, optional cost lines, inputs, summary. The folder name hashes the manifest itself.

The build commit is read from the JAR's established `Build-Commit` field. The complete JAR bytes are additionally hashed; unpackaged tests bind sorted relative class-file paths and hashes, and report the established `unknown` commit when no manifest is available. Preserve the original JAR for semantic verification: a repackaged JAR can have different bytes even at the same source commit.

`verify-artifact` checks the exact layout, non-symlink regular files, canonical ordered manifest, folder/manifest identity, and every file hash. It does not reconstruct arithmetic, authenticate sources, or prove who published the report. `verify` additionally validates and reconstructs the original replay, byte-compares regenerated replay outputs, recomputes settlement, checks that inputs did not change during preparation, and reproduces **every sidecar file including the manifest byte-for-byte**. A maliciously altered report with recomputed hashes may pass integrity verification but fails semantic verification against the preserved originals. A caller can still supply incorrect but internally consistent source values; neither mode authenticates them. Preserve a trusted hash anchor and review authorized source documents separately.

Focused checks: `.\test-portfolio-settlement-audit.ps1`; optionally `-JarPath $jar` to check the packaged build. Fixtures include long/short futures/options, partial closes/reversals, zero option payoff, unexpired/flat positions, required coverage, rounding compatibility, overflow, strict malformed rows, indirect replay input changes, saved-run/report tampering, rehashed false reports, all CLI modes, idempotent publication, and unchanged replay bytes. No exchange/broker reconciliation, licensed feed, real settlement evidence, or portfolio trial statistics are claimed.
