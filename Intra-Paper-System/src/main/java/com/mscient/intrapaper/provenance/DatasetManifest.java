package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.reference.DataRightsStatus;
import com.mscient.intrapaper.reference.TimestampRole;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provenance metadata for one frozen local dataset; it contains no market rows or credentials. */
public record DatasetManifest(
        int schemaVersion,
        String datasetId,
        String sourceName,
        Instant acquiredAtUtc,
        String timezone,
        TimestampRole timestampRole,
        DataRightsStatus dataRightsStatus,
        Optional<String> dataRightsReference,
        String instrumentMasterSnapshotId,
        String instrumentMasterSha256,
        Optional<String> sessionCalendarSha256,
        Map<String, String> inputFileSha256) {

    public DatasetManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported DatasetManifest schema version: " + schemaVersion);
        }
        datasetId = requireNonBlank(datasetId, "datasetId");
        sourceName = requireNonBlank(sourceName, "sourceName");
        acquiredAtUtc = Objects.requireNonNull(acquiredAtUtc, "acquiredAtUtc");
        timezone = requireNonBlank(timezone, "timezone");
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException invalidTimezone) {
            throw new IllegalArgumentException("timezone must be a valid zone ID", invalidTimezone);
        }
        timestampRole = Objects.requireNonNull(timestampRole, "timestampRole");
        dataRightsStatus = Objects.requireNonNull(dataRightsStatus, "dataRightsStatus");
        dataRightsReference = Objects.requireNonNull(dataRightsReference, "dataRightsReference")
                .map(value -> requireLogicalId(value, "dataRightsReference"));
        if (dataRightsStatus == DataRightsStatus.AUTHORIZED_LOCAL_REPLAY && dataRightsReference.isEmpty()) {
            throw new IllegalArgumentException("Authorized replay requires a reviewable data-rights reference");
        }
        instrumentMasterSnapshotId = requireNonBlank(instrumentMasterSnapshotId, "instrumentMasterSnapshotId");
        requireSha256(instrumentMasterSha256, "instrumentMasterSha256");
        sessionCalendarSha256 = Objects.requireNonNull(sessionCalendarSha256, "sessionCalendarSha256");
        sessionCalendarSha256.ifPresent(value -> requireSha256(value, "sessionCalendarSha256"));
        inputFileSha256 = Map.copyOf(Objects.requireNonNull(inputFileSha256, "inputFileSha256"));
        if (inputFileSha256.isEmpty()) {
            throw new IllegalArgumentException("At least one input file hash is required");
        }
        inputFileSha256.forEach((inputId, hash) -> {
            requireLogicalId(inputId, "inputId");
            requireSha256(hash, "input file hash");
        });
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and trimmed");
        }
        return value;
    }

    private static String requireLogicalId(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException(fieldName + " must be a logical ID, not a path");
        }
        if (value.contains("?") || value.contains("#") || value.contains("@")) {
            throw new IllegalArgumentException(fieldName + " must not contain query, fragment, or user-info syntax");
        }
        return value;
    }

    private static void requireSha256(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hex digest");
        }
    }
}
