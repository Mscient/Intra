package com.mscient.intrapaper.data;

import com.mscient.intrapaper.reference.TimestampRole;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds exact-time multi-instrument frames without forward-filling missing observations. */
public final class SynchronizedBarFrameBuilder {
    public record Frame(Instant timestamp, LocalDate sessionDate, Map<String, Bar> barsByInstrument) {
        public Frame {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(sessionDate, "sessionDate");
            Objects.requireNonNull(barsByInstrument, "barsByInstrument");
            if (barsByInstrument.size() < 2) {
                throw new IllegalArgumentException("A synchronized frame requires at least two instruments");
            }
            LinkedHashMap<String, Bar> ordered = new LinkedHashMap<>();
            barsByInstrument.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String instrumentId = requireIdentifier(entry.getKey());
                Bar bar = Objects.requireNonNull(entry.getValue(), "bar");
                if (!instrumentId.equals(bar.instrumentId())) {
                    throw new IllegalArgumentException("Frame key does not match bar instrument ID: " + instrumentId);
                }
                if (!timestamp.equals(bar.timestamp()) || !sessionDate.equals(bar.sessionDate())) {
                    throw new IllegalArgumentException("Every bar in a frame must share its timestamp and session date");
                }
                ordered.put(instrumentId, bar);
            });
            barsByInstrument = Collections.unmodifiableMap(ordered);
        }
    }

    private SynchronizedBarFrameBuilder() { }

    /**
     * Aligns at least two non-empty, strictly time-ordered instrument series.
     * Any timestamp or local-session-date mismatch fails closed; no bar is interpolated or carried forward.
     */
    public static List<Frame> build(Map<String, ? extends List<Bar>> inputSeries) {
        Objects.requireNonNull(inputSeries, "inputSeries");
        if (inputSeries.size() < 2) {
            throw new IllegalArgumentException("At least two instrument series are required");
        }

        TreeMap<String, List<Bar>> series = new TreeMap<>();
        String exchange = null;
        String segment = null;
        String interval = null;
        TimestampRole timestampRole = null;
        Map<String, Bar> identityBars = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<Bar>> input : inputSeries.entrySet()) {
            String instrumentId = requireIdentifier(input.getKey());
            List<Bar> bars = List.copyOf(Objects.requireNonNull(input.getValue(), "bar series"));
            if (bars.isEmpty()) throw new IllegalArgumentException("Bar series is empty: " + instrumentId);
            if (series.putIfAbsent(instrumentId, bars) != null) {
                throw new IllegalArgumentException("Duplicate instrument series: " + instrumentId);
            }
            for (int index = 0; index < bars.size(); index++) {
                Bar bar = Objects.requireNonNull(bars.get(index), "bar");
                if (!instrumentId.equals(bar.instrumentId())) {
                    throw new IllegalArgumentException("Series key " + instrumentId
                            + " does not match bar instrument ID " + bar.instrumentId());
                }
                if (index > 0 && !bar.timestamp().isAfter(bars.get(index - 1).timestamp())) {
                    throw new IllegalArgumentException("Bar timestamps must be strictly increasing for " + instrumentId
                            + " at " + bar.timestamp());
                }
                Bar firstBar = identityBars.putIfAbsent(instrumentId, bar);
                if (firstBar != null && !sameContractIdentity(firstBar, bar)) {
                    throw new IllegalArgumentException("Contract identity changes within series " + instrumentId
                            + " at " + bar.timestamp());
                }
                if (exchange == null) {
                    exchange = bar.exchange();
                    segment = bar.segment();
                    interval = bar.interval();
                    timestampRole = bar.timestampRole();
                } else if (!exchange.equals(bar.exchange()) || !segment.equals(bar.segment())
                        || !interval.equals(bar.interval()) || timestampRole != bar.timestampRole()) {
                    throw new IllegalArgumentException("All series must use the same exchange, segment, interval, and timestamp role");
                }
            }
        }

        List<Map.Entry<String, List<Bar>>> orderedSeries = new ArrayList<>(series.entrySet());
        int frameCount = orderedSeries.getFirst().getValue().size();
        for (Map.Entry<String, List<Bar>> instrument : orderedSeries) {
            if (instrument.getValue().size() != frameCount) {
                throw new IllegalArgumentException("Instrument series have different observation counts: "
                        + orderedSeries.getFirst().getKey() + "=" + frameCount + ", "
                        + instrument.getKey() + "=" + instrument.getValue().size());
            }
        }

        List<Frame> frames = new ArrayList<>(frameCount);
        for (int index = 0; index < frameCount; index++) {
            Instant timestamp = orderedSeries.getFirst().getValue().get(index).timestamp();
            LocalDate sessionDate = orderedSeries.getFirst().getValue().get(index).sessionDate();
            LinkedHashMap<String, Bar> frameBars = new LinkedHashMap<>();
            for (Map.Entry<String, List<Bar>> instrument : orderedSeries) {
                Bar bar = instrument.getValue().get(index);
                if (!timestamp.equals(bar.timestamp())) {
                    throw new IllegalArgumentException("Timestamp mismatch at frame " + index + ": "
                            + orderedSeries.getFirst().getKey() + "=" + timestamp + ", "
                            + instrument.getKey() + "=" + bar.timestamp());
                }
                if (!sessionDate.equals(bar.sessionDate())) {
                    throw new IllegalArgumentException("Session-date mismatch at " + timestamp + ": "
                            + orderedSeries.getFirst().getKey() + "=" + sessionDate + ", "
                            + instrument.getKey() + "=" + bar.sessionDate());
                }
                frameBars.put(instrument.getKey(), bar);
            }
            frames.add(new Frame(timestamp, sessionDate, frameBars));
        }
        return List.copyOf(frames);
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "instrumentId");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("instrument ID is not a canonical identifier: " + value);
        }
        return value;
    }

    private static boolean sameContractIdentity(Bar left, Bar right) {
        if (!left.instrumentId().equals(right.instrumentId())
                || left.contractKind() != right.contractKind()
                || !left.underlying().equals(right.underlying())
                || !left.expiry().equals(right.expiry())
                || !left.optionRight().equals(right.optionRight())) return false;
        if (left.strike().isEmpty() || right.strike().isEmpty()) {
            return left.strike().isEmpty() == right.strike().isEmpty();
        }
        return left.strike().orElseThrow().compareTo(right.strike().orElseThrow()) == 0;
    }
}
