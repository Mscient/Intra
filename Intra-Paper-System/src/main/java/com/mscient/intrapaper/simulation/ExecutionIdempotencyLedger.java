package com.mscient.intrapaper.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Applies each execution ID once and rejects reuse of an ID with a different economic payload. */
public final class ExecutionIdempotencyLedger {
    public enum ApplyResult { APPLIED, DUPLICATE_IDENTICAL }

    private final LinkedHashMap<String, ExecutionEvent> eventsById = new LinkedHashMap<>();

    public synchronized ApplyResult apply(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        ExecutionEvent prior = eventsById.get(event.executionId());
        if (prior == null) {
            eventsById.put(event.executionId(), event);
            return ApplyResult.APPLIED;
        }
        if (prior.equals(event)) return ApplyResult.DUPLICATE_IDENTICAL;
        throw new IllegalStateException("Execution ID was reused with a different payload: " + event.executionId());
    }

    /** Rehydrates from a persisted event sequence; exact duplicates are ignored and conflicts fail closed. */
    public synchronized int restore(Iterable<ExecutionEvent> persistedEvents) {
        Objects.requireNonNull(persistedEvents, "persistedEvents");
        if (!eventsById.isEmpty()) throw new IllegalStateException("Execution ledger restore requires an empty ledger");
        int uniqueEvents = 0;
        try {
            for (ExecutionEvent event : persistedEvents) {
                if (apply(event) == ApplyResult.APPLIED) uniqueEvents = Math.addExact(uniqueEvents, 1);
            }
            return uniqueEvents;
        } catch (RuntimeException failure) {
            eventsById.clear();
            throw failure;
        }
    }

    public synchronized List<ExecutionEvent> events() {
        return List.copyOf(new ArrayList<>(eventsById.values()));
    }
}
