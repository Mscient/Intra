# G3 current-state and dependency review — 2026-11-08

**Decision: INCOMPLETE. G3 is open. Both required candidate slots are NOT TESTABLE.**
This completes the evidence inventory and blocked-case research deliverable, not
the strategy-evidence gate. No candidate was evaluated, selected or rejected on
performance. G4 forward paper has no authorization from this review; G5 remains
closed. Missing performance values are unknown, never zero.

The review starts at freshly fetched `origin/main`,
`0ae90aadc58fd86a4835917ffbbf992a7870bcf2`, on isolated
`codex/g3-strategy-evidence-20261108`. The original `main` checkout at `2b7fe0a`
had tracked edits, untracked implementations and many worktrees. They were
preserved. No existing work was reset, stashed, merged or promoted by this review.
No AGENTS.md was found in the clean source tree. `rg` and `gh` were unavailable;
PowerShell file searches and the public GitHub Actions API were used instead.

The inspected G1 worktree is clean at
`cd6bf95c7e1ef3bcbfcddbf8aac48274fd207c2f`; G2 is clean at
`08b6009a6cd5bd5d27f118988868f31a5237dac0`. These are separate local branch
artifacts, absent from this base main. Selected exact evidence bytes are retained
in [snapshots](snapshots/), with source revisions, Git blobs and byte hashes in
[source-identities.csv](source-identities.csv). Copies preserve their original
classification and approval status. They do not integrate the G1/G2 software.
The original checkout's uncommitted holdout design is identified separately.

Read [candidate evidence](CANDIDATES.md), [registration and methods](PREREGISTRATION.md),
[operator/reviewer guide](OPERATOR-REVIEW.md), and [decision.json](decision.json).
[VALIDATION.md](VALIDATION.md) records exact checks and publication status.

## Prerequisites checked against artifacts

| Dependency | Current evidence and exact missing requirement | Disposition |
| --- | --- | --- |
| G0 signed charter | Main's `g0-2026-11-08/OWNER-AND-ASSUMPTIONS.md` has blank actual values and pending decisions. No owner signature, accepted assumptions/limits, authenticated stop/resume/contact/reviewer record, applicable route clearance or executed rights record supplied. | INCOMPLETE |
| G1 approved hypotheses | Three local cards have `frozen_at_utc=null`, pending owner selection/review/learning/A15/G0 attestations. S01 proposed; S02/S03 deferred. All are index-signal designs. No queue-aware MM or slower RV card exists in the inspected artifacts. | INCOMPLETE |
| G2 data permissions | DR-04 and owner O03 request source/product-specific capture, storage, simulation, replay, backup, derived-output, sharing and deletion grants. Source candidate rows are production-ineligible; no accepted permission corpus supplied. API access and public source pages do not establish rights. | INCOMPLETE |
| G2 market quality | Only three local `g2-synthetic-quality/v1` records, all `SYNTHETIC_OFFLINE_ONLY`, were supplied. No authorized complete dated market corpus or independent identity/session/timestamp/gap validation. | INCOMPLETE |
| G2 simulator validity | Shared replay/risk/accounting and scoped bar/L1 IOC models exist. Queue, impact and latency calibration, full all-in cost attribution, exchange margin, authenticated source/recovery independence remain open. G2 software tests cannot close market validity. | INCOMPLETE |
| Statistical decision | HAC protocol is frozen for SE-only diagnostics. No approved MM/RV primary metric, benchmark, net holdout interval method/level/sidedness/sample adequacy, rejection threshold or capacity sensitivity acceptance rule. Wave A's signal-only interval cannot supply these. | PENDING_METHOD_APPROVAL |
| Custody and search history | Local ledger APIs exist; no actual G3 registration/outcome family, independent timestamp anchor, canonical holdout store, independent custodian release or complete exposure audit supplied. Original checkout's one-shot controller is uncommitted, absent from base main. | INCOMPLETE / UNVERIFIED |
| Independent review | G0 and G1 approvals pending; G2 REVIEW.md is explicitly AI content review, not human/source-rights approval. R14 is a methods recommendation with a separate adopted HAC engineering protocol. No candidate review/sign-off. | INCOMPLETE |

No market dataset or sealed financial artifact was opened or hashed. Reviewed
inputs were source, documentation, card metadata and explicitly synthetic quality
records. Software suites generate synthetic outcomes only. Historic blindness is
UNKNOWN; this session's lack of market access cannot establish prior blindness.

## Existing components and concrete gaps

| Existing implementation | Reuse and boundary |
| --- | --- |
| `ResearchTrialLedger`, `ResearchOutcomeLedger`, `ResearchTrialBinding`, `EvaluationSplitPlan` | Existing eight-field registration, append-only outcomes and split checks. Local timestamps/chains cannot prove every search or exposure was logged. |
| `PortfolioTrialIdentity`, `PortfolioTrialCatalog`, `PortfolioTrialEvaluationAnalyzer`, `PortfolioSessionReturns` | Complete submitted dataset/split family, exact parameter and settlement bindings, development/validation-only scoring. Card/strategy-version declarations and code-review relationship are external. Evaluator requires at least two trials; do not invent a second candidate to satisfy it. |
| Local G1 `evaluation/hypothesis` increment | Strict authored-card/schema/version/companion binding already exists on its separate branch. Do not duplicate it here. It is not owner authentication or a universal trial interceptor. |
| Original uncommitted `HoldoutReleaseInputs`, `PortfolioHoldoutReleaseController`, `SignedHoldoutJournal` | One-shot signed state machine and callback boundary already authored. No replacement vault/scorer built here. Independent review, integration, canonical family store, keys/access separation and actual data commitments remain missing. |
| `SessionReturnStatistics`, `PortfolioSharpeHac` | Preserve existing Sharpe, raw-M DSR and endpoints, CSCV/PBO ties/blocks, fixed HAC protocol. No formulas or acceptance thresholds changed. |
| `ValidatedDataset`, `ValidatedPortfolioDataset`, dated mapping/master/calendar/rules; permission preflight/coverage and feed audit | Reuse supported identity/quality/rights diagnostics. Caller declarations and synthetic PASS are not independent market evidence. Gaps remain explicit; no repair/substitution. |
| `PortfolioReplayEngine`, writer/verifier/determinism verifier, risk replay/gateway/audit | Shared virtual-time strategy-to-risk-to-accounting paths; exact artifacts and source replay. Existing SMA strategies are harnesses, not approved G3 candidates. |
| Execution/settlement cost schedules and accounting | Effective-dated component arithmetic, signed residual basis, explicit settlement provenance and missing-cost coverage. Scope-specific modeled charges do not prove all-in completeness; margin inputs are proxies. |

The concrete missing deliverable was a G3 evidence/dependency/candidate/custody
decision packet joining these artifacts. This increment supplies that packet,
exact documentary snapshots and an integrity manifest. No production Java,
strategy, tariff, risk configuration, statistical protocol or holdout control is
changed. A candidate runner would require choosing missing hypotheses and inputs;
that dependent implementation remains blocked. CI/replay qualification here is
software evidence only, never a negative market-performance experiment.

## Remaining human/source work

Owner: complete G0 charter/authority/A1–A15 and private budget/limit attestations;
approve the actual MM and slower RV hypotheses, full variant/search history and
dated instruments; nominate already authorized data/rights records; freeze all
trial/code/split/cost/fill/risk/benchmark/metric identities before outcomes.

Qualified reviewers: accept exact source/product-purpose permissions and data
quality; resolve the 30 Oct closing gap from authorized raw comparisons, clock
roles, dated session/CAS/pre-open/expiry/lot/tick/master history and amendment
coverage; review account tariff/rounding/taxes/settlement, exchange versus proxy
margin, latency/fill/impact/capacity evidence and limits. Statistical reviewer and
owner must approve the economic hurdle and net holdout decision method; independent
custodian must audit historical exposure and deployed one-shot access. Keep private
amounts, account identifiers, credentials and licensed raw data outside this packet.
