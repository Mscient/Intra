# Synthetic risk replay fixture

Run with the standalone commands in [the integration guide](../../docs/PORTFOLIO-RISK-REPLAY.md).

Two synthetic instruments have four one-minute frames on 2030-05-06 and an artificial later expiry. The SMA target produces two intents at `2030-05-06T03:48:00Z`. F is accepted and fills one contract at 103. O is rejected by `MARGIN_LIMIT`: F consumes proxy margin of 100 and O would add 100 against a cap of 150. Final positions are F +1 and O 0, with no remaining reservations. Final net replay P&L is -1 from the explicit execution fee; gateway P&L is gross of that fee.

Every frame has explicit mark and margin provenance. Both side ceilings are 110 for F and 20 for O. Legacy single-instrument risk values in dataset properties are required by dataset validation but are not adapter fallbacks: only `limits.csv`, `instruments.csv` and `samples.csv` supply adapter risk inputs.

The Java acceptance fixtures also exercise signed shorts/covers, partial/missed fills, closeouts, duplicates, reconciliation, uncertainty, recovery and invalid inputs. Source labels, raw-record hashes, dates, lot sizes and margin values are synthetic. No SPAN/ELM or exchange-margin determination is made.
