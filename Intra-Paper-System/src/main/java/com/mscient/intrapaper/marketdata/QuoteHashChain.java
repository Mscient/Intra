package com.mscient.intrapaper.marketdata;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic SHA-256 chaining primitive for ordered normalized quote events. */
public final class QuoteHashChain {
    private static final int SHA256_BYTES = 32;

    private QuoteHashChain() {}

    /** The first record chains from 32 zero bytes. */
    public static byte[] genesisHash() {
        return new byte[SHA256_BYTES];
    }

    public static byte[] nextHash(byte[] previousHash, MarketQuote quote) {
        if (previousHash == null || previousHash.length != SHA256_BYTES) {
            throw new IllegalArgumentException("previousHash must contain 32 bytes");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousHash.clone());
            digest.update(CanonicalQuoteEncoder.encode(quote));
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailableInRuntime) {
            throw new IllegalStateException("SHA-256 is not available", unavailableInRuntime);
        }
    }

    public static String hex(byte[] hash) {
        if (hash == null || hash.length != SHA256_BYTES) {
            throw new IllegalArgumentException("hash must contain 32 bytes");
        }
        return HexFormat.of().formatHex(hash);
    }
}
