package com.mscient.intrapaper.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Focused executable checks for the offline dated instrument mapping registry. */
public final class DatedInstrumentMappingRegistryTest {
    private static final String SHA = "a".repeat(64);
    private static final String HEADER = String.join(",", DatedInstrumentMappingRegistry.HEADERS);
    private static final String FIRST = row("m-101-v1", "NSE", "101", "NIFTY-FUT-2026-01",
            "NIFTY26JANFUT", "FUTURE", "2026-01-29", "", "",
            "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
    private static final String SECOND = row("m-101-v2", "NSE", "101", "NIFTY-FUT-2026-02",
            "NIFTY26FEBFUT", "FUTURE", "2026-02-26", "", "",
            "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z");

    private DatedInstrumentMappingRegistryTest() { }

    public static void main(String[] args) throws Exception {
        resolvesHalfOpenUtcIntervalsAndBoundaries();
        rejectsDuplicateAndOverlappingMappingsWithRows();
        rejectsUnknownTokensAndMalformedInput();
        isIndependentOfRowOrderAndMachineTimezone();
        System.out.println("DatedInstrumentMappingRegistry focused checks passed.");
    }

    private static void resolvesHalfOpenUtcIntervalsAndBoundaries() throws Exception {
        DatedInstrumentMappingRegistry registry = load(FIRST + "\n" + SECOND + "\n");
        expectUnknown(registry, "2025-12-31T23:59:59Z");
        equal("NIFTY-FUT-2026-01", registry.resolve("NSE", "101", at("2026-01-01T00:00:00Z")).instrumentId());
        equal("m-101-v1", registry.resolve("NSE", "101", at("2026-01-01T12:00:00Z")).mappingId());
        equal("NIFTY-FUT-2026-02", registry.resolve("NSE", "101", at("2026-01-02T00:00:00Z")).instrumentId());
        equal("m-101-v2", registry.resolve("NSE", "101", at("2026-01-02T23:59:59Z")).mappingId());
        expectUnknown(registry, "2026-01-03T00:00:00Z");
    }

    private static void rejectsDuplicateAndOverlappingMappingsWithRows() throws Exception {
        String duplicate = row("m-101-v1", "NSE", "102", "OTHER", "OTHER", "EQUITY", "", "", "",
                "2026-01-01T00:00:00Z", "");
        IllegalArgumentException duplicateError = expectFailure(() -> load(FIRST + "\n" + duplicate + "\n"));
        contains(duplicateError.getMessage(), "Duplicate mapping_id");
        contains(duplicateError.getMessage(), "CSV rows 2 and 3");

        String overlap = row("m-overlap", "NSE", "101", "OTHER", "OTHER", "FUTURE", "2026-02-26", "", "",
                "2026-01-01T12:00:00Z", "2026-01-02T12:00:00Z");
        IllegalArgumentException overlapError = expectFailure(() -> load(FIRST + "\n" + overlap + "\n"));
        contains(overlapError.getMessage(), "m-101-v1");
        contains(overlapError.getMessage(), "m-overlap");
        contains(overlapError.getMessage(), "CSV row 2");
        contains(overlapError.getMessage(), "CSV row 3");
    }

    private static void rejectsUnknownTokensAndMalformedInput() throws Exception {
        DatedInstrumentMappingRegistry registry = load(FIRST + "\n");
        IllegalArgumentException unknown = expectFailure(() -> registry.resolve("NSE", "999", at("2026-01-01T12:00:00Z")));
        contains(unknown.getMessage(), "NSE");
        contains(unknown.getMessage(), "999");
        IllegalArgumentException offset = expectFailure(() -> load(row("bad-time", "NSE", "202", "BAD", "BAD",
                "EQUITY", "", "", "", "2026-01-01T01:00:00+01:00", "")));
        contains(offset.getMessage(), "ending in Z");
        IllegalArgumentException option = expectFailure(() -> load(row("bad-option", "NSE", "203", "OPT", "OPT",
                "OPTION", "2026-01-29", "", "", "2026-01-01T00:00:00Z", "")));
        contains(option.getMessage(), "positive strike");
        IllegalArgumentException malformedCsv = expectFailure(() -> load(FIRST + "\n\"unterminated"));
        contains(malformedCsv.getMessage(), "unterminated quoted field");
    }

    private static void isIndependentOfRowOrderAndMachineTimezone() throws Exception {
        String originalTimezone = System.getProperty("user.timezone");
        try {
            System.setProperty("user.timezone", "America/Los_Angeles");
            DatedInstrumentMappingRegistry first = load(FIRST + "\n" + SECOND + "\n");
            System.setProperty("user.timezone", "Asia/Kolkata");
            DatedInstrumentMappingRegistry reversed = load(SECOND + "\n" + FIRST + "\n");
            equal(first.registrySha256(), reversed.registrySha256());
            equal(first.mappings(), reversed.mappings());
            equal(first.resolve("NSE", "101", at("2026-01-02T00:00:00Z")),
                    reversed.resolve("NSE", "101", at("2026-01-02T00:00:00Z")));
            equal("m-101-v2", first.resolve("NSE", "101", at("2026-01-02T00:00:00Z")).mappingId());
            equal("m-101-v2", reversed.resolve("NSE", "101", at("2026-01-02T00:00:00Z")).mappingId());
            equal("2026-01-02T00:00:00Z", reversed.resolve("NSE", "101", at("2026-01-02T00:00:00Z"))
                    .mapping().effectiveFromUtc().toString());
            equal(SHA, reversed.resolve("NSE", "101", at("2026-01-02T00:00:00Z"))
                    .mapping().sourceFileSha256());
        } finally {
            if (originalTimezone == null) System.clearProperty("user.timezone");
            else System.setProperty("user.timezone", originalTimezone);
        }
    }

    private static DatedInstrumentMappingRegistry load(String rows) throws IOException {
        Path csv = Files.createTempFile("dated-instrument-mappings-", ".csv");
        try {
            Files.writeString(csv, HEADER + "\n" + rows);
            return DatedInstrumentMappingRegistry.load(csv);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private static String row(String id, String exchange, String token, String instrumentId,
            String symbol, String type, String expiry, String strike, String right, String from, String until) {
        return String.join(",", List.of(id, exchange, token, instrumentId, symbol, type,
                expiry, strike, right, from, until, "official-archive", "archive-batch-2026-01", SHA));
    }

    private static Instant at(String value) { return Instant.parse(value); }

    private static void expectUnknown(DatedInstrumentMappingRegistry registry, String timestamp) {
        expectFailure(() -> registry.resolve("NSE", "101", at(timestamp)));
    }

    private static IllegalArgumentException expectFailure(ThrowingAction action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return expected; }
        catch (Exception unexpected) { throw new AssertionError("Unexpected exception", unexpected); }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void contains(String actual, String expected) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError("Expected message to contain '" + expected + "', got: " + actual);
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
