package com.mscient.intrapaper.marketdata;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class FeedLifecycleFormat {
    static final int HASH_BYTES = 32;
    static final int MAX_RECORD_BYTES = 262_144;
    private static final byte[] HEADER = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put("INTRALIF".getBytes(StandardCharsets.US_ASCII)).putInt(1).array();

    private FeedLifecycleFormat() { }

    static byte[] header() { return HEADER.clone(); }
}
