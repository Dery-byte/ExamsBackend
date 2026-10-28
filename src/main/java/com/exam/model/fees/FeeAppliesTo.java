package com.exam.model.fees;

/**
 * Who an item of an itemised fee is charged to: everyone, boarders only (boarding fee, feeding …)
 * or day students only. A student whose residency isn't recorded pays only the EVERYONE items.
 */
public enum FeeAppliesTo {
    EVERYONE, BOARDERS, DAY;

    public static FeeAppliesTo parse(String s) {
        if (s == null || s.isBlank()) return EVERYONE;
        try { return FeeAppliesTo.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose who an item is for: everyone, boarders or day students."); }
    }
}
