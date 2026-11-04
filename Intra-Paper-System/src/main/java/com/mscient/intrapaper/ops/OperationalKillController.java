package com.mscient.intrapaper.ops;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic K0-K3 response latch driven only by explicit observations and caller attestations.
 * It recommends actions; it does not place, cancel, or flatten orders or stop a process.
 */
public final class OperationalKillController {
    private final KillPolicy policy;
    private final Set<String> observationIds = new HashSet<>();
    private LocalDate sessionDate;
    private KillLevel level = KillLevel.NONE;
    private Set<KillTrigger> activeTriggers = Set.of();
    private Instant lastObservedAtUtc;
    private long lastObservedAtMonotonicNanos = -1;
    private long k0HealthySinceMonotonicNanos = -1;
    private int k1Incidents;
    private boolean k1IncidentOpen;

    public OperationalKillController(KillPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized void startSession(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (sessionDate != null && level != KillLevel.NONE) {
            throw new IllegalStateException("Cannot change session while a K0-K3 latch is active");
        }
        if (sessionDate != null && !date.isAfter(sessionDate)) {
            throw new IllegalArgumentException("Operational session dates must strictly increase");
        }
        sessionDate = date;
        level = KillLevel.NONE;
        activeTriggers = Set.of();
        lastObservedAtUtc = null;
        lastObservedAtMonotonicNanos = -1;
        k0HealthySinceMonotonicNanos = -1;
        k1Incidents = 0;
        k1IncidentOpen = false;
        observationIds.clear();
    }

    public synchronized KillDecision observe(SafetyObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (sessionDate == null) throw new IllegalStateException("Operational session has not been initialized");
        if (observationIds.contains(observation.observationId())) {
            throw new IllegalArgumentException("Duplicate safety observation ID: " + observation.observationId());
        }
        validateOrder(observation);
        observationIds.add(observation.observationId());
        KillLevel previous = level;
        Set<KillTrigger> triggers = observation.activeTriggers();
        KillLevel requested = triggers.stream().map(KillTrigger::level).max(Enum::compareTo).orElse(KillLevel.NONE);

        boolean hasK1Incident = requested == KillLevel.K1_CANCEL_AND_BLOCK;
        if (hasK1Incident && !k1IncidentOpen) {
            k1Incidents = Math.addExact(k1Incidents, 1);
            k1IncidentOpen = true;
        } else if (!hasK1Incident) {
            k1IncidentOpen = false;
        }
        if (k1Incidents >= policy.k1IncidentsBeforeK2() && hasK1Incident
                && requested.compareTo(KillLevel.K2_FLATTEN_AND_HALT) < 0) {
            requested = KillLevel.K2_FLATTEN_AND_HALT;
            var escalated = EnumSet.copyOf(triggers);
            escalated.add(KillTrigger.REPEATED_K1_ESCALATION);
            triggers = Set.copyOf(escalated);
        }
        if (requested.compareTo(level) > 0) level = requested;
        activeTriggers = triggers;

        boolean cleared = false;
        if (level == KillLevel.K0_PAUSE && triggers.isEmpty()) {
            if (k0HealthySinceMonotonicNanos < 0) {
                k0HealthySinceMonotonicNanos = observation.observedAtMonotonicNanos();
            }
            long healthyNanos = observation.observedAtMonotonicNanos() - k0HealthySinceMonotonicNanos;
            if (healthyNanos >= policy.k0HealthyClearDuration().toNanos()) {
                level = KillLevel.NONE;
                k0HealthySinceMonotonicNanos = -1;
                cleared = true;
            }
        } else if (!triggers.isEmpty() || level != KillLevel.K0_PAUSE) {
            k0HealthySinceMonotonicNanos = -1;
        }
        lastObservedAtUtc = observation.observedAtUtc();
        lastObservedAtMonotonicNanos = observation.observedAtMonotonicNanos();
        return decision(observation.observationId(), previous, cleared);
    }

    /** K1-K3 require explicit reconciliation/review attestations. K0 uses its configured healthy dwell. */
    public synchronized KillDecision clear(HumanKillClearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        if (sessionDate == null || level == KillLevel.NONE) throw new IllegalStateException("No active kill latch to clear");
        if (level == KillLevel.K0_PAUSE) throw new IllegalStateException("K0 clears only after the configured healthy dwell");
        if (!activeTriggers.isEmpty()) throw new IllegalStateException("Active trigger remains; human clearance is not sufficient");
        if (!clearance.satisfies(level)) throw new IllegalArgumentException("Human clearance evidence is incomplete for " + level);
        KillLevel previous = level;
        level = KillLevel.NONE;
        activeTriggers = Set.of();
        k0HealthySinceMonotonicNanos = -1;
        return decision("human-clearance:" + clearance.reference(), previous, true);
    }

    public synchronized KillLevel level() { return level; }
    public synchronized int k1IncidentCount() { return k1Incidents; }

    private void validateOrder(SafetyObservation observation) {
        if (lastObservedAtUtc != null && observation.observedAtUtc().isBefore(lastObservedAtUtc)) {
            throw new IllegalArgumentException("Safety observation wall time regressed");
        }
        if (lastObservedAtMonotonicNanos >= 0
                && observation.observedAtMonotonicNanos() <= lastObservedAtMonotonicNanos) {
            throw new IllegalArgumentException("Safety observation monotonic time must strictly increase");
        }
        if (lastObservedAtUtc != null && observation.activeTriggers().isEmpty()) {
            long intervalNanos = observation.observedAtMonotonicNanos() - lastObservedAtMonotonicNanos;
            if (intervalNanos > policy.maximumHealthyObservationGap().toNanos()) {
                k0HealthySinceMonotonicNanos = -1;
            }
        }
    }

    private KillDecision decision(String observationId, KillLevel previous, boolean cleared) {
        var responses = switch (level) {
            case NONE -> EnumSet.of(KillDecision.Response.CONTINUE_MONITORING);
            case K0_PAUSE -> EnumSet.of(KillDecision.Response.PAUSE_NEW_RISK);
            case K1_CANCEL_AND_BLOCK -> EnumSet.of(KillDecision.Response.CANCEL_WORKING_ORDERS_AND_BLOCK_NEW_RISK);
            case K2_FLATTEN_AND_HALT -> EnumSet.of(KillDecision.Response.CANCEL_WORKING_ORDERS_AND_BLOCK_NEW_RISK,
                    KillDecision.Response.REQUEST_PROTECTED_RISK_REDUCTION_AND_HALT);
            case K3_DISCONNECT -> EnumSet.of(KillDecision.Response.DISCONNECT_PROCESS_AND_USE_HUMAN_MANUAL_PATH);
        };
        return new KillDecision(observationId, sessionDate, previous, level, level.compareTo(previous) > 0,
                cleared, level == KillLevel.NONE, activeTriggers, responses.stream().toList());
    }
}
