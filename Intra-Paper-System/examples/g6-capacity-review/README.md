# Synthetic G6 dossier

Every record is synthetic. The six sparse sessions and full January–June 2026 calendar are contract fixtures, not a real exchange schedule or six months of G5 operation. The identity hashes and conditions are synthetic declarations. No broker was contacted.

Run the separate G6 CLI against `dossier` and `dossier/history.json`, publishing to a new directory outside the dossier. Both review and verification must return `INCOMPLETE` (exit 2), `g6_closed=false` and `scale_authorized=false`. Missing identity snapshots, G5 authorization and separately approved protocol/reviewer evidence are intentional. The generated test suite also exercises signed but unauthenticated approvals and decisions.

All original bytes and hashes are retained with LF checkout attributes. Regenerate only as a new fixture version using the test class's `generate` mode; never rewrite a real review generation. The numeric synthetic rule is a plumbing check, not a recommended performance, risk or capacity threshold.

No profitability, legal-compliance or trading-readiness conclusion follows. See [operating guide](../../docs/G6-CAPACITY-REVIEW.md).
