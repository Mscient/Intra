package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.ValidatedPortfolioDataset;
import com.mscient.intrapaper.reference.ContractKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementContract.*;

/** Frozen caller events, one per explicitly selected contract. */
public record PortfolioSettlementEvents(List<Event> events, String sourceFileSha256) {
    public static final List<String> HEADERS;
    static {
        var h = new ArrayList<>(List.of("event_id")); h.addAll(PortfolioSettlementContract.HEADERS);
        h.addAll(List.of("settlement_date", "settlement_timestamp", "settlement_value_per_unit_inr", "option_exercise",
                "charge_turnover_per_unit_inr", "charge_turnover_source_reference", "source_reference"));
        HEADERS = List.copyOf(h);
    }
    public record Event(String eventId, PortfolioSettlementContract contract, LocalDate settlementDate,
            Instant timestamp, BigDecimal valuePerUnit, boolean optionExercise,
            Optional<BigDecimal> chargeTurnoverPerUnit, Optional<String> chargeTurnoverSourceReference,
            String sourceReference) {
        public Event {
            id(eventId, "event_id"); Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(settlementDate, "settlementDate"); Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(valuePerUnit, "valuePerUnit");
            Objects.requireNonNull(chargeTurnoverPerUnit, "chargeTurnoverPerUnit");
            Objects.requireNonNull(chargeTurnoverSourceReference, "chargeTurnoverSourceReference");
            text(sourceReference, "source_reference");
            if (settlementDate.isAfter(contract.expiry())) throw new IllegalArgumentException("settlement_date is after expiry");
            if (contract.contractKind() == ContractKind.OPTION) nonnegative(valuePerUnit, "option settlement value");
            else if (optionExercise) throw new IllegalArgumentException("FUTURE cannot declare option_exercise");
            if (chargeTurnoverPerUnit.isPresent() != chargeTurnoverSourceReference.isPresent())
                throw new IllegalArgumentException("Turnover value and source reference must be present together");
            chargeTurnoverPerUnit.ifPresent(v -> nonnegative(v, "charge turnover"));
            chargeTurnoverSourceReference.ifPresent(v -> text(v, "charge turnover source"));
        }
        static Event parse(Map<String, String> row) {
            String turnover = optional(row, "charge_turnover_per_unit_inr");
            String source = optional(row, "charge_turnover_source_reference");
            return new Event(required(row, "event_id"), PortfolioSettlementContract.parse(row),
                    LocalDate.parse(required(row, "settlement_date")), OffsetDateTime.parse(required(row, "settlement_timestamp")).toInstant(),
                    new BigDecimal(required(row, "settlement_value_per_unit_inr")), bool(row, "option_exercise"),
                    turnover.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(turnover)),
                    source.isEmpty() ? Optional.empty() : Optional.of(source), required(row, "source_reference"));
        }
        List<String> cells() {
            var values = new ArrayList<>(List.of(eventId)); values.addAll(contract.cells());
            values.addAll(List.of(settlementDate.toString(), timestamp.toString(), valuePerUnit.toPlainString(),
                    Boolean.toString(optionExercise), chargeTurnoverPerUnit.map(BigDecimal::toPlainString).orElse(""),
                    chargeTurnoverSourceReference.orElse(""), sourceReference));
            return List.copyOf(values);
        }
    }
    public PortfolioSettlementEvents {
        hash(sourceFileSha256);
        var ids = new HashSet<String>(); var instruments = new HashSet<String>();
        for (Event event : events) {
            if (!ids.add(event.eventId()) || !instruments.add(event.contract().instrumentId()))
                throw new IllegalArgumentException("Duplicate settlement event ID or instrument");
        }
        if (events.isEmpty()) throw new IllegalArgumentException("Settlement event file must contain events");
        events = events.stream().sorted(Comparator.comparing(Event::timestamp)
                .thenComparing(e -> e.contract().instrumentId()).thenComparing(Event::eventId)).toList();
    }
    public static PortfolioSettlementEvents load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) throw new IllegalArgumentException("Settlement event headers must be exactly " + HEADERS);
        var events = new ArrayList<Event>();
        for (int i = 0; i < table.rows().size(); i++) {
            try { events.add(Event.parse(table.rows().get(i))); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid settlement event row " + (i + 2) + ": " + e.getMessage(), e); }
        }
        return new PortfolioSettlementEvents(events, table.sha256());
    }

    public static void validateSelection(ValidatedPortfolioDataset dataset, Set<String> targets,
            Optional<PortfolioSettlementEvents> input) {
        if (targets.isEmpty() != input.isEmpty()) throw new IllegalArgumentException("Settlement targets and events must be supplied together");
        if (!dataset.datasetsByInstrument().keySet().containsAll(targets)) throw new IllegalArgumentException("Unknown settlement target");
        Set<String> actual = new TreeSet<>();
        input.ifPresent(value -> value.events.forEach(e -> actual.add(e.contract().instrumentId())));
        if (!actual.equals(targets)) throw new IllegalArgumentException("Missing or unexpected settlement events; expected " + targets + ", found " + actual);
        for (var member : dataset.datasetsByInstrument().entrySet()) {
            var data = member.getValue(); var last = data.bars().getLast();
            if (data.expirySettlement().isPresent() || data.settlementCostSchedule().isPresent())
                throw new IllegalArgumentException("Portfolio datasets must use portfolio settlement events/costs, not legacy single-contract settlement inputs");
            if (last.sessionDate().equals(last.expiry()) && !targets.contains(member.getKey()))
                throw new IllegalArgumentException("Expiry-date data requires an explicit settlement target/event: " + member.getKey());
            if (input.isPresent() && !data.finalSessionComplete()) throw new IllegalArgumentException("Settlement requires a complete final portfolio session");
        }
        if (input.isEmpty()) return;
        for (Event event : input.orElseThrow().events) {
            var data = dataset.datasetsByInstrument().get(event.contract().instrumentId());
            var last = data.bars().getLast();
            if (!event.contract().equals(PortfolioSettlementContract.from(last))) throw new IllegalArgumentException("Settlement dated identity mismatch: " + event.eventId());
            if (!event.settlementDate().equals(last.sessionDate())) throw new IllegalArgumentException("Settlement date must equal final session: " + event.eventId());
            if (!event.timestamp().atZone(ZoneId.of(data.manifest().timezone())).toLocalDate().equals(event.settlementDate()))
                throw new IllegalArgumentException("Settlement timestamp/date/timezone mismatch: " + event.eventId());
            if (event.timestamp().isBefore(last.timestamp().plus(IntervalSupport.duration(last.interval()))))
                throw new IllegalArgumentException("Settlement precedes final bar close: " + event.eventId());
        }
    }
}
