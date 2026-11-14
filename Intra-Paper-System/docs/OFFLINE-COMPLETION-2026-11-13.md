# Offline completion inventory — 2026-11-13

Inspected working checkout HEAD 2b7fe0a7ce97caf80b956c2cc3858f64ec5d6090 on local main (ahead 4/behind 56 versus the locally cached origin/main). No fetch performed. Substantial modified/untracked implementation pre-exists this session and is preserved.

| Area | Implemented | Incomplete / stale / unverified |
|---|---|---|
| Build/tests | Java 21, dependency-free CLI, 23 test Java files and focused PowerShell runners, selected-suite CI | Current revision not yet verified; selected CI omits existing test entrypoints; PowerShell launch hangs locally while Java/native processes work |
| Data/recovery | Strict UTF-8 CSV, dated contracts/calendar/rules, provenance, quote/raw/lifecycle hash chains, checkpoints | Authorized historical expiry coverage absent; rules/source authenticity caller-supplied; unanchored whole-frame journal truncation indistinguishable from a valid prefix |
| Simulation/risk | Next-open and L1 IOC, partial/canceled lifecycle, single long-only and signed portfolio accounting, supplied settlement/costs, risk/loss controls | Incomplete diagnostic bar sessions can enter single replay; exchange margin/daily variation margin/physical delivery unsupported |
| Identity/artifacts | Content-addressed atomic publication, hash and rerun verification | Single run identity omits market-rule/permission input hashes; additional settlement-cost hash absent from dataset manifest input map |
| Evaluation | Cards/registrations/splits/outcomes, development/validation evaluation, separate one-shot controller | SMA harness only; authorized data, calibrated fills/costs, independent review, production custody and observed paper evidence absent |
| Paper/ops | Offline lifecycle, stop policy, drills, daily evidence and external-ledger diagnostics | No connected adapter or paper clock; G0 open; no account/feed access authorized |
| Documentation | Extensive component contracts | Latest build/CI sections historical; operating guide full-fill statements stale; roadmap/design describe separate Python repository |

Read README, implementation tracker, operating guide, G0 open-items, roadmap/design, SESSION_PROTOCOL and RTK instructions. Research documents are context, not connectivity authority. G0–G4 remain evidence-open; G5/G6 outside scope. No account access, market-data capture, credentials, or broker order route. This inventory was produced before this session's implementation changes.
