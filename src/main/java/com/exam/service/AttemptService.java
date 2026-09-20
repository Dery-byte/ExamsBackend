package com.exam.service;

import com.exam.DTO.AttemptDTO;
import com.exam.DTO.AttemptStatusDTO;
import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.QuizAttempt;
import com.exam.model.exam.Report;
import com.exam.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Owns the attempt lifecycle of a quiz.
 *
 * Model:
 *  - {@link QuizAttempt} is a ledger: one row per attempt, each with its own marks. Nothing is overwritten.
 *  - {@link Report} stays the single official result per (student, quiz) and mirrors the latest
 *    non-voided attempt, so all existing consumers (marks sheets, PDFs, review pages) are unchanged.
 *  - "Allow retake" voids the student's latest attempt (kept for audit, no longer counted) which frees
 *    a slot; the next submission then replaces the official marks and answers.
 *
 * Every state change first takes a row lock on the student, so double submits, two tabs and racing
 * requests are serialised and the attempt limit cannot be exceeded.
 */
@Service
public class AttemptService {

    public static final int MAX_ATTEMPTS_LIMIT = 10;

    @Autowired private QuizAttemptRepository        attempts;
    @Autowired private UserRepository               users;
    @Autowired private QuizRepository               quizzes;
    @Autowired private ReportRepository             reports;
    @Autowired private QuizTimerRepository          timers;
    @Autowired private UserQuizProgressRepository   quizProgress;
    @Autowired private TheoryProgressRepository     theoryProgress;

    // ── Student: status / begin / finish ─────────────────────────────────────

    @Transactional(readOnly = true)
    public AttemptStatusDTO myStatus(User student, Quiz quiz) {
        List<QuizAttempt> all = load(student.getId(), quiz.getqId());
        int legacyUsed = all.isEmpty() && hasReport(student.getId(), quiz.getqId()) ? 1 : 0;
        return toStatus(quiz, all, legacyUsed, isReviewed(student.getId(), quiz.getqId()));
    }

    /** Starts a new attempt or resumes the one in progress. Idempotent. Throws 409 when no attempt is available. */
    @Transactional
    public AttemptStatusDTO begin(User student, Quiz quiz) {
        User s = lock(student);
        List<QuizAttempt> all = load(s.getId(), quiz.getqId());
        materializeLegacy(s, quiz, all);
        startOrResume(s, quiz, all);
        return toStatus(quiz, all, 0, isReviewed(s.getId(), quiz.getqId()));
    }

    /** For the student question endpoints: begin/resume when the caller is a student; staff previews are untouched. */
    @Transactional
    public void beginIfStudent(java.security.Principal principal, Long quizId) {
        if (principal == null) return;
        User caller = users.findByUsername(principal.getName()).orElse(null);
        if (caller == null || caller.getRole() != Role.NORMAL) return;
        Quiz quiz = quizzes.findById(quizId).orElseThrow(() -> notFound("Quiz not found"));
        begin(caller, quiz);
    }

    /** Marks the attempt in progress as finished. No-op if none is in progress. */
    @Transactional
    public void finish(User student, Quiz quiz) {
        User s = lock(student);
        List<QuizAttempt> all = load(s.getId(), quiz.getqId());
        active(all).ifPresent(a -> {
            a.setStatus(AttemptStatus.SUBMITTED);
            a.setSubmittedAt(LocalDateTime.now());
            attempts.save(a);
        });
    }

    // ── Recording submissions (called from the marking pipelines) ────────────

    /** Call before writing the objective result. Throws 409 (rolling back the caller) if this submission is not allowed. */
    @Transactional
    public void recordObjective(User student, Quiz quiz, BigDecimal marks) {
        record(student, quiz, true, marks);
    }

    /** Call before writing the theory result. Throws 409 (rolling back the caller) if this submission is not allowed. */
    @Transactional
    public void recordTheory(User student, Quiz quiz, BigDecimal marks) {
        record(student, quiz, false, marks);
    }

    /**
     * Cheap, lock-free pre-check used before an expensive AI evaluation, so a submission that is
     * going to be rejected does not first burn an LLM call. The authoritative check is recordTheory.
     */
    @Transactional(readOnly = true)
    public void assertTheoryMaySubmit(User student, Quiz quiz) {
        List<QuizAttempt> all = load(student.getId(), quiz.getqId());
        QuizAttempt a = active(all).orElse(null);
        if (a != null) {
            if (a.isTheorySubmitted()) throw conflict("The theory section of this attempt was already submitted.");
        } else if (!all.isEmpty()) {
            throw conflict("No attempt is in progress. Start a new attempt first.");
        } else if (hasReport(student.getId(), quiz.getqId()) && limit(quiz) <= 1) {
            throw conflict("You have used all attempts allowed for this quiz.");
        }
    }

    // ── Keep the ledger in step with lecturer edits to the official Report ───

    /** After a lecturer edits the official Report, reflect the marks on the attempt it represents. */
    @Transactional
    public void syncOfficialMarks(Report report) {
        if (report == null || report.getUser() == null || report.getQuiz() == null) return;
        List<QuizAttempt> all = load(report.getUser().getId(), report.getQuiz().getqId());
        latestCounted(all).ifPresent(a -> {
            a.setMarksA(report.getMarks());
            a.setMarksB(report.getMarksB());
            attempts.save(a);
        });
    }

    // ── Staff: view attempts / allow retake ──────────────────────────────────

    @Transactional(readOnly = true)
    public List<AttemptDTO> attemptsForQuiz(Long quizId, User actor) {
        Quiz quiz = quizzes.findById(quizId).orElseThrow(() -> notFound("Quiz not found"));
        assertStaffMayManage(actor, quiz);
        return attempts.findAllForQuiz(quizId).stream().map(this::toDto).toList();
    }

    /**
     * Lets one student take the quiz again. Voids their latest attempt (kept for audit) and clears any
     * half-finished session state. The next submission replaces the official marks.
     */
    @Transactional
    public AttemptStatusDTO grantRetake(Long quizId, Long studentId, User actor, String reason) {
        Quiz quiz = quizzes.findById(quizId).orElseThrow(() -> notFound("Quiz not found"));
        assertStaffMayManage(actor, quiz);

        User student = users.lockById(studentId).orElseThrow(() -> notFound("Student not found"));
        if (student.getRole() != Role.NORMAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retakes can only be granted to students");
        }

        // Once the result has been reviewed it is final: a retake would silently replace reviewed marks.
        if (isReviewed(studentId, quizId)) {
            throw conflict("This result has already been marked as reviewed, so a retake can no longer be allowed.");
        }

        List<QuizAttempt> all = load(studentId, quizId);
        materializeLegacy(student, quiz, all);
        QuizAttempt latest = latestCounted(all)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "This student has no attempt to retake"));

        int remaining = limit(quiz) - (int) counted(all);
        if (latest.getStatus() == AttemptStatus.SUBMITTED && remaining > 0) {
            throw conflict("This student still has " + remaining + " attempt(s) left, so no retake is needed.");
        }

        latest.setStatus(AttemptStatus.VOIDED);
        latest.setVoidedAt(LocalDateTime.now());
        latest.setVoidedById(actor.getId());
        latest.setVoidedByName(actor.getFullName());
        latest.setVoidReason(reason == null || reason.isBlank() ? null : reason.trim().substring(0, Math.min(reason.trim().length(), 500)));
        attempts.save(latest);

        resetSessionState(student, quiz);
        return toStatus(quiz, all, 0, false);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void record(User student, Quiz quiz, boolean objective, BigDecimal marks) {
        User s = lock(student);
        List<QuizAttempt> all = load(s.getId(), quiz.getqId());
        materializeLegacy(s, quiz, all);

        QuizAttempt a = active(all).orElse(null);
        if (a == null) {
            // A genuine new attempt always goes through begin(). Auto-starting is only for the very
            // first attempt (e.g. a session that began before this feature existed). Otherwise a
            // replayed submit could silently burn the next attempt.
            if (!all.isEmpty()) throw conflict("No attempt is in progress. Start a new attempt first.");
            a = startOrResume(s, quiz, all);
        }

        if (objective) {
            if (a.isObjectiveSubmitted()) throw conflict("The objective section of this attempt was already submitted.");
            a.setMarksA(scale(marks));
            a.setObjectiveSubmitted(true);
        } else {
            if (a.isTheorySubmitted()) throw conflict("The theory section of this attempt was already submitted.");
            a.setMarksB(scale(marks));
            a.setTheorySubmitted(true);
        }
        if (isComplete(quiz, a)) {
            a.setStatus(AttemptStatus.SUBMITTED);
            a.setSubmittedAt(LocalDateTime.now());
        }
        attempts.save(a);
    }

    /** Returns the attempt in progress, or starts a new one if the student still has attempts left. */
    private QuizAttempt startOrResume(User s, Quiz quiz, List<QuizAttempt> all) {
        QuizAttempt current = active(all).orElse(null);
        if (current != null) return current;

        // A reviewed result is final. Checked first because it is the more useful reason to show.
        if (isReviewed(s.getId(), quiz.getqId())) {
            throw conflict("Your result for this quiz has already been reviewed, so no further attempts are allowed.");
        }

        int lim = limit(quiz);
        if (counted(all) >= lim) {
            throw conflict("You have used all " + lim + " attempt" + (lim == 1 ? "" : "s") + " allowed for this quiz.");
        }
        if (quiz.getStatus() == QuizStatus.CLOSED) {
            throw conflict("This quiz is closed.");
        }

        boolean hadEarlierAttempts = !all.isEmpty();
        QuizAttempt a = new QuizAttempt();
        a.setUser(s);
        a.setQuiz(quiz);
        a.setAttemptNumber(all.stream().mapToInt(QuizAttempt::getAttemptNumber).max().orElse(0) + 1);
        a.setStatus(AttemptStatus.IN_PROGRESS);
        a.setStartedAt(LocalDateTime.now());
        a = attempts.save(a);
        all.add(a);

        // A fresh attempt must not inherit the previous attempt's timer, saved answers or violations.
        // (Skipped for the very first attempt so an in-flight pre-existing session is not wiped.)
        if (hadEarlierAttempts) resetSessionState(s, quiz);
        return a;
    }

    /** Attempts made before this feature existed have a Report but no ledger row; create it on first touch. */
    private void materializeLegacy(User s, Quiz quiz, List<QuizAttempt> all) {
        if (!all.isEmpty()) return;
        reports.findByUser_IdAndQuiz_qId(s.getId(), quiz.getqId()).stream().findFirst().ifPresent(r -> {
            LocalDateTime when = r.getSubmissionDate() != null ? r.getSubmissionDate() : LocalDateTime.now();
            QuizAttempt a = new QuizAttempt();
            a.setUser(s);
            a.setQuiz(quiz);
            a.setAttemptNumber(1);
            a.setStatus(AttemptStatus.SUBMITTED);
            a.setStartedAt(when);
            a.setSubmittedAt(when);
            a.setMarksA(r.getMarks());
            a.setMarksB(r.getMarksB());
            QuizType t = quiz.getQuizType();
            a.setObjectiveSubmitted(t != QuizType.THEORY);
            a.setTheorySubmitted(t == QuizType.THEORY || t == QuizType.BOTH);
            all.add(attempts.save(a));
        });
    }

    private void resetSessionState(User student, Quiz quiz) {
        timers.deleteByUserIdAndQuiz_qId(student.getId(), quiz.getqId());
        quizProgress.deleteByUserIdAndQuizId(student.getId(), quiz.getqId());
        theoryProgress.deleteByUserAndQuiz(student, quiz);
    }

    private void assertStaffMayManage(User actor, Quiz quiz) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        boolean ok = switch (actor.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> {
                Long dept = actor.getDepartment() == null ? null : actor.getDepartment().getId();
                Category c = quiz.getCategory();
                yield dept != null && Stream.concat(
                                quiz.getPrograms() == null ? Stream.<Program>empty() : quiz.getPrograms().stream(),
                                c == null || c.getPrograms() == null ? Stream.<Program>empty() : c.getPrograms().stream())
                        .anyMatch(p -> p.getDepartment() != null && dept.equals(p.getDepartment().getId()));
            }
            case LECTURER -> {
                Long id = actor.getId();
                yield (quiz.getUser() != null && Objects.equals(quiz.getUser().getId(), id))
                        || (quiz.getCategory() != null && quiz.getCategory().getUser() != null
                            && Objects.equals(quiz.getCategory().getUser().getId(), id));
            }
            default -> false;
        };
        if (!ok) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to manage attempts for this quiz");
    }

    private AttemptStatusDTO toStatus(Quiz quiz, List<QuizAttempt> all, int legacyUsed, boolean reviewed) {
        int lim = limit(quiz);
        int used = (int) counted(all) + legacyUsed;
        int remaining = Math.max(0, lim - used);
        Integer activeNo = active(all).map(QuizAttempt::getAttemptNumber).orElse(null);
        boolean closed = quiz.getStatus() == QuizStatus.CLOSED;
        // A reviewed result is final: no new attempt (an attempt already in progress can still be resumed).
        boolean canStart = activeNo != null || (remaining > 0 && !closed && !reviewed);
        // A retake is "waiting" when staff voided the latest attempt and the student has not started the new one yet.
        boolean retakeGranted = activeNo == null && all.stream()
                .max(java.util.Comparator.comparingInt(QuizAttempt::getAttemptNumber))
                .map(a -> a.getStatus() == AttemptStatus.VOIDED)
                .orElse(false);
        return new AttemptStatusDTO(quiz.getqId(), lim, used, remaining, activeNo, canStart, closed, retakeGranted, reviewed);
    }

    private AttemptDTO toDto(QuizAttempt a) {
        User u = a.getUser();
        return new AttemptDTO(a.getId(), u.getId(), u.getUsername(), u.getFullName(), a.getAttemptNumber(), a.getStatus(),
                a.getStartedAt(), a.getSubmittedAt(), a.getMarksA(), a.getMarksB(),
                a.getVoidedAt(), a.getVoidedByName(), a.getVoidReason());
    }

    private static boolean isComplete(Quiz quiz, QuizAttempt a) {
        QuizType t = quiz.getQuizType();
        if (t == QuizType.THEORY) return a.isTheorySubmitted();
        if (t == QuizType.BOTH) return a.isObjectiveSubmitted() && a.isTheorySubmitted();
        return a.isObjectiveSubmitted();
    }

    private User lock(User student) {
        return users.lockById(student.getId()).orElseThrow(() -> notFound("Student not found"));
    }

    private List<QuizAttempt> load(Long userId, Long quizId) {
        return new ArrayList<>(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(userId, quizId));
    }

    /** True once staff have marked the student's result for this quiz as reviewed. */
    private boolean isReviewed(Long userId, Long quizId) {
        return reports.findByUser_IdAndQuiz_qId(userId, quizId).stream()
                .anyMatch(r -> Boolean.TRUE.equals(r.getIsReviewed()));
    }

    private boolean hasReport(Long userId, Long quizId) {
        return !reports.findByUser_IdAndQuiz_qId(userId, quizId).isEmpty();
    }

    private static java.util.Optional<QuizAttempt> active(List<QuizAttempt> all) {
        return all.stream().filter(a -> a.getStatus() == AttemptStatus.IN_PROGRESS).findFirst();
    }

    private static java.util.Optional<QuizAttempt> latestCounted(List<QuizAttempt> all) {
        return all.stream().filter(a -> a.getStatus() != AttemptStatus.VOIDED)
                .max(java.util.Comparator.comparingInt(QuizAttempt::getAttemptNumber));
    }

    private static long counted(List<QuizAttempt> all) {
        return all.stream().filter(a -> a.getStatus() != AttemptStatus.VOIDED).count();
    }

    private static int limit(Quiz quiz) {
        Integer m = quiz.getMaxAttempts();
        return m == null || m < 1 ? 1 : m;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
