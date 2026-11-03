package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Reconstructs OMS state from hash-verified completed-run ledgers. */
public final class OmsRunRecovery {
    public record RecoveredState(
            String runId,
            Map<String, OmsOrderState> orderStates,
            Map<String, Integer> reservedUnitsByOrder,
            List<ExecutionEvent> uniqueExecutions,
            int duplicateExecutionEventsIgnored,
            int finalPositionUnits) {
        public RecoveredState {
            Objects.requireNonNull(runId, "runId");
            orderStates = Collections.unmodifiableMap(new TreeMap<>(orderStates));
            reservedUnitsByOrder = Collections.unmodifiableMap(new TreeMap<>(reservedUnitsByOrder));
            uniqueExecutions = List.copyOf(uniqueExecutions);
            if (duplicateExecutionEventsIgnored < 0 || finalPositionUnits < 0) {
                throw new IllegalArgumentException("Recovered OMS counts and position must be non-negative");
            }
        }

        public int totalReservedUnits() {
            return reservedUnitsByOrder.values().stream().reduce(0, Math::addExact);
        }
    }

    private record OrderInfo(String status, String side, int requestedUnits) { }

    private OmsRunRecovery() { }

    /** Verifies the complete artifact manifest before restoring its saved order and execution ledgers. */
    public static RecoveredState restore(Path requestedRunFolder) throws IOException {
        Objects.requireNonNull(requestedRunFolder, "requestedRunFolder");
        RunArtifactVerifier.Verification verification = RunArtifactVerifier.verify(requestedRunFolder);
        Path runFolder = requestedRunFolder.toAbsolutePath().normalize();

        Map<String, OrderInfo> orders = readOrders(runFolder.resolve("orders.csv"));
        Map<String, OmsOrderState> orderStates = readOrderStates(runFolder.resolve("order-events.csv"), orders);
        ExecutionRestore executions = readExecutions(runFolder.resolve("fills.csv"), orders, orderStates);
        int netExecutionPosition = applySettlement(
                runFolder.resolve("settlements.csv"), executions.finalPositionUnits());
        int savedPosition = readFinalPosition(runFolder.resolve("equity.csv"));
        if (netExecutionPosition != savedPosition) {
            throw new IOException("Recovered execution/settlement position " + netExecutionPosition
                    + " does not match saved final equity position " + savedPosition);
        }

        Map<String, Integer> reservations = new TreeMap<>();
        for (Map.Entry<String, OmsOrderState> entry : orderStates.entrySet()) {
            OrderInfo order = orders.get(entry.getKey());
            int executed = executions.executedUnitsByOrder().getOrDefault(entry.getKey(), 0);
            int reserved;
            try {
                reserved = OmsOrderStateMachine.remainingReservationUnits(
                        entry.getValue(), order.requestedUnits(), executed);
            } catch (IllegalArgumentException invalidReservation) {
                throw new IOException("Recovered order quantity/state mismatch for " + entry.getKey(), invalidReservation);
            }
            if (reserved > 0) reservations.put(entry.getKey(), reserved);
        }

        return new RecoveredState(verification.runId(), orderStates, reservations,
                executions.ledger().events(), executions.duplicateEventsIgnored(), savedPosition);
    }

    private static Map<String, OrderInfo> readOrders(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        requireExactHeaders(table, "order_id", "decision_timestamp", "submit_timestamp", "fill_timestamp",
                "side", "target_lots", "requested_units", "order_type", "status", "reason");
        Map<String, OrderInfo> orders = new TreeMap<>();
        for (Map<String, String> row : table.rows()) {
            String id = required(row, "order_id");
            String side = required(row, "side");
            if (!side.equals("BUY") && !side.equals("SELL")) throw new IOException("Invalid order side for " + id);
            int requested = parsePositiveInt(row.get("requested_units"), "requested_units");
            String status = required(row, "status");
            if (orders.putIfAbsent(id, new OrderInfo(status, side, requested)) != null) {
                throw new IOException("Duplicate order ID in recovered ledger: " + id);
            }
        }
        return orders;
    }

    private static Map<String, OmsOrderState> readOrderStates(Path path,
            Map<String, OrderInfo> orders) throws IOException {
        CsvTable table = CsvTable.read(path);
        requireExactHeaders(table, "timestamp", "order_id", "event", "detail");
        Map<String, OmsOrderState> states = new HashMap<>();
        Map<String, String> lastEventNames = new HashMap<>();
        Map<String, Instant> lastTimes = new HashMap<>();
        for (Map<String, String> row : table.rows()) {
            String id = required(row, "order_id");
            if (id.isEmpty()) continue;
            if (!orders.containsKey(id)) throw new IOException("Order event refers to unknown order: " + id);
            String event = required(row, "event");
            Instant timestamp = parseInstant(row.get("timestamp"), "order-event timestamp");
            Instant previousTime = lastTimes.put(id, timestamp);
            if (previousTime != null && timestamp.isBefore(previousTime)) {
                throw new IOException("Order event time reversed for " + id);
            }
            String previousEvent = lastEventNames.get(id);
            if (event.equals("CREATED")) {
                if (previousEvent != null) throw new IOException("Order has duplicate CREATED events: " + id);
                states.put(id, OmsOrderState.CREATED);
            } else {
                if (previousEvent == null) throw new IOException("Order event precedes CREATED: " + id);
                try {
                    SimOrderLifecycle.requireTransition(previousEvent, event);
                    states.put(id, SimOrderLifecycle.toOmsState(event));
                } catch (IllegalArgumentException | IllegalStateException invalidTransition) {
                    throw new IOException("Invalid order transition for " + id + ": "
                            + previousEvent + " -> " + event, invalidTransition);
                }
            }
            lastEventNames.put(id, event);
        }
        if (states.size() != orders.size()) throw new IOException("One or more orders have no lifecycle events");
        for (Map.Entry<String, OrderInfo> order : orders.entrySet()) {
            if (!order.getValue().status().equals(lastEventNames.get(order.getKey()))) {
                throw new IOException("Recovered final order state differs from orders.csv for " + order.getKey());
            }
        }
        return states;
    }

    private record ExecutionRestore(ExecutionIdempotencyLedger ledger,
            Map<String, Integer> executedUnitsByOrder, int duplicateEventsIgnored, int finalPositionUnits) { }

    private static ExecutionRestore readExecutions(Path path, Map<String, OrderInfo> orders,
            Map<String, OmsOrderState> orderStates) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> priorHeaders = List.of("fill_id", "order_id", "timestamp", "side", "quantity_units",
                "reference_open_price", "fill_price", "modeled_slippage_inr", "fee_inr", "position_units_after");
        List<String> currentHeaders = List.of("fill_id", "order_id", "timestamp", "side", "quantity_units",
                "reference_open_price", "fill_price", "modeled_slippage_inr", "fee_inr", "margin_per_lot_inr",
                "margin_source_reference", "position_units_after");
        if (!table.headers().equals(priorHeaders) && !table.headers().equals(currentHeaders)) {
            throw new IOException("Unexpected CSV schema in recovered execution ledger");
        }
        List<ExecutionEvent> events = new ArrayList<>();
        Map<String, Integer> positionAfterByExecutionId = new HashMap<>();
        for (Map<String, String> row : table.rows()) {
            String id = required(row, "order_id");
            OrderInfo order = orders.get(id);
            if (order == null) throw new IOException("Execution refers to unknown order: " + id);
            String side = required(row, "side");
            if (!order.side().equals(side)) throw new IOException("Execution side differs from order side: " + id);
            try {
                String executionId = required(row, "fill_id");
                events.add(new ExecutionEvent(executionId, id,
                        parseInstant(row.get("timestamp"), "execution timestamp"),
                        ExecutionEvent.Side.valueOf(side), parsePositiveInt(row.get("quantity_units"), "quantity_units"),
                        new BigDecimal(required(row, "fill_price"))));
                int positionAfter = parseNonNegativeInt(row.get("position_units_after"), "position_units_after");
                Integer priorPositionAfter = positionAfterByExecutionId.putIfAbsent(executionId, positionAfter);
                if (priorPositionAfter != null && priorPositionAfter != positionAfter) {
                    throw new IOException("Duplicate execution ID has conflicting position state: " + executionId);
                }
            } catch (IllegalArgumentException invalidExecution) {
                throw new IOException("Invalid execution record for " + id, invalidExecution);
            }
        }

        ExecutionIdempotencyLedger ledger = new ExecutionIdempotencyLedger();
        int uniqueCount;
        try {
            uniqueCount = ledger.restore(events);
        } catch (IllegalArgumentException | IllegalStateException conflict) {
            throw new IOException("Execution ledger could not be restored", conflict);
        }
        Map<String, Integer> executedUnitsByOrder = new HashMap<>();
        for (ExecutionEvent event : ledger.events()) {
            executedUnitsByOrder.merge(event.orderId(), event.quantityUnits(), Math::addExact);
        }
        for (Map.Entry<String, Integer> executed : executedUnitsByOrder.entrySet()) {
            OrderInfo order = orders.get(executed.getKey());
            if (executed.getValue() > order.requestedUnits()) {
                throw new IOException("Executions exceed requested units for order " + executed.getKey());
            }
            OmsOrderState state = orderStates.get(executed.getKey());
            try {
                OmsOrderStateMachine.remainingReservationUnits(state, order.requestedUnits(), executed.getValue());
            } catch (IllegalArgumentException invalidQuantity) {
                throw new IOException("Execution quantity does not agree with final state for " + executed.getKey(),
                        invalidQuantity);
            }
        }
        int units = 0;
        try {
            for (ExecutionEvent event : ledger.events()) {
                units = Math.addExact(units, event.side() == ExecutionEvent.Side.BUY
                        ? event.quantityUnits() : -event.quantityUnits());
                if (units < 0) throw new IOException("Recovered SELL execution exceeds open long units");
                Integer recordedUnits = positionAfterByExecutionId.get(event.executionId());
                if (recordedUnits == null || recordedUnits != units) {
                    throw new IOException("Recovered position after execution differs from fills.csv for "
                            + event.executionId());
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Recovered position units overflow", overflow);
        }
        return new ExecutionRestore(ledger, Map.copyOf(executedUnitsByOrder),
                events.size() - uniqueCount, units);
    }

    private static int applySettlement(Path path, int netPosition) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> previousHeaders = List.of("instrument_id", "settlement_date", "settlement_timestamp", "settlement_kind",
                "settlement_value", "exit_value_per_unit", "position_units_settled", "gross_pnl_inr", "source_reference");
        List<String> currentHeaders = List.of("instrument_id", "settlement_date", "settlement_timestamp", "settlement_kind",
                "settlement_value", "exit_value_per_unit", "position_units_settled", "gross_pnl_inr", "settlement_fees_inr", "source_reference");
        if (!table.headers().equals(previousHeaders) && !table.headers().equals(currentHeaders)) {
            throw new IOException("Unexpected CSV schema in recovered settlement ledger");
        }
        if (table.rows().size() > 1) throw new IOException("More than one settlement in a single-contract run");
        if (table.rows().isEmpty()) return netPosition;
        int settledUnits = parseNonNegativeInt(table.rows().getFirst().get("position_units_settled"), "position_units_settled");
        if (settledUnits != netPosition) throw new IOException("Settlement units do not match reconstructed open position");
        return netPosition - settledUnits;
    }

    private static int readFinalPosition(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        requireExactHeaders(table, "timestamp", "session_date", "close", "position_units", "realized_pnl_inr",
                "unrealized_pnl_inr", "fees_paid_inr", "net_pnl_inr", "risk_state");
        if (table.rows().isEmpty()) throw new IOException("Saved equity ledger is empty");
        return parseNonNegativeInt(table.rows().getLast().get("position_units"), "position_units");
    }

    private static void requireExactHeaders(CsvTable table, String... expected) throws IOException {
        if (!table.headers().equals(List.of(expected))) {
            throw new IOException("Unexpected CSV schema in recovered run ledger");
        }
    }

    private static String required(Map<String, String> row, String field) throws IOException {
        String value = row.get(field);
        if (value == null) throw new IOException("Missing ledger field: " + field);
        return value;
    }

    private static int parsePositiveInt(String value, String field) throws IOException {
        int parsed = parseInt(value, field);
        if (parsed < 1) throw new IOException(field + " must be positive");
        return parsed;
    }

    private static int parseNonNegativeInt(String value, String field) throws IOException {
        int parsed = parseInt(value, field);
        if (parsed < 0) throw new IOException(field + " must be non-negative");
        return parsed;
    }

    private static int parseInt(String value, String field) throws IOException {
        try {
            return Integer.parseInt(Objects.requireNonNull(value, field));
        } catch (NumberFormatException | NullPointerException invalid) {
            throw new IOException("Invalid integer in recovered ledger field " + field, invalid);
        }
    }

    private static Instant parseInstant(String value, String field) throws IOException {
        try {
            return Instant.parse(Objects.requireNonNull(value, field));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid instant in recovered ledger field " + field, invalid);
        }
    }
}
