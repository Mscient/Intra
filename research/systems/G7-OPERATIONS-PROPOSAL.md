# G7 Operations, Drift and Requalification — proposal

The original project roadmap defines **G0–G6**. G7 is a proposed extension outside
that roadmap. It is **not an approved project gate** and remains unadopted.
The owner must explicitly adopt a reviewed proposal before any future G7 gate
could exist. This delivery changes no G0–G6 decision or closure status and cannot
replace or repair an earlier gate.

`g7_closed=false` · `operation_authorized=false` · `scale_authorized=false`

The proposed tool examines local evidence for a future, separately authorized
period against its exact previously approved strategy/build/configuration/source/
instrument/route/capacity/risk/procedure envelope. It identifies missing/stale
coverage, drift, reconciliation exceptions, incidents, stop/recovery evidence gaps
and the predeclared human escalation/requalification path. Its outputs are
diagnostics for independent and owner review. They cannot connect to a service,
send/cancel orders, operate a kill switch, resume trading, modify limits or grant
scaling authority.

The [evidence contract](../../Intra-Paper-System/docs/G7-OPERATIONS-EVIDENCE.md)
defines strict versioned local schemas, deterministic evaluate/verify/explain/append
commands, immutable generations and the limitations of byte hashes and supplied
signatures. Existing daily/feed/replay/risk/settlement verifiers are reused where
the original dependencies exist. No external-account ledger or provisioned
source/G0–G6/reviewer/owner identity-authentication adapter exists in this checkout.
Exact normalized ledger comparison preserves that limitation.

Accordingly `ELIGIBLE_FOR_HUMAN_REVIEW` is reserved and unavailable in v1. Even
perfectly matching synthetic evidence and signed caller assertions remain blocked.
Hashes cannot establish a person, qualifications, independence, historical viewing,
rights, custody or completeness against omitted records. No real operational
evidence or human owner/independent review was supplied for this work.

Before adoption, the owner and qualified independent reviewer would need to
define applicability, exact earlier-gate evidence and current authorized envelope,
review cadence/freshness, authenticated source/custody/reviewer provisioning,
severity and stop/escalation/requalification procedures, genuine recovery evidence,
and separate authority for any operating period. Those human decisions remain
outside this implementation. This proposal neither closes a gate nor supplies
operation or scale authority.

The [validation report](../../Intra-Paper-System/docs/G7-OPERATIONS-VALIDATION.md)
and synthetic fixtures document software checks only. Hosted CI and local checks
must be reported for their exact revisions separately.
