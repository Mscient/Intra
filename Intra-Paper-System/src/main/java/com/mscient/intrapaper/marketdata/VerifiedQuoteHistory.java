package com.mscient.intrapaper.marketdata;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** A complete, integrity-checked quote journal snapshot whose bytes remained stable while reading. */
public record VerifiedQuoteHistory(Path path, String fileSha256, QuoteJournalSummary summary,
        List<MarketQuote> quotes) {

    public VerifiedQuoteHistory {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fileSha256 must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(summary, "summary");
        quotes = List.copyOf(Objects.requireNonNull(quotes, "quotes"));
        if (summary.eventCount() != quotes.size()) {
            throw new IllegalArgumentException("quote summary event count differs from loaded history");
        }
    }

    public static VerifiedQuoteHistory load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        String before = sha256(absolute);
        List<MarketQuote> quotes = new ArrayList<>();
        QuoteJournalSummary summary = QuoteJournalReader.replay(absolute, quotes::add);
        String after = sha256(absolute);
        if (!before.equals(after)) {
            throw new IOException("Quote journal changed while it was being validated: " + absolute);
        }
        return new VerifiedQuoteHistory(absolute, after, summary, quotes);
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
