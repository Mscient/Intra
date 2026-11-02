package com.mscient.intrapaper.marketdata;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** On-disk format constants for the append-only normalized quote journal. */
final class QuoteJournalFormat {
    static final int VERSION = 1;
    static final int HASH_BYTES = 32;
    static final int MAX_RECORD_BYTES = 1_048_576;
    private static final byte[] MAGIC = "INTRAQJ1".getBytes(StandardCharsets.US_ASCII);

    private QuoteJournalFormat() {}

    static byte[] header() {
        return ByteBuffer.allocate(MAGIC.length + Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(VERSION)
                .array();
    }
}
