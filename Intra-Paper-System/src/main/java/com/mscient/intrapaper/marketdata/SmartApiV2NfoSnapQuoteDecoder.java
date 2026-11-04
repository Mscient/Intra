package com.mscient.intrapaper.marketdata;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.DatedInstrumentMappingRegistry;
import com.mscient.intrapaper.reference.InstrumentMappingResolution;
import com.mscient.intrapaper.reference.InstrumentMasterSnapshot;
import com.mscient.intrapaper.reference.InstrumentReference;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Strict offline decoder for documented SmartAPI WebSocket 2.0 NFO Snap Quote packets. */
public final class SmartApiV2NfoSnapQuoteDecoder implements RawQuoteDecoder {
    private static final int SNAP_QUOTE_MODE = 3;
    private static final int NFO_EXCHANGE_TYPE = 2;
    private static final int SNAP_QUOTE_BYTES = 379;
    private static final int TOKEN_OFFSET = 2;
    private static final int TOKEN_BYTES = 25;
    private static final int SEQUENCE_OFFSET = 27;
    private static final int EXCHANGE_TIME_OFFSET = 35;
    private static final int BEST_FIVE_OFFSET = 147;
    private static final int LEVEL_BYTES = 20;
    private static final int LEVELS_PER_SIDE = 5;
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");

    private final DatedInstrumentMappingRegistry mappingRegistry;
    private final InstrumentMasterSnapshot instrumentMaster;

    public SmartApiV2NfoSnapQuoteDecoder(DatedInstrumentMappingRegistry mappingRegistry,
            InstrumentMasterSnapshot instrumentMaster) {
        this.mappingRegistry = Objects.requireNonNull(mappingRegistry, "mappingRegistry");
        this.instrumentMaster = Objects.requireNonNull(instrumentMaster, "instrumentMaster");
    }

    @Override
    public Optional<MarketQuote> decode(RawMarketMessage message) {
        Objects.requireNonNull(message, "message");
        byte[] payload = message.payload();
        if (isHeartbeat(payload)) return Optional.empty();
        if (payload.length != SNAP_QUOTE_BYTES) {
            throw new IllegalArgumentException("Unsupported SmartAPI packet length " + payload.length
                    + "; only the documented 379-byte Snap Quote mode is accepted");
        }
        ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int mode = Byte.toUnsignedInt(packet.get(0));
        int exchangeType = Byte.toUnsignedInt(packet.get(1));
        if (mode != SNAP_QUOTE_MODE) {
            throw new IllegalArgumentException("Unsupported SmartAPI subscription mode " + mode
                    + "; Snap Quote mode 3 is required for bid/ask snapshots");
        }
        if (exchangeType != NFO_EXCHANGE_TYPE) {
            throw new IllegalArgumentException("Unsupported SmartAPI exchange type " + exchangeType
                    + "; this decoder accepts NFO exchange type 2 only");
        }

        String token = readToken(payload);
        long sourceSequence = packet.getLong(SEQUENCE_OFFSET);
        if (sourceSequence < 0) throw new IllegalArgumentException("SmartAPI packet sequence must be non-negative");
        long exchangeEpochMillis = packet.getLong(EXCHANGE_TIME_OFFSET);
        if (exchangeEpochMillis <= 0) throw new IllegalArgumentException("SmartAPI exchange timestamp must be positive epoch milliseconds");
        Instant exchangeTime = Instant.ofEpochMilli(exchangeEpochMillis);
        InstrumentMappingResolution resolution = mappingRegistry.resolve("NFO", token, exchangeTime);
        if (resolution.mapping().instrumentType() != ContractKind.FUTURE
                && resolution.mapping().instrumentType() != ContractKind.OPTION) {
            throw new IllegalArgumentException("SmartAPI NFO token does not resolve to a futures or options mapping");
        }
        LocalDate sessionDate = exchangeTime.atZone(NSE_ZONE).toLocalDate();
        InstrumentReference datedReference = instrumentMaster.requireContract("NSE", "NFO", token, sessionDate);
        var datedMapping = resolution.mapping();
        if (!datedReference.instrumentId().equals(resolution.instrumentId())
                || !datedReference.tradingSymbol().equals(datedMapping.tradingSymbol())
                || datedReference.contractKind() != datedMapping.instrumentType()
                || !datedReference.expiry().equals(datedMapping.expiry())
                || !datedReference.strike().equals(datedMapping.strike())
                || !datedReference.optionRight().equals(datedMapping.optionRight())) {
            throw new IllegalArgumentException("UTC token registry and session-dated instrument master disagree on contract identity");
        }
        long tickSizePaise = toPaise(datedReference.tickSize());

        BookLevel bid = bestLevel(packet, 0);
        BookLevel ask = bestLevel(packet, 1);
        long bidTicks = toTicks(bid.pricePaise(), tickSizePaise, "bid");
        long askTicks = toTicks(ask.pricePaise(), tickSizePaise, "ask");
        return Optional.of(new MarketQuote(1, 1, message.source(), resolution.instrumentId(), Optional.of(exchangeTime),
                message.receivedAtUtc(), message.receivedAtMonotonicNanos(), bidTicks, bid.quantity(),
                askTicks, ask.quantity()));
    }

    @Override
    public OptionalLong sourceSequence(RawMarketMessage message) {
        Objects.requireNonNull(message, "message");
        byte[] payload = message.payload();
        if (isHeartbeat(payload)) return OptionalLong.empty();
        if (payload.length != SNAP_QUOTE_BYTES) {
            throw new IllegalArgumentException("Unsupported SmartAPI packet length " + payload.length
                    + "; only the documented 379-byte Snap Quote mode is accepted");
        }
        ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (Byte.toUnsignedInt(packet.get(0)) != SNAP_QUOTE_MODE
                || Byte.toUnsignedInt(packet.get(1)) != NFO_EXCHANGE_TYPE) {
            throw new IllegalArgumentException("Source sequence is available only for NFO Snap Quote mode 3 packets");
        }
        long sequence = packet.getLong(SEQUENCE_OFFSET);
        if (sequence < 0) throw new IllegalArgumentException("SmartAPI packet sequence must be non-negative");
        return OptionalLong.of(sequence);
    }

    @Override
    public String sequenceDomain() {
        return "smartapi.nfo_snap_quote.packet_sequence";
    }

    private static long toPaise(BigDecimal tickSizeRupees) {
        try {
            long tickSizePaise = tickSizeRupees.movePointRight(2).longValueExact();
            if (tickSizePaise < 1) throw new ArithmeticException("non-positive paise tick");
            return tickSizePaise;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Dated NFO tick size must be an exact positive number of paise", invalid);
        }
    }

    private static BookLevel bestLevel(ByteBuffer packet, int side) {
        int expectedFlag = side == 0 ? 1 : 0;
        int sideOffset = BEST_FIVE_OFFSET + side * LEVELS_PER_SIDE * LEVEL_BYTES;
        long bestPrice = 0;
        long bestQuantity = 0;
        for (int level = 0; level < LEVELS_PER_SIDE; level++) {
            int offset = sideOffset + level * LEVEL_BYTES;
            short flag = packet.getShort(offset);
            long quantity = packet.getLong(offset + 2);
            long pricePaise = packet.getLong(offset + 10);
            short orderCount = packet.getShort(offset + 18);
            if (quantity < 0 || pricePaise < 0 || orderCount < 0) {
                throw new IllegalArgumentException("SmartAPI best-five level contains a negative value");
            }
            if (quantity == 0 && pricePaise == 0 && orderCount == 0) continue;
            if (flag != expectedFlag) {
                throw new IllegalArgumentException("SmartAPI best-five level has an unexpected buy/sell flag");
            }
            if ((quantity == 0) != (pricePaise == 0)) {
                throw new IllegalArgumentException("SmartAPI best-five level has price without size or size without price");
            }
            if (quantity == 0) continue;
            if (side == 0 ? pricePaise > bestPrice : bestPrice == 0 || pricePaise < bestPrice) {
                bestPrice = pricePaise;
                bestQuantity = quantity;
            } else if (pricePaise == bestPrice) {
                bestQuantity = Math.addExact(bestQuantity, quantity);
            }
        }
        return new BookLevel(bestPrice, bestQuantity);
    }

    private static long toTicks(long pricePaise, long tickSizePaise, String side) {
        if (pricePaise == 0) return 0;
        if (pricePaise % tickSizePaise != 0) {
            throw new IllegalArgumentException("SmartAPI " + side + " price in paise is not an exact multiple of mapped tick size");
        }
        return pricePaise / tickSizePaise;
    }

    private static String readToken(byte[] payload) {
        int end = TOKEN_OFFSET;
        int limit = TOKEN_OFFSET + TOKEN_BYTES;
        while (end < limit && payload[end] != 0) end++;
        if (end == TOKEN_OFFSET) throw new IllegalArgumentException("SmartAPI packet token is empty");
        for (int i = end; i < limit; i++) {
            if (payload[i] != 0) throw new IllegalArgumentException("SmartAPI packet token has non-zero bytes after terminator");
        }
        String token = new String(payload, TOKEN_OFFSET, end - TOKEN_OFFSET, StandardCharsets.US_ASCII);
        if (!token.matches("[0-9]+")) throw new IllegalArgumentException("SmartAPI NFO token must contain ASCII digits only");
        return token;
    }

    private static boolean isHeartbeat(byte[] payload) {
        return java.util.Arrays.equals(payload, "ping".getBytes(StandardCharsets.US_ASCII))
                || java.util.Arrays.equals(payload, "pong".getBytes(StandardCharsets.US_ASCII));
    }

    private record BookLevel(long pricePaise, long quantity) { }
}
