package com.mscient.intrapaper.simulation.portfolio.risk;

import com.mscient.intrapaper.simulation.portfolio.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Gates the delegate before simulated execution and receives validated replay outcomes. Single replay use. */
public final class PortfolioRiskFillModel implements PortfolioFillModel, PortfolioReplayLifecycle {
    private final PortfolioFillModel delegate;
    private final OfflinePortfolioRiskAdapter adapter;
    private Instant lastFrame;
    public PortfolioRiskFillModel(PortfolioFillModel delegate, OfflinePortfolioRiskAdapter adapter) {
        this.delegate = Objects.requireNonNull(delegate); this.adapter = Objects.requireNonNull(adapter);
        if (delegate instanceof PortfolioReplayLifecycle) throw new IllegalArgumentException("Nested replay lifecycle is unsupported");
    }
    @Override public String id() { return "OFFLINE_RISK_GATEWAY_IOC_V1"; }
    @Override public Map<String, String> parameters() {
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("risk_inputs_sha256", adapter.inputsFingerprint());
        parameters.put("delegate_id", delegate.id());
        delegate.parameters().forEach((key, value) -> parameters.put("delegate." + key, value));
        parameters.put("margin_basis", "CALLER_SUPPLIED_PROXY_NOT_SPAN_ELM");
        return Collections.unmodifiableMap(parameters);
    }
    @Override public void beforeFrame(Instant timestamp, LocalDate session, Map<String, Long> units, Map<String, Integer> lots) {
        if (lastFrame != null && !timestamp.isAfter(lastFrame)) throw new IllegalStateException("Create a fresh risk adapter for each replay");
        adapter.beginFrame(timestamp, session.toString(), units, lots); lastFrame = timestamp;
    }
    @Override public Optional<Fill> fill(PortfolioReplayEngine.OrderIntent intent, OpeningMarketPoint opening) {
        if (!opening.timestamp().equals(intent.executionTimestamp())) throw new IllegalArgumentException("Opening/intent timestamp mismatch");
        if (!adapter.evaluate(intent).accepted()) return Optional.empty();
        return delegate.fill(intent, opening);
    }
    @Override public void afterOutcome(PortfolioReplayEngine.OrderOutcome outcome) {
        var decision = adapter.decisions().stream().filter(d -> d.intent().intentId().equals(outcome.intent().intentId())).findFirst().orElseThrow();
        if (!decision.accepted()) {
            if (outcome.execution().isPresent()) throw new IllegalStateException("Rejected risk proposal cannot execute");
            return;
        }
        outcome.execution().ifPresent(fill -> adapter.execution("FILL:" + fill.executionId(), outcome.intent().intentId(),
                fill.timestamp(), fill.quantityUnits(), fill.price()));
        if (outcome.filledUnits() < outcome.intent().requestedUnits())
            adapter.cancel("IOC:" + outcome.intent().intentId(), outcome.intent().intentId(), outcome.intent().executionTimestamp());
    }
    @Override public void executionUncertain(PortfolioReplayEngine.OrderIntent intent, RuntimeException failure) {
        adapter.uncertain("UNCERTAIN:" + intent.intentId(), "SIMULATED_EXECUTION_FAILURE:" + failure.getClass().getSimpleName());
    }
    @Override public void afterFrame(Map<String, Long> signedUnits) { adapter.assertReconciled(signedUnits, Map.of()); }
}
