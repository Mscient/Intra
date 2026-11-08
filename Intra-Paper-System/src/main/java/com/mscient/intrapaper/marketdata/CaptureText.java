package com.mscient.intrapaper.marketdata;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Prevents lossy replacement of caller-provided journal identity text. */
final class CaptureText {
    private CaptureText() { }

    static void validate(String value, String field) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be free of control characters");
        }
        try {
            int length = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value)).remaining();
            if (length > 65_536) throw new IllegalArgumentException(field + " exceeds 65536 UTF-8 bytes");
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(field + " must contain valid Unicode", malformed);
        }
    }
}
