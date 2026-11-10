# Synthetic G5 fixtures

`test-g5-readiness.ps1 -OutputRoot <ignored-private-output>` generates the strict
fixtures locally. `src/test/java/com/mscient/intrapaper/g5/G5AcceptanceTest.java`
is the fixture source. No signing private key is saved or tracked. All keys,
opaque identities, scope records, amounts, dates, claims and session/drill records
are synthetic. They establish no authority or operational evidence.

The generated `evidence/jvm-repeat` directory is a populated synthetic dossier;
`evidence/jvm-private.json` supplies explicit synthetic thresholds and zero risk
amounts. Its retained head is in `evidence/jvm-head.txt`. Its result is BLOCKED,
even after synthetic signatures and approval events are added. Required verifier
inputs omitted by this structural fixture remain INCOMPLETE. Existing verifier
behavior is exercised separately in the full offline CI matrix.

Fixtures cover individually open, absent, stale and tampered G0–G4 evidence;
identity, rights, route, hosting, rule, review, operational and reconciliation
failures; malformed input, traversal, junctions, history corruption, immutable
publication and deterministic reproduction. Never replace synthetic fixture data
with actual people, contacts, credentials, accounts or financial values.
