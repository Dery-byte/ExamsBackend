package com.exam.service.reports;

import com.exam.model.comms.AuditLog;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.examops.RemarkRequest;
import com.exam.model.fees.FeePayment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The read-only queries behind the reports. They select plain columns, not entities: most entities
 * here load their links eagerly, and a report reads every student, result or mark in scope.
 */
@Repository
public class ReportQueries {

    @PersistenceContext
    private EntityManager em;

    // ── Rows ─────────────────────────────────────────────────────────────────

    public record StudentRow(Long id, String username, String firstname, String lastname, String email, String phone,
                             Long programId, Long departmentId, Integer level, Integer semester, boolean enabled) {
        public String name() {
            return (Objects.toString(firstname, "") + " " + Objects.toString(lastname, "")).trim();
        }
    }

    public record RegistrationRow(Long courseId, Long studentId, Long sessionId, String sessionName, Date registeredOn) {}

    public record ResultRow(Long studentId, Long quizId, Double percentage, LocalDateTime submittedAt,
                            boolean reviewed, String evaluationMethod) {}

    public record AttemptRow(Long studentId, Long quizId, AttemptStatus status, LocalDateTime startedAt,
                             LocalDateTime voidedAt, String voidedBy, String voidReason) {}

    public record EventRow(Long studentId, Long quizId, String type, LocalDateTime occurredAt) {}

    public record RemarkRow(Long id, Long quizId, RemarkRequest.Status status, LocalDateTime createdAt,
                            LocalDateTime respondedAt, BigDecimal scoreBefore, BigDecimal scoreAfter) {}

    public record JobRow(Long id, Long quizId, Long studentId, TheoryGradingJob.Status status, int tries,
                         String lastError, LocalDateTime createdAt) {}

    /** One graded course result on a marks sheet. */
    public record MarkRow(Long studentId, Long courseId, Integer creditUnits, BigDecimal score, String grade,
                          BigDecimal gradePoint, Long sheetId, String sheetStatus, Long programId, Long sessionId,
                          LocalDate sessionStart, String level, Integer semester) {}

    public record SheetRow(Long id, Long programId, String level, Integer semester, String status, Long sessionId,
                           String sessionName, Instant publishAt, String teacherFirstname, String teacherLastname) {
        public String teacher() {
            String n = (Objects.toString(teacherFirstname, "") + " " + Objects.toString(teacherLastname, "")).trim();
            return n.isEmpty() ? null : n;
        }
    }

    /** students = distinct students on the sheet; rows = course results; entered = results with a non-zero total. */
    public record SheetCounts(long students, long rows, long entered) {}

    public record PaymentRow(LocalDateTime createdAt, LocalDateTime paidAt, String reference, String studentUsername,
                             String studentFirstname, String studentLastname, FeePayment.Method method, BigDecimal amount,
                             FeePayment.Status status, String recordedBy, String note, String programName, Integer level) {}

    // ── People and registrations ─────────────────────────────────────────────

    public List<StudentRow> students() {
        return rows("SELECT u.id, u.username, u.firstname, u.lastname, u.email, u.phone, p.id, d.id, "
                + "u.currentLevel, u.currentSemester, u.enabled FROM User u LEFT JOIN u.program p LEFT JOIN u.department d "
                + "WHERE u.role = com.exam.model.Role.NORMAL", Map.of()).stream()
                .map(r -> new StudentRow((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                        (Long) r[6], (Long) r[7], (Integer) r[8], (Integer) r[9], Boolean.TRUE.equals(r[10])))
                .toList();
    }

    public List<RegistrationRow> registrations() {
        return rows("SELECT c.cid, u.id, s.id, s.name, r.RegDate FROM Registered_courses r JOIN r.category c JOIN r.user u "
                + "LEFT JOIN r.session s", Map.of()).stream()
                .map(r -> new RegistrationRow((Long) r[0], (Long) r[1], (Long) r[2], (String) r[3], (Date) r[4]))
                .toList();
    }

    // ── Quizzes ──────────────────────────────────────────────────────────────

    public List<ResultRow> quizResults(Period p) {
        return rows("SELECT u.id, q.qId, r.percentage, r.submissionDate, r.isReviewed, r.evaluationMethod "
                + "FROM report r JOIN r.user u JOIN r.quiz q WHERE 1 = 1" + p.where("r.submissionDate"), p.params()).stream()
                .map(r -> new ResultRow((Long) r[0], (Long) r[1], (Double) r[2], (LocalDateTime) r[3],
                        Boolean.TRUE.equals(r[4]), (String) r[5]))
                .toList();
    }

    public List<AttemptRow> attempts(Period p) {
        return rows("SELECT u.id, q.qId, a.status, a.startedAt, a.voidedAt, a.voidedByName, a.voidReason "
                + "FROM QuizAttempt a JOIN a.user u JOIN a.quiz q WHERE 1 = 1" + p.where("a.startedAt"), p.params()).stream()
                .map(r -> new AttemptRow((Long) r[0], (Long) r[1], (AttemptStatus) r[2], (LocalDateTime) r[3],
                        (LocalDateTime) r[4], (String) r[5], (String) r[6]))
                .toList();
    }

    public List<EventRow> proctoringEvents(Period p) {
        return rows("SELECT u.id, q.qId, e.type, e.occurredAt FROM ProctoringEvent e JOIN e.user u JOIN e.quiz q WHERE 1 = 1"
                + p.where("e.occurredAt"), p.params()).stream()
                .map(r -> new EventRow((Long) r[0], (Long) r[1], (String) r[2], (LocalDateTime) r[3]))
                .toList();
    }

    public List<RemarkRow> remarks(Period p) {
        return rows("SELECT r.id, q.qId, r.status, r.createdAt, r.respondedAt, r.scoreBefore, r.scoreAfter "
                + "FROM RemarkRequest r JOIN r.report rep JOIN rep.quiz q WHERE 1 = 1" + p.where("r.createdAt"), p.params()).stream()
                .map(r -> new RemarkRow((Long) r[0], (Long) r[1], (RemarkRequest.Status) r[2], (LocalDateTime) r[3],
                        (LocalDateTime) r[4], (BigDecimal) r[5], (BigDecimal) r[6]))
                .toList();
    }

    public List<JobRow> gradingJobs(Period p) {
        return rows("SELECT j.id, j.quizId, j.userId, j.status, j.tries, j.lastError, j.createdAt FROM TheoryGradingJob j WHERE 1 = 1"
                + p.where("j.createdAt"), p.params()).stream()
                .map(r -> new JobRow((Long) r[0], (Long) r[1], (Long) r[2], (TheoryGradingJob.Status) r[3],
                        r[4] == null ? 0 : ((Number) r[4]).intValue(), (String) r[5], (LocalDateTime) r[6]))
                .toList();
    }

    // ── One quiz ─────────────────────────────────────────────────────────────

    /** A student's result on a quiz (marks = section A, marksB = section B). */
    public record QuizScoreRow(Long studentId, Long reportId, BigDecimal marks, BigDecimal marksB, BigDecimal maxB,
                               Double percentage, String grade, LocalDateTime submittedAt, boolean reviewed) {}

    /** A student's chosen options for one objective question (their latest attempt). */
    public record ObjectiveAnswerRow(Long studentId, Long questionId, String[] selected) {}

    /** A student's mark for one theory question. */
    public record TheoryAnswerRow(Long studentId, String questionNo, double score, double maxMarks) {}

    public List<QuizScoreRow> quizScores(Long quizId) {
        return rows("SELECT u.id, r.id, r.marks, r.marksB, r.maxScoreSectionB, r.percentage, r.grade, r.submissionDate, r.isReviewed "
                + "FROM report r JOIN r.user u WHERE r.quiz.qId = :quizId", Map.of("quizId", quizId)).stream()
                .map(r -> new QuizScoreRow((Long) r[0], (Long) r[1], (BigDecimal) r[2], (BigDecimal) r[3], (BigDecimal) r[4],
                        (Double) r[5], (String) r[6], (LocalDateTime) r[7], Boolean.TRUE.equals(r[8])))
                .toList();
    }

    public List<AttemptRow> quizAttempts(Long quizId) {
        return rows("SELECT u.id, q.qId, a.status, a.startedAt, a.voidedAt, a.voidedByName, a.voidReason "
                + "FROM QuizAttempt a JOIN a.user u JOIN a.quiz q WHERE q.qId = :quizId", Map.of("quizId", quizId)).stream()
                .map(r -> new AttemptRow((Long) r[0], (Long) r[1], (AttemptStatus) r[2], (LocalDateTime) r[3],
                        (LocalDateTime) r[4], (String) r[5], (String) r[6]))
                .toList();
    }

    public List<EventRow> quizEvents(Long quizId) {
        return rows("SELECT u.id, q.qId, e.type, e.occurredAt FROM ProctoringEvent e JOIN e.user u JOIN e.quiz q WHERE q.qId = :quizId",
                Map.of("quizId", quizId)).stream()
                .map(r -> new EventRow((Long) r[0], (Long) r[1], (String) r[2], (LocalDateTime) r[3]))
                .toList();
    }

    public List<ObjectiveAnswerRow> objectiveAnswers(Long quizId) {
        return rows("SELECT u.id, q.quesId, sa.selectedOptions FROM StudentAnswer sa JOIN sa.user u JOIN sa.question q "
                + "WHERE q.quiz.qId = :quizId", Map.of("quizId", quizId)).stream()
                .map(r -> new ObjectiveAnswerRow((Long) r[0], (Long) r[1], (String[]) r[2]))
                .toList();
    }

    public List<TheoryAnswerRow> theoryAnswers(Long quizId) {
        return rows("SELECT u.id, a.quesNo, a.score, a.maxMarks FROM Answer a JOIN a.user u WHERE a.quiz.qId = :quizId",
                Map.of("quizId", quizId)).stream()
                .map(r -> new TheoryAnswerRow((Long) r[0], (String) r[1], ((Number) r[2]).doubleValue(), ((Number) r[3]).doubleValue()))
                .toList();
    }

    /** A re-mark request with what it was about and who answered it. */
    public record RemarkDetailRow(Long id, Long quizId, Long studentId, String reason, RemarkRequest.Status status,
                                  LocalDateTime createdAt, LocalDateTime respondedAt, BigDecimal scoreBefore, BigDecimal scoreAfter,
                                  String responderFirstname, String responderLastname, String response) {
        public String responder() {
            String n = (Objects.toString(responderFirstname, "") + " " + Objects.toString(responderLastname, "")).trim();
            return n.isEmpty() ? null : n;
        }
    }

    public List<RemarkDetailRow> remarkDetails(Period p) {
        return rows("SELECT r.id, q.qId, st.id, r.reason, r.status, r.createdAt, r.respondedAt, r.scoreBefore, r.scoreAfter, "
                + "rb.firstname, rb.lastname, r.response FROM RemarkRequest r JOIN r.report rep JOIN rep.quiz q JOIN r.student st "
                + "LEFT JOIN r.respondedBy rb WHERE 1 = 1" + p.where("r.createdAt"), p.params()).stream()
                .map(r -> new RemarkDetailRow((Long) r[0], (Long) r[1], (Long) r[2], (String) r[3], (RemarkRequest.Status) r[4],
                        (LocalDateTime) r[5], (LocalDateTime) r[6], (BigDecimal) r[7], (BigDecimal) r[8], (String) r[9],
                        (String) r[10], (String) r[11]))
                .toList();
    }

    // ── Marks sheets ─────────────────────────────────────────────────────────

    /** Graded results (grade set, not "N/A") on sheets with one of these statuses. */
    public List<MarkRow> gradedMarks(Collection<String> sheetStatuses) {
        return rows("SELECT st.id, c.cid, c.creditUnits, m.totalScore, m.grade, m.gradePoint, s.id, s.status, p.id, "
                + "sess.id, sess.startDate, s.level, s.semester FROM StudentCourseMark m JOIN m.student st JOIN m.course c "
                + "JOIN m.semesterSheet s LEFT JOIN s.program p LEFT JOIN s.session sess "
                + "WHERE s.status IN :statuses AND m.grade IS NOT NULL AND m.grade <> 'N/A'", Map.of("statuses", sheetStatuses)).stream()
                .map(r -> new MarkRow((Long) r[0], (Long) r[1], (Integer) r[2], (BigDecimal) r[3], (String) r[4],
                        (BigDecimal) r[5], (Long) r[6], (String) r[7], (Long) r[8], (Long) r[9], (LocalDate) r[10],
                        (String) r[11], (Integer) r[12]))
                .toList();
    }

    /** Every result row on these sheets, graded or not (grade may be null or "N/A"). */
    public List<MarkRow> marksOnSheets(Collection<Long> sheetIds) {
        if (sheetIds.isEmpty()) return List.of();
        return rows("SELECT st.id, c.cid, c.creditUnits, m.totalScore, m.grade, m.gradePoint, s.id, s.status, p.id, "
                + "sess.id, sess.startDate, s.level, s.semester FROM StudentCourseMark m JOIN m.student st JOIN m.course c "
                + "JOIN m.semesterSheet s LEFT JOIN s.program p LEFT JOIN s.session sess WHERE s.id IN :ids", Map.of("ids", sheetIds)).stream()
                .map(r -> new MarkRow((Long) r[0], (Long) r[1], (Integer) r[2], (BigDecimal) r[3], (String) r[4],
                        (BigDecimal) r[5], (Long) r[6], (String) r[7], (Long) r[8], (Long) r[9], (LocalDate) r[10],
                        (String) r[11], (Integer) r[12]))
                .toList();
    }

    public List<SheetRow> sheets() {
        return rows("SELECT s.id, p.id, s.level, s.semester, s.status, sess.id, sess.name, s.publishAt, t.firstname, t.lastname "
                + "FROM SemesterSheet s LEFT JOIN s.program p LEFT JOIN s.session sess LEFT JOIN s.classTeacher t", Map.of()).stream()
                .map(r -> new SheetRow((Long) r[0], (Long) r[1], (String) r[2], (Integer) r[3], (String) r[4], (Long) r[5],
                        (String) r[6], (Instant) r[7], (String) r[8], (String) r[9]))
                .toList();
    }

    /** sheet id → ids of the courses on it. */
    public Map<Long, Set<Long>> sheetCourses() {
        Map<Long, Set<Long>> out = new HashMap<>();
        for (Object[] r : rows("SELECT s.id, c.cid FROM SemesterSheet s JOIN s.courses c", Map.of()))
            out.computeIfAbsent((Long) r[0], k -> new HashSet<>()).add((Long) r[1]);
        return out;
    }

    public Map<Long, SheetCounts> sheetCounts() {
        Map<Long, SheetCounts> out = new HashMap<>();
        for (Object[] r : rows("SELECT s.id, COUNT(DISTINCT st.id), COUNT(m), "
                + "SUM(CASE WHEN m.totalScore > 0 THEN 1 ELSE 0 END) FROM StudentCourseMark m JOIN m.semesterSheet s "
                + "JOIN m.student st GROUP BY s.id", Map.of()))
            out.put((Long) r[0], new SheetCounts(num(r[1]), num(r[2]), num(r[3])));
        return out;
    }

    // ── Audit log ────────────────────────────────────────────────────────────

    /** entity id → when one of these actions last happened to it (e.g. a marks sheet's id). */
    public Map<String, LocalDateTime> lastActionOnEntity(Collection<String> actions) {
        Map<String, LocalDateTime> out = new HashMap<>();
        for (Object[] r : rows("SELECT a.entityId, MAX(a.createdAt) FROM AuditLog a WHERE a.action IN :actions "
                + "AND a.entityId IS NOT NULL GROUP BY a.entityId", Map.of("actions", actions)))
            out.put((String) r[0], (LocalDateTime) r[1]);
        return out;
    }

    /** actor id → [last recorded action time, number of recorded actions] for people with this role. */
    public Map<Long, Object[]> activityByActor(String role) {
        Map<Long, Object[]> out = new HashMap<>();
        for (Object[] r : rows("SELECT a.actorId, MAX(a.createdAt), COUNT(a) FROM AuditLog a WHERE a.actorRole = :role "
                + "AND a.actorId IS NOT NULL GROUP BY a.actorId", Map.of("role", role)))
            out.put((Long) r[0], new Object[]{r[1], num(r[2])});
        return out;
    }

    /** Entries with one of these actions, newest first, leaving out what {@code hiddenRole} did. */
    public List<AuditLog> auditEntries(Collection<String> actions, String hiddenRole, Period p, int limit) {
        TypedQuery<AuditLog> q = em.createQuery("SELECT a FROM AuditLog a WHERE a.action IN :actions "
                + "AND (a.actorRole IS NULL OR a.actorRole <> :hidden)" + p.where("a.createdAt")
                + " ORDER BY a.createdAt DESC", AuditLog.class);
        q.setParameter("actions", actions);
        q.setParameter("hidden", hiddenRole);
        p.params().forEach(q::setParameter);
        return q.setMaxResults(limit).getResultList();
    }

    /** [action, count, last time] for these actions in the period. */
    public List<Object[]> auditCountsByAction(Collection<String> actions, String hiddenRole, Period p) {
        Map<String, Object> params = new HashMap<>(p.params());
        params.put("actions", actions);
        params.put("hidden", hiddenRole);
        return rows("SELECT a.action, COUNT(a), MAX(a.createdAt) FROM AuditLog a WHERE a.action IN :actions "
                + "AND (a.actorRole IS NULL OR a.actorRole <> :hidden)" + p.where("a.createdAt") + " GROUP BY a.action", params);
    }

    /** [actor name, role, count, last time] for these actions in the period. */
    public List<Object[]> auditCountsByActor(Collection<String> actions, String hiddenRole, Period p) {
        Map<String, Object> params = new HashMap<>(p.params());
        params.put("actions", actions);
        params.put("hidden", hiddenRole);
        return rows("SELECT a.actorName, a.actorRole, COUNT(a), MAX(a.createdAt) FROM AuditLog a WHERE a.action IN :actions "
                + "AND (a.actorRole IS NULL OR a.actorRole <> :hidden)" + p.where("a.createdAt")
                + " GROUP BY a.actorName, a.actorRole", params);
    }

    // ── Documents ────────────────────────────────────────────────────────────

    public List<com.exam.model.academic.DocumentVerification> documents(Period p) {
        TypedQuery<com.exam.model.academic.DocumentVerification> q = em.createQuery(
                "SELECT d FROM DocumentVerification d WHERE 1 = 1" + p.whereInstant("d.issuedAt") + " ORDER BY d.issuedAt DESC",
                com.exam.model.academic.DocumentVerification.class);
        p.instantParams().forEach(q::setParameter);
        return q.getResultList();
    }

    // ── Fees ─────────────────────────────────────────────────────────────────

    /** (student id, schedule id) → total successfully paid, for one session. */
    public Map<List<Long>, BigDecimal> paidByStudentAndSchedule(Long sessionId) {
        Map<List<Long>, BigDecimal> out = new HashMap<>();
        for (Object[] r : rows("SELECT st.id, sc.id, SUM(p.amount) FROM FeePayment p JOIN p.student st JOIN p.schedule sc "
                + "WHERE p.session.id = :sessionId AND p.status = :status GROUP BY st.id, sc.id",
                Map.of("sessionId", sessionId, "status", FeePayment.Status.SUCCESS)))
            out.put(List.of((Long) r[0], (Long) r[1]), (BigDecimal) r[2]);
        return out;
    }

    /** [method, channel, count, total] of successful payments in a session. */
    public List<Object[]> paymentsByMethod(Long sessionId) {
        return rows("SELECT p.method, p.channel, COUNT(p), SUM(p.amount) FROM FeePayment p "
                + "WHERE p.session.id = :sessionId AND p.status = :status GROUP BY p.method, p.channel",
                Map.of("sessionId", sessionId, "status", FeePayment.Status.SUCCESS));
    }

    /** Payments entered by staff (cash, bank transfer, other) and every voided payment, newest first. */
    public List<PaymentRow> manualAndVoidedPayments(Long sessionId) {
        return rows("SELECT p.createdAt, p.paidAt, p.reference, st.username, st.firstname, st.lastname, p.method, p.amount, "
                + "p.status, p.recordedBy, p.note, p.programName, p.level FROM FeePayment p JOIN p.student st "
                + "WHERE p.session.id = :sessionId AND (p.method <> :online OR p.status = :voided) ORDER BY p.createdAt DESC",
                Map.of("sessionId", sessionId, "online", FeePayment.Method.PAYSTACK, "voided", FeePayment.Status.VOIDED)).stream()
                .map(r -> new PaymentRow((LocalDateTime) r[0], (LocalDateTime) r[1], (String) r[2], (String) r[3], (String) r[4],
                        (String) r[5], (FeePayment.Method) r[6], (BigDecimal) r[7], (FeePayment.Status) r[8], (String) r[9],
                        (String) r[10], (String) r[11], (Integer) r[12]))
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Object[]> rows(String jpql, Map<String, ?> params) {
        TypedQuery<Object[]> q = em.createQuery(jpql, Object[].class);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
