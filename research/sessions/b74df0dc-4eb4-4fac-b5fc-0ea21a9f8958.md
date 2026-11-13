# G2 admission retained-evidence and final staging review

Session: b74df0dc-4eb4-4fac-b5fc-0ea21a9f8958
Date: 2026-11-13

Objective: continue the existing G2 checkout, preserve all current changes, verify retained validation and exact build identity, resolve whitespace without changing evidence bytes, review and commit locally only.

Initial state: codex/g2-remaining-engineering-2026-11-09 at 3c774d2f2971c1ebe39d361e250057e9e872b971, 98 staged files, no unstaged/untracked files. Stored origin/main was 6c398f1e8d090a527f413d713dc7d294e411c548, with local divergence 3 ahead/4 behind. Its freshness is unverified; supplied context records github.com DNS fetch failure. No fetch was attempted.

Sources consulted: RTK.md, SESSION_PROTOCOL.md, README, research/BOOK_TRACKER.md, recent G2 sessions and SESSION_INDEX.csv, operating guide, readiness inventory/gaps, admission/path/permission production contracts, schemas, CI/test/wrapper runners, retained stage/environment/manifest/checksum records and original/resumed local synthetic artifacts. No book research or access status changed.

Reviewed the scoped admission semantics and permission-before-resource-path ordering; no production source/schema/existing runner change was needed. Direct verification passed for all 85 evidence checksums and all 38 stage logs against their original/resumed files. Source/JAR reports/artifacts match; actual JAR Build-Commit and SHA-256 and all four recorded JDK file hashes match. Tested build remains 3c774d2, JAR 5d094cb8d787d4b3fbaa8b055c98ddeb3b1e9abe806e53a6af7bae1b197672c7, Microsoft JDK 21.0.13.0.2+1-LTS. Current review uses PowerShell 7.6.10; retained tests used 7.6.9. No new tests/package build are claimed.

The 38 passing stages comprise 21 source suites and 17 other stages: 36 retained plus two resumed. Admission recorded 66 assertions and six schema cases per mode; contract tests recorded 365 checks per mode. The original RUN-only packaged log, absent summary/exit, partial runner and JAR remain unchanged. No uninterrupted full rerun or hosted exact-build success is claimed.

Whitespace errors came from 1,340 retained CRLF terminators under -text; there were no actual trailing spaces or tabs. Scoped whitespace=cr-at-eol preserves evidence bytes and space/tab detection. Corrected unsupported fetch-freshness claims, clarified historical versus admission validation in readiness/CI docs, saved the verification driver/log/checksums, and completed the pending report/session records. Final staging is restricted to reviewed G2 paths; Git whitespace checks and staged review precede the report commit. The index follows separately with the report commit ID.

G2 remains INCOMPLETE: owner/private decisions, exact authenticated rights and lawful corpus, issuer/source/reference truth, completeness, universal semantics/transformation/recovery contracts, independent review, and operational campaign/recovery/release evidence are missing. Hashes and synthetic tests establish no rights, issuer identity, evidence completeness, market correctness, profitability or trading/operational readiness.

Access limits: only repository records, code, synthetic logs/artifacts and installed build files were read. No real data, credentials, broker/account/feed/cloud/order path or network request was used. Read-only RTK commands hung in the sandbox and succeeded outside it; required inspections used the working execution path. No reset, clean, checkout replacement, rebase, merge or push occurred. The user's local-only instructions override SESSION_PROTOCOL publication steps.

Next actions require independent external evidence and separately authorized integration/publication; any G2/G5 combination needs a clean combined build and preservation of both matrices. [Completion report](../systems/2026-11-09-g2-admission-validation.md) · [Review evidence](../systems/g2-admission-review-2026-11-13/README.md).
