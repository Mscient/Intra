# Offline data contract — first cut

This first cut defines metadata models only. It does not import market files, connect to a broker, determine entitlements, or validate a dataset for strategy scoring.

## Instrument master

`InstrumentReference` retains source token, exchange, segment, trading symbol, contract kind, underlying, expiry, strike/right where applicable, lot size, tick size, and an inclusive effective-date range. `InstrumentMasterSnapshot` binds those records to a retrieval time, effective-from date, and raw source-file SHA-256.

Resolve a source token only through one dated snapshot and an explicit session date. Resolution fails when the snapshot is stale, no contract matches, or multiple active rows match. A current snapshot cannot be used for dates before its effective-from date. Keep each snapshot immutable and preserve expired futures/options as separate contracts.

## Dataset provenance

`DatasetManifest` records source label, acquisition time, timezone, timestamp role, data-rights review state/reference, exact instrument-master snapshot/hash, optional session-calendar hash, and hashes for input files. Input identifiers are logical IDs rather than paths. The manifest stores no rows, credentials, account IDs, IP addresses, or private source URLs.

Unknown timestamp semantics stay `UNKNOWN`. Data rights stay `UNVERIFIED` until a reviewable authorization reference is provided. These metadata models do not replace bar/quote-quality checks, session completeness checks, or holdout rules.

## Current gate

No market-data input is staged. The expired-contract history, local retention permission, timestamp convention, current fees, and session calendar remain unresolved. Do not generate market-performance claims from this schema or from synthetic fixtures.
