package com.mscient.intrapaper.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict UTF-8 CSV reader for the versioned local input contracts. */
public record CsvTable(List<String> headers, List<Map<String, String>> rows, String sha256) {
    public CsvTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid CSV input SHA-256");
        }
    }

    public static CsvTable read(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new DigestInputStream(Files.newInputStream(path), digest), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("CSV is empty: " + path);
            }
            List<String> headers = parseLine(headerLine.replaceFirst("^\\uFEFF", ""));
            if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("CSV headers must be non-empty: " + path);
            }
            if (headers.stream().distinct().count() != headers.size()) {
                throw new IllegalArgumentException("Duplicate CSV headers: " + path);
            }
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseLine(line);
                if (values.size() != headers.size()) {
                    throw new IllegalArgumentException("CSV row " + lineNumber + " has " + values.size()
                            + " fields; expected " + headers.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), values.get(i));
                }
                rows.add(Map.copyOf(row));
            }
            return new CsvTable(headers, rows, HexFormat.of().formatHex(digest.digest()));
        }
    }

    public void requireHeaders(String... required) {
        for (String header : required) {
            if (!headers.contains(header)) {
                throw new IllegalArgumentException("Missing required CSV column: " + header);
            }
        }
    }

    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                } else {
                    field.append(ch);
                }
            } else {
                if (quoteClosed) {
                    if (ch != ',') {
                        throw new IllegalArgumentException("CSV field has characters after its closing quote");
                    }
                    fields.add(field.toString());
                    field.setLength(0);
                    quoteClosed = false;
                } else if (ch == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else if (ch == '"') {
                    if (!field.isEmpty()) {
                        throw new IllegalArgumentException("CSV quote may only start at the beginning of a field");
                    }
                    quoted = true;
                } else {
                    field.append(ch);
                }
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV line has an unterminated quoted field");
        }
        fields.add(field.toString());
        return fields;
    }

    public static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
