package com.exam.model.academic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What kind of institution the whole system runs as. Set by the developer; it changes the wording
 * (course → subject, lecturer → teacher, …), the calendar (2 semesters vs 3 terms), the level numbers
 * (100, 200 … vs 1, 2 …) and which features are shown.
 */
public enum SystemMode {

    UNIVERSITY("University / Tertiary"),
    SHS("Senior High School"),
    BASIC("Primary / JHS (Basic school)"),
    ALL_SCHOOLS("SHS, JHS and Primary");

    private final String label;

    SystemMode(String label) { this.label = label; }

    public String label() { return label; }

    public boolean isSchool() { return this != UNIVERSITY; }

    /** Semesters (university) or terms (schools) in an academic year, unless a programme says otherwise. */
    public int defaultPeriodsPerLevel() { return isSchool() ? 3 : 2; }

    /** Level numbers step by 100 at university (100, 200 …) and by 1 in schools (Form 1, Class 1 …). */
    public int levelStep() { return isSchool() ? 1 : 100; }

    /** The words each mode uses; the keys are the university words. */
    public Map<String, String> terms() {
        Map<String, String> t = new LinkedHashMap<>();
        boolean school = isSchool();
        t.put("level", switch (this) { case SHS -> "Form"; case BASIC, ALL_SCHOOLS -> "Class"; default -> "Level"; });
        t.put("semester", school ? "Term" : "Semester");
        t.put("course", school ? "Subject" : "Course");
        t.put("courses", school ? "Subjects" : "Courses");
        t.put("program", this == BASIC ? "Section" : "Programme");
        t.put("lecturer", school ? "Teacher" : "Lecturer");
        t.put("hod", switch (this) { case SHS -> "Headmaster"; case BASIC, ALL_SCHOOLS -> "Head Teacher"; default -> "HOD"; });
        t.put("student", this == BASIC ? "Pupil" : "Student");
        t.put("studentId", school ? "Admission No." : "Student ID");
        t.put("reportCard", school ? "Terminal Report" : "Semester Report Card");
        return t;
    }

    // ── The current mode, readable from entities that can't inject services (e.g. Program) ──

    private static volatile SystemMode current = UNIVERSITY;

    public static SystemMode current() { return current; }

    public static void setCurrent(SystemMode mode) { current = mode == null ? UNIVERSITY : mode; }

    public static SystemMode parse(String s) {
        if (s == null) return null;
        try { return SystemMode.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
