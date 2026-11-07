# Synthetic feed audit examples

All identities, schedules, thresholds, messages, and provenance are synthetic caller declarations. No exchange/vendor evidence is represented.

| Input bundle | Expected status |
| --- | --- |
| `inputs/complete/evidence.json` | PASS: no configured anomaly detected |
| `inputs/missing-window/evidence.json` | FAIL: no messages observed |
| `inputs/closed-pause/evidence.json` | PASS: explicit closed interval |
| `inputs/heartbeat-absence/evidence.json` | FAIL: configured heartbeat absent |
| `inputs/missing-policy/evidence.json` | INCOMPLETE: clock-skew policy missing |

Binary `.bin` files are real version-1 hash-chained raw/lifecycle journals generated with the existing writers. They are synthetic, not captured market traffic. JSON and provenance hashes bind exact LF bytes. Do not apply newline conversion after hashing.

See the [guide](../../docs/FEED-COMPLETENESS-GAP-AUDIT.md) for fields, interval semantics, supported offline decoders, limits, commands, and exit codes. The full acceptance matrix additionally generates mappings, quote journals, multiple part/scope cases, malformed/tampered inputs, and repeated verified reports. Run `test-feed-completeness-audit.ps1` to retain that evidence.

To reproduce these five examples in a new empty destination, use `test-feed-completeness-audit.ps1 -ExamplesOutput <new-directory>`. It never overwrites an existing journal.
