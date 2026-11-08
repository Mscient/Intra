# G0 decision, conflict and next-action log — version 1

| Decision | Evidence / resulting boundary |
|---|---|
| D-G0-01 | User's G0 task supersedes historical amounts and G1 budget deferral. Actual mandatory owner inputs remain blank. |
| D-G0-02 | Isolate from freshly fetched main on codex/g0-charter-20261108. Do not merge/cherry-pick/stash other worktree changes. |
| D-G0-03 | Freeze this implementation to offline evidence checking. All market/account/order activity and spending remain unapproved/disabled. |
| D-G0-04 | Primary source bytes/URLs and caller decisions are different evidence types. No compliance/authority/currentness inference from checksums. |
| D-G0-05 | Deterministic explicit-date assessment and exact-byte reconstruction; no implicit clock, defaults or thresholds. New location/date/pack inputs require renewed review/report; a new build requires a new report and human reassessment of software changes. Review binds pack inputs; report additionally binds implementation/build. |
| D-G0-06 | Qualified human review represented only as unverified attestation with exact separate record. No signature verifier or external trust anchor configured. Actual independent authentication remains a human task. |
| D-G0-07 | Synthetic PASS is checker plumbing evidence. Published pending example is INCOMPLETE, and actual G0 remains open. |

| Conflict/gap | Status / exact next action and owner |
|---|---|
| May standards versus July/September/current consolidation | P09 current consolidation retrieved and relevant §§8.1–8.10/16 read. It replaces MSD67753 and compiles through 2026-04-30. Original amendment history and later changes/applicability remain UNRESOLVED; researcher/reviewer complete that timeline. P07 FAQ explicitly cannot override circulars. |
| TOPS per-exchange vs per-exchange/segment, “below” vs “not exceeding”, message counting | UNRESOLVED. Reviewer/broker provide exact aggregation/window/tenth-request treatment for new, modify, cancel, reject, retries and children. Keep distinct from endpoint quotas and OTR. No limiter implemented. |
| SEBI OAuth language vs SDK login methods | P09 §8.4.8.9 includes SEBI-mandated 2FA/other communicated mechanisms. Actual broker product flow, session/key/IP constraints and applicability remain UNRESOLVED; obtain dated primary/product confirmation. No authentication trial requested. |
| Broker announcement broadly bans IOC; NSE FAQ identifies commodity IOC restriction | UNRESOLVED. Broker and current exchange originals establish each segment/order/session rule. Simulated IOC models confer no outbound order permission. |
| SDK retains depth mode and reconnect code; historical notes say deprecation/other heartbeat | UNRESOLVED. Current primary service docs/dated product evidence must specify field support, quotas, heartbeat, acknowledgement and reconnect/replay. Do not infer replay or complete capture. |
| Historical plan eight-year audit target vs old NSE five-year text and current SEBI record law | UNRESOLVED applicability. Reviewer maps current regulation/consolidation per record class, actor, holds and licence deletion. Retention duty does not grant market-data replay/storage rights. |
| Endpoint/SDK visible; rights absent | UNRESOLVED. Owner privately supplies already-authorized agreements/entitlements with every use dimension. No request to collect prices, create a key, contact broker or purchase data. |
| Nifty and India VM/standby are plans | PENDING_OWNER_INPUT. Owner explicitly chooses research scope and future access/storage/retention; verify broker standby/product rules only with authorization and evidence. |
| Capital/spend/loss/team/authority/reviewer unknown | PENDING_OWNER_INPUT. Complete private register, then independent human review of exact evidence. Never copy plan amounts. |
| Local PASS vs actual G0 closure | G0 remains INCOMPLETE. Software validation and a checksum cannot replace owner/human/legal/broker decisions or certify readiness. |

Before editing, repository/worktree state was audited on 2026-11-08. Initial shared
main HEAD was `2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090`, ahead 4/behind 34 of
remote main. `git fetch origin main` completed; base is
`ff4874167e889a26990e1f4d9d2b1ffa4acbefe6`. The separate G0 worktree was created
clean from that base. No unrelated work was moved or overwritten. There was no
applicable AGENTS.md in the source tree. `rg` was unavailable; reads used Git and
PowerShell. Worktree audit used per-command safe.directory for read-only inspection,
not a global configuration change. Worktree names below are sanitized directory labels.

| Worktree | Initial HEAD | State before G0 editing |
|---|---|---|
| shared codex-intra-origin-20261103 | 2b7fe0a | DIRTY, 77 entries |
| c2-pricer-validation | 56383ec | CLEAN |
| ci21w-20261107 | 138a84d | CLEAN |
| data-permission-coverage-20261107 | e858e1f | DIRTY, 4 entries |
| data-permission-coverage-resumed | 0bf9ba4 | CLEAN |
| dataset-intended-use-review | 5785958 | CLEAN |
| feed-audit | a7cd3c1 | CLEAN |
| integration-review-feed-audit | ff48741 | CLEAN |
| integration-review-incident-drills | d45d367 | CLEAN |
| intra-latest | e858e1f | CLEAN |
| local-integration-review | 07e5483 | CLEAN |
| portfolio-dated-costs | 2eab899 | CLEAN |
| portfolio-expiry-settlement-audit | 8581001 | CLEAN |
| portfolio-settlement | 9a5ef1a | CLEAN |
| portfolio-sharpe-hac | 015878a | CLEAN |
| portfolio-trial-evaluation | 2419b8e | CLEAN |
| r14-dr12-main-merge | ca36d6c | CLEAN |
| r14-methods-review | 3f9ee04 | CLEAN |
| risk-gateway-durability | 9a5ef1a | CLEAN |
| risk-settlement-review | fe552d2 | CLEAN |
| task3-publish | aabb532 | CLEAN |

Actual gate closing sequence: owner completes private decisions → researcher/reviewer
resolves primary applicability/rights → human authenticates reviewer and reviews exact
pack → rerun checker and independently retain digests → independent reviewer explicitly
records G0 closure or reservations. None of this authorizes a later broker/feed/order
stage; any such activity needs its own permission and gate evidence.
