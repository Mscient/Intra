# S0 / G0 charter pack — version 1, reviewed 8 November 2026

**G0 is OPEN / INCOMPLETE.** The research, checker and synthetic validation deliverable
does not supply the owner's decisions, executed rights, authenticated independent
review or broker-specific applicability evidence. No market/account access, purchase,
data acquisition or live activity is authorized by this pack or the historical PDFs.

This version freezes the software boundary to Java 21 offline research and paper
diagnostics. Order routing is absent; account/network access, spending and live
authorization are disabled. G1 strategy selection, G2 data/simulator development,
broker adapters, feed connection and order submission are excluded from this change.
The owner has not yet frozen an actual programme charter. Nifty-first remains a
planning assumption; no contract, date range, account or future live universe is
selected on the owner's behalf. Angel One SmartAPI is a proposed future read-only
path, contingent on explicit rights and route review; it is not connected here.

Read the [dated sources and applicability matrix](SOURCES-AND-REQUIREMENTS.md),
[A1–A15 and owner decisions](OWNER-AND-ASSUMPTIONS.md), [decision/evidence gaps](DECISIONS.md),
[software guide](../../../Intra-Paper-System/docs/G0-CHARTER-CHECKER.md), and
[validation record](VALIDATION.md). The normative checker vocabulary is
[G0Schema.java](../../../Intra-Paper-System/src/main/java/com/mscient/intrapaper/g0/G0Schema.java).

The source of truth read before implementation was the latest fetched main's final
implementation plan, Phase 4 continuation, implementation tracker and next research
requests. Related inputs reviewed include the capability matrix, R1–R13 findings,
DR-02/04/05/08 rights/broker/retail-algo/execution research, owner handoff, independent
risk gateway, daily-ops evidence, incident drill and feed audit contracts. Those notes
are historical leads/implementation descriptions. Every current material rule claim
below points to a primary document or is explicitly UNRESOLVED.

Current software limits are material: simulated next-bar/L1 fills are conditional
models, not market executions or queue/capacity evidence; margin inputs are proxies,
not exchange/account SPAN/ELM; feed audits compare caller observations against caller
schedules and cannot certify complete vendor delivery or rights; synthetic incident
and daily-ops drills prove only their offline scenarios and provide no live cancel,
flat, reconciliation or G4 session evidence. Saved-input importers/decoders have no
live conformance or entitlement implication. G0 checker PASS is an evidence-pack
status, never legal approval, broker approval or trading readiness.

Independent reviewer sign-off: **PENDING_OWNER_INPUT**. Name/qualified role, identity
authentication, independence, exact source/charter digest, review date, decision and
unresolved reservations are missing. No signature has been supplied or verified.
The agent and automated tests cannot serve as that independent human reviewer.
