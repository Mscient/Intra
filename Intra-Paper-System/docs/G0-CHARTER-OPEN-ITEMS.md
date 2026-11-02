# S0 / G0 charter — open items

**Status:** Open. The project remains offline and has no broker or data-source connection.

The final implementation plan requires these items to be confirmed before connectivity, data capture, or cost/risk assumptions are encoded. Existing research is evidence about prior choices, not current account authorization.

| Item | Existing project note | G0 status |
|---|---|---|
| Operator and stop authority | One human operator is assumed by the final plan. | Confirm the responsible operator, independent reviewer, stop authority, and incident contact. |
| Account and route | Earlier research records Angel One SmartAPI for read-only Nifty market data. | Confirm the route and current account/API terms from official broker documents. No account access has occurred. |
| Market-data permission and retention | Existing notes flag expired F&O history and retention as unconfirmed. | Confirm lawful local storage/replay terms, history coverage, and source fallback. |
| Build universe | Earlier design starts with Nifty for plumbing; futures are sequenced before options. | Confirm this remains the initial build universe; do not treat it as the future live universe. |
| Rules and broker limits | Prior notes cite dated SEBI/NSE/broker material. | Refresh current rules, static-IP/standby, order types, rates, and audit requirements before any route depends on them. |
| Cost and capital assumptions | The attached plan includes example financial assumptions. | Replace with operator-approved actual values in a private record. Do not put personal budgets or loss limits in this repository. |
| Risk and emergency controls | The design calls for hard limits and K0–K3 stop controls. | Confirm actual stop limits, manual path, and independent reviewer. No live controls are implemented. |
| Strategy hypothesis | The plan names a queue-aware simulator candidate and a slower RV candidate. | No candidate is frozen. G1 requires a research card, benchmark, costs, variants, and rejection criteria. |
| Data readiness | Local research identifies index timestamp/session gaps and missing dated derivative history. | No performance run until the specific dataset, timestamps, dated contracts, costs, and data rights pass review. |

## Fail-closed rule

If route, data rights, current rules, budgets, or stop authority are unclear, keep the project offline. This document records questions; it does not answer them or authorize any API access.
