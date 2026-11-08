# Local G6 software validation receipts

Date: 2026-11-09. Exact tested software commit: `1facc354143aee97cec68ab074ed9930fa114f61` on `codex/g6-capacity-review`, based on `origin/main` at `7d6f6c56424b4e50903121eaf8ee491a9ce15607`.

The selected full offline matrix passed from a clean checkout on Microsoft JDK 21.0.13.0.2. `summary.log` records all 21 source suites and 17 build/package/drill/verification stages. `manifest-check.log` binds the production JAR to the exact tested commit and its SHA-256. G6 source and packaged logs each record 217 checks across 73 cases and 59 schema-validated documents, with no skipped G6 tests.

`packaged-cli-acceptance.json` records separate Java processes for review, verify and explain plus the PowerShell runner against the checked-in synthetic example. UTC/en-US and Pacific/Apia/tr-TR reports, artifacts and histories were byte-identical. Review, verification and explanation all returned 2 (`INCOMPLETE`), with `g6_closed=false` and `scale_authorized=false`. Sixty-one example/published JSON documents passed offline schema validation.

`local-log-sha256.json` inventories all retained matrix logs in the original workspace's `g6-ci-1facc35/logs` directory; the concise subset here is copied byte-for-byte. The production JAR remains at `g6-ci-1facc35/package/intra-paper-system.jar`; standalone CLI output is at `g6-cli-1facc35-verified`. `local-packaged-acceptance.ps1.txt` is an archival copy of the temporary validation script as executed in that workspace layout, not an installed runner. The supported runner is `Intra-Paper-System/g6-capacity-review.ps1`.

The subsequent validation-record commit contains documentation/receipts only. These results belong to the tested commit above. Hosted CI is **NOT RUN** for that exact commit: nothing was pushed or merged, and no hosted success is inferred from another revision.

These are synthetic/software checks. They do not authenticate an optional G5 pilot, supply six months of live evidence, qualify an independent reviewer, close G6, authorize scaling, or establish profitability, legal compliance or trading readiness. The implementation's missing independent authentication and source adapters are documented in the [operating guide](../../../Intra-Paper-System/docs/G6-CAPACITY-REVIEW.md) and [completion report](../../../Intra-Paper-System/docs/G6-CAPACITY-REVIEW-COMPLETION.md).
