# Dated instrument mapping registry

`DatedInstrumentMappingRegistry` resolves an archived exchange token to a stable contract identity using the exchange, token, and message timestamp. It reads a caller-selected local UTF-8 CSV and performs no network access. The registry is separate from WebSocket parsing and broker/order code.

The CSV header must match this order exactly:

```csv
mapping_id,exchange,token,instrument_id,trading_symbol,instrument_type,expiry,strike,option_right,effective_from_utc,effective_until_utc,source,source_reference,source_file_sha256
```

`mapping_id` is unique. `instrument_type` is `INDEX`, `EQUITY`, `FUTURE`, or `OPTION`. Futures require `expiry`; options require `expiry`, positive `strike`, and `CALL` or `PUT` in `option_right`. Index and equity rows leave derivative fields blank. `effective_from_utc` is required; `effective_until_utc` may be blank for an open-ended interval. Timestamps must be ISO-8601 UTC instants ending in `Z`.

Intervals are half-open: `effective_from_utc <= message_timestamp < effective_until_utc`; a blank end is unbounded. Adjacent rows can change identity exactly at the prior row's end. Resolution uses `Instant` and never infers an NSE session date or the machine's local timezone. Convert any source-local timestamp explicitly before resolution.

Mappings for the same exchange and token cannot overlap. Duplicate IDs, overlaps, malformed rows, unknown token/time combinations, and invalid timestamps fail closed. Results include the complete mapping row and canonical registry SHA-256; canonical ordering makes the result stable if CSV rows are reordered. `source_file_sha256` identifies the archived source file that supports that row; the returned registry SHA-256 fingerprints the normalized mapping contents.

The SmartAPI NFO Snap Quote importer uses `exchange=NFO` in this registry, resolves at the packet's exchange timestamp, then cross-checks instrument ID, symbol, type, expiry, strike, and option right against the `NSE`/`NFO` dated instrument master in `run.properties`. The dated instrument master supplies tick size. A disagreement fails closed. The CLI reports both the registry fingerprint and instrument-master source hash.

Minimal usage:

```java
var registry = DatedInstrumentMappingRegistry.load(Path.of("instrument-mappings.csv"));
var resolution = registry.resolve("NFO", "12345", Instant.parse("2026-01-02T09:15:00Z"));
String instrumentId = resolution.instrumentId();
String mappingId = resolution.mappingId();
String registrySha256 = resolution.registrySha256();
```

`test-mapping-registry.ps1` runs the focused Java 21 checks for half-open boundaries, duplicates, overlaps, unknown tokens, malformed input, CSV order independence, and machine-timezone independence.
