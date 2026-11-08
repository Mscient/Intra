package com.mscient.intrapaper.g5;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import com.mscient.intrapaper.data.CsvTable;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Existing verifiers receive an isolated tree; references escaping that tree are rejected first. */
final class G5VerifierPaths {
    private G5VerifierPaths() { }
    static void beforeInvocation(String verifier, List<Path> args, Path root) throws IOException {
        for (Path p : args) {
            if (!p.normalize().startsWith(root)) throw invalid();
            safe(p,java.nio.file.Files.isDirectory(p));
        }
        switch (verifier) {
            case "PERMISSION", "HYPOTHESIS_CARD", "DAILY_OPS", "FEED_AUDIT" -> {
                for (Path p : args) check(json(read(p)),"");
            }
            case "PORTFOLIO_REPLAY" -> { if (args.size()==3) replay(args.get(0),args.get(1),root); }
            case "RISK_AUDIT" -> { if (args.size()==5) replay(args.get(2),args.get(3),root); }
            case "SETTLEMENT_AUDIT" -> { if (args.size()==6) replay(args.get(2),args.get(3),root); }
            case "TRIAL_EVALUATION" -> { if (args.size()==6) { check(json(read(args.get(1))),""); check(json(read(args.get(3))),""); } }
            default -> { }
        }
    }
    private static void replay(Path inputs, Path settings, Path root) throws IOException {
        // Parse using the existing CSV rules; never infer format safety from an extension.
        CsvTable table=CsvTable.read(inputs);
        table.requireHeaders("bars_csv","run_properties");
        for (Map<String,String> row : table.rows()) {
            reference(inputs.getParent(),row.get("bars_csv"),root);
            Path config=reference(inputs.getParent(),row.get("run_properties"),root);
            properties(config,root);
        }
        properties(settings,root);
    }
    private static void properties(Path file, Path root) throws IOException {
        Properties p=new Properties();
        // Decode Java escapes and continuation lines before checking paths used by RunConfig.
        p.load(new StringReader(decode(read(file))));
        for (String key : p.stringPropertyNames()) {
            String value=p.getProperty(key).strip(); check(value,key);
            if ((key.endsWith(".file") || key.endsWith("_file") || key.endsWith("_path") || key.endsWith("_csv")) && !value.isEmpty() && !value.equals("NONE"))
                reference(file.getParent(),value,root);
        }
    }
    private static Path reference(Path base, String value, Path root) throws IOException {
        Path p=base.resolve(relative(value)).normalize();
        if (!p.startsWith(root)) throw invalid();
        return safe(p,false);
    }
    static void validate(Map<String,byte[]> files) throws IOException {
        for (var e : files.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("history/") || name.startsWith("decisions/") || name.startsWith("archive/")) continue;
            if (name.endsWith(".json")) check(com.mscient.intrapaper.data.StrictJsonParser.parse(decode(e.getValue())),"");
        }
    }
    private static void check(Object o, String key) throws IOException {
        if (o instanceof Map<?,?> m) { for (var e : m.entrySet()) check(e.getValue(),e.getKey().toString()); }
        else if (o instanceof List<?> a) { for (Object v : a) check(v,key); }
        else if (o instanceof String s) {
            if (s.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) throw invalid();
            String k = key.toLowerCase(Locale.ROOT);
            // Base64 signatures and public keys are values, not paths; they may start with '/'.
            if ((k.equals("file") || k.equals("path") || k.endsWith(".file") || k.endsWith("_path") || k.endsWith("_file") || k.endsWith("_folder") || k.endsWith("_csv") || k.endsWith("_properties") || k.equals("arguments")) && !s.isEmpty()) relative(s);
        }
    }
}
