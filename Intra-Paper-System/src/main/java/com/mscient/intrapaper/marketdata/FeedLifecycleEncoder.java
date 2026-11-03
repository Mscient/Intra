package com.mscient.intrapaper.marketdata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

final class FeedLifecycleEncoder {
    private FeedLifecycleEncoder() { }

    static byte[] encode(FeedLifecycleEvent event) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(event.schemaVersion());
                writeString(out, event.source());
                writeString(out, event.connectionId());
                writeString(out, event.type().name());
                out.writeBoolean(event.subscriptionId().isPresent());
                if (event.subscriptionId().isPresent()) writeString(out, event.subscriptionId().orElseThrow());
                out.writeLong(event.observedAtUtc().getEpochSecond());
                out.writeInt(event.observedAtUtc().getNano());
                out.writeLong(event.observedAtMonotonicNanos());
                out.writeBoolean(event.detailCode().isPresent());
                if (event.detailCode().isPresent()) writeString(out, event.detailCode().orElseThrow().name());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode feed lifecycle event", impossible);
        }
    }

    static FeedLifecycleEvent decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int schema = in.readInt();
            String source = readString(in);
            String connectionId = readString(in);
            FeedLifecycleEvent.Type type;
            try {
                type = FeedLifecycleEvent.Type.valueOf(readString(in));
            } catch (IllegalArgumentException unknown) {
                throw new IOException("Unknown feed lifecycle event type", unknown);
            }
            Optional<String> subscriptionId = in.readBoolean() ? Optional.of(readString(in)) : Optional.empty();
            Instant timestamp = Instant.ofEpochSecond(in.readLong(), in.readInt());
            long monotonic = in.readLong();
            Optional<FeedLifecycleEvent.DetailCode> detailCode = Optional.empty();
            if (in.readBoolean()) {
                try {
                    detailCode = Optional.of(FeedLifecycleEvent.DetailCode.valueOf(readString(in)));
                } catch (IllegalArgumentException unknown) {
                    throw new IOException("Unknown feed lifecycle detail code", unknown);
                }
            }
            if (in.available() != 0) throw new IOException("Trailing bytes in feed lifecycle event");
            return new FeedLifecycleEvent(schema, source, connectionId, type, subscriptionId,
                    timestamp, monotonic, detailCode);
        } catch (EOFException truncated) {
            throw new IOException("Truncated feed lifecycle event", truncated);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid feed lifecycle event: " + invalid.getMessage(), invalid);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 65_536) throw new IOException("Invalid feed lifecycle string length");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated feed lifecycle string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
