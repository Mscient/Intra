package com.mscient.intrapaper.marketdata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RawCaptureEncoder {
    private RawCaptureEncoder() { }

    static byte[] encode(RawMarketMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(message.schemaVersion());
                writeString(out, message.source());
                writeString(out, message.connectionId());
                writeString(out, message.subscriptionId());
                out.writeBoolean(message.sourceSequence().isPresent());
                if (message.sourceSequence().isPresent()) out.writeLong(message.sourceSequence().getAsLong());
                out.writeLong(message.receivedAtUtc().getEpochSecond());
                out.writeInt(message.receivedAtUtc().getNano());
                out.writeLong(message.receivedAtMonotonicNanos());
                byte[] payload = message.payload();
                out.writeInt(payload.length);
                out.write(payload);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode raw capture record", impossible);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
