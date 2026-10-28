package com.exam.model;

/** Whether a student goes home every day or lives in a school hostel. Only stored while the institution has both. */
public enum Residency {
    DAY, BOARDING;

    public static Residency parse(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().toUpperCase().replace(' ', '_');
        return switch (v) {
            case "DAY", "DAY_STUDENT" -> DAY;
            case "BOARDING", "BOARDER", "BOARDING_STUDENT" -> BOARDING;
            default -> throw new IllegalArgumentException("Residency must be Day or Boarding.");
        };
    }
}
