# Offline historical market-data ingestion

- Session: 91f6ec89-de16-4eea-bcca-7cc2ea26471b
- Date: 2026-11-09 (Asia/Kolkata).
- Objective: isolated Java 21 offline planning, permission-gated ingestion and artifact
  verification for a future authorized Angel One candle connector.
- Confirmed repository: `C:/Users/prash/AppData/Local/Temp/codex-intra-origin-20261103`.
  Initial local main was `2b7fe0a` with extensive unrelated uncommitted changes.
  Read-only `git ls-remote origin refs/heads/main` confirmed the integration base
  `7d6f6c56424b4e50903121eaf8ee491a9ce15607`; no fetch or market-data request was made.
- Worktree: `angelone-historical-ingestion-worktree`, branch
  `codex/angelone-historical-ingestion`, created from that verified main commit.
  Original user changes are preserved. No applicable AGENTS.md was found. The user
  explicitly overrides SESSION_PROTOCOL.md's main/push convention: commit only on
  the isolated branch, with no push or merge.
- Inspected/reused: existing strict JSON parser, canonical Bar/CSV contract,
  dated mapping registry, instrument master and calendar readers, preflight/coverage
  checkers, deterministic artifact conventions, Java 21 build and CI-local runners.
- Implemented: versioned schemas and fictional fixtures; configurable window plan;
  fail-closed provider gate; strict offset/number/OHLC parsing; dated coverage and
  duplicate/order diagnostics; normalization; immutable-addressed raw bytes; atomic
  publication and complete recomputation; CLI/PowerShell runner; user guide and tests.
- Verification: source acceptance reached 251 checks including separate CLI JVMs;
  the committed full-matrix/package evidence is recorded in the
  [validation report](../systems/2026-11-09-historical-ingestion-validation.md).
- Limits/status: permission checkers are not authenticated provider-rights verifiers;
  Angel One input stays blocked before raw access. Synthetic source identity remains
  separate. Public documentation locators are recorded, not fetched or newly reviewed.
  No real data, credentials, owner approvals or market trials were supplied. No book
  research or access status changed. G0/G2 remain open/incomplete; no production gate
  is raised; the owner-proposed 20 days remains unverified. No cloud choice or deletion
  scheduler exists. Future scope/retention/cloud approvals and trusted evidence
  machinery are explicit integration prerequisites, not follow-up authorization.
