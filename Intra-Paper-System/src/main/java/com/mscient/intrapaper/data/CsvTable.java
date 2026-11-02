package com.mscient.intrapaper.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict UTF-8 CSV reader for the versioned local input contracts. */
public record CsvTable(List<String> headers, List<Map<String, String>> rows) {
    public CsvTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }

    public static CsvTable read(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
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
            return new CsvTable(headers, rows);
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
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else {
                field.append(ch);
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
