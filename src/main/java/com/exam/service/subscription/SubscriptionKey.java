package com.exam.service.subscription;

import java.time.LocalDate;

/**
 * What a signed subscription key says: who it is for and until when the institution has paid.
 * After {@code paidUntil} come {@code graceDays} of full use, then {@code restrictedDays} read-only,
 * then the portal is suspended. A key can also suspend the portal outright ({@code suspended}), which
 * is how the vendor portal suspends a client remotely; a later key lifts it.
 */
public record SubscriptionKey(
        String id,
        String institutionId,
        String institutionName,
        String plan,
        LocalDate issued,
        LocalDate paidUntil,
        int graceDays,
        int restrictedDays,
        /** When it was issued, in epoch milliseconds (0 on keys made before sequencing): orders same-day keys. */
        long seq,
        boolean suspended,
        /** The vendor's reason when {@code suspended}; null otherwise. */
        String note
) {

    /** Whether this key was issued after the other one. */
    public boolean isNewerThan(SubscriptionKey other) {
        if (other == null) return true;
        if (seq > 0 && other.seq() > 0) return seq > other.seq();
        if (!issued.equals(other.issued())) return issued.isAfter(other.issued());
        return seq > other.seq();
    }
}
