# Parallel coding and research task briefs

**Prepared:** 2026-11-04 (Asia/Kolkata)  
**Repository:** `https://github.com/Mscient/Intra.git`  
**Base branch:** `main`. A delegated coding agent must use its own branch/worktree and must not commit directly to shared `main`.

These briefs are independent handoffs. The coding task must not change DSR/PBO formulas while the research task is unresolved. Both tasks preserve the project's offline-only boundary: no credentials, feed capture, broker connection, order routing, live trading, or performance claim.

**Status:** C1 has been implemented in `Intra-Paper-System/` at commit `775860108294e551a37ece183c3c217721c207ea` and clean-built on JDK 21.0.13. Runtime behavior has not been exercised. R14 remains the next open research task.

## Coding task C1 — append-only outcome-exposure accounting for research trials (implemented)

### Objective

Extend the existing research-trial registration ledger so it can distinguish a registered variant that never produced a performance outcome from an attempt whose performance information was produced or viewed. This makes the history auditable for future selection-bias analysis. Do not alter DSR's trial count or the statistical evaluator's family-selection policy in this task.

### Start by reading

- `Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/ResearchTrialLedger.java`
- `Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/ResearchTrialBinding.java`
- `Intra-Paper-System/src/main/java/com/mscient/intrapaper/evaluation/OfflineEvaluationAnalyzer.java`
- CLI commands and ledger help in `Intra-Paper-System/src/main/java/com/mscient/intrapaper/cli/IntraPaperCli.java`
- Trial registration and `statistical-evaluate` sections in `Intra-Paper-System/docs/OPERATING-GUIDE.md`
- `Intra-Paper-System/IMPLEMENTATION-TRACKER.md`

### Scope

1. Inspect the existing canonical encoding, SHA-256 chain, locking, append/resume, and strict input conventions. Reuse them; do not introduce a second ledger format without a compatibility reason.
2. Add append-only events linked to an existing registered `trial_id`, with event type, UTC timestamp, operator-supplied reason/reference, previous-event hash, and event hash. Proposed minimum events:
   - `OUTCOME_PRODUCED` — a run produced any performance-bearing output;
   - `OUTCOME_VIEWED` — a person or process inspected performance information;
   - `MECHANICAL_FAILURE_NO_OUTCOME` — attempt failed before any performance information was created or viewed;
   - `ABANDONED` — attempt stopped, with whether an outcome had already been produced/viewed represented by the prior event history.
3. Require that the trial already exists in the registration ledger. Reject duplicate event IDs, unknown trial IDs, invalid/reordered timestamps, malformed hashes, unknown event types, chain breaks, event mutation, and impossible state transitions. A later mechanical-failure event must not erase prior outcome exposure.
4. Provide a strict CLI path to append one event and a read-only verifier/summary that reports counts by trial and exposure state. Do not echo private file contents or credentials.
5. Add a fail-closed evaluator compatibility check only if it can be done without changing current DSR/PBO math: if an outcome-history ledger is supplied, require chain validity and report its hash/event counts in evaluator provenance. Keep optionality and backward compatibility explicit.
6. Document limits: this local hash chain is tamper-evident only relative to a trusted prior hash; it cannot prove who viewed a result, prevent a copied file from being edited wholesale, or recover unlogged experiments.

### Out of scope

- Do not estimate effective independent N, modify DSR/PSR/Sharpe/PBO formulas, set thresholds, classify profitability, or change `performance_claim_eligible=false`.
- Do not open or alter the holdout, emit holdout P&L, add broker/feed/network behavior, or auto-run strategy variants.
- Do not make the ledger claim to detect undeclared experiments or prove human viewing.

### Completion criteria

- Strict append and full-chain verification work from a Java 21 clean build.
- Invalid/tampered/reordered/impossible event histories fail closed; valid histories yield deterministic summaries.
- Existing trial registration and `statistical-evaluate` flows remain source-compatible and retain current output semantics when the optional exposure ledger is absent.
- Operating guide and implementation tracker state the guarantees and limits accurately.
- Deliver a commit on a separate branch, changed-file summary, clean build result, and any design decisions that need review. No push to `main` or merge.

## Research task R14 — effective trial count and dependence-aware session Sharpe

### Objective

Produce a primary-source-backed methods decision memo that determines what can responsibly be added after the current DSR approximation. The result should compare a conservative effective-N sensitivity report with dependence-aware Sharpe uncertainty methods, and recommend a small, preregisterable next increment for daily/session returns.

### Questions to answer

1. What exact DSR independent-trial-count estimators are proposed in the DSR paper, especially its average-correlation/effective-N appendix? State assumptions, edge cases, and where the approximation may mislead for highly clustered, negatively correlated, or adaptively searched variants.
2. For session-level NIFTY returns, what assumptions underlie ordinary PSR/DSR and annualized Sharpe? Explain how serial dependence changes Sharpe scaling and uncertainty; distinguish return dependence from dependence among candidate trials.
3. Compare implementable choices for the next increment: raw-N upper bound plus declared sensitivity grid; correlation-based effective N; Lo-style autocorrelation adjustment; HAC/Newey-West variance; moving/stationary block bootstrap; or another method only if supported by primary sources. Report data requirements, finite-sample tradeoffs, failure behavior, deterministic replay needs, and validation strategy for each.
4. Explain the relationship and differences among DSR, PBO/CSCV, chronological walk-forward evaluation, and a one-shot sealed holdout. Identify which questions each can answer and which it cannot.
5. State what the current code can support from session-level net P&L and what it lacks (e.g. complete exposure history, enough sessions, trial return alignment, predeclared dependence bandwidth/block length). Do not assume bar-level labels or purging are needed without a defined label design.
6. Recommend preregistration fields and output fields, including sensitivity ranges and limitations, without choosing a performance pass threshold.

### Source requirements

- Read the primary DSR paper directly: Bailey & López de Prado (2014), https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf, especially Eq. 1–2 and Appendix A.3.
- Read the PBO paper directly: Bailey, Borwein, López de Prado & Zhu (2015 revised text), https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf, especially Algorithm 2.3 and limitations.
- Read primary Sharpe/dependence references (including Lo's Sharpe-ratio statistics work and original HAC/bootstrap methods as needed). Give bibliographic identifiers, exact equations/sections, and stable source links. Secondary code/blogs may be implementation cross-checks only.
- Distinguish claims verified from full text, abstract/metadata only, and unresolved. Do not rely on recollection. Do not quote long copyrighted passages.

### Deliverables and constraints

- A Markdown memo under `research/systems/` with recommendation, assumptions, equations in original-frequency conventions, source table, implementation implications, open choices, and explicit caveats.
- A compact proposed data/output contract for a later code task; do not implement Java in this research task.
- No market data, account information, credentials, or performance claims. No DSR/PBO thresholds selected after observing outcomes.
- Commit the memo on a separate branch and provide a concise handoff summary. Do not push to `main` or modify active code.

### Suggested task order

R14 can proceed independently of the committed C1 ledger. Any later evaluator integration that uses exposure events must wait until R14 is reviewed and a separate statistical-method decision is recorded.

