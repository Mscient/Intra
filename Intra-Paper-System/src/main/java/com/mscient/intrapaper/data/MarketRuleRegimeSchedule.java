package com.mscient.intrapaper.data;

import com.mscient.intrapaper.reference.ContractKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Caller-supplied, effective-dated market-rule regime registry. */
public record MarketRuleRegimeSchedule(Map<String, MarketRuleRegime> regimes, String sha256) {
    private static final List<String> HEADERS = List.of(
            "market_rule_regime_id", "effective_from", "effective_through", "exchange", "segment",
            "contract_kind", "timezone", "session_open_local", "session_close_local", "preopen_enabled",
            "preopen_start_local", "preopen_end_local", "expiry_weekday", "holiday_expiry_shift",
            "lot_size", "tick_size", "expiry_elm_addon_percent", "calendar_spread_expiry_eligible",
            "source_reference", "source_published_date", "source_sha256", "verification_status");

    public enum VerificationStatus {
        PRIMARY_SOURCE_REVIEWED,
        PROVISIONAL,
        UNVERIFIED,
        SYNTHETIC_ONLY
    }

    public record MarketRuleRegime(
            String id,
            LocalDate effectiveFrom,
            Optional<LocalDate> effectiveThrough,
            String exchange,
            String segment,
            ContractKind contractKind,
            String timezone,
            LocalTime sessionOpenLocal,
            LocalTime sessionCloseLocal,
            boolean preopenEnabled,
            Optional<LocalTime> preopenStartLocal,
            Optional<LocalTime> preopenEndLocal,
            Optional<DayOfWeek> expiryWeekday,
            Optional<String> holidayExpiryShift,
            int lotSize,
            BigDecimal tickSize,
            Optional<BigDecimal> expiryElmAddonPercent,
            Optional<Boolean> calendarSpreadExpiryEligible,
            String sourceReference,
            Optional<LocalDate> sourcePublishedDate,
            Optional<String> sourceSha256,
            VerificationStatus verificationStatus) {

        public MarketRuleRegime {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            effectiveThrough = Objects.requireNonNull(effectiveThrough, "effectiveThrough");
            Objects.requireNonNull(exchange, "exchange");
            Objects.requireNonNull(segment, "segment");
            Objects.requireNonNull(contractKind, "contractKind");
            Objects.requireNonNull(timezone, "timezone");
            ZoneId.of(timezone);
            Objects.requireNonNull(sessionOpenLocal, "sessionOpenLocal");
            Objects.requireNonNull(sessionCloseLocal, "sessionCloseLocal");
            preopenStartLocal = Objects.requireNonNull(preopenStartLocal, "preopenStartLocal");
            preopenEndLocal = Objects.requireNonNull(preopenEndLocal, "preopenEndLocal");
            expiryWeekday = Objects.requireNonNull(expiryWeekday, "expiryWeekday");
            holidayExpiryShift = Objects.requireNonNull(holidayExpiryShift, "holidayExpiryShift");
            Objects.requireNonNull(tickSize, "tickSize");
            expiryElmAddonPercent = Objects.requireNonNull(expiryElmAddonPercent, "expiryElmAddonPercent");
            calendarSpreadExpiryEligible = Objects.requireNonNull(
                    calendarSpreadExpiryEligible, "calendarSpreadExpiryEligible");
            Objects.requireNonNull(sourceReference, "sourceReference");
            sourcePublishedDate = Objects.requireNonNull(sourcePublishedDate, "sourcePublishedDate");
            sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
            Objects.requireNonNull(verificationStatus, "verificationStatus");

            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid market-rule regime ID: " + id);
            }
            if (id.isBlank() || exchange.isBlank() || segment.isBlank() || sourceReference.isBlank()) {
                throw new IllegalArgumentException("Regime ID, exchange, segment, and source reference must be non-empty");
            }
            if (!id.equals(id.strip()) || !exchange.equals(exchange.strip())
                    || !segment.equals(segment.strip()) || !timezone.equals(timezone.strip())
                    || !sourceReference.equals(sourceReference.strip())) {
                throw new IllegalArgumentException("Market-rule text fields must be trimmed");
            }
            if (sourceReference.length() > 2000) {
                throw new IllegalArgumentException("source_reference must be at most 2000 characters");
            }
            if (effectiveThrough.isPresent() && effectiveThrough.orElseThrow().isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("effective_through must not precede effective_from");
            }
            if (!sessionCloseLocal.isAfter(sessionOpenLocal)) {
                throw new IllegalArgumentException("session_close_local must be later than session_open_local");
            }
            if (preopenEnabled != (preopenStartLocal.isPresent() && preopenEndLocal.isPresent())) {
                throw new IllegalArgumentException("Enabled pre-open requires both local times; disabled pre-open requires blanks");
            }
            if (preopenEnabled && (!preopenEndLocal.orElseThrow().isAfter(preopenStartLocal.orElseThrow())
                    || preopenEndLocal.orElseThrow().isAfter(sessionOpenLocal))) {
                throw new IllegalArgumentException("Pre-open times must be ordered and end no later than the regular session open");
            }
            if (lotSize <= 0 || tickSize.signum() <= 0) {
                throw new IllegalArgumentException("lot_size and tick_size must be positive");
            }
            expiryElmAddonPercent.ifPresent(value -> {
                if (value.signum() < 0) throw new IllegalArgumentException("expiry_elm_addon_percent must be non-negative");
            });
            holidayExpiryShift.ifPresent(value -> {
                if (!value.equals(value.strip())) throw new IllegalArgumentException("holiday_expiry_shift must be trimmed");
                if (!List.of("PREVIOUS_TRADING_DAY", "NEXT_TRADING_DAY", "NONE", "UNRESOLVED").contains(value)) {
                    throw new IllegalArgumentException("Unsupported holiday_expiry_shift: " + value);
                }
            });
            sourceSha256.ifPresent(value -> {
                if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("source_sha256 must be lowercase SHA-256");
            });
            if (verificationStatus == VerificationStatus.PRIMARY_SOURCE_REVIEWED
                    && (sourceSha256.isEmpty() || sourcePublishedDate.isEmpty())) {
                throw new IllegalArgumentException(
                        "PRIMARY_SOURCE_REVIEWED regimes require source_published_date and source_sha256");
            }
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom)
                    && (effectiveThrough.isEmpty() || !date.isAfter(effectiveThrough.orElseThrow()));
        }
    }

    public MarketRuleRegimeSchedule {
        regimes = Map.copyOf(regimes);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Market-rule regime schedule requires a SHA-256");
        }
    }

    public static MarketRuleRegimeSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Market-rule regime CSV headers must exactly match: " + HEADERS);
        }
        Map<String, MarketRuleRegime> regimes = new HashMap<>();
        for (int index = 0; index < table.rows().size(); index++) {
            Map<String, String> row = table.rows().get(index);
            int rowNumber = index + 2;
            String id = required(row, "market_rule_regime_id", rowNumber);
            Optional<LocalDate> effectiveThrough = optional(row, "effective_through")
                    .map(LocalDate::parse);
            boolean preopenEnabled = strictBoolean(row, "preopen_enabled", rowNumber);
            MarketRuleRegime regime = new MarketRuleRegime(
                    id,
                    LocalDate.parse(required(row, "effective_from", rowNumber)),
                    effectiveThrough,
                    required(row, "exchange", rowNumber),
                    required(row, "segment", rowNumber),
                    ContractKind.valueOf(required(row, "contract_kind", rowNumber)),
                    required(row, "timezone", rowNumber),
                    LocalTime.parse(required(row, "session_open_local", rowNumber)),
                    LocalTime.parse(required(row, "session_close_local", rowNumber)),
                    preopenEnabled,
                    optional(row, "preopen_start_local").map(LocalTime::parse),
                    optional(row, "preopen_end_local").map(LocalTime::parse),
                    optional(row, "expiry_weekday").map(DayOfWeek::valueOf),
                    optional(row, "holiday_expiry_shift"),
                    Integer.parseInt(required(row, "lot_size", rowNumber)),
                    new BigDecimal(required(row, "tick_size", rowNumber)),
                    optional(row, "expiry_elm_addon_percent").map(BigDecimal::new),
                    optional(row, "calendar_spread_expiry_eligible").map(value -> parseBoolean(
                            value, "calendar_spread_expiry_eligible", rowNumber)),
                    required(row, "source_reference", rowNumber),
                    optional(row, "source_published_date").map(LocalDate::parse),
                    optional(row, "source_sha256"),
                    VerificationStatus.valueOf(required(row, "verification_status", rowNumber)));
            if (regimes.putIfAbsent(id, regime) != null) {
                throw new IllegalArgumentException("Duplicate market-rule regime ID at row " + rowNumber + ": " + id);
            }
        }
        if (regimes.isEmpty()) throw new IllegalArgumentException("Market-rule regime CSV has no rows");
        requireNonOverlappingScopes(new ArrayList<>(regimes.values()));
        return new MarketRuleRegimeSchedule(regimes, table.sha256());
    }

    public MarketRuleRegime require(String id, LocalDate date,
            String exchange, String segment, ContractKind contractKind) {
        MarketRuleRegime regime = regimes.get(id);
        if (regime == null) throw new IllegalArgumentException("Unknown market-rule regime ID: " + id);
        if (!regime.covers(date)) {
            throw new IllegalArgumentException("Market-rule regime " + id + " is not effective on " + date);
        }
        if (!regime.exchange().equals(exchange) || !regime.segment().equals(segment)
                || regime.contractKind() != contractKind) {
            throw new IllegalArgumentException("Market-rule regime scope does not match " + exchange + "/"
                    + segment + "/" + contractKind + " on " + date);
        }
        return regime;
    }

    private static void requireNonOverlappingScopes(List<MarketRuleRegime> regimes) {
        Map<String, List<MarketRuleRegime>> scopes = new HashMap<>();
        for (MarketRuleRegime regime : regimes) {
            String scope = regime.exchange() + "|" + regime.segment() + "|" + regime.contractKind();
            scopes.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(regime);
        }
        for (Map.Entry<String, List<MarketRuleRegime>> scoped : scopes.entrySet()) {
            List<MarketRuleRegime> rows = scoped.getValue().stream()
                    .sorted((left, right) -> left.effectiveFrom().compareTo(right.effectiveFrom()))
                    .toList();
            for (int index = 1; index < rows.size(); index++) {
                MarketRuleRegime previous = rows.get(index - 1);
                MarketRuleRegime next = rows.get(index);
                if (previous.effectiveThrough().isEmpty()
                        || !previous.effectiveThrough().orElseThrow().isBefore(next.effectiveFrom())) {
                    throw new IllegalArgumentException("Overlapping market-rule regime effective ranges for "
                            + scoped.getKey() + ": " + previous.id() + " and " + next.id());
                }
            }
        }
    }

    private static String required(Map<String, String> row, String name, int rowNumber) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed at row " + rowNumber);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " must be trimmed");
        return Optional.of(value);
    }

    private static boolean strictBoolean(Map<String, String> row, String name, int rowNumber) {
        return parseBoolean(required(row, name, rowNumber), name, rowNumber);
    }

    private static boolean parseBoolean(String value, String name, int rowNumber) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException(name + " must be true or false at row " + rowNumber);
    }
}