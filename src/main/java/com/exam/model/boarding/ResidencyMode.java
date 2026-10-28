package com.exam.model.boarding;

/**
 * How the institution houses its students, chosen by the developer:
 * DAY (no boarding at all, the module is hidden), BOARDING (every student is a boarder) or
 * BOTH (each student is a day student or a boarder).
 */
public enum ResidencyMode {
    DAY("Day only"),
    BOARDING("Boarding only"),
    BOTH("Day and boarding");

    private final String label;

    ResidencyMode(String label) { this.label = label; }

    public String label() { return label; }

    /** Whether hostels, rooms and allocations are in use. */
    public boolean hasBoarding() { return this != DAY; }

    public static ResidencyMode parse(String s) {
        if (s == null) return null;
        try { return ResidencyMode.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
