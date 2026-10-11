package com.exam.service.subscription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The subscription worked out for one day. All dates are inclusive: the portal is ACTIVE up to and
 * including {@code paidUntil}, in GRACE up to {@code graceEnd}, RESTRICTED up to {@code restrictedEnd},
 * and SUSPENDED after that (or at once when the developer suspends it).
 */
public record SubscriptionStatus(
        SubscriptionState state,
        Source source,
        /** Why the installed key is not being used (null when it is, or when none is installed). */
        String keyProblem,
        SubscriptionKey key,
        LocalDate today,
        LocalDate paidUntil,
        LocalDate graceEnd,
        LocalDate restrictedEnd,
        /** The developer's extension, as far as it counts (capped); null when none applies. */
        LocalDate extendedUntil,
        /** Set while the developer has suspended the portal by hand. */
        String suspendedReason,
        LocalDateTime suspendedAt
) {

    /** Where the dates come from. */
    public enum Source { KEY, NO_KEY, INVALID_KEY, WRONG_INSTITUTION }

    /** Without a valid key, a deployment runs this many days (from first start) before restrictions begin. */
    public static final int UNLICENSED_GRACE_DAYS = 30;
    public static final int UNLICENSED_RESTRICTED_DAYS = 7;
    /** A developer's extension can push the end of grace back by at most this many days. */
    public static final int MAX_EXTENSION_DAYS = 30;

    public static SubscriptionStatus evaluate(SubscriptionKey key, Source source, String keyProblem, LocalDate firstSeen,
                                              LocalDate today, LocalDate extension, String suspendedReason,
                                              LocalDateTime suspendedAt) {
        LocalDate paid;
        int grace, restricted;
        if (key != null) {
            paid = key.paidUntil();
            grace = key.graceDays();
            restricted = key.restrictedDays();
        } else {
            paid = firstSeen.minusDays(1);
            grace = UNLICENSED_GRACE_DAYS;
            restricted = UNLICENSED_RESTRICTED_DAYS;
        }
        LocalDate graceEnd = paid.plusDays(grace);
        LocalDate extendedUntil = null;
        if (extension != null && extension.isAfter(graceEnd)) {
            LocalDate cap = graceEnd.plusDays(MAX_EXTENSION_DAYS);
            extendedUntil = extension.isAfter(cap) ? cap : extension;
            graceEnd = extendedUntil;
        }
        LocalDate restrictedEnd = graceEnd.plusDays(restricted);
        boolean manual = suspendedReason != null && !suspendedReason.isBlank();
        boolean byProvider = key != null && key.suspended();

        SubscriptionState state = manual || byProvider ? SubscriptionState.SUSPENDED : stateOn(today, paid, graceEnd, restrictedEnd);
        return new SubscriptionStatus(state, source, keyProblem, key, today, paid, graceEnd, restrictedEnd,
                extendedUntil, manual ? suspendedReason : null, manual ? suspendedAt : null);
    }

    private static SubscriptionState stateOn(LocalDate d, LocalDate paid, LocalDate graceEnd, LocalDate restrictedEnd) {
        if (!d.isAfter(paid)) return SubscriptionState.ACTIVE;
        if (!d.isAfter(graceEnd)) return SubscriptionState.GRACE;
        if (!d.isAfter(restrictedEnd)) return SubscriptionState.RESTRICTED;
        return SubscriptionState.SUSPENDED;
    }

    /** Suspended by the developer on this server. */
    public boolean manuallySuspended() { return suspendedReason != null; }

    /** Suspended by the vendor portal, through a signed key. */
    public boolean suspendedByProvider() { return key != null && key.suspended(); }

    /** The first day of the next, stricter state; null once suspended. */
    public LocalDate nextChange() {
        if (manuallySuspended() || suspendedByProvider()) return null;
        return switch (state) {
            case ACTIVE -> paidUntil.plusDays(1);
            case GRACE -> graceEnd.plusDays(1);
            case RESTRICTED -> restrictedEnd.plusDays(1);
            case SUSPENDED -> null;
        };
    }

    public SubscriptionState nextState() {
        LocalDate d = nextChange();
        return d == null ? null : stateOn(d, paidUntil, graceEnd, restrictedEnd);
    }

    /** Whole days until {@link #nextChange()}; null once suspended. */
    public Long daysLeft() {
        LocalDate d = nextChange();
        return d == null ? null : ChronoUnit.DAYS.between(today, d);
    }

    /** True when access is limited (read-only or suspended). */
    public boolean limited() {
        return state == SubscriptionState.RESTRICTED || state == SubscriptionState.SUSPENDED;
    }
}
