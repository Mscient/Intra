# G2 source and data research — 2026-11-05

**Task 5 research return: DR-01–DR-11 and DR-13.** Twelve separate briefs, prepared by split Codex researchers, with evidence grades, document pinpoints/dates, implementation consequences and exact owner-input lists. DR-12 and DR-14–DR-17 were not assigned in this task.

The public-source research and blocked-case protocols are delivered. **G2 is still open.** Missing raw evidence, licensed-use terms, account facts and inaccessible amendments are recorded as blockers; none was guessed or acquired. These reports are candidates for independent source/rights/compliance review, not legal approval or evidence that a simulator is ready for market use.

## Report index

| Brief | Return | What remains blocked |
|---|---|---|
| [DR-01 — exchange/contract rules](DR-01.md) | [14-row rule-event timeline](DR-01-regimes.csv); cohort-specific lot/expiry changes and dated go-live notices | Detailed live CAS/pre-open sources, dated masters, full holiday/settlement/amendment coverage |
| [DR-02 — SmartAPI capabilities](DR-02.md) | Endpoint/field/capability matrices; SDK versus documentation distinctions | Current formal schemas/quotas, timestamp/replay guarantees, account-product and data-use terms |
| [DR-03 — candle gap](DR-03.md) | Reproducible comparison protocol and non-repair policy | Authorized original requests/responses, hashes, comparison samples, time semantics and cause |
| [DR-04 — data and licensing](DR-04.md) | Official product/rights comparison, minimum corpus and manifest | Product-specific simulator/retention/derived-use grants; no market dataset acquired |
| [DR-05 — compliance](DR-05.md) | Dated SEBI/NSE lineage, OPS/access/tagging obligations and decision tree | Current inaccessible consolidation, exact counting edges and account-specific interpretation |
| [DR-06 — costs](DR-06.md) | [20 candidate cost rows](DR-06-cost-candidates.csv), synthetic arithmetic, event/base/side conflicts | Actual tariff, invoice rounding/aggregation, disputed expiry brokerage and exact exercise turnover |
| [DR-07 — margin](DR-07.md) | [8 method rows](DR-07-method-candidates.csv); exchange/client/broker distinctions | Authorized historical parameter corpus, exact as-of snapshots, portfolio model and broker terms |
| [DR-08 — order mechanics](DR-08.md) | Lifecycle/priority/model boundary and execution test vectors | Route-specific mechanics, licensed event history and calibrated queue/latency evidence |
| [DR-09 — option inputs](DR-09.md) | Convention/input schema, Greek units, quote eligibility and model checks | Contract settlement wording conflict, synchronized authorized inputs, expiry/curve/carry choices |
| [DR-10 — clock/feed/coverage](DR-10.md) | Measurement dictionary, clock/sequence/coverage methodology | Feed guarantees, clock quality evidence, licensed timestamp fixture and publication expectations |
| [DR-11 — recovery](DR-11.md) | SAFE-state runbook, failure matrix, drill cards, daily evidence schema | Independent reconciliation sources, operational controls, owner limits/roles, actual paper drills |
| [DR-13 — risk-gateway review](DR-13.md) | Current-code gap review, layers, lifecycle/reservations, formulas and failure vectors | Independent process/source/clearance integration, full event/model coverage and owner policy |

The [consolidated owner-input checklist](OWNER-INPUTS.md) is a handoff index; each report contains the exact topic-specific fields and questions. The [review record](REVIEW.md) states what was reviewed and what remains unapproved.

## Findings that change the handoff

- Primary go-live notices establish CAS-related derivative changes on **2026-08-03** and revised derivatives pre-open on **2026-09-07**. Those notices also contain mock schedules, which must not be copied as normal trading hours. Detailed referenced circulars remain unavailable. [FAOP75472 p1](https://nsearchives.nseindia.com/content/circulars/FAOP75472.pdf), [FAOP76186 p1](https://nsearchives.nseindia.com/content/circulars/FAOP76186.pdf).
- The **2026-03-01** transaction/IPFT split and **2026-04-01** STT change require separate dated components. Correct total arithmetic does not validate an account's aggregation, expiry treatment or invoice. [FA73061 rate table](https://nsearchives.nseindia.com/content/circulars/FA73061.pdf), [FATAX73524 pp1–2](https://nsearchives.nseindia.com/content/circulars/FATAX73524.pdf).
- The current NSE historical product specification is **v1.19**, dated 2026-08-03. Older v1.17 findings need version scope; archive timestamp interpretation still has a GMT/constant conflict. NSE's product label “Level 3” means aggregated depth in the reviewed offering, not automatically order-level queue evidence. See DR-04/08/10.
- Public availability does not establish simulator data rights. NSE Clearing website terms explicitly restrict simulation-related use and automated collection; a separate applicable permission is needed before using website data in a simulator. This task acquired no SPAN/ELM or market-data files. [Terms, clauses 5 and 10](https://www.nseclearing.in/terms-conditions-0).
- The reported candle gap remains an unverified raw-data case with **unknown cause**. CAS time overlap, a checksum, or plausible missing-label arithmetic cannot establish causation or completeness.
- Recovery and risk libraries exist, but independent external reconciliation, process isolation, complete lifecycle/correction/settlement accounting and actual operational drills remain separate evidence requirements. Concurrent implementation work was not changed by this research.

## Source-review gate before any production use

Every machine-readable rule/cost/method row is **pending review and `production_eligible=false`**. Their schemas intentionally describe research candidates and are not production registry schemas. Unknown effective-through dates do not mean a rule is approved indefinitely. DR-01 records contract-generation/cohort events; DR-06 uses inclusive date bounds; production schedules may use different boundaries and require explicit conversion plus boundary checks.

Before promotion, a qualified reviewer records exact claim/field, issuer and inspected document, publication/effective dates, segment/product/account scope, pinpoint, amendments/corrections, unresolved conflicts, rights basis, permitted use and reviewer/date. The owner supplies account and policy inputs. Only an accepted, scope-complete record may feed a separately reviewed registry change and sourced boundary fixtures. A parser pass or `VERIFIED` caller label cannot perform that review.

**Hashes prove file identity, not source authenticity, legal completeness, permission, market-data completeness or human approval.** The local source identity table binds code observations; the report manifest binds this report pack. Neither is an external trust anchor. No source PDFs, private terms, credentials or raw market datasets are bundled.

These are local research artifacts. No production Java/registry edit, live feed, authenticated API request, order, broker contact, data acquisition, spend, commit or push was performed by this task. No performance or gate-closure claim is made.
