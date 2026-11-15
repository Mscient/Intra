package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Frozen intraday hypothesis; calendar exits are decided before the final bar opens. */
final class SessionSmaStrategy implements PortfolioStrategy {
    private final PortfolioSmaCrossoverStrategy delegate;
    private final ValidatedPortfolioDataset dataset;
    private final String codeSha256;
    private final String frozenTrialSha256;
    SessionSmaStrategy(PortfolioSmaCrossoverStrategy delegate, ValidatedPortfolioDataset dataset, String frozenTrialSha256) throws java.io.IOException {
        this.delegate = delegate; this.dataset = dataset; this.frozenTrialSha256 = frozenTrialSha256;
        this.codeSha256 = NiftyResearchWorkflow.hash(NiftyResearchWorkflow.actualCode());
    }
    public String id() { return "NIFTY_SESSION_SMA_RESEARCH_V1"; }
    public Map<String,String> parameters() {
        var result = new TreeMap<>(delegate.parameters());
        result.put("code_artifact_sha256",codeSha256);
        result.put("frozen_trial_sha256",frozenTrialSha256);
        result.put("session_policy", "RESET_WARMUP_EXIT_NEXT_FINAL_BAR_OPEN");
        return Collections.unmodifiableMap(result);
    }
    public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> history, Instant time,
            PortfolioAccountingLedger.Snapshot snapshot) {
        var last = history.getLast();
        var source = dataset.datasetsByInstrument().values().iterator().next();
        var finalOpen = source.sessionCalendar().get(last.sessionDate()).lastBarTimestamp().orElseThrow();
        if (!last.timestamp().isBefore(finalOpen.minus(IntervalSupport.duration(last.barsByInstrument().values().iterator().next().interval())))) {
            var flat = new TreeMap<String,Integer>(); last.barsByInstrument().keySet().forEach(id -> flat.put(id,0));
            return new PortfolioTarget(flat, "preregistered final-bar-open exit");
        }
        int start = history.size()-1;
        while (start > 0 && history.get(start-1).sessionDate().equals(last.sessionDate())) start--;
        return delegate.evaluate(history.subList(start, history.size()),time,snapshot);
    }
}
