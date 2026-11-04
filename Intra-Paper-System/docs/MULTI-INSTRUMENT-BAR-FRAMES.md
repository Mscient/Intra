# Multi-instrument bar frames

`SynchronizedBarFrameBuilder` is an offline data-layer primitive for preparing exact-time basket inputs from already parsed `Bar` series. It is intended as a foundation for a future portfolio replay; it is not wired into the existing one-contract runner.

## Contract

- Supply at least two non-empty series keyed by canonical instrument ID.
- Each series must contain one stable contract identity and strictly increasing timestamps. The option/futures kind, underlying, expiry, strike, and option right cannot change under the same instrument ID.
- All series must use the same exchange, segment, interval, and timestamp role.
- Every series must have the same number of observations, and each ordinal row must have the exact same timestamp and local session date.
- Output maps and frame lists are immutable and instrument keys are sorted for deterministic iteration.
- A missing, duplicate, reordered, or mismatched observation is an error. The builder never intersects away missing timestamps, fills forward, interpolates, or infers a session calendar.

Effective-dated lot/tick values and source tokens may vary within an instrument series; upstream dataset validation remains responsible for checking them against the corresponding dated reference sources. The caller must also validate session completeness, data rights, timestamps/timezone, source quality, and all market rules before treating frames as research evidence.

## Current boundary

This API does not provide portfolio positions, strategy decisions, execution, risk checks, cost aggregation, cross-contract valuation, or a performance report. It cannot make an unsynchronized source valid by rejecting fewer rows. No broker, network, or live order path is present.

Run the focused checks with `\.\test-synchronized-bar-frames.ps1` from PowerShell with `JAVA_HOME` set to JDK 21.
