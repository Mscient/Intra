package com.mscient.intrapaper.marketdata;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Sanitized connection/subscription control fact with both wall-clock and monotonic receive times. */
public record FeedLifecycleEvent(int schemaVersion, String source, String connectionId, Type type,
        Optional<String> subscriptionId, Instant observedAtUtc, long observedAtMonotonicNanos,
        Optional<DetailCode> detailCode) {

    public enum Type {
        CONNECTION_REQUESTED,
        CONNECTION_ESTABLISHED,
        AUTHENTICATED,
        CONNECTION_FAILED,
        SUBSCRIPTION_REQUESTED,
        SUBSCRIPTION_ACKNOWLEDGED,
        SUBSCRIPTION_REJECTED,
        UNSUBSCRIPTION_REQUESTED,
        UNSUBSCRIPTION_ACKNOWLEDGED,
        HEARTBEAT,
        DISCONNECTED,
        RECONNECT_SCHEDULED
    }

    /** Fixed diagnostic vocabulary avoids persisting arbitrary broker/server error text. */
    public enum DetailCode {
        TIMEOUT,
        AUTHORIZATION_REJECTED,
        RATE_LIMITED,
        SESSION_EXPIRED,
        PROTOCOL_ERROR,
        NETWORK_ERROR,
        REMOTE_CLOSED,
        OTHER
    }

    public FeedLifecycleEvent {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported feed lifecycle schema version: " + schemaVersion);
        source = requireText(source, "source");
        connectionId = requireText(connectionId, "connectionId");
        type = Objects.requireNonNull(type, "type");
        subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId")
                .map(value -> requireText(value, "subscriptionId"));
        observedAtUtc = Objects.requireNonNull(observedAtUtc, "observedAtUtc");
        detailCode = Objects.requireNonNull(detailCode, "detailCode");
        boolean subscriptionEvent = switch (type) {
            case SUBSCRIPTION_REQUESTED, SUBSCRIPTION_ACKNOWLEDGED, SUBSCRIPTION_REJECTED,
                    UNSUBSCRIPTION_REQUESTED, UNSUBSCRIPTION_ACKNOWLEDGED -> true;
            default -> false;
        };
        if (subscriptionEvent != subscriptionId.isPresent()) {
            throw new IllegalArgumentException("subscriptionId must be present only for subscription lifecycle events");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-empty, trimmed, and free of control characters");
        }
        CaptureText.validate(value, field);
        return value;
    }
}
