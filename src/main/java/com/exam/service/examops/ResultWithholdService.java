package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.examops.ResultWithhold;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.repository.ResultWithholdRepository;
import com.exam.repository.UserRepository;
import com.exam.service.ReportEmailService;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Staff hold back one student's quiz result while classmates' results are out (e.g. violations, or an
 * unusually long time taken). While withheld, the student sees "withheld" instead of marks on every
 * page, can't open the answers, print or download the result, or ask for a re-mark; the result email
 * and "result released" notice wait; and the marks are not copied into a marks sheet. Staff always see
 * everything. The reason is for staff only.
 */
@Service
public class ResultWithholdService {

    /** What a student sees in place of the result (the staff reason is never shown). */
    public static final String STUDENT_MESSAGE = "Your result for this quiz is being withheld. Please contact your lecturer.";
    private static final int MIN_REASON = 5;
    private static final int MAX_REASON = 500;

    @Autowired private ResultWithholdRepository withholdRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private SystemSettingService systemSettingService;
    @Autowired private ReportEmailService reportEmailService;
    @Autowired private NotificationService notificationService;
    @Autowired private ObjectMapper objectMapper;

    /** A student asked for a result that is withheld (403, with {@link #STUDENT_MESSAGE}). */
    public static class ResultWithheldException extends RuntimeException {
        public ResultWithheldException() { super(STUDENT_MESSAGE); }
    }

    // ── Who may ─────────────────────────────────────────────────────────────

    /** The Super Admin always; HODs and lecturers when the Super Admin allows it. */
    public boolean mayWithhold(User staff) {
        if (staff == null || staff.getRole() == null) return false;
        return switch (staff.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> systemSettingService.getBooleanSetting(SystemSettingService.RESULT_WITHHOLD_ADMIN, true);
            case LECTURER -> systemSettingService.getBooleanSetting(SystemSettingService.RESULT_WITHHOLD_LECTURER, true);
            default -> false;
        };
    }

    /** Students (and anyone not known) get the withheld view; staff see everything. */
    public static boolean hidesFrom(User caller) {
        return caller == null || caller.getRole() == null || caller.getRole() == Role.NORMAL;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean isWithheld(Long studentId, Long quizId) {
        if (studentId == null || quizId == null) return false;
        return withholdRepository.existsByQuiz_qIdAndStudent_IdAndActiveTrue(quizId, studentId);
    }

    @Transactional(readOnly = true)
    public Set<Long> withheldQuizIds(Long studentId) {
        if (studentId == null) return Set.of();
        return new HashSet<>(withholdRepository.findActiveQuizIdsForStudent(studentId));
    }

    /** For a student caller, refuses a withheld result (ResultWithheldException → 403). Staff pass. */
    public void requireVisible(User caller, Long studentId, Long quizId) {
        if (hidesFrom(caller) && isWithheld(studentId, quizId)) throw new ResultWithheldException();
    }

    /**
     * Reports as a student may see them: withheld ones keep their place (quiz, date) but lose the
     * marks, percentage and grade, and carry withheld=true and the message. Staff get the list as is.
     */
    @Transactional(readOnly = true)
    public List<?> forViewer(User caller, List<Report> reports) {
        if (reports == null || reports.isEmpty() || !hidesFrom(caller)) return reports;
        Map<Long, Set<Long>> withheldByStudent = new HashMap<>();
        List<Object> out = new ArrayList<>(reports.size());
        for (Report r : reports) {
            Long sid = r.getUser() != null ? r.getUser().getId() : null;
            Long qid = r.getQuiz() != null ? r.getQuiz().getqId() : null;
            boolean held = sid != null && qid != null
                    && withheldByStudent.computeIfAbsent(sid, this::withheldQuizIds).contains(qid);
            if (!held) { out.add(r); continue; }
            Map<String, Object> m = objectMapper.convertValue(r, new TypeReference<LinkedHashMap<String, Object>>() {});
            m.put("marks", null);
            m.put("marksB", null);
            m.put("percentage", null);
            m.put("grade", null);
            m.put("withheld", true);
            m.put("withheldMessage", STUDENT_MESSAGE);
            out.add(m);
        }
        return out;
    }

    /** Staff: the quiz's withheld results, by student id (reason, who, when). */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Object>> withheldForQuiz(Long quizId) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (ResultWithhold w : withholdRepository.findActiveForQuiz(quizId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", w.getReason());
            m.put("by", w.getWithheldByName());
            m.put("at", w.getWithheldAt());
            out.put(w.getStudent().getId(), m);
        }
        return out;
    }

    // ── Changes ─────────────────────────────────────────────────────────────

    /** Holds back the student's result for the quiz, with a reason (staff only). */
    @Transactional
    public Map<String, Object> withhold(User staff, Long quizId, Long studentId, String reason) {
        Quiz quiz = checkAccess(staff, quizId);
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON) throw new IllegalArgumentException("Please give a reason (at least " + MIN_REASON + " characters).");
        if (why.length() > MAX_REASON) throw new IllegalArgumentException("The reason is too long (at most " + MAX_REASON + " characters).");
        User student = student(studentId);
        boolean tookIt = !quizAttemptRepository.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(studentId, quizId).isEmpty()
                || !reportRepository.findByUser_IdAndQuiz_qId(studentId, quizId).isEmpty();
        if (!tookIt) throw new IllegalArgumentException("This student has not taken this quiz.");
        if (withholdRepository.existsByQuiz_qIdAndStudent_IdAndActiveTrue(quizId, studentId)) {
            throw new IllegalArgumentException("This student's result is already withheld.");
        }
        ResultWithhold w = new ResultWithhold();
        w.setQuiz(quiz);
        w.setStudent(student);
        w.setReason(why);
        w.setWithheldByName(CurrentUserService.displayName(staff));
        w.setWithheldAt(LocalDateTime.now());
        withholdRepository.save(w);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("withheld", true);
        out.put("by", w.getWithheldByName());
        out.put("at", w.getWithheldAt());
        return out;
    }

    /**
     * Lets the student see the result again. If it has already been reviewed, the result email and
     * the "result released" notice that waited go out now.
     */
    @Transactional
    public Map<String, Object> release(User staff, Long quizId, Long studentId) {
        checkAccess(staff, quizId);
        User student = student(studentId);
        ResultWithhold w = withholdRepository.findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(quizId, studentId)
                .orElseThrow(() -> new IllegalArgumentException("This student's result is not withheld."));
        w.setActive(false);
        w.setReleasedByName(CurrentUserService.displayName(staff));
        w.setReleasedAt(LocalDateTime.now());
        withholdRepository.save(w);

        boolean reviewed = reportRepository.findTopByUser_IdAndQuiz_qIdOrderBySubmissionDateDesc(studentId, quizId)
                .map(r -> Boolean.TRUE.equals(r.getIsReviewed())).orElse(false);
        if (reviewed) {
            reportEmailService.sendReviewedReport(student, w.getQuiz());
            notificationService.resultReleased(student, w.getQuiz());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("withheld", false);
        out.put("notified", reviewed);
        return out;
    }

    private Quiz checkAccess(User staff, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        ExamAccess.requireQuiz(staff, quiz);
        if (!mayWithhold(staff)) throw new AccessDeniedException("The Super Admin has not allowed your role to withhold results.");
        return quiz;
    }

    private User student(Long studentId) {
        User student = userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        if (student.getRole() != Role.NORMAL) throw new IllegalArgumentException("Only a student's result can be withheld.");
        return student;
    }
}
