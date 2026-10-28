package com.exam.model;

/** A student's gender, recorded by staff; boarding uses it to keep boys' and girls' hostels apart. */
public enum Gender {
    MALE, FEMALE;

    public static Gender parse(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().toUpperCase();
        return switch (v) {
            case "MALE", "M", "BOY" -> MALE;
            case "FEMALE", "F", "GIRL" -> FEMALE;
            default -> throw new IllegalArgumentException("Gender must be Male or Female.");
        };
    }
}
