package com.exam.model.exam;

/** How much of the quiz's total duration a student gets before an auto-close quiz shuts itself. */
public enum AutoCloseFraction {
    HALF(2, "Half"),
    QUARTER(4, "A quarter");

    private final int divisor;
    private final String label;

    AutoCloseFraction(int divisor, String label) {
        this.divisor = divisor;
        this.label = label;
    }

    /** Minutes-until-close = totalDurationMinutes / divisor. */
    public int getDivisor() {
        return divisor;
    }

    public String getLabel() {
        return label;
    }
}
