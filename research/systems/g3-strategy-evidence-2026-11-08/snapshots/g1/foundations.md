# S1 / G1 foundation increment

Version 1, prepared 2026-11-08. **G0 OPEN. G1 INCOMPLETE.** This delivers preparatory research and offline specification tooling that do not depend on owner capital, identity, route, source permission or strategy selection. It does not complete the dependent gate. No profitability, trading-readiness or legal-compliance conclusion follows.

The clean branch started at freshly fetched `origin/main` **ff48741**. Existing unrelated dirty main and other worktrees were preserved; no uncommitted G0 material was promoted. Main's `docs/G0-CHARTER-OPEN-ITEMS.md` explicitly says Open, with unresolved responsible operator/reviewer/stop authority, route/current terms, data permissions/history, universe, current rules/limits, private budgets and data readiness. No signed charter, owner A1–A15 confirmation or current complete review record closes those criteria. Implemented risk/permission/testing software is not that missing evidence.

## Reviewable outputs

- [Learning pack](G1-LEARNING-PACK-2026-11-08.md): microstructure/book glossary and exercise, derivatives/pricer limits, statistics/design; reading/comprehension/not-covered sections. Human completion unclaimed.
- [Primary source register](g1-foundations-2026-11-08/source-register-v1.md): 19 dated records with access failures and exact reading boundaries.
- [Instrument/data comparison](G1-INSTRUMENT-COMPARISON-2026-11-08.md): five candidates, fields/clocks/reference/history, permission actions, costs and specific dispositions. No market source currently eligible.
- [Cost-wall equations](G1-COST-WALL-V1.md) and [G1-COST/v1 component register](g1-foundations-2026-11-08/cost-model-v1.csv): 24 itemized rows; actual amounts blank, known public rate references separated from unknowns. No numeric break-even claim.
- [G1-S01 v1](g1-foundations-2026-11-08/g1-s01-v1.card.json), [G1-S02 v1](g1-foundations-2026-11-08/g1-s02-v1.card.json), [G1-S03 v1](g1-foundations-2026-11-08/g1-s03-v1.card.json): full strictly validated authored specifications, no freeze or approval timestamps.
- [Variant/exposure ledger](g1-foundations-2026-11-08/variant-ledger-v1.md), [registry/schema/CLI guide](../../Intra-Paper-System/docs/HYPOTHESIS-CARD-REGISTRY.md), [owner/reviewer checklist](G1-OWNER-REVIEW-CHECKLIST.md), and [validation/session record](../sessions/2026-11-08-g1-foundations.md).

S01 is **PROPOSED_PENDING_OWNER_SELECTION**, at most one proposed primary. Its priority is an authorship judgment about explaining/auditing a one-event-per-session baseline, not an owner choice or ranking from results. S02 and S03 are **DEFERRED**, with their fixed rules and shared three-rule Wave A family still disclosed. All are index-signal specifications, no executable P&L. Existing Wave A requires reporting all three if that protocol is used; any owner-approved change requires a new version/family and untouched periods. The final plan's queue-aware simulator/paper candidate and slower RV study remain later requirements; this signal-only proposal does not replace or approve them.

## Inspected components and reuse

Paths below are relative to `Intra-Paper-System/src/main/java/com/mscient/intrapaper/` unless shown otherwise.

| Existing component/path | Reuse and verified boundary |
| --- | --- |
| `book/ToyLimitOrderBook.java`; `docs/TOY-ORDERBOOK.md`; `book/CallAuctionEngine.java` | Reuse toy integer-tick FIFO/partials/cancels/missed IOC for learning; generic auction separate. No new book/emulator or exchange calibration. |
| `pricing/EuropeanOptionPricer.java`, `Black76OptionPricer.java`, `OptionPricingDiagnostics.java`; `validate-option-pricers.ps1`; R11 archive | Reuse theoretical valuation/Greeks and existing independent numerical validation; numerical agreement does not validate market inputs/returns. |
| `simulation/Strategy.java`, `SmaCrossoverStrategy.java`; `simulation/portfolio/PortfolioStrategy.java`, `PortfolioSmaCrossoverStrategy.java`; Wave A spec | Existing SMA implementations are harnesses. Preserve existing signal rules; no new alpha/execution implementation or parameter search. |
| `reference/InstrumentReference.java`, `InstrumentMasterSnapshot.java`, `DatedInstrumentMappingRegistry.java`; `data/ValidatedDataset.java` | Existing dated identity, registry, calendar/dataset/provenance conventions inform card requirements; no replacement reference service. |
| `permission/preflight`, `permission/coverage`; their docs/schemas/guarded callback | Reuse explicit intended-use and evidence-scope checks later with actual records. Their synthetic PASS is not permission or global legacy-call interception. |
| `evaluation/ResearchTrialLedger.java`, `ResearchOutcomeLedger.java`, `EvaluationSplitPlan.java` | Preserve exact eight-field card/data/split/parameter registration and outcome chain; no second trial or outcome ledger. |
| `evaluation/portfolio/PortfolioTrialIdentity.java`, `PortfolioTrialEvaluationAnalyzer.java`, catalog/evaluation CLI; `evaluation/SessionReturnStatistics.java`; HAC protocol | Existing common family, artifact verification, DSR/PBO/HAC definitions unchanged. Card validator's optional binding does not score, grant eligibility or touch holdout. |
| `provenance/CostSchedule.java`, `ComponentCostSchedule.java`, `SettlementCostSchedule.java`; `simulation/portfolio/PortfolioExecutionCostSchedule.java`, `PortfolioSettlementCostSchedule.java` | Reuse runtime dated cost/accounting conventions. New planning CSV cannot be loaded as an actual tariff; blanks not replaced by zero. |
| `simulation/QuoteExecutionBook.java`; portfolio fill models/artifact verifier; `audit/feed`; risk/ops docs and selected CI | Preserve conservative model limits, caller-supplied evidence, existing risk/recovery/permission/feed checks and commit-identified package build. |

The real gap was the arbitrary card-hash input beside a draft Markdown template: no strict complete card schema or version snapshot guard. The small `evaluation/hypothesis` package fills that gap and binds companion bytes. It neither globally enforces owner truth nor changes existing research/trading behavior. Proposed or synthetic cards are refused by its optional trial-binding check; every successful report remains ineligible.

## Exact blockers and next evidence

| Blocker | Gate / next required evidence |
| --- | --- |
| B00 signed charter/assumptions absent | G0: actual owner confirms all A1–A15 with dispositions; dated signature/review and stop authority record. Illustrative PDF values cannot confirm them. |
| B01 operator, stop/release authority, incident contact, independent reviewer | G0: privately held identities/authority and reviewer acceptance; public record only status/opaque ID if approved. AI is not independent review. |
| B02 own-account route/current rule review | G0: owner route confirmation plus primary SEBI/NSE amendment chain and broker/account API/static-IP/order-type/rate/OTR/retention terms reviewed for intended scope. No connectivity/order test authorized. |
| B03 data rights | G0/G1/G2: actual terms for each source/product, collection/storage/backups/replay/non-display/derived/sharing/deletion and qualified owner/reviewer scope review. No rights inferred from API access or public pages. |
| B04 initial universe/scope | G0/G1: confirm or revise Nifty-first A11 and signal-only versus later traded-instrument scope. Future live universe is a G3 output. |
| B05 actual private capital/budgets/limits/manual stop path | G0/G1: actual private A15/D40 precommitment and reviewed control derivation, without numbers in Git/chat; separate signed non-sensitive attestation only when provided. |
| B06 targeted owner learning | G1: human explains signal/cost wall/data limits/rejection evidence, completes comprehension checklists and reviewer acknowledgment. Pack delivery/tests are not completed training. |
| B07 strategy/card selection and independent review | G1: owner chooses at most one primary, reviews mechanism/benchmark/full variants/history/cost/rejection; real freeze record in new version and independent custody/timing review. Current cards unapproved. |
| B08 date-sensitive cost/account economics | G1 comparison/G2/G3 economics: current amendment review, private tariff/invoice/rounding/GST/clearing/settlement basis, margin/funding and operating/time allocation. Reference rates alone insufficient. |
| B09 timestamps/session/history | G1 feasibility/G2: authorized complete index/contract series, verified bar availability, dated calendars/CAS/expiry/lot/tick/master, closing-gap explanation without repair, history/roll/survivorship exclusions. |
| B10 sample/splits/variant exposure | G1 design/G2 execution: owner/reviewer exposure audit, complete trial count or disclosed unknown, adequate development-only precision and frozen chronological boundaries/seed before labels; unused holdout custody. |
| B11 execution evidence | G2/G3: authorized bid/ask/trade/depth, measured relevant latency, whole-lot partial/missed/failed-exit treatment, impact/adverse-selection and capacity evidence. Snapshot queue claims remain unsupported. |

G1 closure requires genuinely closed G0, all owner/reviewer/learning/A15 decisions, and one frozen card with the plan's required content. G2 data evidence remains a prerequisite for actual experiments/economic validation; `PENDING_G2_DATA` split text is an honest G1 dependency, not permission to inspect holdout. The blocker list distinguishes gate closure from future experiment readiness. Passing synthetic/software/CI checks resolves none of these owner/source/market evidence gaps.
