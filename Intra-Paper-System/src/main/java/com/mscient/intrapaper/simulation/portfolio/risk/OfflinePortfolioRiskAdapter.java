package com.mscient.intrapaper.simulation.portfolio.risk;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.riskgateway.RiskGateway;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import com.mscient.intrapaper.simulation.portfolio.PortfolioExecution;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine.OrderIntent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs.*;

/** Offline, single-caller adapter. It owns its gateway; it cannot route orders or infer external positions. */
public final class OfflinePortfolioRiskAdapter {
    public record Decision(OrderIntent intent, Instant frameTime, boolean accepted, String reason,
            String bindingSha256, RiskState before, Optional<RiskDecision> gatewayDecision) { }
    private record Command(String kind, String id, List<String> fields) {
        Command { fields = List.copyOf(fields); }
        String encoded() { return pack(List.of(kind, id, pack(fields))); }
    }
    private final PortfolioRiskInputs inputs;
    private final String inputsHash;
    private RiskGateway gateway;
    private final LinkedHashMap<String, Command> commands = new LinkedHashMap<>();
    private final LinkedHashMap<String, Decision> decisions = new LinkedHashMap<>();
    private final List<String> trace = new ArrayList<>();
    private Instant frameTime;
    private String session;
    private String frameFailure = "NO_FRAME";
    private String uncertainty = "";

    public OfflinePortfolioRiskAdapter(PortfolioRiskInputs inputs) {
        this.inputs = Objects.requireNonNull(inputs); this.inputsHash = inputs.fingerprint();
        gateway = new RiskGateway(inputs.configuration());
    }
    public RiskState state() { return gateway.state(); }
    public String uncertainty() { return uncertainty; }
    public List<Decision> decisions() { return List.copyOf(decisions.values()); }
    public String inputsFingerprint() { return inputsHash; }

    /** Full ledger positions, including zeroes, must be supplied at the exact frame open. */
    public void beginFrame(Instant at, String sessionId, Map<String, Long> signedUnits, Map<String, Integer> lotSizes) {
        run(new Command("FRAME", "FRAME:" + at, List.of(at.toString(), sessionId, map(signedUnits), map(lotSizes))));
    }
    public Decision evaluate(OrderIntent intent) {
        Command command = new Command("PROPOSE", "PROPOSE:" + intent.intentId(), List.of(Long.toString(intent.sequence()),
                intent.intentId(), intent.decisionTimestamp().toString(), intent.executionTimestamp().toString(), intent.instrumentId(),
                intent.side().name(), Long.toString(intent.currentUnits()), Long.toString(intent.targetUnits()),
                Long.toString(intent.requestedUnits()), intent.reason()));
        run(command);
        return decisions.get(intent.intentId());
    }
    /** An incremental fill, never a cumulative quantity. Repeated identical event IDs are no-ops. */
    public void execution(String eventId, String orderId, Instant at, long quantityUnits, BigDecimal price) {
        run(new Command("EXECUTE", eventId, List.of(orderId, at.toString(), Long.toString(quantityUnits), price.toPlainString())));
    }
    /** Confirmation that every residual contract was cancelled, including a confirmed IOC miss. */
    public void cancel(String eventId, String orderId, Instant at) {
        run(new Command("CANCEL", eventId, List.of(orderId, at.toString())));
    }
    public void uncertain(String eventId, String reason) {
        requireText(reason); run(new Command("UNCERTAIN", eventId, List.of(reason)));
    }
    /** Exact caller confirmation only; never modifies positions or silently releases a reservation. */
    public void reconcile(String eventId, Map<String, Long> signedUnits, Map<String, Long> remainingUnitsByOrder) {
        run(new Command("RECONCILE", eventId, List.of(map(signedUnits), map(remainingUnitsByOrder))));
    }
    /** Assertion used by replay after applying its accounting entries; it does not clear uncertainty. */
    public void assertReconciled(Map<String, Long> signedUnits, Map<String, Long> remainingUnitsByOrder) {
        if (!positions().equals(signedUnits) || !reservations().equals(remainingUnitsByOrder))
            throw new IllegalStateException("Risk/portfolio positions or reservations differ");
        if (!uncertainty.isEmpty()) throw new IllegalStateException("Risk execution remains uncertain: " + uncertainty);
    }
    public Map<String, Long> positions() {
        Map<String, Long> result = new TreeMap<>();
        inputs.lotSizes().forEach((id, lot) -> result.put(id, Math.multiplyExact(state().executedPositions().getOrDefault(id, 0L), lot)));
        return sorted(result);
    }
    public Map<String, Long> reservations() {
        Map<String, Long> result = new TreeMap<>();
        state().orders().forEach((id, order) -> {
            if (order.status() == OrderStatus.WORKING) result.put(id, Math.multiplyExact(order.remainingQuantity(), inputs.lotSizes().get(order.instrumentId())));
        });
        return sorted(result);
    }
    private void run(Command command) {
        requireText(command.id());
        Command previous = commands.get(command.id());
        if (previous != null) {
            if (!previous.equals(command)) throw new IllegalArgumentException("Conflicting adapter event ID: " + command.id());
            return;
        }
        switch (command.kind()) {
            case "FRAME" -> frame(command);
            case "PROPOSE" -> propose(command);
            case "EXECUTE" -> fill(command);
            case "CANCEL" -> cancellation(command);
            case "UNCERTAIN" -> uncertainty = command.fields().getFirst();
            case "RECONCILE" -> {
                if (!positions().equals(longMap(command.fields().get(0))) || !reservations().equals(longMap(command.fields().get(1))))
                    throw new IllegalStateException("Reconciliation must exactly confirm signed positions and every reservation");
                uncertainty = "";
            }
            default -> throw new IllegalArgumentException("Unknown adapter command");
        }
        commands.put(command.id(), command);
        trace.add(csv(command.id(), command.kind(), frameTime == null ? "" : frameTime.toString(),
                inputsHash, sha(command.encoded()), map(positions()), map(reservations()), uncertainty,
                sha(state().toString())));
    }
    private void frame(Command command) {
        var f = command.fields(); Instant at = Instant.parse(f.get(0)); String newSession = f.get(1);
        requireText(newSession);
        if (frameTime != null && !at.isAfter(frameTime)) throw new IllegalArgumentException("Frames must increase strictly");
        Map<String, Long> suppliedPositions = longMap(f.get(2));
        Map<String, Long> suppliedLots = longMap(f.get(3));
        Map<String, Long> configuredLots = new TreeMap<>(); inputs.lotSizes().forEach((id, lot) -> configuredLots.put(id, lot.longValue()));
        if (!configuredLots.equals(suppliedLots)) throw new IllegalArgumentException("Dated replay lot sizes differ from configured risk contract multipliers");
        if (!suppliedPositions.keySet().equals(inputs.lotSizes().keySet())) throw new IllegalArgumentException("Complete signed position map required");
        frameTime = at; session = newSession;
        frameFailure = validateFrame(at, newSession);
        if (!positions().equals(suppliedPositions)) {
            uncertainty = "POSITION_MISMATCH"; frameFailure = "POSITION_MISMATCH";
        }
        if (!frameFailure.isEmpty()) return;
        Map<String, InstrumentMark> marks = new TreeMap<>();
        inputs.frames().get(at).samples().forEach((id, sample) -> marks.put(id, new InstrumentMark(sample.mark(), sample.margin())));
        RiskGateway candidate = candidate();
        if (!candidate.state().activeSessionId().equals(Optional.of(newSession))) {
            if (!reservations().isEmpty() || !uncertainty.isEmpty()) { frameFailure = "UNRECONCILED_SESSION_BOUNDARY"; return; }
            candidate.startSession(new SessionStartEvent("RG:" + command.id(), newSession, at, marks));
        } else candidate.applyMarkToMarket(new MarkToMarketEvent("RG:" + command.id(), newSession, at, marks));
        gateway = candidate;
    }
    private String validateFrame(Instant at, String sessionId) {
        var frame = inputs.frames().get(at);
        if (frame == null) return "MISSING_FRAME_INPUTS";
        if (!frame.sessionId().equals(sessionId)) return "RISK_SESSION_MISMATCH";
        for (String id : inputs.lotSizes().keySet()) {
            var sample = frame.samples().get(id);
            if (sample == null || sample.mark().isEmpty()) return "MISSING_MARK:" + id;
            if (sample.markAsOf().orElseThrow().isAfter(at)) return "FUTURE_MARK:" + id;
            if (stale(sample.markAsOf().orElseThrow(), at)) return "STALE_MARK:" + id;
            if (sample.margin().isEmpty()) return "MISSING_MARGIN:" + id;
            MarginInput margin = sample.margin().orElseThrow();
            if (!margin.instrumentId().equals(id) || !margin.sessionId().equals(sessionId) || margin.asOf().isAfter(at))
                return "INVALID_MARGIN:" + id;
            if (stale(margin.asOf(), at)) return "STALE_MARGIN:" + id;
        }
        return "";
    }
    private boolean stale(Instant asOf, Instant at) { return Duration.between(asOf, at).compareTo(inputs.configuration().maximumInputAge()) > 0; }
    private void propose(Command command) {
        var f = command.fields();
        OrderIntent intent = new OrderIntent(Long.parseLong(f.get(0)), f.get(1), Instant.parse(f.get(2)), Instant.parse(f.get(3)),
                f.get(4), PortfolioExecution.Side.valueOf(f.get(5)), Long.parseLong(f.get(6)), Long.parseLong(f.get(7)), Long.parseLong(f.get(8)), f.get(9));
        if (frameTime == null || !frameTime.equals(intent.executionTimestamp())) throw new IllegalArgumentException("Intent must bind to the exact active frame time");
        RiskState before = state(); String failure = frameFailure;
        if (!uncertainty.isEmpty()) failure = "EXECUTION_UNCERTAIN:" + uncertainty;
        Integer lot = inputs.lotSizes().get(intent.instrumentId());
        if (lot == null) failure = "UNKNOWN_INSTRUMENT";
        else if (intent.requestedUnits() % lot != 0 || intent.targetUnits() % lot != 0 || intent.currentUnits() % lot != 0) failure = "NON_CONTRACT_QUANTITY";
        else if (intent.currentUnits() != positions().get(intent.instrumentId())) {
            uncertainty = "INTENT_POSITION_MISMATCH"; failure = uncertainty;
        }
        Optional<RiskDecision> risk = Optional.empty();
        if (failure.isEmpty()) {
            var sample = inputs.frames().get(frameTime).samples().get(intent.instrumentId());
            OrderProposal proposal = new OrderProposal(intent.intentId(), session, frameTime, intent.instrumentId(),
                    OrderSide.valueOf(intent.side().name()), intent.requestedUnits() / lot,
                    intent.side() == PortfolioExecution.Side.BUY ? sample.buyLimit() : sample.sellLimit(), sample.margin());
            RiskGateway candidate = candidate(); RiskDecision decision = candidate.evaluate(proposal);
            gateway = candidate; risk = Optional.of(decision); failure = decision.reason().name();
        }
        String binding = sha(pack(List.of(inputsHash, command.encoded(), frameTime.toString(), session, before.toString(), uncertainty)));
        decisions.put(intent.intentId(), new Decision(intent, frameTime, risk.map(RiskDecision::accepted).orElse(false), failure, binding, before, risk));
    }
    private void fill(Command command) {
        var f = command.fields(); WorkingOrder order = state().orders().get(f.get(0));
        requireFrameTime(Instant.parse(f.get(1)));
        if (order == null) throw new IllegalArgumentException("No accepted order for execution");
        long units = Long.parseLong(f.get(2)); int lot = inputs.lotSizes().get(order.instrumentId());
        if (units <= 0 || units % lot != 0) throw new IllegalArgumentException("Execution must be positive whole contracts");
        BigDecimal price = new BigDecimal(f.get(3));
        if (price.compareTo(order.limitPrice()) > 0)
            throw new IllegalArgumentException("Execution price exceeds the caller-supplied risk valuation ceiling");
        RiskGateway candidate = candidate();
        candidate.applyExecution(new ExecutionEvent("RG:" + command.id(), state().activeSessionId().orElseThrow(), Instant.parse(f.get(1)),
                order.orderId(), order.instrumentId(), order.side(), units / lot, price));
        gateway = candidate;
    }
    private void cancellation(Command command) {
        var f = command.fields(); RiskGateway candidate = candidate();
        requireFrameTime(Instant.parse(f.get(1)));
        candidate.applyCancellation(new CancellationEvent("RG:" + command.id(), state().activeSessionId().orElseThrow(), Instant.parse(f.get(1)), f.get(0)));
        gateway = candidate;
    }
    private RiskGateway candidate() { return RiskGateway.restore(inputs.configuration(), state().eventHistory()); }
    private void requireFrameTime(Instant at) {
        if (!at.equals(frameTime)) throw new IllegalArgumentException("Execution/cancellation must use the active frame time");
    }

    /** Portable deterministic checkpoint, including rejected decisions and unresolved uncertainty. Caller persists atomically. */
    public byte[] checkpoint() {
        StringBuilder out = new StringBuilder("OFFLINE_PORTFOLIO_RISK_V1\n" + inputsHash + "\n");
        commands.values().forEach(c -> out.append(c.encoded()).append('\n'));
        out.append("END:").append(commands.size()).append(':').append(sha(out.toString())).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static OfflinePortfolioRiskAdapter restore(PortfolioRiskInputs inputs, byte[] checkpoint, String expectedSha256) {
        if (!com.mscient.intrapaper.provenance.RunConfig.sha256(checkpoint).equals(expectedSha256))
            throw new IllegalArgumentException("Checkpoint digest mismatch");
        if (checkpoint.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Checkpoint exceeds 64 MiB");
        String[] lines = new String(checkpoint, StandardCharsets.UTF_8).split("\n", -1);
        OfflinePortfolioRiskAdapter adapter = new OfflinePortfolioRiskAdapter(inputs);
        if (lines.length < 4 || !lines[0].equals("OFFLINE_PORTFOLIO_RISK_V1") || !lines[1].equals(adapter.inputsHash))
            throw new IllegalArgumentException("Checkpoint version or risk inputs/configuration mismatch");
        for (int n = 2; n < lines.length - 2; n++) {
            var values = unpack(lines[n]);
            if (values.size() != 3) throw new IllegalArgumentException("Malformed checkpoint command");
            adapter.run(new Command(values.get(0), values.get(1), unpack(values.get(2))));
        }
        if (!Arrays.equals(checkpoint, adapter.checkpoint())) throw new IllegalArgumentException("Truncated or noncanonical checkpoint");
        return adapter;
    }
    public String decisionsCsv() {
        StringBuilder out = new StringBuilder("intent_id,decision_time_utc,frame_time_utc,instrument_id,side,current_units,target_units,requested_units,accepted,reason,inputs_sha256,binding_sha256,pre_state_base64,gateway_decision_base64\n");
        decisions.values().forEach(d -> out.append(csv(d.intent().intentId(), d.intent().decisionTimestamp().toString(), d.frameTime().toString(),
                d.intent().instrumentId(), d.intent().side().name(), Long.toString(d.intent().currentUnits()), Long.toString(d.intent().targetUnits()),
                Long.toString(d.intent().requestedUnits()), Boolean.toString(d.accepted()), d.reason(), inputsHash, d.bindingSha256(),
                pack(List.of(d.before().toString())), pack(List.of(d.gatewayDecision().map(Object::toString).orElse("ADAPTER_REJECTED"))))).append('\n'));
        return out.toString();
    }
    public String traceCsv() { return "event_id,event_type,frame_time_utc,inputs_sha256,command_sha256,positions_units_base64,remaining_units_base64,uncertainty,state_sha256\n" + String.join("\n", trace) + (trace.isEmpty() ? "" : "\n"); }
    private static String csv(String... fields) { return Arrays.stream(fields).map(CsvTable::escape).collect(java.util.stream.Collectors.joining(",")); }
    private static String map(Map<String, ? extends Number> values) {
        List<String> fields = new ArrayList<>(); new TreeMap<>(values).forEach((k, v) -> fields.add(pack(List.of(k, v.toString()))));
        return fields.isEmpty() ? "" : pack(fields);
    }
    private static Map<String, Long> longMap(String value) {
        Map<String, Long> result = new TreeMap<>();
        if (!value.isEmpty()) for (String field : unpack(value)) {
            var pair = unpack(field);
            if (pair.size() != 2 || result.putIfAbsent(pair.get(0), Long.parseLong(pair.get(1))) != null)
                throw new IllegalArgumentException("Malformed quantity map");
        }
        return sorted(result);
    }
}
