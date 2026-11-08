package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.reference.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Uses caller-selected dated references. Missing records stay unknown; never fills or repairs. */
final class CandleQuality {
    static final String HEADER="timestamp,instrument_id,exchange,segment,source_token,trading_symbol,contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,interval,timestamp_role,open,high,low,close,volume,open_interest,source,raw_record_hash\n";
    record Result(Map<String,Object> report, byte[] normalized) { }
    private CandleQuality() { }
    static Result check(HistoricalRequest r, List<SmartApiCandleAdapterV1.Candle> rows,
            DatedInstrumentMappingRegistry registry, InstrumentMasterSnapshot master, Map<LocalDate,SessionCalendarEntry> calendar) {
        SortedSet<String> incomplete=new TreeSet<>(), invalid=new TreeSet<>(), mappings=new TreeSet<>();
        if (registry==null) incomplete.add("MAPPING_MISSING_OR_INVALID");
        if (master==null) incomplete.add("MASTER_MISSING_OR_INVALID");
        if (calendar==null) incomplete.add("CALENDAR_MISSING_OR_INVALID");
        SortedSet<Instant> expected=new TreeSet<>();
        ZoneId zone=ZoneId.of(r.s("timezone"));
        LocalDate start=r.from().atZone(zone).toLocalDate(), end=r.to().minusNanos(1).atZone(zone).toLocalDate();
        if (calendar!=null) for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) {
            SessionCalendarEntry session=calendar.get(day);
            if(session==null) { incomplete.add("CALENDAR_DATE_UNCOVERED"); continue; }
            if (!session.open()) continue;
            Instant first=session.firstBarTimestamp().orElseThrow(), last=session.lastBarTimestamp().orElseThrow();
            if(!first.atZone(zone).toLocalDate().equals(day) || !last.atZone(zone).toLocalDate().equals(day)
                || first.getNano()!=0 || last.getNano()!=0 || Duration.between(first,last).getSeconds()%r.interval().getSeconds()!=0) {
                incomplete.add("CALENDAR_GRID_INVALID"); continue;
            }
            for(Instant t=first;!t.isAfter(last);t=t.plus(r.interval())) if(!t.isBefore(r.from()) && t.isBefore(r.to())) {
                require(expected.size()<100000,"COVERAGE_TOO_LARGE"); expected.add(t);
            }
        }
        // Check master and mapping coverage at every expected timestamp, including absent candles.
        for (Instant t:expected) {
            try { resolve(r,t,registry,master,mappings); }
            catch (RuntimeException e) { incomplete.add("DATED_REFERENCE_UNCOVERED_OR_INCONSISTENT"); }
        }
        Map<Instant,SmartApiCandleAdapterV1.Candle> seen=new TreeMap<>();
        List<String> exact=new ArrayList<>(), conflicting=new ArrayList<>(), unexpected=new ArrayList<>();
        StringBuilder csv=new StringBuilder(HEADER); Instant prior=null; int count=0; boolean normalizable=registry!=null && master!=null && calendar!=null;
        for (var candle:rows) {
            Instant t=candle.time();
            var old=seen.putIfAbsent(t,candle);
            if(old!=null) {
                boolean equal=old.open().compareTo(candle.open())==0 && old.high().compareTo(candle.high())==0
                    && old.low().compareTo(candle.low())==0 && old.close().compareTo(candle.close())==0 && Objects.equals(old.volume(),candle.volume());
                (equal?exact:conflicting).add(t.toString()); invalid.add(equal?"EXACT_DUPLICATE":"CONFLICTING_DUPLICATE");
            }
            if(prior!=null && t.isBefore(prior)) invalid.add("ROWS_OUT_OF_ORDER"); prior=t;
            SessionCalendarEntry session=calendar==null?null:calendar.get(t.atZone(zone).toLocalDate());
            if(session==null) { incomplete.add("CALENDAR_DATE_UNCOVERED"); normalizable=false; }
            else if (!expected.contains(t)) {
                unexpected.add(t.toString()); invalid.add("UNEXPECTED_TIMESTAMP");
                if(!session.open() || t.isBefore(session.firstBarTimestamp().orElseThrow()) || t.isAfter(session.lastBarTimestamp().orElseThrow())) invalid.add("SESSION_BOUNDARY_VIOLATION");
            }
            InstrumentReference instrument;
            try { instrument=resolve(r,t,registry,master,mappings); }
            catch (RuntimeException e) { incomplete.add("DATED_REFERENCE_UNCOVERED_OR_INCONSISTENT"); normalizable=false; continue; }
            try {
                Bar b=new Bar(t,t.atZone(zone).toLocalDate(),instrument.instrumentId(),instrument.exchange(),instrument.segment(),
                    instrument.sourceToken(),instrument.tradingSymbol(),instrument.contractKind(),instrument.underlying().orElseThrow(),
                    instrument.expiry().orElseThrow(),instrument.strike(),instrument.optionRight(),instrument.lotSize(),instrument.tickSize(),
                    r.s("interval"),TimestampRole.valueOf(r.s("timestamp_role")),candle.open(),candle.high(),candle.low(),candle.close(),
                    candle.volume(),null,r.s("source"),candle.rowHash());
                csv.append(row(b)); count++;
            } catch (RuntimeException e) { invalid.add("CANONICAL_BAR_INVALID"); }
        }
        SortedSet<Instant> missing=new TreeSet<>(expected); missing.removeAll(seen.keySet());
        if(!missing.isEmpty()) incomplete.add("EXPECTED_CANDLES_MISSING");
        if(rows.isEmpty()) incomplete.add("NO_CANDLES");
        String status=!invalid.isEmpty()?"REJECTED":!incomplete.isEmpty()?"INCOMPLETE":"PASS_SYNTHETIC";
        byte[] normalized=normalizable && invalid.isEmpty()?csv.toString().getBytes(StandardCharsets.UTF_8):null;
        return new Result(obj("schema_version","historical-quality/v1","status",status,"invalid_reasons",invalid,"incomplete_reasons",incomplete,
            "input_rows",rows.size(),"normalized_rows",normalized==null?0:count,"expected_timestamps",expected.stream().map(Instant::toString).toList(),
            "missing_timestamps",missing.stream().map(Instant::toString).toList(),"unexpected_timestamps",unexpected,"exact_duplicates",exact,
            "conflicting_duplicates",conflicting,"mapping_ids",mappings,"calendar_id",r.s("calendar_id"),"calendar_basis",r.s("calendar_basis"),
            "coverage_assumption","EXPLICIT_CALENDAR_FIRST_LAST_INCLUSIVE_ON_DECLARED_INTERVAL_AND_TIMESTAMP_ROLE",
            "missing_value_meaning","UNKNOWN","permission_approved",false,"production_eligible",false),normalized);
    }
    private static InstrumentReference resolve(HistoricalRequest r, Instant t, DatedInstrumentMappingRegistry registry,
            InstrumentMasterSnapshot master, Set<String> mappings) {
        require(registry!=null && master!=null,"REFERENCE_UNAVAILABLE");
        var resolution=registry.resolve(r.s("exchange"),r.s("source_token"),t); var m=resolution.mapping();
        InstrumentReference i=master.requireContract("NSE",r.s("exchange"),r.s("source_token"),t.atZone(ZoneId.of(r.s("timezone"))).toLocalDate());
        require(i.instrumentId().equals(r.s("instrument_id")) && i.instrumentId().equals(m.instrumentId()) && i.tradingSymbol().equals(m.tradingSymbol())
            && i.contractKind()==m.instrumentType() && i.expiry().equals(m.expiry()) && i.strike().equals(m.strike()) && i.optionRight().equals(m.optionRight()),"REFERENCE_IDENTITY_CONFLICT");
        require(!t.atZone(ZoneId.of(r.s("timezone"))).toLocalDate().isAfter(i.expiry().orElseThrow()),"EXPIRED_CONTRACT");
        mappings.add(m.mappingId()); return i;
    }
    private static String row(Bar b) {
        List<String> fields=List.of(b.timestamp().toString(),b.instrumentId(),b.exchange(),b.segment(),b.sourceToken(),b.tradingSymbol(),
            b.contractKind().name(),b.underlying(),b.expiry().toString(),b.strike().map(BigDecimal::toPlainString).orElse(""),
            b.optionRight().map(Enum::name).orElse(""),Integer.toString(b.lotSize()),b.tickSize().toPlainString(),b.interval(),b.timestampRole().name(),
            b.open().toPlainString(),b.high().toPlainString(),b.low().toPlainString(),b.close().toPlainString(),b.volume()==null?"":b.volume().toString(),"",b.source(),b.rawRecordHash());
        return String.join(",",fields.stream().map(s->"\""+s.replace("\"","\"\"")+"\"").toList())+"\n";
    }
}
