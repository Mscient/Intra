package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.data.IntervalSupport;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** One explicitly dated contract request; all intervals are half-open UTC. */
record HistoricalRequest(Map<String,Object> fields, Instant from, Instant to, Instant captured,
                         LocalDate assessment, Duration interval) {
    static final String SCHEMA="historical-request/v1";
    static HistoricalRequest parse(byte[] b) throws java.io.IOException {
        safeText(utf8(b)); Map<String,Object> m=map(IngestionIO.parse(b));
        keys(m,"schema_version","request_id","source","exchange","source_token","instrument_id","interval",
            "from","to","timestamp_role","timezone","captured_at","assessment_date","purpose","storage",
            "retention_days","retention_status","master_snapshot_id","master_retrieved_at","master_effective_from",
            "calendar_id","calendar_basis");
        require(str(m,"schema_version").equals(SCHEMA),"REQUEST_VERSION");
        for (String k:List.of("request_id","instrument_id","master_snapshot_id","calendar_id")) id(str(m,k));
        require(Set.of("ANGEL_ONE","SYNTHETIC_CANDLES").contains(str(m,"source")),"SOURCE_UNSUPPORTED");
        require(str(m,"exchange").equals("NFO"),"EXCHANGE_UNSUPPORTED");
        require(str(m,"source_token").matches("[0-9]{1,18}"),"TOKEN_AMBIGUOUS");
        require(Set.of("BAR_START","BAR_END").contains(str(m,"timestamp_role")),"TIMESTAMP_ROLE_UNKNOWN");
        require(str(m,"timezone").equals("Asia/Kolkata"),"TIMEZONE_UNSUPPORTED");
        require(Set.of("SIMULATION","BACKTEST").contains(str(m,"purpose")),"PURPOSE_UNSUPPORTED");
        require(str(m,"storage").equals("LOCAL_ONLY"),"STORAGE_UNSUPPORTED");
        integer(m.get("retention_days"),1,3650);
        boolean synthetic=str(m,"source").equals("SYNTHETIC_CANDLES");
        require(str(m,"retention_status").equals(synthetic?"SYNTHETIC_TEST_ONLY":"OWNER_PROPOSED_UNVERIFIED"),"RETENTION_UNVERIFIED");
        require(str(m,"calendar_basis").equals(synthetic?"SYNTHETIC":"CALLER_SUPPLIED_UNVERIFIED"),"CALENDAR_BASIS_INVALID");
        Instant from=timestamp(str(m,"from")), to=timestamp(str(m,"to")), captured=timestamp(str(m,"captured_at"));
        LocalDate assessment=date(str(m,"assessment_date"));
        timestamp(str(m,"master_retrieved_at")); date(str(m,"master_effective_from"));
        require(from.isBefore(to) && Duration.between(from,to).compareTo(Duration.ofDays(366))<=0,"REQUEST_RANGE_INVALID");
        Duration interval=IntervalSupport.duration(str(m,"interval"));
        require(from.getNano()==0 && to.getNano()==0 && from.getEpochSecond()%60==0 && to.getEpochSecond()%60==0,"REQUEST_PRECISION");
        require(Duration.between(from,to).getSeconds()%interval.getSeconds()==0,"REQUEST_GRID_INVALID");
        require(!captured.atZone(ZoneOffset.UTC).toLocalDate().isAfter(assessment),"ASSESSMENT_BEFORE_CAPTURE");
        return new HistoricalRequest(Map.copyOf(m),from,to,captured,assessment,interval);
    }
    static Instant timestamp(String s) {
        require(s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})"),"EXPLICIT_OFFSET_REQUIRED");
        return OffsetDateTime.parse(s).toInstant();
    }
    static LocalDate date(String s) {
        require(s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"DATE_INVALID"); return LocalDate.parse(s);
    }
    String s(String key) { return str(fields,key); }
    boolean synthetic() { return s("source").equals("SYNTHETIC_CANDLES"); }
}
