package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.examops.ProctoringEvent;
import com.exam.repository.ProctoringEventRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.service.comms.CurrentUserService;
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

    @Transactional
    public void record(User student, Long quizId, String type, Integer violationNumber, String ip) {
        if (student == null || student.getRole() != Role.NORMAL) return;   // only students are proctored
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Event type is required.");
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        if (eventRepository.countByQuiz_qIdAndUser_Id(quizId, student.getId()) >= MAX_EVENTS_PER_ATTEMPT) return;

        ProctoringEvent e = new ProctoringEvent();
        e.setUser(student);
        e.setQuiz(quiz);
        String clean = type.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        e.setType(clean.length() > MAX_TYPE_LENGTH ? clean.substring(0, MAX_TYPE_LENGTH) : clean);
        e.setViolationNumber(violationNumber);
        e.setIpAddress(ip);
        eventRepository.save(e);
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

        Integer maxViolations = quiz.getMaxViolations();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User s : students.values()) {
            List<ProctoringEvent> events = byStudent.getOrDefault(s.getId(), List.of());
            long violations = events.stream().filter(e -> !"auto-submit".equals(e.getType())).count();
            Map<String, Long> byType = events.stream()
                    .collect(Collectors.groupingBy(ProctoringEvent::getType, TreeMap::new, Collectors.counting()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getId());
            m.put("name", CurrentUserService.displayName(s));
            m.put("username", s.getUsername());
            m.put("violations", violations);
            m.put("byType", byType);
            m.put("autoSubmitted", events.stream().anyMatch(e -> "auto-submit".equals(e.getType())));
            m.put("firstEventAt", events.isEmpty() ? null : events.get(0).getOccurredAt());
            m.put("lastEventAt", events.isEmpty() ? null : events.get(events.size() - 1).getOccurredAt());
            m.put("submitted", reports.containsKey(s.getId()));
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
