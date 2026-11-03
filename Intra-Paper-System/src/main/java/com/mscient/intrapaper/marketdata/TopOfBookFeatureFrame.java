package com.mscient.intrapaper.marketdata;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Snapshot features plus continuity information for one normalized quote-journal event. */
public record TopOfBookFeatureFrame(
        TopOfBookFeatures snapshot,
        boolean hasPriorSnapshot,
        boolean sequenceContinuous,
        boolean sameSourceAsPrior,
        long missingSequenceNumbers,
        Optional<BigDecimal> l1OrderFlowImbalanceUnits) {

    public TopOfBookFeatureFrame {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(l1OrderFlowImbalanceUnits, "l1OrderFlowImbalanceUnits");
        if (missingSequenceNumbers < 0
                || sequenceContinuous != (hasPriorSnapshot && missingSequenceNumbers == 0)
                || sameSourceAsPrior && !hasPriorSnapshot
                || (!hasPriorSnapshot && missingSequenceNumbers != 0)
                || (l1OrderFlowImbalanceUnits.isPresent() && (!sequenceContinuous || !sameSourceAsPrior))) {
            throw new IllegalArgumentException("Invalid quote-journal feature continuity state");
        }
    }
}
