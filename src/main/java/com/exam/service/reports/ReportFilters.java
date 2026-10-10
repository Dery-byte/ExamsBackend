package com.exam.service.reports;

import java.time.LocalDate;

/**
 * Filters a report can take. Each report uses the ones it lists in {@link ReportCatalog}; the rest
 * are ignored. Null means "all".
 *
 * @param courseId   one course: a filter on some reports, a drill-down (clicking a row) on others
 * @param quizId     one quiz: likewise
 * @param status     a report-specific choice, e.g. a marks sheet status or a student's standing
 * @param lecturerId set by the server for a lecturer: only courses they teach and quizzes they manage
 */
public record ReportFilters(Long sessionId, Long departmentId, Long programId, Integer level, Integer semester,
                            LocalDate from, LocalDate to, Long courseId, Long quizId, String status, Long lecturerId) {

    public ReportFilters(Long sessionId, Long departmentId, Long programId, Integer level, Integer semester,
                         LocalDate from, LocalDate to, Long courseId, Long quizId, String status) {
        this(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status, null);
    }

    public static ReportFilters none() {
        return new ReportFilters(null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean hasStatus() { return status != null && !status.isBlank(); }

    /** The same filters locked to one department (an HOD's reports). */
    public ReportFilters withDepartment(Long id) {
        return new ReportFilters(sessionId, id, programId, level, semester, from, to, courseId, quizId, status, lecturerId);
    }

    /** The same filters locked to one lecturer's courses and quizzes (null: no lock). */
    public ReportFilters withLecturer(Long id) {
        return new ReportFilters(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status, id);
    }
}
