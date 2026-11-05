package com.mscient.intrapaper.evaluation.portfolio;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic writer and strict object helpers for the dedicated catalog/report contracts. */
final class PortfolioEvaluationJson {
    private PortfolioEvaluationJson() { }

    static Map<String, Object> object(Object... fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parsedObject(Object value, String label) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException(label + " must be a JSON object");
        return (Map<String, Object>) map;
    }

    static String string(Map<String, Object> value, String key) throws IOException {
        if (!(value.get(key) instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw new IOException(key + " must be a nonempty trimmed JSON string");
        }
        return text;
    }

    static String write(Object value) {
        StringBuilder text = new StringBuilder();
        append(text, value, 0);
        return text.append('\n').toString();
    }

    private static void append(StringBuilder text, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            text.append('{');
            int index = 0;
            for (var entry : map.entrySet()) {
                if (index++ > 0) text.append(',');
                text.append('\n').append(" ".repeat(indent + 2));
                quote(text, entry.getKey().toString());
                text.append(": ");
                append(text, entry.getValue(), indent + 2);
            }
            if (!map.isEmpty()) text.append('\n').append(" ".repeat(indent));
            text.append('}');
        } else if (value instanceof List<?> list) {
            text.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) text.append(',');
                text.append('\n').append(" ".repeat(indent + 2));
                append(text, list.get(index), indent + 2);
            }
            if (!list.isEmpty()) text.append('\n').append(" ".repeat(indent));
            text.append(']');
        } else if (value instanceof String string) {
            quote(text, string);
        } else if (value instanceof BigDecimal number) {
            text.append(number.stripTrailingZeros().toPlainString());
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Nonfinite report statistic");
            text.append(BigDecimal.valueOf(number).stripTrailingZeros().toPlainString());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            text.append(value);
        } else if (value == null) {
            text.append("null");
        } else {
            throw new IllegalArgumentException("Unsupported JSON report type");
        }
    }

    private static void quote(StringBuilder text, String value) {
        text.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (ch < 32) {
                        text.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) ch));
                    } else text.append(ch);
                }
            }
        }
        text.append('"');
    }
}
