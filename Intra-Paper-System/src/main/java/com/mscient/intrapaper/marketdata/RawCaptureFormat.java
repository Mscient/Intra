package com.mscient.intrapaper.marketdata;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class RawCaptureFormat {
    static final int HASH_BYTES = 32;
    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final byte[] HEADER = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put("INTRARAW".getBytes(StandardCharsets.US_ASCII)).putInt(1).array();
    private RawCaptureFormat() { }
    static byte[] header() { return HEADER.clone(); }
}
