package com.exam.service.reports;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The time a report covers: [from, to). Both null means all time.
 * Comes from the chosen dates, else from the chosen academic session's start and end dates.
 */
public record Period(LocalDateTime from, LocalDateTime to, String label) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    static final LocalDateTime EARLIEST = LocalDateTime.of(1900, 1, 1, 0, 0);
    static final LocalDateTime LATEST = LocalDateTime.of(3000, 1, 1, 0, 0);

    public static Period allTime() { return new Period(null, null, "All time"); }

    /** Whole days, both ends included; either end may be open. */
    public static Period of(LocalDate fromDay, LocalDate toDay, String label) {
        if (fromDay == null && toDay == null) return allTime();
        if (fromDay != null && toDay != null && toDay.isBefore(fromDay))
            throw new IllegalArgumentException("The end date is before the start date.");
        LocalDateTime from = fromDay == null ? EARLIEST : fromDay.atStartOfDay();
        LocalDateTime to = toDay == null ? LATEST : toDay.plusDays(1).atStartOfDay();
        String text = label != null ? label
                : (fromDay == null ? "Up to " + DAY.format(toDay)
                : toDay == null ? "From " + DAY.format(fromDay)
                : DAY.format(fromDay) + " – " + DAY.format(toDay));
        return new Period(from, to, text);
    }

    public boolean bounded() { return from != null; }

    public boolean contains(LocalDateTime t) {
        if (!bounded()) return true;
        return t != null && !t.isBefore(from) && t.isBefore(to);
    }

    public boolean contains(LocalDate d) {
        return d != null && contains(d.atStartOfDay());
    }

    /** JPQL condition on a LocalDateTime field ("" when unbounded); parameters from {@link #params()}. */
    String where(String field) {
        return bounded() ? " AND " + field + " >= :periodFrom AND " + field + " < :periodTo" : "";
    }

    Map<String, Object> params() {
        return bounded() ? Map.of("periodFrom", from, "periodTo", to) : Map.of();
    }

    /** Same as {@link #where} for an Instant field. */
    String whereInstant(String field) {
        return bounded() ? " AND " + field + " >= :periodFrom AND " + field + " < :periodTo" : "";
    }

    Map<String, Object> instantParams() {
        if (!bounded()) return Map.of();
        ZoneId zone = ZoneId.systemDefault();
        return Map.of("periodFrom", from.atZone(zone).toInstant(), "periodTo", to.atZone(zone).toInstant());
    }
}
