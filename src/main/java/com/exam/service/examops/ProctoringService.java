package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.examops.ProctoringEvent;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.QuizAttempt;
import com.exam.repository.ProctoringEventRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.repository.UserRepository;
import com.exam.service.QuizTimerService;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Stores proctoring events from the exam page and builds the per-quiz proctoring report for staff. */
@Service
public class ProctoringService {

    /** Guard against a misbehaving client flooding the table. */
    private static final int MAX_EVENTS_PER_ATTEMPT = 500;
    private static final int MAX_TYPE_LENGTH = 60;

    @Autowired private ProctoringEventRepository eventRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QuizTimerService quizTimerService;
    @Autowired private SystemSettingService systemSettingService;

    /** Event saved when staff reset a student's violations (not a violation itself). */
    public static final String RESET_TYPE = "violations-reset";
    private static final String AUTO_SUBMIT = "auto-submit";
    private static final int MIN_REASON = 5;
    private static final int MAX_REASON = 500;

    @Transactional
    public void record(User student, Long quizId, String type, Integer violationNumber, String ip) {
        record(student, quizId, type, violationNumber, ip, false);
    }

    /** beforeReset: the violation was stamped with an older reset number, so the report shows it but does not count it. */
    @Transactional
    public void record(User student, Long quizId, String type, Integer violationNumber, String ip, boolean beforeReset) {
        if (student == null || student.getRole() != Role.NORMAL) return;   // only students are proctored
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Event type is required.");
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        if (eventRepository.countByQuiz_qIdAndUser_Id(quizId, student.getId()) >= MAX_EVENTS_PER_ATTEMPT) return;

        ProctoringEvent e = new ProctoringEvent();
        e.setUser(student);
        e.setQuiz(quiz);
        String clean = type.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        // Only staff create reset events (resetViolations); a student's page sending one would clear its own count
        if (RESET_TYPE.equals(clean)) throw new IllegalArgumentException("Unknown event type.");
        e.setType(clean.length() > MAX_TYPE_LENGTH ? clean.substring(0, MAX_TYPE_LENGTH) : clean);
        e.setViolationNumber(violationNumber);
        e.setIpAddress(ip);
        if (beforeReset) e.setBeforeReset(true);
        eventRepository.save(e);
    }

    /** Whether this staff member may reset violations: the Super Admin always; HODs and lecturers when the Super Admin allows it. */
    public boolean mayResetViolations(User staff) {
        if (staff == null || staff.getRole() == null) return false;
        return switch (staff.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> systemSettingService.getBooleanSetting(SystemSettingService.VIOLATION_RESET_ADMIN, true);
            case LECTURER -> systemSettingService.getBooleanSetting(SystemSettingService.VIOLATION_RESET_LECTURER, true);
            default -> false;
        };
    }

    /** {@link #resetViolations(User, Long, Long, String, Integer)} back to 0. */
    @Transactional
    public Map<String, Object> resetViolations(User staff, Long quizId, Long studentId, String reason) {
        return resetViolations(staff, quizId, studentId, reason, 0);
    }

    /**
     * Sets a student's violation count for the quiz back to 0, or down to a lower number staff enter,
     * while they are still sitting it, with a reason. Any lock-out is cleared; the student's open exam
     * page picks the change up on its next save. Earlier violations stay in the timeline, marked as
     * before the reset; the reset event keeps the number it was set to (in violationNumber).
     */
    @Transactional
    public Map<String, Object> resetViolations(User staff, Long quizId, Long studentId, String reason, Integer newCount) {
        int setTo = newCount == null ? 0 : newCount;
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        ExamAccess.requireQuiz(staff, quiz);
        if (!mayResetViolations(staff)) {
            throw new AccessDeniedException("The Super Admin has not allowed your role to reset violations.");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON) throw new IllegalArgumentException("Please give a reason (at least " + MIN_REASON + " characters).");
        if (why.length() > MAX_REASON) throw new IllegalArgumentException("The reason is too long (at most " + MAX_REASON + " characters).");
        User student = userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        if (student.getRole() != Role.NORMAL) throw new IllegalArgumentException("Only a student's violations can be reset.");
        if (!quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(studentId, quizId, AttemptStatus.IN_PROGRESS)) {
            throw new IllegalArgumentException("This student has already submitted the quiz, so there is nothing to reset.");
        }

        int resetNo = quizTimerService.resetViolations(quizId, studentId, setTo);

        // Always saved, even past the per-attempt event cap: it explains the count
        ProctoringEvent e = new ProctoringEvent();
        e.setUser(student);
        e.setQuiz(quiz);
        e.setType(RESET_TYPE);
        e.setViolationNumber(setTo);
        e.setActorName(CurrentUserService.displayName(staff));
        e.setNote(why);
        eventRepository.save(e);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("resetNo", resetNo);
        out.put("count", setTo);
        out.put("resetAt", e.getOccurredAt());
        out.put("resetBy", e.getActorName());
        return out;
    }

    /** Per-student summary for one quiz, most-flagged first. Includes students with no events. */
    @Transactional(readOnly = true)
    public Map<String, Object> quizReport(User staff, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        ExamAccess.requireQuiz(staff, quiz);

        Map<Long, List<ProctoringEvent>> byStudent = eventRepository.findByQuiz_qIdOrderByOccurredAtAsc(quizId).stream()
                .collect(Collectors.groupingBy(e -> e.getUser().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<Long, User> students = new LinkedHashMap<>();
        Map<Long, Report> reports = new HashMap<>();
        for (Report r : reportRepository.findByQuiz_qId(quizId)) {
            if (r.getUser() == null) continue;
            students.putIfAbsent(r.getUser().getId(), r.getUser());
            reports.putIfAbsent(r.getUser().getId(), r);
        }
        byStudent.values().forEach(list -> students.putIfAbsent(list.get(0).getUser().getId(), list.get(0).getUser()));

        // Students sitting the quiz right now: only their violations can be reset
        Set<Long> inProgress = quizAttemptRepository.findAllForQuiz(quizId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.IN_PROGRESS && a.getUser() != null)
                .map(a -> a.getUser().getId())
                .collect(Collectors.toSet());

        Integer maxViolations = quiz.getMaxViolations();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User s : students.values()) {
            List<ProctoringEvent> events = byStudent.getOrDefault(s.getId(), List.of());
            // Counting starts again after the latest reset; violations stamped from before it don't count
            int lastReset = -1;
            for (int i = 0; i < events.size(); i++) if (RESET_TYPE.equals(events.get(i).getType())) lastReset = i;
            List<ProctoringEvent> current = events.subList(lastReset + 1, events.size()).stream()
                    .filter(e -> !Boolean.TRUE.equals(e.getBeforeReset()))
                    .toList();
            // A reset to a number (not 0) starts the count from that number
            Integer setTo = lastReset >= 0 ? events.get(lastReset).getViolationNumber() : null;
            long sinceReset = current.stream().filter(e -> !AUTO_SUBMIT.equals(e.getType())).count();
            long violations = (setTo == null ? 0 : setTo) + sinceReset;
            long allViolations = events.stream().filter(e -> !AUTO_SUBMIT.equals(e.getType()) && !RESET_TYPE.equals(e.getType())).count();
            Map<String, Long> byType = current.stream()
                    .collect(Collectors.groupingBy(ProctoringEvent::getType, TreeMap::new, Collectors.counting()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getId());
            m.put("name", CurrentUserService.displayName(s));
            m.put("username", s.getUsername());
            m.put("violations", violations);
            m.put("violationsBeforeReset", allViolations - sinceReset);
            m.put("byType", byType);
            m.put("autoSubmitted", current.stream().anyMatch(e -> AUTO_SUBMIT.equals(e.getType())));
            m.put("firstEventAt", events.isEmpty() ? null : events.get(0).getOccurredAt());
            m.put("lastEventAt", events.isEmpty() ? null : events.get(events.size() - 1).getOccurredAt());
            m.put("submitted", reports.containsKey(s.getId()));
            m.put("inProgress", inProgress.contains(s.getId()));
            m.put("hasEvents", !events.isEmpty());
            if (lastReset >= 0) {
                ProctoringEvent r = events.get(lastReset);
                m.put("lastResetAt", r.getOccurredAt());
                m.put("lastResetBy", r.getActorName());
                m.put("lastResetReason", r.getNote());
                m.put("lastResetTo", setTo == null ? 0 : setTo);
                m.put("resetCount", events.stream().filter(e -> RESET_TYPE.equals(e.getType())).count());
            }
            m.put("flag", flag(violations, maxViolations));
            rows.add(m);
        }
        rows.sort(Comparator.<Map<String, Object>, Long>comparing(m -> (Long) m.get("violations")).reversed()
                .thenComparing(m -> String.valueOf(m.get("name"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quizId", quiz.getqId());
        out.put("quizTitle", quiz.getTitle());
        out.put("courseCode", quiz.getCategory() != null ? quiz.getCategory().getCourseCode() : null);
        out.put("proctoringEnabled", Boolean.TRUE.equals(quiz.getProctoringEnabled()));
        out.put("violationAction", quiz.getViolationAction() != null ? quiz.getViolationAction().name() : null);
        out.put("maxViolations", maxViolations);
        out.put("canResetViolations", mayResetViolations(staff));
        // The reset dialog shows how long the next lock-out would be for each count it offers
        out.put("delaySeconds", quiz.getDelaySeconds());
        out.put("delayMultiplier", quiz.getDelayMultiplier());
        out.put("students", rows);
        return out;
    }

    /** Full event timeline for one student in one quiz. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> studentTimeline(User staff, Long quizId, Long studentId) {
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        ExamAccess.requireQuiz(staff, quiz);
        return eventRepository.findByQuiz_qIdAndUser_IdOrderByOccurredAtAsc(quizId, studentId).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getType());
            m.put("violationNumber", e.getViolationNumber());
            m.put("occurredAt", e.getOccurredAt());
            m.put("ipAddress", e.getIpAddress());
            m.put("beforeReset", Boolean.TRUE.equals(e.getBeforeReset()));
            if (e.getActorName() != null) m.put("actorName", e.getActorName());
            if (e.getNote() != null) m.put("note", e.getNote());
            return m;
        }).toList();
    }

    /** CLEAN (none), WATCH (some), SERIOUS (reached the quiz's violation limit). */
    private static String flag(long violations, Integer max) {
        if (violations == 0) return "CLEAN";
        if (max != null && max > 0 && violations >= max) return "SERIOUS";
        return "WATCH";
    }
}
