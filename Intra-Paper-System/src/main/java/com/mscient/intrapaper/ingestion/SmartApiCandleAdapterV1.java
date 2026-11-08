package com.mscient.intrapaper.ingestion;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Only the documented successful six-column candle envelope. No provider transport. */
final class SmartApiCandleAdapterV1 {
    static final String VERSION="smartapi-candles/1";
    record Candle(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  Long volume, String rowHash) { }
    private SmartApiCandleAdapterV1() { }
    static List<Candle> parseResponse(byte[] raw, HistoricalRequest request) throws IOException {
        safeText(utf8(raw)); Map<String,Object> root=map(parse(raw));
        keys(root,"status","message","errorcode","data");
        require(Boolean.TRUE.equals(root.get("status")) && str(root,"message").equals("SUCCESS")
            && str(root,"errorcode").isEmpty(),"PROVIDER_RESPONSE_NOT_SUCCESS");
        List<?> rows=list(root.get("data")); require(rows.size()<=100000,"TOO_MANY_CANDLES");
        List<Candle> candles=new ArrayList<>();
        for (Object value:rows) {
            List<?> row=list(value); require(row.size()==6,"CANDLE_SHAPE_INVALID");
            require(row.get(0) instanceof String,"TIMESTAMP_REQUIRED");
            Instant time=HistoricalRequest.timestamp((String)row.get(0));
            require(!time.isBefore(request.from()) && time.isBefore(request.to()),"CANDLE_OUTSIDE_REQUEST");
            BigDecimal o=price(row.get(1)), h=price(row.get(2)), l=price(row.get(3)), c=price(row.get(4));
            require(h.compareTo(l)>=0 && h.compareTo(o)>=0 && h.compareTo(c)>=0 && l.compareTo(o)<=0 && l.compareTo(c)<=0,"OHLC_IMPOSSIBLE");
            Long volume=row.get(5)==null?null:integer(row.get(5),0,Long.MAX_VALUE);
            candles.add(new Candle(time,o,h,l,c,volume,hash(bytes(row))));
        }
        return List.copyOf(candles);
    }
    private static BigDecimal price(Object v) {
        require(v instanceof BigDecimal,"PRICE_NOT_NUMBER"); BigDecimal n=(BigDecimal)v;
        require(n.signum()>0 && n.precision()<=32 && Math.abs((long)n.scale())<=10,"PRICE_RANGE_INVALID");
        return n.stripTrailingZeros();
    }
}
