# Owner-input and A1–A15 registers — version 1

All actual values below are **blank / unknown** because the human operator has not
supplied them. Decision status is **PENDING_OWNER_INPUT** for every owner-input row.
Do not send private amounts, account identifiers, credentials or private contacts
through a public issue, commit or tracked fixture. Use the guide's separate private
input directory. Statements subsequently supplied by a caller are OWNER_ATTESTED,
not independently verified. AI and test fixtures provide no human signature/review.

| Checker decision ID | Actual value | Exact owner decision / reason pending |
|---|---|---|
| operator | | Accountable human identity; keep private mapping from opaque ID. |
| stop_authority | | Person who can stop programme immediately, including absence/deputy policy. |
| resume_authority | | Person who can approve recovery/resume; no automatic restart authority. |
| human_reviewer | | Independent human with appropriate rules/rights/risk expertise and qualification evidence. |
| incident_contact | | Reachable incident role/contact and availability; private contact mapping. |
| own_capital_only | | Explicit own-capital-only statement; customer funds/advice/provider/family activity classification. |
| account_route_applicability | | Nonidentifying legal/account class, residence/jurisdiction, enabled segments, data-only vs order app, direct/self-coded/hosted/vendor category and current broker acceptance evidence. |
| risk_capital_definition | | Assets committed, exclusions, liquidity/encumbrance, valuation date, currency, refresh and liquidity buffer; no loss-permission implication. |
| risk_capital_value | | Actual privately held capital amount under that definition, not an example. |
| annual_spend_ceiling | | Actual maximum recurring 12-month cash spend, inclusions/taxes/subscriptions/cloud/static IP/data, approval and tracking rules. |
| live_loss_ceiling | | Independently chosen maximum live loss, measuring realised/unrealised/fees/withdrawals/profits, horizon and hard stop; G0 authorizes no use of this limit. |
| risk_capital_is_loss_ceiling | | Explicit NO; never treat available capital as acceptable loss. |
| currency | | Explicit currency; V1 supports INR with no inferred conversion. |
| budget_period | | Exact 12-month inclusive-start/exclusive-end programme period. |
| limits_are_actual | | Confirmation all privately entered figures are actual deliberate decisions, not illustrative placeholders; may explicitly choose zero without enabling activity. |
| strategy_scope | | Research/paper activity boundaries and excluded hypotheses/activity; no G1 selection in this work. |
| order_activity_scope | | Explicit NONE for frozen G0; any order route requires a separate future gate. |
| instruments_in | | Exact proposed research instruments/products and date scope; no assumed approval of Nifty. |
| instruments_out | | Exclusions: other venues/segments, memberships, customer funds, advice and live strategy activity as decided. |
| initial_universe | | Confirm/reject/replace Nifty-first plumbing assumption; distinguish index signals from dated tradable contracts. |
| access_path | | Proposed future read-only broker/product/hosting route, primary/standby, fallback/manual role; all evidence and permissions pending. |
| data_sources | | Source/product IDs for each intended use; broker entitlement alone insufficient. |
| data_purposes | | Each actual purpose: storage, replay, simulation, transformation, research/derived output as licensed. |
| data_storage | | Opaque IDs for every private compute/storage/backup location and jurisdiction/access policy. |
| data_retention | | Per-use retention `use_id:days`, expiry/termination/legal holds and evidence basis; no defaults. |
| data_sharing | | Parties/roles and scope of sharing, or explicit NONE; private mappings. |
| data_publication | | Raw/derived/code-fixture/report rights and restrictions, or explicit NONE. |
| data_deletion | | Expiry/termination/hold release process, originals/backups/derived deletion and evidence. |
| stop_triggers | | Measurable programme/spend/loss/data/rule/incident/absence triggers, detector, immediate action and record preservation. No copied PDF numerical thresholds. |
| escalation_authority | | Escalation decision owner, unavailable-person fallback and contact path. |
| recovery_requirements | | Root-cause resolution, incident closure, reconciliation, source refresh, intact provenance and independently reviewed drill evidence required before resume. |
| resume_conditions | | Exact human approval, recovery evidence and change-review/cooling requirements; no elapsed-time or reassuring-price automatic reset. |
| independent_review_completed | | Whether independent human has reviewed exact charter, decisions and source/right evidence; actual record missing. |
| ruleset_acceptance | | Reviewed applicable current rule/product document set and explicit unresolved reservations. |
| source_freshness_policy | | Review frequency/expiry/change monitoring, evidence owner and triggers for a new dated charter. |
| no_order_acknowledgement | | Explicit acknowledgement of no order/account/network authorization and absent order routing. |

A1–A15 derive from final-plan §11 and continuation §0.2. Historical values are
planning inputs; none confirms the operator's situation. Every assumption is also
a mandatory checker decision row, with reason and decision provenance.

| ID | Historical assumption / current G0 treatment | Status | Evidence or exact owner action missing |
|---|---|---|---|
| A1 | NSE Indian derivatives/equities/ETFs; US/CME comparators | UNRESOLVED | Owner confirms instruments/segments and excludes comparator market access; rights per product. |
| A2 | NSE primary, BSE secondary; Indian rules | UNRESOLVED | Jurisdiction/venue/route decision and applicable primary rules. No BSE route built. |
| A3 | Small proprietary team/capital illustrative range | UNRESOLVED | Actual operator/team, own-capital attestation and private capital definition/value; discard illustrative range as a limit. |
| A4 | Tier 3 route with later latency tiers as references | UNRESOLVED | Owner acknowledges planning tier and unavailable route measurement; no latency claim from offline timing. |
| A5 | Original C++/Rust assumption superseded by D42 Java 21 | CONFIRMED_WITH_EVIDENCE for implementation only | Build/source establishes Java 21 implementation; owner's technology acceptance remains pending. This local code evidence does not authenticate an owner statement. |
| A6 | Learn, paper, optional smallest live after gates | UNRESOLVED | Owner accepts stage/stop boundary; current no-order software boundary evidenced independently of future intent. |
| A7 | Historical rules/fees snapshot | UNRESOLVED | Current primary amendment chain/applicability and freshness decision; dates in PDFs not current clearance. |
| A8 | Own account, broker API, no customer funds/advice/membership/colocation/paid tick feed initially | UNRESOLVED | Owner confirms activity/legal/account/product scope and rights. No capital/access implied. |
| A9 | India static-IP VM plus standby, broker WebSocket | UNRESOLVED | Owner chooses hosting/budget; broker confirms app/IP/standby; licensors approve capture/storage/replay/backup. |
| A10 | Human plus AI; human controls credentials/capital/release | UNRESOLVED | Human operator, deputy/escalation, stop/resume and independent reviewer assigned; AI cannot be the reviewer. |
| A11 | Nifty futures/options first; later other instruments | UNRESOLVED | Nifty-first retained only as planning assumption until owner selects research scope; live universe deferred to G3. |
| A12 | Historical policy/OTR/AI/source snapshot | UNRESOLVED | Current watchlist/source ownership and complete applicability review; no absent-search-result claim. |
| A13 | Java/CS skills, market/options/statistics learning | UNRESOLVED | Operator attests skills/learning needs and review support; no degree/competence inferred from repository. |
| A14 | Illustrative weekly time and stage calendar | UNRESOLVED | Actual time/availability and incident coverage; calendar never lowers evidence gates. |
| A15 | Illustrative capital, recurring-spend and live-loss amounts | UNRESOLVED | All three actual private decisions, horizon/accounting definitions and stop authority. This task requires G0 inputs now, overriding older G1 deferral. |

Risk capital describes the committed resource base, with a precise valuation and
liquidity definition. Annual recurring spend caps programme cash outgo for a defined
year. Live-loss ceiling caps tolerated trading loss independently. Capital is not
acceptable loss; spend is not trading loss; matching illustrative amounts do not
equate their purposes. Actual values remain blank until the operator deliberately
supplies them privately. The historical PDF amounts are not copied into actual
inputs. An explicit actual zero is a human decision, not a missing-value default.
G0 remains offline even after any budgets are entered.

The owner must define whether P&L, costs, unrealised loss, withdrawals/profit offsets,
margin/liquidity, earlier losses, funding shocks and programme cash consumption
affect each stop. No automatic top-up or guessed numeric limit exists here. The
checker compares explicit declarations; it computes no trading risk or margin.

Independent sign-off remains **PENDING_OWNER_INPUT**: identity and qualification
evidence, independently authenticated contact/role, independence from operator,
exact charter/source/right digests, dated review decision/reservations and stop/resume
acknowledgements. V1 only represents unverified caller attestations. No configured
independent verification key exists and no signature is claimed verified.
