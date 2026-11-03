package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Reconstructs connection and subscription states, counting illegal transitions without dropping source facts. */
public final class FeedLifecycleAnalyzer {
    public enum ConnectionState { NEW, CONNECTING, CONNECTED, AUTHENTICATED, FAILED, DISCONNECTED, RECONNECT_SCHEDULED }
    private enum SubscriptionState { REQUESTED, ACTIVE, REJECTED, CANCEL_PENDING, UNKNOWN }
    private record ConnectionKey(String source, String connectionId) { }

    private static final class MutableConnection {
        private ConnectionState state = ConnectionState.NEW;
        private final Map<String, SubscriptionState> subscriptions = new HashMap<>();
    }

    private final Map<ConnectionKey, MutableConnection> connections = new HashMap<>();
    private long transitionAnomalies;
    private long activeSubscriptions;
    private long unresolvedSubscriptions;

    private FeedLifecycleAnalyzer() { }

    public static FeedLifecycleAnalysis analyze(Path journalPath) throws IOException {
        FeedLifecycleAnalyzer analyzer = new FeedLifecycleAnalyzer();
        FeedLifecycleSummary journal = FeedLifecycleJournalReader.replay(journalPath, analyzer::accept);
        return analyzer.finish(journal);
    }

    private void accept(FeedLifecycleEvent event) {
        ConnectionKey key = new ConnectionKey(event.source(), event.connectionId());
        MutableConnection connection = connections.computeIfAbsent(key, ignored -> new MutableConnection());
        String subscriptionId = event.subscriptionId().orElse(null);
        switch (event.type()) {
            case CONNECTION_REQUESTED -> transition(connection,
                    ConnectionState.NEW, ConnectionState.DISCONNECTED,
                    ConnectionState.FAILED, ConnectionState.RECONNECT_SCHEDULED);
            case CONNECTION_ESTABLISHED -> transitionTo(connection, ConnectionState.CONNECTING,
                    ConnectionState.CONNECTED);
            case AUTHENTICATED -> transitionTo(connection, ConnectionState.CONNECTED,
                    ConnectionState.AUTHENTICATED);
            case CONNECTION_FAILED -> {
                if (isOneOf(connection.state, ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                        ConnectionState.AUTHENTICATED)) {
                    connection.state = ConnectionState.FAILED;
                    markSubscriptionsUnknown(connection);
                } else anomaly();
            }
            case SUBSCRIPTION_REQUESTED -> {
                if (connection.state != ConnectionState.AUTHENTICATED
                        || isOneOf(connection.subscriptions.get(subscriptionId), SubscriptionState.REQUESTED,
                                SubscriptionState.ACTIVE, SubscriptionState.CANCEL_PENDING)) {
                    anomaly();
                } else connection.subscriptions.put(subscriptionId, SubscriptionState.REQUESTED);
            }
            case SUBSCRIPTION_ACKNOWLEDGED -> subscriptionTransition(connection, subscriptionId,
                    SubscriptionState.REQUESTED, SubscriptionState.ACTIVE);
            case SUBSCRIPTION_REJECTED -> subscriptionTransition(connection, subscriptionId,
                    SubscriptionState.REQUESTED, SubscriptionState.REJECTED);
            case UNSUBSCRIPTION_REQUESTED -> subscriptionTransition(connection, subscriptionId,
                    SubscriptionState.ACTIVE, SubscriptionState.CANCEL_PENDING);
            case UNSUBSCRIPTION_ACKNOWLEDGED -> subscriptionTransition(connection, subscriptionId,
                    SubscriptionState.CANCEL_PENDING, null);
            case HEARTBEAT -> {
                if (!isOneOf(connection.state, ConnectionState.CONNECTED, ConnectionState.AUTHENTICATED)) anomaly();
            }
            case DISCONNECTED -> {
                if (isOneOf(connection.state, ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                        ConnectionState.AUTHENTICATED, ConnectionState.FAILED)) {
                    connection.state = ConnectionState.DISCONNECTED;
                    markSubscriptionsUnknown(connection);
                } else anomaly();
            }
            case RECONNECT_SCHEDULED -> transitionToEither(connection,
                    ConnectionState.DISCONNECTED, ConnectionState.FAILED, ConnectionState.RECONNECT_SCHEDULED);
        }
    }

    private void subscriptionTransition(MutableConnection connection, String id,
            SubscriptionState required, SubscriptionState next) {
        if (connection.subscriptions.get(id) != required) {
            anomaly();
        } else if (next == null) {
            connection.subscriptions.remove(id);
        } else connection.subscriptions.put(id, next);
    }

    private void markSubscriptionsUnknown(MutableConnection connection) {
        connection.subscriptions.replaceAll((ignored, state) -> switch (state) {
            case ACTIVE, REQUESTED, CANCEL_PENDING -> SubscriptionState.UNKNOWN;
            case REJECTED, UNKNOWN -> state;
        });
    }

    private void transition(MutableConnection connection, ConnectionState... allowed) {
        for (ConnectionState state : allowed) {
            if (connection.state == state) {
                connection.state = ConnectionState.CONNECTING;
                return;
            }
        }
        anomaly();
    }

    private void transitionTo(MutableConnection connection, ConnectionState required, ConnectionState next) {
        if (connection.state == required) connection.state = next;
        else anomaly();
    }

    private void transitionToEither(MutableConnection connection, ConnectionState first,
            ConnectionState second, ConnectionState next) {
        if (connection.state == first || connection.state == second) connection.state = next;
        else anomaly();
    }

    private static boolean isOneOf(Object value, Object... allowed) {
        if (value == null) return false;
        for (Object candidate : allowed) if (value == candidate) return true;
        return false;
    }

    private void anomaly() { transitionAnomalies = Math.addExact(transitionAnomalies, 1); }

    private FeedLifecycleAnalysis finish(FeedLifecycleSummary journal) {
        EnumMap<ConnectionState, Long> states = new EnumMap<>(ConnectionState.class);
        for (ConnectionState state : ConnectionState.values()) states.put(state, 0L);
        connections.values().forEach(connection -> states.compute(connection.state,
                (ignored, count) -> Math.addExact(count, 1)));
        for (MutableConnection connection : connections.values()) {
            for (SubscriptionState state : connection.subscriptions.values()) {
                if (state == SubscriptionState.ACTIVE) activeSubscriptions = Math.addExact(activeSubscriptions, 1);
                if (isOneOf(state, SubscriptionState.REQUESTED, SubscriptionState.CANCEL_PENDING,
                        SubscriptionState.UNKNOWN)) unresolvedSubscriptions = Math.addExact(unresolvedSubscriptions, 1);
            }
        }
        return new FeedLifecycleAnalysis(journal, transitionAnomalies, activeSubscriptions,
                unresolvedSubscriptions, states);
    }
}
