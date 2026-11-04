package com.mscient.intrapaper.data;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Dependency-free strict JSON parser shared by offline source importers. */
public final class StrictJsonParser {
    private final String source;
    private int position;

    private StrictJsonParser(String source) { this.source = source; }

    public static Object parse(String source) throws IOException {
        StrictJsonParser parser = new StrictJsonParser(source);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.position != source.length()) throw parser.error("Trailing JSON content");
        return value;
    }

    private Object value(int depth) throws IOException {
        if (depth > 64) throw error("JSON nesting exceeds 64 levels");
        whitespace();
        if (position >= source.length()) throw error("Unexpected end of JSON");
        return switch (source.charAt(position)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws IOException {
        position++;
        whitespace();
        Map<String, Object> result = new TreeMap<>();
        if (take('}')) return result;
        while (true) {
            whitespace();
            if (position >= source.length() || source.charAt(position) != '"') {
                throw error("Object key must be a string");
            }
            String key = string();
            whitespace();
            require(':');
            Object value = value(depth);
            if (result.containsKey(key)) throw error("Duplicate JSON object key: " + key);
            result.put(key, value);
            whitespace();
            if (take('}')) return result;
            require(',');
        }
    }

    private List<Object> array(int depth) throws IOException {
        position++;
        whitespace();
        List<Object> result = new ArrayList<>();
        if (take(']')) return result;
        while (true) {
            result.add(value(depth));
            whitespace();
            if (take(']')) return result;
            require(',');
        }
    }

    private String string() throws IOException {
        require('"');
        StringBuilder result = new StringBuilder();
        while (position < source.length()) {
            char character = source.charAt(position++);
            if (character == '"') return result.toString();
            if (character < 0x20) throw error("Unescaped control character in string");
            if (character != '\\') {
                result.append(character);
                continue;
            }
            if (position >= source.length()) throw error("Incomplete JSON escape");
            char escaped = source.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(unicodeEscape());
                default -> throw error("Invalid JSON escape");
            }
        }
        throw error("Unterminated JSON string");
    }

    private char unicodeEscape() throws IOException {
        if (position + 4 > source.length()) throw error("Incomplete Unicode escape");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(source.charAt(position++), 16);
            if (digit < 0) throw error("Invalid Unicode escape");
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Object number() throws IOException {
        int start = position;
        if (take('-') && position >= source.length()) throw error("Incomplete JSON number");
        if (take('0')) {
            if (position < source.length() && Character.isDigit(source.charAt(position))) {
                throw error("Leading zero in JSON number");
            }
        } else {
            digits(true);
        }
        if (take('.')) digits(true);
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            digits(true);
        }
        try { return new BigDecimal(source.substring(start, position)); }
        catch (NumberFormatException invalid) { throw error("Invalid JSON number"); }
    }

    private void digits(boolean required) throws IOException {
        int start = position;
        while (position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
        if (required && position == start) throw error("Expected JSON digits");
    }

    private Object literal(String literal, Object value) throws IOException {
        if (!source.startsWith(literal, position)) throw error("Invalid JSON literal");
        position += literal.length();
        return value;
    }

    private void whitespace() {
        while (position < source.length()) {
            char character = source.charAt(position);
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r') position++;
            else break;
        }
    }

    private boolean take(char expected) {
        if (position < source.length() && source.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void require(char expected) throws IOException {
        if (!take(expected)) throw error("Expected '" + expected + "'");
    }

    private IOException error(String message) {
        return new IOException(message + " at JSON offset " + position);
    }
}
