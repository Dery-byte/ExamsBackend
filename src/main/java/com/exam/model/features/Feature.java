package com.exam.model.features;

/**
 * Features the Super Admin can switch on or off.
 * <p>
 * SYSTEM features apply everywhere and only the Super Admin controls them.
 * DEPARTMENT features have a system-wide switch (Super Admin) and, while that is on, each
 * department can be switched off or back on by its HOD (or by the Super Admin).
 * Every feature starts ON so existing behaviour is unchanged until someone switches it.
 */
public enum Feature {

    STUDENT_SELF_SIGNUP(Scope.SYSTEM, "Students", "Student self sign-up",
            "Students can create their own accounts from the sign-up page. When off, only staff can add students."),
    HOD_ANALYTICS(Scope.SYSTEM, "HODs", "Department analytics for HODs",
            "HODs can open the Analytics dashboard for their department."),
    HOD_DATA_TOOLS(Scope.SYSTEM, "HODs", "Data tools for HODs",
            "HODs can bulk-import students, lecturers and courses, bulk-enrol students and export results."),
    HOD_PROMOTION(Scope.SYSTEM, "HODs", "HODs can promote students",
            "HODs can move students in their department to the next level or semester."),
    FORCE_PASSWORD_CHANGE(Scope.SYSTEM, "Everyone", "Force a password change on first sign-in",
            "Accounts whose password was set by staff (imports, new lecturers and HODs, password resets by an admin) must choose their own password before using the system."),
    DOCUMENT_VERIFICATION(Scope.SYSTEM, "Everyone", "Verification codes on transcripts and report cards",
            "Each downloaded transcript or report card gets a unique code that anyone can check on the public verification page."),
    HOD_ANNOUNCEMENTS(Scope.SYSTEM, "HODs", "HODs can post announcements",
            "HODs can post announcements to their department."),

    STUDENT_COURSE_REGISTRATION(Scope.DEPARTMENT, "Students", "Students register for their own courses",
            "Students pick their courses on the Register Course page. When off, staff enrol them."),
    REMARK_REQUESTS(Scope.DEPARTMENT, "Students", "Re-mark requests",
            "Students can ask for a reviewed script to be re-marked."),
    STUDENT_TIMETABLE(Scope.DEPARTMENT, "Students", "Exam timetable for students",
            "Students see the Exam Timetable page with their upcoming assessments."),
    STUDENT_TRANSCRIPT(Scope.DEPARTMENT, "Students", "Student transcripts and CGPA",
            "Students can view and download their transcript and CGPA. (Also needs the Marks Sheet switch for students.)"),
    STUDENT_REPORT_CARD(Scope.DEPARTMENT, "Students", "Student report cards",
            "Students can view and download their report cards (published results). (Also needs the Marks Sheet switch for students.)"),
    QUESTION_BANK(Scope.DEPARTMENT, "Staff", "Question bank",
            "Lecturers and HODs keep reusable questions per course and draw them into quizzes.");

    public enum Scope { SYSTEM, DEPARTMENT }

    private final Scope scope;
    private final String audience;
    private final String label;
    private final String description;

    Feature(Scope scope, String audience, String label, String description) {
        this.scope = scope;
        this.audience = audience;
        this.label = label;
        this.description = description;
    }

    public Scope scope() { return scope; }
    public String audience() { return audience; }
    public String label() { return label; }
    public String description() { return description; }

    /** SystemSetting key holding the system-wide switch. */
    public String settingKey() { return "FEATURE_" + name(); }
}
