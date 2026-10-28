package com.exam.model.boarding;

import com.exam.model.Gender;

/** Who a hostel, block or room is for. */
public enum HostelGender {
    BOYS, GIRLS, MIXED;

    /** Whether a student of this gender may live here; a student with no gender recorded fits only mixed spaces. */
    public boolean admits(Gender g) {
        return switch (this) {
            case MIXED -> true;
            case BOYS -> g == Gender.MALE;
            case GIRLS -> g == Gender.FEMALE;
        };
    }

    public String label() {
        return switch (this) { case BOYS -> "boys"; case GIRLS -> "girls"; case MIXED -> "boys and girls"; };
    }

    public static HostelGender parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return HostelGender.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose boys, girls or mixed."); }
    }
}
