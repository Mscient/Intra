package com.mscient.intrapaper.riskgateway;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;

/**
 * Deterministic, in-memory risk ledger. It only evaluates proposals and applies
 * caller-supplied events; it has no transport or order-routing API.
 */
public final class RiskGateway {
    private final RiskConfiguration configuration;
    private final Map<String, Long> positions = new TreeMap<>();
    private final Map<String, WorkingOrder> orders = new TreeMap<>();
    private final Map<String, InstrumentMark> marks = new TreeMap<>();
    private final Map<String, Instant> markTimes = new TreeMap<>();
    private final Map<String, Long> openingPositions = new TreeMap<>();
    private final Map<String, BigDecimal> openingMarks = new TreeMap<>();
    private final Map<String, BigDecimal> tradeCashflows = new TreeMap<>();
    private final List<RiskEvent> history = new ArrayList<>();
    private final Map<String, RiskEvent> eventsById = new HashMap<>();
    private final Map<String, OrderProposal> proposalsById = new HashMap<>();
    private final Map<String, RiskDecision> decisionsById = new HashMap<>();

    private String sessionId;
    private Instant lastEventAt;
    private BigDecimal dailyProfitLoss;
    private boolean dailyLossHalted;

    public RiskGateway(RiskConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Rebuilds in-memory risk state by replaying an already verified ordered event list. */
    public static RiskGateway restore(RiskConfiguration configuration, List<RiskEvent> verifiedEvents) {
        Objects.requireNonNull(verifiedEvents, "verifiedEvents");
        RiskGateway gateway = new RiskGateway(configuration);
        for (RiskEvent event : verifiedEvents) gateway.applyEvent(Objects.requireNonNull(event, "event"));
        return gateway;
    }

    public synchronized RiskState state() {
        return snapshot();
    }

    /** Transaction candidate, including process-local rejected-proposal decisions. */
    synchronized RiskGateway copyForPersistence() {
        RiskGateway copy = new RiskGateway(configuration);
        copy.positions.putAll(positions);
        copy.orders.putAll(orders);
        copy.marks.putAll(marks);
        copy.markTimes.putAll(markTimes);
        copy.openingPositions.putAll(openingPositions);
        copy.openingMarks.putAll(openingMarks);
        copy.tradeCashflows.putAll(tradeCashflows);
        copy.history.addAll(history);
        copy.eventsById.putAll(eventsById);
        copy.proposalsById.putAll(proposalsById);
        copy.decisionsById.putAll(decisionsById);
        copy.sessionId = sessionId;
        copy.lastEventAt = lastEventAt;
        copy.dailyProfitLoss = dailyProfitLoss;
        copy.dailyLossHalted = dailyLossHalted;
        return copy;
    }

    public synchronized void startSession(SessionStartEvent event) {
        applyEvent(event);
    }

    public synchronized void applyMarkToMarket(MarkToMarketEvent event) {
        applyEvent(event);
    }

    public synchronized void applyExecution(ExecutionEvent event) {
        applyEvent(event);
    }

    public synchronized void applyCancellation(CancellationEvent event) {
        applyEvent(event);
    }

    synchronized void applyEventForPersistence(RiskEvent event) {
        applyEvent(event);
    }

    /**
     * Evaluates a proposal and reserves its full remaining quantity only when
     * accepted. A repeated proposal ID and matching payload returns its original
     * decision; conflicting reuse is rejected.
     */
    public synchronized RiskDecision evaluate(OrderProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");

        OrderProposal previousProposal = proposalsById.get(proposal.proposalId());
        if (previousProposal != null) {
            if (previousProposal.equals(proposal)) {
                return decisionsById.get(proposal.proposalId());
            }
            return decision(proposal.proposalId(), false, DecisionReason.CONFLICTING_EVENT_ID,
                    Optional.empty(), false);
        }
        if (eventsById.containsKey(proposal.proposalId())) {
            return decision(proposal.proposalId(), false, DecisionReason.CONFLICTING_EVENT_ID,
                    Optional.empty(), false);
        }
        proposalsById.put(proposal.proposalId(), proposal);

        DecisionReason validation = validateProposal(proposal);
        if (validation != null) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false, validation,
                    Optional.empty(), false));
        }

        if (dailyProfitLoss != null
                && dailyProfitLoss.compareTo(configuration.maxDailyLoss().negate()) <= 0) {
            dailyLossHalted = true;
        }
        if (dailyProfitLoss == null) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.MISSING_MARK, Optional.empty(), false));
        }

        ExposureBuild build;
        try {
            build = buildExposure(proposal);
        } catch (ArithmeticException overflow) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.POSITION_LIMIT, Optional.empty(), false));
        }
        if (build.failure() != null) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false, build.failure(),
                    Optional.empty(), false));
        }

        boolean closeoutException = dailyLossHalted && isStrictRiskReducingCloseout(proposal);
        if (dailyLossHalted && !closeoutException) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.SESSION_HALTED, Optional.of(build.summary()), false));
        }

        if (exceedsPositionLimit(build.summary())) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.POSITION_LIMIT, Optional.of(build.summary()), closeoutException));
        }
        if (!closeoutException
                && build.summary().projectedGrossNotional().compareTo(configuration.maxGrossNotional()) > 0) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.GROSS_NOTIONAL_LIMIT, Optional.of(build.summary()), false));
        }
        if (!closeoutException
                && build.summary().projectedSuppliedMargin().compareTo(configuration.maxSuppliedMargin()) > 0) {
            return cacheDecision(proposal, decision(proposal.proposalId(), false,
                    DecisionReason.MARGIN_LIMIT, Optional.of(build.summary()), false));
        }

        OrderAcceptedEvent accepted = new OrderAcceptedEvent(proposal);
        WorkingOrder workingOrder = new WorkingOrder(
                proposal.proposalId(), proposal.instrumentId(), proposal.side(),
                proposal.quantity(), 0, proposal.quantity(),
                proposal.limitPrice().orElseThrow(), proposal.marginInput().orElseThrow(),
                proposal.occurredAt(), OrderStatus.WORKING);
        orders.put(workingOrder.orderId(), workingOrder);
        record(accepted);
        return cacheDecision(proposal, decision(proposal.proposalId(), true,
                DecisionReason.ACCEPTED, Optional.of(build.summary()), closeoutException));
    }


    private RiskDecision cacheDecision(OrderProposal proposal, RiskDecision value) {
        decisionsById.put(proposal.proposalId(), value);
        return value;
    }

    private DecisionReason validateProposal(OrderProposal proposal) {
        if (sessionId == null) {
            return DecisionReason.NO_ACTIVE_SESSION;
        }
        if (!sessionId.equals(proposal.sessionId())) {
            return DecisionReason.SESSION_MISMATCH;
        }
        if (lastEventAt != null && proposal.occurredAt().isBefore(lastEventAt)) {
            return DecisionReason.OUT_OF_ORDER;
        }
        if (!configuration.instruments().containsKey(proposal.instrumentId())) {
            return DecisionReason.UNKNOWN_INSTRUMENT;
        }
        if (proposal.quantity() <= 0) {
            return DecisionReason.INVALID_QUANTITY;
        }
        if (proposal.limitPrice().isEmpty()) {
            return DecisionReason.MISSING_PRICE;
        }
        if (proposal.limitPrice().orElseThrow().signum() <= 0) {
            return DecisionReason.INVALID_PRICE;
        }
        if (proposal.marginInput().isEmpty()) {
            return DecisionReason.MISSING_MARGIN;
        }
        return validateMargin(proposal.marginInput().orElseThrow(), proposal.instrumentId(),
                proposal.sessionId(), proposal.occurredAt());
    }

    private DecisionReason validateMargin(
            MarginInput input, String instrumentId, String expectedSession, Instant at) {
        if (!input.instrumentId().equals(instrumentId)
                || !input.sessionId().equals(expectedSession)
                || input.asOf().isAfter(at)) {
            return DecisionReason.INVALID_MARGIN;
        }
        if (Duration.between(input.asOf(), at).compareTo(configuration.maximumInputAge()) > 0) {
            return DecisionReason.STALE_MARGIN;
        }
        return null;
    }

    private ExposureBuild buildExposure(OrderProposal proposal) {
        BigDecimal executedNotional = BigDecimal.ZERO;
        BigDecimal executedMargin = BigDecimal.ZERO;

        for (Map.Entry<String, Long> entry : positions.entrySet()) {
            long quantity = entry.getValue();
            if (quantity == 0) {
                continue;
            }
            String instrumentId = entry.getKey();
            InstrumentMark instrumentMark = marks.get(instrumentId);
            if (instrumentMark == null || instrumentMark.markPrice().isEmpty()) {
                return ExposureBuild.failure(DecisionReason.MISSING_MARK);
            }
            Instant markAt = markTimes.get(instrumentId);
            if (markAt == null || markAt.isAfter(proposal.occurredAt())
                    || Duration.between(markAt, proposal.occurredAt())
                    .compareTo(configuration.maximumInputAge()) > 0) {
                return ExposureBuild.failure(DecisionReason.STALE_MARK);
            }
            if (instrumentMark.marginInput().isEmpty()) {
                return ExposureBuild.failure(DecisionReason.MISSING_MARGIN);
            }
            MarginInput margin = instrumentMark.marginInput().orElseThrow();
            DecisionReason marginFailure = validateMargin(
                    margin, instrumentId, sessionId, proposal.occurredAt());
            if (marginFailure != null) {
                return ExposureBuild.failure(marginFailure);
            }
            InstrumentRiskSpec spec = configuration.instruments().get(instrumentId);
            executedNotional = executedNotional.add(notional(
                    Math.abs(quantity), instrumentMark.markPrice().orElseThrow(), spec));
            executedMargin = executedMargin.add(marginFor(Math.abs(quantity), margin));
        }

        BigDecimal reservedNotional = BigDecimal.ZERO;
        BigDecimal reservedMargin = BigDecimal.ZERO;
        TreeMap<String, Long> buys = new TreeMap<>();
        TreeMap<String, Long> sells = new TreeMap<>();
        for (WorkingOrder order : activeOrders()) {
            DecisionReason marginFailure = validateMargin(
                    order.marginInput(), order.instrumentId(), sessionId, proposal.occurredAt());
            if (marginFailure != null) {
                return ExposureBuild.failure(marginFailure);
            }
            InstrumentRiskSpec spec = configuration.instruments().get(order.instrumentId());
            reservedNotional = reservedNotional.add(notional(
                    order.remainingQuantity(), order.limitPrice(), spec));
            reservedMargin = reservedMargin.add(marginFor(order.remainingQuantity(), order.marginInput()));
            addReservation(order.instrumentId(), order.side(), order.remainingQuantity(), buys, sells);
        }

        InstrumentRiskSpec proposedSpec = configuration.instruments().get(proposal.instrumentId());
        BigDecimal proposedNotional = notional(
                proposal.quantity(), proposal.limitPrice().orElseThrow(), proposedSpec);
        BigDecimal proposedMargin = marginFor(proposal.quantity(), proposal.marginInput().orElseThrow());
        addReservation(proposal.instrumentId(), proposal.side(), proposal.quantity(), buys, sells);

        TreeMap<String, PositionRange> ranges = new TreeMap<>();
        Set<String> instruments = new java.util.TreeSet<>();
        instruments.addAll(positions.keySet());
        instruments.addAll(buys.keySet());
        instruments.addAll(sells.keySet());
        for (String instrument : instruments) {
            long position = positions.getOrDefault(instrument, 0L);
            long minimum = Math.subtractExact(position, sells.getOrDefault(instrument, 0L));
            long maximum = Math.addExact(position, buys.getOrDefault(instrument, 0L));
            ranges.put(instrument, new PositionRange(minimum, maximum));
        }

        ExposureSummary summary = new ExposureSummary(
                executedNotional,
                reservedNotional,
                executedNotional.add(reservedNotional).add(proposedNotional),
                executedMargin,
                reservedMargin,
                executedMargin.add(reservedMargin).add(proposedMargin),
                ranges);
        return ExposureBuild.success(summary);
    }

    private boolean exceedsPositionLimit(ExposureSummary summary) {
        for (Map.Entry<String, PositionRange> entry : summary.projectedPositionRanges().entrySet()) {
            InstrumentRiskSpec spec = configuration.instruments().get(entry.getKey());
            if (spec == null || entry.getValue().minimum() == Long.MIN_VALUE
                    || entry.getValue().maximumAbsolutePosition() > spec.maxAbsolutePosition()) {
                return true;
            }
        }
        return false;
    }

    private boolean isStrictRiskReducingCloseout(OrderProposal proposal) {
        long position = positions.getOrDefault(proposal.instrumentId(), 0L);
        if (position == 0 || proposal.side().sign() == Long.signum(position)
                || proposal.quantity() > Math.abs(position)) {
            return false;
        }
        return activeOrders().stream().noneMatch(order -> order.instrumentId().equals(proposal.instrumentId()));
    }

    private List<WorkingOrder> activeOrders() {
        return orders.values().stream()
                .filter(order -> order.status() == OrderStatus.WORKING && order.remainingQuantity() > 0)
                .toList();
    }

    private static void addReservation(
            String instrumentId, OrderSide side, long quantity,
            Map<String, Long> buys, Map<String, Long> sells) {
        Map<String, Long> destination = side == OrderSide.BUY ? buys : sells;
        destination.put(instrumentId, Math.addExact(destination.getOrDefault(instrumentId, 0L), quantity));
    }

    private BigDecimal notional(long quantity, BigDecimal price, InstrumentRiskSpec spec) {
        return BigDecimal.valueOf(quantity).multiply(price).multiply(spec.contractMultiplier());
    }

    private static BigDecimal marginFor(long quantity, MarginInput margin) {
        return BigDecimal.valueOf(quantity).multiply(margin.amountPerContract());
    }

    private RiskDecision decision(
            String id, boolean accepted, DecisionReason reason,
            Optional<ExposureSummary> exposure, boolean closeoutException) {
        return new RiskDecision(id, accepted, List.of(reason), positions, activeOrderMap(),
                exposure, Optional.ofNullable(dailyProfitLoss), dailyLossHalted, closeoutException);
    }

    private Map<String, WorkingOrder> activeOrderMap() {
        TreeMap<String, WorkingOrder> active = new TreeMap<>();
        for (WorkingOrder order : activeOrders()) {
            active.put(order.orderId(), order);
        }
        return Collections.unmodifiableMap(active);
    }

    private void applyEvent(RiskEvent event) {
        Objects.requireNonNull(event, "event");
        RiskEvent previous = eventsById.get(event.eventId());
        if (previous != null) {
            if (previous.equals(event)) {
                return;
            }
            throw new RiskEventException("conflicting reuse of event ID: " + event.eventId());
        }
        if (proposalsById.containsKey(event.eventId())) {
            throw new RiskEventException("event ID was already used by a proposal: " + event.eventId());
        }
        if (lastEventAt != null && event.occurredAt().isBefore(lastEventAt)) {
            throw new RiskEventException("out-of-order event: " + event.eventId());
        }

        if (event instanceof OrderAcceptedEvent accepted) {
            RiskDecision replayed = evaluate(accepted.proposal());
            if (!replayed.accepted()) {
                throw new RiskEventException("persisted accepted proposal no longer passes replayed risk state: "
                        + replayed.reason());
            }
            return;
        }

        if (event instanceof SessionStartEvent start) {
            applySessionStart(start);
        } else {
            requireActiveSession(event.sessionId());
            if (event instanceof MarkToMarketEvent markEvent) {
                applyMark(markEvent);
            } else if (event instanceof ExecutionEvent execution) {
                applyFill(execution);
            } else if (event instanceof CancellationEvent cancellation) {
                applyCancel(cancellation);
            } else {
                throw new RiskEventException("unsupported externally supplied event type");
            }
        }
        record(event);
        refreshDailyProfitLoss(event.occurredAt());
        refreshHalt();
    }

    private void applySessionStart(SessionStartEvent event) {
        if (sessionId != null && sessionId.equals(event.sessionId())) {
            throw new RiskEventException("session ID is already active");
        }
        if (!activeOrders().isEmpty()) {
            throw new RiskEventException("cannot start a session with working orders");
        }
        for (Map.Entry<String, Long> entry : positions.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            InstrumentMark opening = event.openingMarks().get(entry.getKey());
            if (opening == null || opening.markPrice().isEmpty()) {
                throw new RiskEventException("opening mark is required for position " + entry.getKey());
            }
        }
        TreeMap<String, InstrumentMark> newMarks = new TreeMap<>();
        for (Map.Entry<String, InstrumentMark> entry : event.openingMarks().entrySet()) {
            validateMarkEntry(entry.getKey(), entry.getValue(), event.sessionId(), event.occurredAt());
            newMarks.put(entry.getKey(), entry.getValue());
        }

        sessionId = event.sessionId();
        openingPositions.clear();
        openingPositions.putAll(positions);
        openingMarks.clear();
        for (Map.Entry<String, Long> entry : openingPositions.entrySet()) {
            if (entry.getValue() != 0) {
                openingMarks.put(entry.getKey(), newMarks.get(entry.getKey()).markPrice().orElseThrow());
            }
        }
        tradeCashflows.clear();
        marks.clear();
        marks.putAll(newMarks);
        markTimes.clear();
        newMarks.keySet().forEach(instrument -> markTimes.put(instrument, event.occurredAt()));
        dailyProfitLoss = BigDecimal.ZERO;
        dailyLossHalted = false;
    }

    private void applyMark(MarkToMarketEvent event) {
        if (event.marks().isEmpty()) {
            throw new RiskEventException("mark-to-market event must contain at least one instrument");
        }
        for (Map.Entry<String, InstrumentMark> entry : event.marks().entrySet()) {
            validateMarkEntry(entry.getKey(), entry.getValue(), event.sessionId(), event.occurredAt());
        }
        marks.putAll(event.marks());
        event.marks().keySet().forEach(instrument -> markTimes.put(instrument, event.occurredAt()));
    }

    private void validateMarkEntry(
            String instrumentId, InstrumentMark instrumentMark, String expectedSession, Instant at) {
        if (!configuration.instruments().containsKey(instrumentId)) {
            throw new RiskEventException("unknown instrument in mark input: " + instrumentId);
        }
        Objects.requireNonNull(instrumentMark, "instrument mark");
        if (instrumentMark.markPrice().isPresent()
                && instrumentMark.markPrice().orElseThrow().signum() <= 0) {
            throw new RiskEventException("mark price must be positive for " + instrumentId);
        }
        if (instrumentMark.marginInput().isPresent()) {
            MarginInput margin = instrumentMark.marginInput().orElseThrow();
            if (!margin.instrumentId().equals(instrumentId)
                    || !margin.sessionId().equals(expectedSession)
                    || margin.asOf().isAfter(at)) {
                throw new RiskEventException("inconsistent margin input for " + instrumentId);
            }
        }
    }

    private void applyFill(ExecutionEvent event) {
        if (event.quantity() <= 0 || event.fillPrice().signum() <= 0) {
            throw new RiskEventException("execution quantity and price must be positive");
        }
        WorkingOrder order = orders.get(event.orderId());
        if (order == null || order.status() != OrderStatus.WORKING) {
            throw new RiskEventException("execution does not reference a working order");
        }
        if (!order.instrumentId().equals(event.instrumentId()) || order.side() != event.side()) {
            throw new RiskEventException("execution instrument or side conflicts with accepted order");
        }
        if (event.quantity() > order.remainingQuantity()) {
            throw new RiskEventException("execution exceeds remaining order quantity");
        }

        long signedQuantity = Math.multiplyExact((long) event.side().sign(), event.quantity());
        long newPosition = Math.addExact(positions.getOrDefault(event.instrumentId(), 0L), signedQuantity);
        positions.put(event.instrumentId(), newPosition);

        InstrumentRiskSpec spec = configuration.instruments().get(event.instrumentId());
        BigDecimal cashflow = BigDecimal.valueOf(-signedQuantity)
                .multiply(event.fillPrice())
                .multiply(spec.contractMultiplier());
        tradeCashflows.merge(event.instrumentId(), cashflow, BigDecimal::add);

        long filled = Math.addExact(order.filledQuantity(), event.quantity());
        long remaining = Math.subtractExact(order.remainingQuantity(), event.quantity());
        OrderStatus status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.WORKING;
        orders.put(order.orderId(), new WorkingOrder(
                order.orderId(), order.instrumentId(), order.side(), order.originalQuantity(),
                filled, remaining, order.limitPrice(), order.marginInput(), order.acceptedAt(), status));
    }

    private void applyCancel(CancellationEvent event) {
        WorkingOrder order = orders.get(event.orderId());
        if (order == null || order.status() != OrderStatus.WORKING || order.remainingQuantity() <= 0) {
            throw new RiskEventException("cancellation does not reference a working order");
        }
        orders.put(order.orderId(), new WorkingOrder(
                order.orderId(), order.instrumentId(), order.side(), order.originalQuantity(),
                order.filledQuantity(), 0, order.limitPrice(), order.marginInput(),
                order.acceptedAt(), OrderStatus.CANCELLED));
    }

    private void requireActiveSession(String eventSession) {
        if (sessionId == null) {
            throw new RiskEventException("session start event is required before this event");
        }
        if (!sessionId.equals(eventSession)) {
            throw new RiskEventException("event session does not match active session");
        }
    }

    private void record(RiskEvent event) {
        history.add(event);
        eventsById.put(event.eventId(), event);
        lastEventAt = event.occurredAt();
    }

    private void refreshDailyProfitLoss(Instant at) {
        dailyProfitLoss = calculateDailyProfitLoss(at).orElse(null);
    }

    private Optional<BigDecimal> calculateDailyProfitLoss(Instant at) {
        BigDecimal total = BigDecimal.ZERO;
        Set<String> instruments = new java.util.TreeSet<>();
        instruments.addAll(positions.keySet());
        instruments.addAll(openingPositions.keySet());
        instruments.addAll(tradeCashflows.keySet());

        for (String instrument : instruments) {
            InstrumentRiskSpec spec = configuration.instruments().get(instrument);
            if (spec == null) {
                return Optional.empty();
            }
            long currentQuantity = positions.getOrDefault(instrument, 0L);
            long openingQuantity = openingPositions.getOrDefault(instrument, 0L);
            BigDecimal currentValue = BigDecimal.ZERO;
            if (currentQuantity != 0) {
                InstrumentMark currentMark = marks.get(instrument);
                Instant observed = markTimes.get(instrument);
                if (currentMark == null || currentMark.markPrice().isEmpty() || observed == null
                        || observed.isAfter(at)
                        || Duration.between(observed, at).compareTo(configuration.maximumInputAge()) > 0) {
                    return Optional.empty();
                }
                currentValue = BigDecimal.valueOf(currentQuantity)
                        .multiply(currentMark.markPrice().orElseThrow())
                        .multiply(spec.contractMultiplier());
            }
            BigDecimal openingValue = BigDecimal.ZERO;
            if (openingQuantity != 0) {
                BigDecimal openingPrice = openingMarks.get(instrument);
                if (openingPrice == null) {
                    return Optional.empty();
                }
                openingValue = BigDecimal.valueOf(openingQuantity)
                        .multiply(openingPrice)
                        .multiply(spec.contractMultiplier());
            }
            total = total.add(currentValue).subtract(openingValue)
                    .add(tradeCashflows.getOrDefault(instrument, BigDecimal.ZERO));
        }
        return Optional.of(total);
    }

    private void refreshHalt() {
        if (dailyProfitLoss != null
                && dailyProfitLoss.compareTo(configuration.maxDailyLoss().negate()) <= 0) {
            dailyLossHalted = true;
        }
    }

    private RiskState snapshot() {
        return new RiskState(
                Optional.ofNullable(sessionId), positions, orders, marks, markTimes,
                openingPositions, openingMarks, tradeCashflows,
                Optional.ofNullable(dailyProfitLoss), dailyLossHalted,
                Optional.ofNullable(lastEventAt), history);
    }

    private record ExposureBuild(ExposureSummary summary, DecisionReason failure) {
        static ExposureBuild success(ExposureSummary summary) {
            return new ExposureBuild(summary, null);
        }

        static ExposureBuild failure(DecisionReason failure) {
            return new ExposureBuild(null, failure);
        }
    }

    public record InstrumentRiskSpec(
            String instrumentId,
            String currency,
            BigDecimal contractMultiplier,
            long maxAbsolutePosition) {
        public InstrumentRiskSpec {
            requireText(instrumentId, "instrumentId");
            requireText(currency, "currency");
            Objects.requireNonNull(contractMultiplier, "contractMultiplier");
            if (contractMultiplier.signum() <= 0 || maxAbsolutePosition <= 0) {
                throw new IllegalArgumentException("multiplier and position limit must be positive");
            }
        }
    }

    public record RiskConfiguration(
            String currency,
            Map<String, InstrumentRiskSpec> instruments,
            BigDecimal maxGrossNotional,
            BigDecimal maxSuppliedMargin,
            BigDecimal maxDailyLoss,
            Duration maximumInputAge) {
        public RiskConfiguration {
            requireText(currency, "currency");
            Objects.requireNonNull(instruments, "instruments");
            TreeMap<String, InstrumentRiskSpec> copy = new TreeMap<>(instruments);
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("at least one instrument is required");
            }
            copy.forEach((key, spec) -> {
                Objects.requireNonNull(spec, "instrument spec");
                if (!key.equals(spec.instrumentId()) || !currency.equals(spec.currency())) {
                    throw new IllegalArgumentException("instrument key or currency is inconsistent");
                }
            });
            instruments = immutableMap(copy);
            requirePositive(maxGrossNotional, "maxGrossNotional");
            requirePositive(maxSuppliedMargin, "maxSuppliedMargin");
            requirePositive(maxDailyLoss, "maxDailyLoss");
            Objects.requireNonNull(maximumInputAge, "maximumInputAge");
            if (maximumInputAge.isNegative() || maximumInputAge.isZero()) {
                throw new IllegalArgumentException("maximumInputAge must be positive");
            }
        }
    }

    /** Caller-supplied margin per contract. This is not a SPAN/ELM calculation. */
    public record MarginInput(
            String instrumentId,
            BigDecimal amountPerContract,
            String source,
            String reference,
            String sessionId,
            Instant asOf) {
        public MarginInput {
            requireText(instrumentId, "instrumentId");
            requirePositive(amountPerContract, "amountPerContract");
            requireText(source, "source");
            requireText(reference, "reference");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    public record InstrumentMark(
            Optional<BigDecimal> markPrice,
            Optional<MarginInput> marginInput) {
        public InstrumentMark {
            Objects.requireNonNull(markPrice, "markPrice");
            Objects.requireNonNull(marginInput, "marginInput");
        }

        public static InstrumentMark of(BigDecimal price, MarginInput margin) {
            return new InstrumentMark(Optional.of(price), Optional.of(margin));
        }

        public static InstrumentMark priceOnly(BigDecimal price) {
            return new InstrumentMark(Optional.of(price), Optional.empty());
        }

        public static InstrumentMark missing() {
            return new InstrumentMark(Optional.empty(), Optional.empty());
        }
    }

    /** Query-only proposal; acceptance creates an internal OrderAcceptedEvent reservation. */
    public record OrderProposal(
            String proposalId,
            String sessionId,
            Instant occurredAt,
            String instrumentId,
            OrderSide side,
            long quantity,
            Optional<BigDecimal> limitPrice,
            Optional<MarginInput> marginInput) {
        public OrderProposal {
            requireText(proposalId, "proposalId");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(instrumentId, "instrumentId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(limitPrice, "limitPrice");
            Objects.requireNonNull(marginInput, "marginInput");
        }
    }

    public enum OrderSide {
        BUY(1),
        SELL(-1);

        private final int sign;

        OrderSide(int sign) {
            this.sign = sign;
        }

        public int sign() {
            return sign;
        }
    }

    public sealed interface RiskEvent permits SessionStartEvent, OrderAcceptedEvent,
            ExecutionEvent, CancellationEvent, MarkToMarketEvent {
        String eventId();
        String sessionId();
        Instant occurredAt();
    }

    public record SessionStartEvent(
            String eventId,
            String sessionId,
            Instant occurredAt,
            Map<String, InstrumentMark> openingMarks) implements RiskEvent {
        public SessionStartEvent {
            requireText(eventId, "eventId");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(openingMarks, "openingMarks");
            openingMarks = immutableMap(new TreeMap<>(openingMarks));
        }
    }

    public record OrderAcceptedEvent(OrderProposal proposal) implements RiskEvent {
        public OrderAcceptedEvent {
            Objects.requireNonNull(proposal, "proposal");
        }

        @Override public String eventId() { return proposal.proposalId(); }
        @Override public String sessionId() { return proposal.sessionId(); }
        @Override public Instant occurredAt() { return proposal.occurredAt(); }
    }

    public record ExecutionEvent(
            String eventId,
            String sessionId,
            Instant occurredAt,
            String orderId,
            String instrumentId,
            OrderSide side,
            long quantity,
            BigDecimal fillPrice) implements RiskEvent {
        public ExecutionEvent {
            requireText(eventId, "eventId");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(orderId, "orderId");
            requireText(instrumentId, "instrumentId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(fillPrice, "fillPrice");
        }
    }

    public record CancellationEvent(
            String eventId,
            String sessionId,
            Instant occurredAt,
            String orderId) implements RiskEvent {
        public CancellationEvent {
            requireText(eventId, "eventId");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(orderId, "orderId");
        }
    }

    public record MarkToMarketEvent(
            String eventId,
            String sessionId,
            Instant occurredAt,
            Map<String, InstrumentMark> marks) implements RiskEvent {
        public MarkToMarketEvent {
            requireText(eventId, "eventId");
            requireText(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(marks, "marks");
            marks = immutableMap(new TreeMap<>(marks));
        }
    }

    public enum DecisionReason {
        ACCEPTED,
        NO_ACTIVE_SESSION,
        SESSION_MISMATCH,
        OUT_OF_ORDER,
        UNKNOWN_INSTRUMENT,
        INVALID_QUANTITY,
        MISSING_PRICE,
        INVALID_PRICE,
        MISSING_MARGIN,
        INVALID_MARGIN,
        MISSING_MARK,
        STALE_MARK,
        STALE_MARGIN,
        POSITION_LIMIT,
        GROSS_NOTIONAL_LIMIT,
        MARGIN_LIMIT,
        SESSION_HALTED,
        CONFLICTING_EVENT_ID
    }

    public enum OrderStatus {
        WORKING,
        FILLED,
        CANCELLED
    }

    public record WorkingOrder(
            String orderId,
            String instrumentId,
            OrderSide side,
            long originalQuantity,
            long filledQuantity,
            long remainingQuantity,
            BigDecimal limitPrice,
            MarginInput marginInput,
            Instant acceptedAt,
            OrderStatus status) {
        public WorkingOrder {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(limitPrice, "limitPrice");
            Objects.requireNonNull(marginInput, "marginInput");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(status, "status");
        }
    }

    public record PositionRange(long minimum, long maximum) {
        public PositionRange {
            if (minimum > maximum) {
                throw new IllegalArgumentException("minimum must not exceed maximum");
            }
        }

        public long maximumAbsolutePosition() {
            return Math.max(Math.absExact(minimum), Math.absExact(maximum));
        }
    }

    public record ExposureSummary(
            BigDecimal executedGrossNotional,
            BigDecimal workingOrderReservedGrossNotional,
            BigDecimal projectedGrossNotional,
            BigDecimal executedSuppliedMargin,
            BigDecimal workingOrderReservedMargin,
            BigDecimal projectedSuppliedMargin,
            Map<String, PositionRange> projectedPositionRanges) {
        public ExposureSummary {
            Objects.requireNonNull(executedGrossNotional, "executedGrossNotional");
            Objects.requireNonNull(workingOrderReservedGrossNotional, "workingOrderReservedGrossNotional");
            Objects.requireNonNull(projectedGrossNotional, "projectedGrossNotional");
            Objects.requireNonNull(executedSuppliedMargin, "executedSuppliedMargin");
            Objects.requireNonNull(workingOrderReservedMargin, "workingOrderReservedMargin");
            Objects.requireNonNull(projectedSuppliedMargin, "projectedSuppliedMargin");
            projectedPositionRanges = immutableMap(new TreeMap<>(projectedPositionRanges));
        }
    }

    public record RiskDecision(
            String proposalId,
            boolean accepted,
            List<DecisionReason> reasons,
            Map<String, Long> executedPositions,
            Map<String, WorkingOrder> workingOrderReservations,
            Optional<ExposureSummary> exposure,
            Optional<BigDecimal> dailyProfitLoss,
            boolean dailyLossHalted,
            boolean riskReducingHaltException) {
        public RiskDecision {
            requireText(proposalId, "proposalId");
            reasons = List.copyOf(reasons);
            executedPositions = immutableMap(new TreeMap<>(executedPositions));
            workingOrderReservations = immutableMap(new TreeMap<>(workingOrderReservations));
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(dailyProfitLoss, "dailyProfitLoss");
        }

        public DecisionReason reason() {
            return reasons.getFirst();
        }
    }

    public record RiskState(
            Optional<String> activeSessionId,
            Map<String, Long> executedPositions,
            Map<String, WorkingOrder> orders,
            Map<String, InstrumentMark> latestMarks,
            Map<String, Instant> markObservedAt,
            Map<String, Long> sessionOpeningPositions,
            Map<String, BigDecimal> sessionOpeningMarks,
            Map<String, BigDecimal> sessionTradeCashflows,
            Optional<BigDecimal> dailyProfitLoss,
            boolean dailyLossHalted,
            Optional<Instant> lastEventAt,
            List<RiskEvent> eventHistory) {
        public RiskState {
            Objects.requireNonNull(activeSessionId, "activeSessionId");
            executedPositions = immutableMap(new TreeMap<>(executedPositions));
            orders = immutableMap(new TreeMap<>(orders));
            latestMarks = immutableMap(new TreeMap<>(latestMarks));
            markObservedAt = immutableMap(new TreeMap<>(markObservedAt));
            sessionOpeningPositions = immutableMap(new TreeMap<>(sessionOpeningPositions));
            sessionOpeningMarks = immutableMap(new TreeMap<>(sessionOpeningMarks));
            sessionTradeCashflows = immutableMap(new TreeMap<>(sessionTradeCashflows));
            Objects.requireNonNull(dailyProfitLoss, "dailyProfitLoss");
            Objects.requireNonNull(lastEventAt, "lastEventAt");
            eventHistory = List.copyOf(eventHistory);
        }

        public Map<String, WorkingOrder> workingOrderReservations() {
            TreeMap<String, WorkingOrder> active = new TreeMap<>();
            orders.forEach((id, order) -> {
                if (order.status() == OrderStatus.WORKING && order.remainingQuantity() > 0) {
                    active.put(id, order);
                }
            });
            return immutableMap(active);
        }
    }

    public static final class RiskEventException extends IllegalArgumentException {
        public RiskEventException(String message) {
            super(message);
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
