# Offline data contract — first cut

This contract now has an offline derivatives-bar validator and replay path. It still does not capture market files, connect to a broker, determine entitlements, or authorize strategy-performance claims.

The command-line CSV schemas and running instructions are documented in [OPERATING-GUIDE.md](OPERATING-GUIDE.md). The implementation accepts one dated futures contract or one listed-option premium series per run. For options, the supplied bars are premiums; valuation, Greeks, and multi-leg positions are not modeled. Long-option expiry settlement is an explicit source-attributed input; itemized exercise STT and SEBI turnover are available when configured with a sourced turnover basis. Other expiry charges remain unmodeled.

Volume and open interest may be blank when the source does not provide them. The parser preserves missing values as null and preserves numeric zero; downstream strategies must not interpret null as zero.

## Instrument master

`InstrumentReference` retains source token, exchange, segment, trading symbol, contract kind, underlying, expiry, strike/right where applicable, lot size, tick size, and an inclusive effective-date range. `InstrumentMasterSnapshot` binds those records to a retrieval time, effective-from date, and raw source-file SHA-256.

Resolve a source token only through one dated snapshot and an explicit session date. Resolution fails when the snapshot is stale, no contract matches, or multiple active rows match. A current snapshot cannot be used for dates before its effective-from date. Keep each snapshot immutable and preserve expired futures/options as separate contracts.

## Dataset provenance

`DatasetManifest` records source label, acquisition time, timezone, timestamp role, data-rights review state/reference, exact instrument-master snapshot/hash, optional session-calendar hash, and hashes for input files. Input identifiers are logical IDs rather than paths. The manifest stores no rows, credentials, account IDs, IP addresses, or private source URLs.

Unknown timestamp semantics stay `UNKNOWN`. Data rights stay `UNVERIFIED` until a reviewable authorization reference is provided. The CLI now checks normalized bar fields, instrument resolution, supplied session-window boundaries, and within-session interval spacing. Those checks do not verify the source's calendar itself, validate quote/depth quality, or implement holdout rules.

## Current gate

The bundled example is synthetic and exists only to exercise file plumbing. No authorized market-data input is staged. Expired-contract history, local retention permission, timestamp convention, current fees, margin, exchange-calendar completeness, and G0/G1/G2 evidence remain unresolved. The simulator marks every result as diagnostic-only and never sets `performance_claim_eligible` to true.
