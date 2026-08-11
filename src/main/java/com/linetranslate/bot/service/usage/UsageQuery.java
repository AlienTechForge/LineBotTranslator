package com.linetranslate.bot.service.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;

/** Immutable filter for database-side usage aggregation. */
public record UsageQuery(
        Instant fromInclusive,
        Instant toExclusive,
        String provider,
        String model,
        UsageContentKind contentKind) {

    public UsageQuery {
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Usage query start must precede end");
        }
        provider = normalize(provider);
        model = trim(model);
    }

    public static UsageQuery all() {
        return new UsageQuery(null, null, null, null, null);
    }

    public static UsageQuery forDay(LocalDate day, ZoneId zoneId) {
        if (day == null || zoneId == null) {
            throw new IllegalArgumentException("Day and zone are required");
        }
        return new UsageQuery(
                day.atStartOfDay(zoneId).toInstant(),
                day.plusDays(1).atStartOfDay(zoneId).toInstant(),
                null, null, null);
    }

    public static UsageQuery forMonth(YearMonth month, ZoneId zoneId) {
        if (month == null || zoneId == null) {
            throw new IllegalArgumentException("Month and zone are required");
        }
        return new UsageQuery(
                month.atDay(1).atStartOfDay(zoneId).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant(),
                null, null, null);
    }

    public UsageQuery withProvider(String value) {
        return new UsageQuery(fromInclusive, toExclusive, value, model, contentKind);
    }

    public UsageQuery withModel(String value) {
        return new UsageQuery(fromInclusive, toExclusive, provider, value, contentKind);
    }

    public UsageQuery withContentKind(UsageContentKind value) {
        return new UsageQuery(fromInclusive, toExclusive, provider, model, value);
    }

    private static String normalize(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
