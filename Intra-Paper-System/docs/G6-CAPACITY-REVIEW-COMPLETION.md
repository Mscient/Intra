# G6 software delivery and validation

Branch: `codex/g6-capacity-review`, isolated from unrelated work. Base: `7d6f6c56424b4e50903121eaf8ee491a9ce15607` from `origin/main`. No push or merge is authorized or performed.

The standalone Java 21 reviewer, immutable hash-chained history, exact report/build decision bindings, versioned schemas, synthetic example, PowerShell runners and operating guide are implemented. Existing trading, pricing, replay, risk, cost, statistical and settlement calculations remain unchanged. The full offline matrix includes the G6 source suite and packaged checks.

Tested software commit and JAR `Build-Commit`: **`1facc354143aee97cec68ab074ed9930fa114f61`**. The implementation started in `2857574`; the tested revision also propagates the existing risk verifier's `BREACH` status to G6 `FAIL`. The subsequent validation-record commit changes documentation/receipts only; it is not represented as the tested JAR revision.

JAR SHA-256: `804643f5bfd02b03f99b5ce089ee433cc720cd65c6087613e7228e27295ffb32`.

| Local validation on Microsoft JDK 21.0.13.0.2 | Result |
| --- | --- |
| Selected full offline CI matrix, clean exact commit | PASS: 21 source suites and 17 build/package/drill/verification stages |
| G6 focused source checks | PASS: 217 checks, 73 cases |
| G6 focused packaged checks | PASS: 217 checks, 73 cases |
| Offline JSON Schema validation | PASS: 59 generated documents per G6 suite; 61 documents for checked-in example plus two published generations |
| Separate CLI review, verify, explain and PowerShell runner | PASS: synthetic exit 2 (`INCOMPLETE`); integrity and report reproduction verified |
| UTC/en-US versus Pacific/Apia/tr-TR, separate JVMs | PASS: report, artifact, input history and generated history bytes identical |
| JAR manifest identity | PASS: exact `Build-Commit` matched the tested clean revision |
| Hosted CI | **NOT RUN** for this commit; no hosted success claimed |

The full matrix includes risk-process recovery (728 checks, 308 child JVMs and 24 forced terminations), portfolio accounting/replay/risk/settlement, daily operations (455 assertions), feed audit (507 checks), and the unchanged statistical/HAC suite (30,019 HAC checks). G6 exercised actual symlink and hardlink rejection, publication locking, failed-publication cleanup, signature/history/source tampering, missing/excluded sessions and ledger/protocol/identity contradictions. No G6 test was skipped.

Concise receipts and log digests are retained [with the validation record](../../research/systems/g6-capacity-review-2026-11-09/README.md). Complete local logs and the production JAR are in workspace directory `g6-ci-1facc35`; standalone command outputs are in `g6-cli-1facc35-verified`. Earlier drafts and the intentionally superseded initial matrix are not final acceptance evidence. The local CLI validation script needed a PowerShell quoting correction before its successful run; committed Java code was unchanged.

The G6 software deliverable does **not** close G6. G6 remains **INCOMPLETE** until verified six-calendar-month G5 evidence and qualified independent review exist. **No scale-up is authorized.** The baseline has no authenticated optional G5 pilot evidence. The current tool cannot produce eligibility without additional independently provisioned authentication and source adapters; supplied signatures authenticate bytes only, not people, source truth or prior approval time.

Outstanding evidence: actual authorized G5 pilot and all earlier gate prerequisites; authenticated original exports and exact strategy/build/configuration/instrument/calendar/rule/cost/risk/route/deployment/data/execution identities; complete calendar and reconciliation/incident evidence; preregistered separately approved rules; owner scenario ladder; authenticated qualified independent review and separate owner decision bound to the exact report and build.

Any recommendation applies only to the exact tested strategy, instruments, build, configuration, data sources, costs, risk limits and jointly tested size envelope. No profitability, legal-compliance or trading-readiness conclusion follows from synthetic/software checks. See [operating guide](G6-CAPACITY-REVIEW.md).
