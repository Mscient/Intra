package com.mscient.intrapaper.data;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Offline importer for saved Angel One SmartAPI historical candle response JSON. */
public final class SmartApiCandleImporter {
    public record ImportResult(Path outputPath, long candleCount, String responseSha256,
            String metadataSha256, String normalizedCsvSha256, String timestampRole,
            String timezone) { }

    private record Metadata(String instrumentId, String exchange, String segment, String sourceToken,
            String tradingSymbol, ContractKind contractKind, String underlying, LocalDate expiry,
            Optional<BigDecimal> strike, Optional<OptionRight> optionRight, int lotSize,
            BigDecimal tickSize, String interval, TimestampRole timestampRole, ZoneId timezone,
            String source, String requestFrom, String requestTo) { }

    private static final String[] METADATA_HEADERS = {
            "instrument_id", "exchange", "segment", "source_token", "trading_symbol", "contract_kind",
            "underlying", "expiry", "strike", "option_right", "lot_size", "tick_size", "interval",
            "timestamp_role", "timezone", "source", "request_fromdate", "request_todate"
    };
    private static final String OUTPUT_HEADER = "timestamp,instrument_id,exchange,segment,source_token,trading_symbol,"
            + "contract_kind,underlying,expiry,strike,option_right,lot_size,tick_size,interval,timestamp_role,"
            + "open,high,low,close,volume,open_interest,source,raw_record_hash";
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_RESPONSE_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CANDLES = 1_000_000;

    private SmartApiCandleImporter() { }

    /** Converts one saved successful SmartAPI response and one-row caller metadata CSV into normalized bars. */
    public static ImportResult importResponse(Path responsePath, Path metadataPath,
            Path requestedOutputPath) throws IOException {
        Path response = responsePath.toAbsolutePath().normalize();
        Path metadataFile = metadataPath.toAbsolutePath().normalize();
        Path output = requestedOutputPath.toAbsolutePath().normalize();
        if (response.equals(metadataFile) || response.equals(output) || metadataFile.equals(output)) {
            throw new IllegalArgumentException("Response, metadata, and output paths must be different");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());

        long responseSize = Files.size(response);
        if (responseSize <= 0 || responseSize > MAX_RESPONSE_BYTES) {
            throw new IOException("SmartAPI response file is empty or exceeds the supported 128 MiB size");
        }
        byte[] responseBytes = Files.readAllBytes(response);
        if (responseBytes.length == 0 || responseBytes.length > MAX_RESPONSE_BYTES || responseBytes.length != responseSize) {
            throw new IOException("SmartAPI response file changed while it was being read or exceeds the supported size");
        }
        String responseSha = sha256(responseBytes);
        String responseText = decodeUtf8(responseBytes);
        CsvTable metadataTable = CsvTable.read(metadataFile);
        if (!List.of(METADATA_HEADERS).equals(metadataTable.headers())) {
            throw new IllegalArgumentException("Metadata CSV must have the exact documented SmartAPI metadata header and order");
        }
        if (metadataTable.rows().size() != 1) throw new IllegalArgumentException("Metadata CSV must contain exactly one data row");
        Metadata metadata = readMetadata(metadataTable.rows().getFirst());
        Instant requestStart = LocalDateTime.parse(metadata.requestFrom(), REQUEST_TIME)
                .atZone(metadata.timezone()).toInstant();
        Instant requestEnd = LocalDateTime.parse(metadata.requestTo(), REQUEST_TIME)
                .atZone(metadata.timezone()).toInstant();
        String source = metadata.source() + "|response_sha256=" + responseSha
                + "|metadata_sha256=" + metadataTable.sha256()
                + "|request=" + metadata.interval() + ":" + metadata.requestFrom() + ":" + metadata.requestTo();

        Object parsed = StrictJsonParser.parse(responseText);
        Map<String, Object> root = object(parsed, "response");
        if (!Boolean.TRUE.equals(root.get("status"))) {
            throw new IOException("SmartAPI response status is not true");
        }
        if (!(root.get("message") instanceof String)) throw new IOException("SmartAPI response message is missing or not a string");
        if (!(root.get("errorcode") instanceof String errorCode)) throw new IOException("SmartAPI errorcode is missing or not a string");
        if (!errorCode.isEmpty()) throw new IOException("SmartAPI response contains an error code");
        if (!(root.get("data") instanceof List<?> rawCandles)) throw new IOException("SmartAPI response data is not an array");
        if (rawCandles.isEmpty()) throw new IOException("SmartAPI response contains no candles");
        if (rawCandles.size() > MAX_CANDLES) throw new IOException("SmartAPI response exceeds one million candle rows");

        List<Bar> bars = new ArrayList<>(rawCandles.size());
        Instant previousTimestamp = null;
        for (int i = 0; i < rawCandles.size(); i++) {
            try {
                List<?> row = array(rawCandles.get(i), "candle row");
                if (row.size() != 6) throw new IllegalArgumentException("expected [timestamp,open,high,low,close,volume]");
                if (!(row.getFirst() instanceof String timestampText)) {
                    throw new IllegalArgumentException("timestamp must be an ISO-8601 string with a UTC offset");
                }
                OffsetDateTime timestamp = OffsetDateTime.parse(timestampText);
                Instant instant = timestamp.toInstant();
                if (instant.isBefore(requestStart) || instant.isAfter(requestEnd)) {
                    throw new IllegalArgumentException("timestamp lies outside the supplied local request window");
                }
                if (previousTimestamp != null && !instant.isAfter(previousTimestamp)) {
                    throw new IllegalArgumentException("timestamps must be strictly increasing in response order");
                }
                previousTimestamp = instant;
                BigDecimal open = decimal(row.get(1), "open");
                BigDecimal high = decimal(row.get(2), "high");
                BigDecimal low = decimal(row.get(3), "low");
                BigDecimal close = decimal(row.get(4), "close");
                Long volume = optionalLong(row.get(5), "volume");
                String rawRow = String.join("\t", timestampText, open.toString(), high.toString(),
                        low.toString(), close.toString(), volume == null ? "" : volume.toString());
                String rowHash = sha256(rawRow.getBytes(StandardCharsets.UTF_8));
                bars.add(new Bar(instant, instant.atZone(metadata.timezone()).toLocalDate(),
                        metadata.instrumentId(), metadata.exchange(), metadata.segment(), metadata.sourceToken(),
                        metadata.tradingSymbol(), metadata.contractKind(), metadata.underlying(), metadata.expiry(),
                        metadata.strike(), metadata.optionRight(), metadata.lotSize(), metadata.tickSize(),
                        metadata.interval(), metadata.timestampRole(), open, high, low, close, volume, null,
                        source, rowHash));
            } catch (RuntimeException invalidRow) {
                throw new IOException("Invalid SmartAPI candle row " + (i + 1) + ": " + invalidRow.getMessage(), invalidRow);
            }
        }

        Path parent = output.getParent();
        Path fileName = output.getFileName();
        if (parent == null || fileName == null) throw new IOException("Output path must name a file");
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, "." + fileName + ".smartapi-" + UUID.randomUUID(), ".tmp");
        boolean published = false;
        String outputSha;
        try {
            MessageDigest digest = newSha256();
            try (BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(
                    new DigestOutputStream(Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING), digest),
                    StandardCharsets.UTF_8))) {
                writer.write(OUTPUT_HEADER);
                writer.write('\n');
                for (Bar bar : bars) {
                    writer.write(csvRow(bar));
                    writer.write('\n');
                }
            }
            outputSha = HexFormat.of().formatHex(digest.digest());
            try {
                Files.createLink(output, staging);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Atomic no-overwrite output publication requires filesystem hard-link support", unsupported);
            }
            published = true;
            try { Files.deleteIfExists(staging); }
            catch (IOException ignored) { /* A complete published file remains valid. */ }
        } finally {
            if (!published) Files.deleteIfExists(staging);
        }
        return new ImportResult(output, bars.size(), responseSha, metadataTable.sha256(),
                outputSha, metadata.timestampRole().name(), metadata.timezone().getId());
    }

    private static Metadata readMetadata(Map<String, String> row) {
        String exchange = required(row, "exchange");
        if (!exchange.equals("NFO")) throw new IllegalArgumentException("NIFTY futures/options candles require exchange=NFO");
        String segment = required(row, "segment");
        if (!segment.equals("NFO")) throw new IllegalArgumentException("segment must be NFO for this importer");
        String interval = required(row, "interval");
        IntervalSupport.duration(interval);
        String roleText = required(row, "timestamp_role");
        TimestampRole role = TimestampRole.valueOf(roleText);
        if (role != TimestampRole.BAR_START && role != TimestampRole.BAR_END) {
            throw new IllegalArgumentException("timestamp_role must be explicitly BAR_START or BAR_END");
        }
        ZoneId timezone = ZoneId.of(required(row, "timezone"));
        String from = required(row, "request_fromdate");
        String to = required(row, "request_todate");
        LocalDateTime fromDateTime = LocalDateTime.parse(from, REQUEST_TIME);
        LocalDateTime toDateTime = LocalDateTime.parse(to, REQUEST_TIME);
        if (toDateTime.isBefore(fromDateTime)) throw new IllegalArgumentException("request_todate precedes request_fromdate");
        String source = required(row, "source");
        if (!source.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("source must be a short identifier without spaces");
        }
        Optional<BigDecimal> strike = optional(row.get("strike")).map(BigDecimal::new);
        Optional<OptionRight> right = optional(row.get("option_right")).map(OptionRight::valueOf);
        return new Metadata(required(row, "instrument_id"), exchange, segment,
                required(row, "source_token"), required(row, "trading_symbol"),
                ContractKind.valueOf(required(row, "contract_kind")), required(row, "underlying"),
                LocalDate.parse(required(row, "expiry")), strike, right,
                Integer.parseInt(required(row, "lot_size")), new BigDecimal(required(row, "tick_size")),
                interval, role, timezone, source, from, to);
    }

    private static String csvRow(Bar bar) {
        return String.join(",", csv(bar.timestamp().toString()), csv(bar.instrumentId()), csv(bar.exchange()),
                csv(bar.segment()), csv(bar.sourceToken()), csv(bar.tradingSymbol()), csv(bar.contractKind().name()),
                csv(bar.underlying()), csv(bar.expiry().toString()), csv(bar.strike().map(BigDecimal::toPlainString).orElse("")),
                csv(bar.optionRight().map(Enum::name).orElse("")), csv(Integer.toString(bar.lotSize())),
                csv(bar.tickSize().toPlainString()), csv(bar.interval()), csv(bar.timestampRole().name()),
                csv(bar.open().toPlainString()), csv(bar.high().toPlainString()), csv(bar.low().toPlainString()),
                csv(bar.close().toPlainString()), csv(bar.volume() == null ? "" : bar.volume().toString()),
                "", csv(bar.source()), csv(bar.rawRecordHash()));
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String required(Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Optional<String> optional(String value) {
        if (value == null || value.isEmpty()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException("Optional metadata values must be trimmed");
        return Optional.of(value);
    }

    private static Map<String, Object> object(Object value, String name) throws IOException {
        if (!(value instanceof Map<?, ?> parsed)) throw new IOException("SmartAPI " + name + " is not a JSON object");
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("SmartAPI " + name + " has a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<?> array(Object value, String name) throws IOException {
        if (!(value instanceof List<?> result)) throw new IOException("SmartAPI " + name + " is not an array");
        return result;
    }

    private static BigDecimal decimal(Object value, String name) {
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException(name + " must be a JSON number");
        return number;
    }

    private static Long optionalLong(Object value, String name) {
        if (value == null) return null;
        try {
            long parsed = decimal(value, name).longValueExact();
            if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return parsed;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " must be a non-negative integer", invalid);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("SmartAPI response is not valid UTF-8", invalid);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
}
