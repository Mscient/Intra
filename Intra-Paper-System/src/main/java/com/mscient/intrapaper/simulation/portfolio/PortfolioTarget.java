package com.mscient.intrapaper.simulation.portfolio;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete signed-lot target for every instrument in one portfolio strategy decision. */
public record PortfolioTarget(Map<String, Integer> signedLotsByInstrument, String reason) {
    public PortfolioTarget {
        Objects.requireNonNull(signedLotsByInstrument, "signedLotsByInstrument");
        TreeMap<String, Integer> ordered = new TreeMap<>();
        signedLotsByInstrument.forEach((instrumentId, signedLots) -> {
            if (instrumentId == null || !instrumentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("Invalid portfolio target instrument ID: " + instrumentId);
            }
            if (signedLots == null) throw new IllegalArgumentException("Target lots must not be null: " + instrumentId);
            if (ordered.putIfAbsent(instrumentId, signedLots) != null) {
                throw new IllegalArgumentException("Duplicate portfolio target instrument: " + instrumentId);
            }
        });
        if (ordered.isEmpty()) throw new IllegalArgumentException("Portfolio target must include instruments");
        signedLotsByInstrument = Collections.unmodifiableMap(ordered);
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || !reason.equals(reason.strip())) {
            throw new IllegalArgumentException("reason must be non-empty and trimmed");
        }
    }
}
