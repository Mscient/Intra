package com.mscient.intrapaper.marketdata;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

/** Reads the writer's locked handle without closing it or opening a competing Windows handle. */
final class JournalChannelInput {
    private JournalChannelInput() { }
    static InputStream borrow(FileChannel channel) throws IOException {
        channel.position(0);
        return new FilterInputStream(Channels.newInputStream(channel)) {
            @Override public void close() { /* The writer owns the channel and its lifetime lock. */ }
        };
    }
}
