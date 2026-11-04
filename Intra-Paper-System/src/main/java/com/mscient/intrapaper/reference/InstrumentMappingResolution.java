package com.mscient.intrapaper.reference;

import java.util.Objects;

/** Resolved stable identity plus the exact mapping row and canonical registry fingerprint. */
public record InstrumentMappingResolution(DatedInstrumentMapping mapping, String registrySha256) {
    public InstrumentMappingResolution {
        mapping = Objects.requireNonNull(mapping, "mapping");
        registrySha256 = Objects.requireNonNull(registrySha256, "registrySha256");
        if (!registrySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("registrySha256 must be a lowercase SHA-256 digest");
        }
    }

    public String instrumentId() { return mapping.instrumentId(); }
    public String mappingId() { return mapping.mappingId(); }
}
