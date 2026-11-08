# Declared candidate/variant family — G1-VARIANTS/v1

Prepared 2026-11-08; no new market experiment performed. **Prior experiment history UNKNOWN / independently unverified.** Earlier Wave A notes disclose partial 2026-11-02 smoke results and a 2026-10-30 closing timestamp gap. We have not treated these as untouched development or validation, nor inferred a complete trial count. Owner/reviewer must audit earlier exposure and select new untouched periods before a freeze. SMA/pricer/toy/portfolio synthetic configurations are engineering fixtures, not statistical discoveries; their total historic configurations are not asserted.

| Family / candidate | Exact baseline or status | Declared variations / disposition |
| --- | --- | --- |
| WAVEA3_V1 / S01-OR15-H15 | First close outside first 15 complete minute high/low; at most one signal/session; 15-minute primary | G1-S01 v1 proposed first for review, **PROPOSED_PENDING_OWNER_SELECTION**. No range/lookback/threshold grid. |
| WAVEA3_V1 / S02-M30-C15-H15 | Sign of 30-minute close log return at 15-minute cadence; 15-minute primary | G1-S02 v1 DEFERRED pending owner selection; no volatility filter. |
| WAVEA3_V1 / S03-GAP15-H15 | First-15-minute move opposite nonzero overnight gap; one signal; 15-minute primary | G1-S03 v1 DEFERRED pending owner selection; no gap-size filter. |
| WAVEA3_V1 secondary diagnostics | Each of three fixed rules at 5- and 30-minute horizon | Six descriptive horizon outputs, not six additional primary winners. Time-of-day/tails/hit rate descriptive. If promoted to decision tests, new declared family and multiplicity review first. |
| WAVEA3_V1 inference sensitivity | Stationary bootstrap mean block lengths 2/5/10; 10,000 resamples and recorded seed | Five is existing primary design; two/ten are diagnostics. Three rules × validation/holdout = **six confirmatory comparisons** in existing Wave A, Bonferroni 5% familywise proposal. Never discard rules after outcomes. |
| S04 VWAP deviation | Threshold/regime/entry/exit UNRESOLVED | Rejected on index volume; future traded instrument requires new owner-approved card. No grid specified or authorized. |
| S05 spot/futures RV | Carry/basis model, executable hedge, threshold/exit UNRESOLVED | Deferred slower RV archetype in final plan. Index lead-lag may be diagnostic, never cash-leg arbitrage. Separate future card required. |
| S06 schedules | Parent orders, TWAP/VWAP deadlines/sizes UNRESOLVED | Execution study deferred; no alpha selection. |
| S07 pairs/RV | Pair/hedge/threshold/relationship-break rule UNRESOLVED | Deferred; no pair/universe selected. |
| S08 OFI | Event feature/horizon/freshness UNRESOLVED | Event data missing; snapshot proxy would need distinct card. |
| S09 queue-aware MM | Reservation/quote/inventory/queue/latency rules UNRESOLVED | Planned engineering simulator/paper candidate; existing toy teaches matching only. Queue-dependent market evidence not testable. No live selection. |
| S10 delta-hedged options | Surface/forecast/hedge/sizing/expiry UNRESOLVED | Deferred; no sourced synchronized premium/hedge archive. |
| SMA single/portfolio harness | Existing code strategies/configuration fixtures | Explicitly not G1 economic hypothesis. No count of past market searches asserted. |
| Universe alternatives | C_INDEX, C_FUT, C_OPT_CALL, C_OPT_PUT, C_SYNTHETIC | All recorded in comparison. Nifty-first assumption; derivatives defer, synthetic not market. No holdout-driven universe selection. |
| G1-COST/v1 K0/K1/K2/K3 | Documentary-fee, full-fill, partial/missed, stressed-economics scenarios | Four execution/economic sensitivities for later *separate* traded-instrument card. Magnitudes unknown; changing execution/filter based on results counts as another search. |

Three authored signal cards, three baseline variants, six primary confirmatory comparisons, six descriptive horizon outputs and four planned cost scenarios are **declarations**, not the total number of historical experiments. Mechanisms/primary priority are research judgments without market effect estimates. The final plan still requires a queue-aware simulator/paper candidate and one slower RV study at later gates; the proposed signal card does not substitute for those requirements or nominate a live strategy.

Exact split dates, sample floor/precision, random seed, independent preregistration and all human decisions remain pending. If owner elects to depart from the existing Wave A three-rule protocol, record a new card/version/family and untouched evaluation periods before outcomes; do not retroactively relabel an observed winner as the sole preregistered hypothesis.
