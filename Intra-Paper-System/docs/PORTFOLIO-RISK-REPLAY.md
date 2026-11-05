# Offline risk gateway adapter for portfolio replay

`simulation.portfolio.risk` connects the independent Task 2 `RiskGateway` to next-frame-open portfolio replay. `OfflinePortfolioRiskAdapter` owns a gateway and recovery transcript. `PortfolioRiskFillModel` gates a simulated IOC model and receives validated outcomes through `PortfolioReplayLifecycle`. `PortfolioRiskReplayRunner` publishes the replay and risk evidence together.

This is an offline integration with no network client, account connection or order-submission API. Acceptance creates only an internal reservation. All monetary values are INR. Supplied margin is a **caller-supplied per-contract proxy, not SPAN/ELM**. Results remain `REPLAY_DIAGNOSTIC_ONLY` and performance-ineligible.

## Evaluation contract

At each frame open, replay supplies the exact timestamp, session date, complete signed accounting positions (including zeroes), and dated lot sizes. The first frame's zeroes come from the new empty accounting ledger; the direct adapter API never replaces missing position keys with zeroes. A gateway contract equals one explicitly configured replay lot, with its lot size as multiplier. Unit quantities must divide exactly into whole contracts. Positions must match the gateway ledger; a mismatch freezes acceptance instead of overwriting state.

Risk input lookup requires the **exact execution-frame timestamp**. There is no previous-row lookup, nearest timestamp, bar-price substitution, zero margin, unlimited limit or price fallback. Every configured instrument needs a source-attributed mark and margin proxy. Both original as-of timestamps must be at or before the frame time and no older than the explicit maximum age. Missing, stale, future or inconsistent inputs reject increasing and reducing intents. Malformed input structure fails loading.

For each nonzero target delta, the adapter evaluates an `OrderIntent` immediately before invoking the fill model. Its evidence binds original strategy decision time, actual execution-frame time, current signed state, risk-input fingerprint and decision binding hash. Instruments execute in stable sorted order, so earlier fills and working reservations count against later proposals. There is no atomic multi-leg/netting assumption. A target already at its desired position creates no order. A target cancelled at a session boundary creates no reservation; that disposition stays in replay `decisions.csv`.

Rejection prevents the delegate fill model from running. The existing replay order schema reports this as `MISSED` with no execution; `risk-decisions.csv` distinguishes `accepted=false` from a risk-accepted IOC miss. Full fills consume their reservation, partial fills consume only filled contracts and then cancel the confirmed IOC residual, and confirmed misses cancel the full reservation. After each frame, positions must match accounting exactly and all IOC reservations must be gone.

A delegate exception, invalid fill or feedback failure aborts replay and records adapter uncertainty. Existing gateway reservations remain held. The uncertainty freeze is maintained alongside the gateway state in the adapter/checkpoint and blocks every new proposal. Task 2 has no native uncertainty event; direct use of its gateway would bypass this adapter's freeze. No uncertain result is fabricated as a cancellation or missed fill.

Gateway gross exposure, worst-case position intervals, margin proxies and daily-loss policies remain unchanged. Ordinary reducing orders still reserve additional gross and margin exposure. The strict closeout exception after a daily-loss halt may bypass gross/margin caps, but cannot reverse through zero and still needs complete valid inputs. Gateway daily P&L is **gross of replay fees**; its configured loss threshold is not a net-of-cost loss threshold.

## Explicit risk inputs

Use UTF-8 CSVs with exact headers, as shown in [the synthetic fixture](../examples/portfolio-risk-replay/).

| File | Required contract |
| --- | --- |
| `limits.csv` | Exactly one row: `currency,max_gross_notional_inr,max_margin_proxy_inr,max_daily_loss_inr,maximum_input_age_seconds`. Currency is INR; all limits and age are positive. |
| `instruments.csv` | `instrument_id,lot_size,max_abs_contracts`. Exact replay universe and positive whole lot sizes/caps. The replay's own lot caps also apply. |
| `samples.csv` | Exact frame/instrument rows, ISO session date, mark price/as-of/source/reference, margin-per-contract/as-of/source/reference, and side-specific risk price ceilings. See fixture header. |

Missing marks use all four mark fields blank; missing margin uses all four margin fields blank. They cause rejection. Partial provenance is malformed and fails loading. A blank side-specific ceiling produces gateway `MISSING_PRICE` when that side is proposed. Omitted frames or instruments also reject. Duplicate records and unknown instruments fail validation.

`buy_risk_price_ceiling` and `sell_risk_price_ceiling` are both upper bounds on the price used for risk valuation. They are passed to the gateway's `limitPrice` field for reservation valuation, **not broker limit-order instructions**. Both buy and sell simulated fills must be at or below the applicable ceiling; higher fills abort with uncertainty. The caller must explicitly allow for its chosen fill/slippage model. Sell fills below their ceiling are permitted.

Task 2 timestamps mark events at application time. The adapter applies a complete mark set at frame time only after independently validating the original caller as-of timestamps. Original times, source references, values, limits and raw-file hashes remain bound by the frozen risk-input fingerprint and are checked again each frame. This does not authenticate the source or prove when inputs were acquired.

## Standalone runner

From `Intra-Paper-System`, use JDK 21 on PATH:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac).Source)
$env:INTRA_PAPER_BUILD_DIR = Join-Path (Get-Location) 'build/task3-package'
.\build.ps1
$jar = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
$fixture = 'examples/portfolio-risk-replay'
.\portfolio-risk-replay.ps1 -JarPath $jar run `
  "$fixture/inputs.csv" "$fixture/portfolio.properties" `
  "$fixture/limits.csv" "$fixture/instruments.csv" "$fixture/samples.csv" output/risk-replay

# Substitute the bundle directory printed by run.
.\portfolio-risk-replay.ps1 -JarPath $jar verify-artifact "output/risk-replay/<bundle-id>"
.\portfolio-risk-replay.ps1 -JarPath $jar verify `
  "$fixture/inputs.csv" "$fixture/portfolio.properties" `
  "$fixture/limits.csv" "$fixture/instruments.csv" "$fixture/samples.csv" "output/risk-replay/<bundle-id>"
.\test-portfolio-risk-replay.ps1
.\test-portfolio-risk-replay.ps1 -JarPath $jar
```

The Java entry point is `com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskReplayRunner`; arguments after `-JarPath` are identical. `run` refuses overwrite. Use a new output root for another run, or `verify` to compare regeneration.

## Provenance bundle

The bundle directory name is SHA-256 of canonical `risk-manifest.properties`. Publication uses staging and atomic same-volume rename. It contains:

- `replay/<replay-id>/`: standard v3 replay artifacts. The fill-model identity includes risk-input fingerprint and delegate identity/parameters, so changes to risk values, provenance or input file bytes change the replay ID.
- `risk-decisions.csv`: one row per intent; accepted flag, reason, original decision and execution-frame timestamps, signed quantities, input/binding hashes and Base64 gateway pre-state/decision evidence. `ADAPTER_REJECTED` indicates invalid input consistency or uncertainty prevented gateway evaluation.
- `risk-events.csv`: lifecycle command sequence, full signed unit positions, active residual unit reservations, uncertainty, command hash and resulting gateway-state hash. Map cells encode sorted key/value pairs in Base64.
- `risk-checkpoint.txt`: deterministic, versioned command transcript including rejected proposals and uncertainty. No Java native object serialization is used.
- `risk-manifest.properties`: risk-input fingerprint, replay-manifest hash, margin-proxy label and hashes of the three evidence files.

`verify-artifact` checks layout, evidence hashes, replay artifacts and the risk fingerprint in replay provenance. `verify` additionally restores the checkpoint, compares recovered decisions/events, reruns the portfolio replay and compares every published byte. Use this runner for risk-enabled replays; ordinary `portfolio-replay-verify` cannot reconstruct the risk wrapper.

Retain original input CSVs, portfolio dependencies, matching build and expected hashes independently. The bundle does not copy risk inputs. Hashes detect mismatches but are not authenticated source signatures. Existing build identity rules apply; dirty builds may report `unknown-dirty`.

## Java integration and recovery

```java
PortfolioRiskInputs inputs = PortfolioRiskInputs.load(limits, instruments, samples);
OfflinePortfolioRiskAdapter risk = new OfflinePortfolioRiskAdapter(inputs);
var replay = PortfolioReplayCsvRunner.replay(portfolioInputs, portfolioSettings,
    model -> new PortfolioRiskFillModel(model, risk));
// Use PortfolioRiskReplayRunner.run(...) to publish the complete evidence bundle.
```

Create a fresh adapter for each complete replay. Custom callers use `beginFrame`, `evaluate`, incremental `execution`, confirmed `cancel`, and explicit `reconcile` in order. Execution/cancellation timestamps must equal the active frame timestamp. Repeated identical event IDs are no-ops, including after recovery; conflicting reuse throws. Retrying a decided intent returns its original decision and does not authorize another simulated execution.

`checkpoint()` returns bytes for caller-managed atomic persistence. Keep their SHA-256. `restore(inputs, bytes, expectedSha256)` rebuilds all state under the exact same input/configuration fingerprint, rejecting corruption, changed inputs, truncation, noncanonical/duplicate transcript entries and checkpoints above 64 MiB. This is an explicit checkpoint, not a per-event crash-durable journal. The runner restarts from frozen inputs; a risk checkpoint alone does not resume strategy/accounting state.

After uncertainty, retain reservations, apply only confirmed incremental fills/cancellations, then `reconcile` with complete signed positions and every remaining order's residual quantity. It must match exactly and cannot change positions or erase reservations. Successful explicit reconciliation clears the freeze while still-working reservations continue consuming risk. Ordinary after-frame assertions never clear uncertainty.

## Scope and acceptance checks

The initial integration requires `portfolio.settlement_policy=NONE`. Explicit final-session cash settlement is rejected before replay: Task 2 has no settlement/P&L-reset event, and cash settlement must not be fabricated as an order fill. Existing non-risk settlement replay remains available. The combined path needs a gateway settlement-event contract. Cross-session signed carry is supported with complete new-session marks and no unresolved reservations/uncertainty. Dated lot-size changes require a new explicitly configured replay.

The test runner covers increasing and signed reducing proposals, loss-halt closeouts, limits, partial/missed fills, duplicates/conflicts, uncertainty, recovery, corrupt checkpoints, stale/missing/future inputs, wrong frame/position/lot metadata, reservation reconciliation, sessions, gate-before-fill ordering, provenance and deterministic regeneration. It also runs existing replay, cost, artifact and settlement regression checks. All fixtures are synthetic pipeline tests, with no market-performance or live-readiness claim.
