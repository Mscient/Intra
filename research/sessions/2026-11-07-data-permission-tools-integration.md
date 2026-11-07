# Dataset permission tools integration and CI verification

Session: 191792d0-9675-410f-a4db-42677810c41b
Date: 2026-11-07 (Asia/Kolkata)
Branch: `codex/dataset-intended-use-preflight-20261107`
Base: `origin/main` `e858e1fa0d9f659c88b023ce363e5612fcbda6f7` at integration start.

## Integrated work

This branch contains the Java 21 dataset intended-use preflight and the separate multi-record data-permission coverage checker. The checker integration was cherry-picked as `484f5856e89d79333a60d6f5e187457368eb4f21` from the isolated implementation branch; the preflight commits remain in this branch's ancestry. The CI workflow now runs both tools from source, checks preflight against the production JAR, builds and tests the standalone coverage JAR, and checks coverage against the production JAR. Documentation describes both tools and retains their limitations.

The original permission-coverage report records its own commit-time state. The feature is included here; this session records the later integrated CI result.

## Validation

Combined local Windows Java 21 CI completed with `VALIDATION_STATUS=PASS` at source revision `8ae33e418748bc0125b0b652af23117b047bc556` using Microsoft OpenJDK 21.0.13.0.2. All eleven selected focused suites passed, including:

- Risk gateway: 447 checks.
- Risk gateway process recovery: 728 checks, 308 child JVMs, 24 forced terminations.
- Portfolio replay, risk replay, accounting, risk audit (79 checks), settlement audit (88 checks), and trial evaluation (165 checks plus 30,019 frozen synthetic HAC checks).
- Daily operations: 455 assertions.
- Intended-use preflight: 300 source checks and 300 packaged checks, plus 10 synthetic CLI drill invocations.
- Data permission coverage: 102 scenarios / 1,648 assertions against both the standalone JAR and the production JAR.

The production JAR manifest `Build-Commit` exactly matched `8ae33e418748bc0125b0b652af23117b047bc556`. Its SHA-256 was `9b47e672aa58d73a3c9afb6375870d886405fe90c94c0b22885f84e476f7562a`. `git diff --check` passed. Logs and generated JAR were retained at `C:\Users\prash\AppData\Local\Temp\codex-permission-integration-ci-20261107`.

## Limits and publication

All permission fixtures are synthetic and caller-supplied. These tools do not authenticate source evidence, grant legal permission, or close G2. Local CI does not claim hosted Actions success. The permissions remain unapproved and production-ineligible pending owner evidence and qualified review.