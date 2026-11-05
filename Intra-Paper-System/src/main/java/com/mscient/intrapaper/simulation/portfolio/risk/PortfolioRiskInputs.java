package com.mscient.intrapaper.simulation.portfolio.risk;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Frozen caller inputs. There is deliberately no interpolation, latest-value lookup or price fallback. */
public record PortfolioRiskInputs(RiskConfiguration configuration, Map<String, Integer> lotSizes,
        Map<Instant, FrameInputs> frames, Map<String, String> sourceHashes) {
    public record Sample(Optional<BigDecimal> mark, Optional<Instant> markAsOf, String markSource,
            String markReference, Optional<MarginInput> margin, Optional<BigDecimal> buyLimit,
            Optional<BigDecimal> sellLimit) {
        public Sample {
            Objects.requireNonNull(mark); Objects.requireNonNull(markAsOf); Objects.requireNonNull(margin);
            Objects.requireNonNull(buyLimit); Objects.requireNonNull(sellLimit);
            Objects.requireNonNull(markSource); Objects.requireNonNull(markReference);
            mark.ifPresent(v -> positive(v, "mark")); buyLimit.ifPresent(v -> positive(v, "buy limit"));
            sellLimit.ifPresent(v -> positive(v, "sell limit"));
            if (mark.isPresent() != markAsOf.isPresent()
                    || (mark.isPresent() && (markSource.isBlank() || markReference.isBlank()))
                    || (mark.isEmpty() && (!markSource.isEmpty() || !markReference.isEmpty())))
                throw new IllegalArgumentException("Mark requires price, as-of, source and reference together");
        }
        List<String> fields() {
            return List.of(mark.map(BigDecimal::toPlainString).orElse(""), markAsOf.map(Instant::toString).orElse(""),
                    markSource, markReference, margin.map(m -> pack(List.of(m.instrumentId(), m.amountPerContract().toPlainString(),
                            m.source(), m.reference(), m.sessionId(), m.asOf().toString()))).orElse(""),
                    buyLimit.map(BigDecimal::toPlainString).orElse(""), sellLimit.map(BigDecimal::toPlainString).orElse(""));
        }
    }
    public record FrameInputs(Instant timestamp, String sessionId, Map<String, Sample> samples) {
        public FrameInputs {
            Objects.requireNonNull(timestamp); requireText(sessionId); samples = sorted(samples);
        }
    }
    public PortfolioRiskInputs {
        Objects.requireNonNull(configuration); lotSizes = sorted(lotSizes); frames = sorted(frames); sourceHashes = sorted(sourceHashes);
        if (!configuration.currency().equals("INR") || !lotSizes.keySet().equals(configuration.instruments().keySet()))
            throw new IllegalArgumentException("Replay requires INR and exact configured instrument/lot coverage");
        for (var entry : lotSizes.entrySet()) {
            if (entry.getValue() < 1 || configuration.instruments().get(entry.getKey()).contractMultiplier()
                    .compareTo(BigDecimal.valueOf(entry.getValue())) != 0)
                throw new IllegalArgumentException("Gateway multiplier must equal explicit replay lot size");
        }
        frames.forEach((at, frame) -> {
            if (!at.equals(frame.timestamp()) || !configuration.instruments().keySet().containsAll(frame.samples().keySet()))
                throw new IllegalArgumentException("Frame identity or instrument universe mismatch");
        });
        sourceHashes.values().forEach(h -> { if (!h.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source hash"); });
    }
    public String fingerprint() {
        List<String> values = new ArrayList<>(List.of("PORTFOLIO_RISK_INPUTS_V1", configuration.currency(),
                configuration.maxGrossNotional().toPlainString(), configuration.maxSuppliedMargin().toPlainString(),
                configuration.maxDailyLoss().toPlainString(), configuration.maximumInputAge().toString()));
        configuration.instruments().forEach((id, spec) -> values.add(pack(List.of("INSTRUMENT", id,
                spec.contractMultiplier().toPlainString(), Long.toString(spec.maxAbsolutePosition()), lotSizes.get(id).toString()))));
        frames.forEach((at, frame) -> {
            values.add(pack(List.of("FRAME", at.toString(), frame.sessionId())));
            frame.samples().forEach((id, sample) -> values.add(pack(List.of("SAMPLE", id, pack(sample.fields())))));
        });
        sourceHashes.forEach((name, hash) -> values.add(pack(List.of("SOURCE", name, hash))));
        return sha(pack(values));
    }
    public static PortfolioRiskInputs load(Path limits, Path instruments, Path samples) throws IOException {
        CsvTable l = read(limits, "currency,max_gross_notional_inr,max_margin_proxy_inr,max_daily_loss_inr,maximum_input_age_seconds");
        CsvTable i = read(instruments, "instrument_id,lot_size,max_abs_contracts");
        CsvTable s = read(samples, "frame_time_utc,session_id,instrument_id,mark_asof_utc,mark_price,mark_source,mark_reference,margin_asof_utc,margin_per_contract,margin_source,margin_reference,buy_risk_price_ceiling,sell_risk_price_ceiling");
        if (l.rows().size() != 1) throw new IllegalArgumentException("Exactly one explicit limits row is required");
        var row = l.rows().getFirst();
        Map<String, Integer> lots = new TreeMap<>(); Map<String, InstrumentRiskSpec> specs = new TreeMap<>();
        for (var r : i.rows()) {
            String id = required(r, "instrument_id"); int lot = Integer.parseInt(required(r, "lot_size"));
            if (lot < 1 || lots.putIfAbsent(id, lot) != null) throw new IllegalArgumentException("Invalid/duplicate instrument lot size");
            specs.put(id, new InstrumentRiskSpec(id, required(row, "currency"), BigDecimal.valueOf(lot),
                    Long.parseLong(required(r, "max_abs_contracts"))));
        }
        RiskConfiguration config = new RiskConfiguration(required(row, "currency"), specs,
                new BigDecimal(required(row, "max_gross_notional_inr")), new BigDecimal(required(row, "max_margin_proxy_inr")),
                new BigDecimal(required(row, "max_daily_loss_inr")), Duration.ofSeconds(Long.parseLong(required(row, "maximum_input_age_seconds"))));
        Map<Instant, FrameInputs> frames = new TreeMap<>();
        for (var r : s.rows()) {
            Instant at = Instant.parse(required(r, "frame_time_utc")); String session = required(r, "session_id");
            String id = required(r, "instrument_id");
            Optional<MarginInput> margin = Optional.empty();
            if (!r.get("margin_per_contract").isEmpty()) margin = Optional.of(new MarginInput(id,
                    new BigDecimal(required(r, "margin_per_contract")), required(r, "margin_source"),
                    required(r, "margin_reference"), session, Instant.parse(required(r, "margin_asof_utc"))));
            else if (!r.get("margin_source").isEmpty() || !r.get("margin_reference").isEmpty() || !r.get("margin_asof_utc").isEmpty())
                throw new IllegalArgumentException("Partial margin provenance");
            Sample sample = new Sample(decimal(r, "mark_price"), r.get("mark_asof_utc").isEmpty() ? Optional.empty()
                    : Optional.of(Instant.parse(required(r, "mark_asof_utc"))), r.get("mark_source"), r.get("mark_reference"), margin,
                    decimal(r, "buy_risk_price_ceiling"), decimal(r, "sell_risk_price_ceiling"));
            FrameInputs prior = frames.get(at);
            if (prior != null && !prior.sessionId().equals(session)) throw new IllegalArgumentException("Conflicting frame session");
            Map<String, Sample> copy = new TreeMap<>(prior == null ? Map.of() : prior.samples());
            if (copy.putIfAbsent(id, sample) != null) throw new IllegalArgumentException("Duplicate frame/instrument sample");
            frames.put(at, new FrameInputs(at, session, copy));
        }
        return new PortfolioRiskInputs(config, lots, frames, Map.of("limits", l.sha256(), "instruments", i.sha256(), "samples", s.sha256()));
    }
    static CsvTable read(Path path, String headers) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(List.of(headers.split(",")))) throw new IllegalArgumentException("Expected headers: " + headers);
        return table;
    }
    static Optional<BigDecimal> decimal(Map<String, String> row, String key) {
        return row.get(key).isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(required(row, key)));
    }
    static String required(Map<String, String> row, String key) { String v = row.get(key); requireText(v); return v; }
    static void requireText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("Required trimmed single-line value");
    }
    static void positive(BigDecimal value, String label) { if (value.signum() <= 0) throw new IllegalArgumentException(label + " must be positive"); }
    static <K extends Comparable<? super K>, V> Map<K, V> sorted(Map<K, V> map) { return Collections.unmodifiableMap(new TreeMap<>(map)); }
    static String sha(String value) { return RunConfig.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static String pack(List<String> fields) {
        return fields.stream().map(v -> Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
    }
    static List<String> unpack(String value) {
        return Arrays.stream(value.split("\\.", -1)).map(v -> new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)).toList();
    }
}
