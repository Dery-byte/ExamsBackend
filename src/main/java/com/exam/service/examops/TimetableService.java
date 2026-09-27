package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.Registered_coursesRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exam timetable built from each quiz's scheduled date and start time.
 * <p>
 * Students see scheduled assessments for the courses they're registered in. Staff see the
 * quizzes they manage (Super Admin: all, optionally narrowed by department / program / level).
 * Clashes: for a student, any two of their assessments that overlap; for staff, two quizzes
 * that overlap and are sat by the same program and level.
 */
@Service
public class TimetableService {

    @Autowired private QuizRepository quizRepository;
    @Autowired private Registered_coursesRepository registeredCoursesRepository;
    @Autowired private NumberOfTheoryToAnswerRepository theorySectionRepository;

    private record Slot(Quiz quiz, LocalDateTime start, LocalDateTime end, Set<Long> programIds, String level) {}

    @Transactional(readOnly = true)
    public Map<String, Object> timetable(User u, LocalDate from, LocalDate to, Long departmentId, Long programId, String level) {
        LocalDate start = from != null ? from : LocalDate.now().minusDays(7);
        LocalDate end = to != null ? to : start.plusDays(90);
        if (end.isBefore(start)) throw new IllegalArgumentException("The end date is before the start date.");

        boolean student = u.getRole() == Role.NORMAL;
        Set<Long> myCourseIds = student
                ? registeredCoursesRepository.findRegistrationsByUserId(u.getId()).stream()
                    .filter(r -> r.getCategory() != null).map(r -> r.getCategory().getCid()).collect(Collectors.toSet())
                : Set.of();
        String levelFilter = normLevel(level);

        List<Slot> slots = quizRepository.findAll().stream()
                .filter(q -> q.getQuizDate() != null && q.getStartTime() != null)
                .filter(q -> !q.getQuizDate().isBefore(start) && !q.getQuizDate().isAfter(end))
                .filter(q -> student
                        ? q.getCategory() != null && myCourseIds.contains(q.getCategory().getCid()) && (q.isActive() || q.isAutoOpen())
                        : ExamAccess.canManageQuiz(u, q))
                .map(this::slot)
                .filter(s -> departmentId == null || deptMatches(s.quiz(), departmentId))
                .filter(s -> programId == null || s.programIds().contains(programId))
                .filter(s -> levelFilter == null || levelFilter.equals(s.level()))
                .sorted(Comparator.comparing(Slot::start))
                .toList();

        // Clash detection (sweep over time-sorted slots)
        Map<Long, List<Map<String, Object>>> clashes = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot a = slots.get(i);
            for (int j = i + 1; j < slots.size() && slots.get(j).start().isBefore(a.end()); j++) {
                Slot b = slots.get(j);
                boolean relevant = student || (Objects.equals(a.level(), b.level())
                        && !Collections.disjoint(a.programIds(), b.programIds()));
                if (!relevant) continue;
                clashes.computeIfAbsent(a.quiz().getqId(), k -> new ArrayList<>()).add(ref(b.quiz()));
                clashes.computeIfAbsent(b.quiz().getqId(), k -> new ArrayList<>()).add(ref(a.quiz()));
            }
        }

        List<Map<String, Object>> items = slots.stream().map(s -> {
            Quiz q = s.quiz();
            Category c = q.getCategory();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quizId", q.getqId());
            m.put("title", q.getTitle());
            m.put("courseCode", c != null ? c.getCourseCode() : null);
            m.put("courseTitle", c != null ? c.getTitle() : null);
            m.put("level", s.level());
            m.put("programs", programNames(q));
            m.put("date", q.getQuizDate());
            m.put("startTime", q.getStartTime());
            m.put("start", s.start());
            m.put("end", s.end());
            m.put("durationMinutes", java.time.Duration.between(s.start(), s.end()).toMinutes());
            m.put("published", q.isActive());
            m.put("autoOpen", q.isAutoOpen());
            m.put("status", q.getStatus() != null ? q.getStatus().name() : null);
            m.put("lecturer", CurrentUserService.displayName(c != null && c.getUser() != null ? c.getUser() : q.getUser()));
            m.put("clashesWith", clashes.getOrDefault(q.getqId(), List.of()));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start);
        out.put("to", end);
        out.put("items", items);
        out.put("clashCount", clashes.size());
        return out;
    }

    private Slot slot(Quiz q) {
        LocalDateTime start = LocalDateTime.of(q.getQuizDate(), q.getStartTime());
        int minutes = parseMinutes(q.getQuizTime()) + theorySectionRepository.findByQuiz_qId(q.getqId()).stream()
                .mapToInt(t -> t.getTimeAllowed() == null ? 0 : t.getTimeAllowed()).sum();
        LocalDateTime end = start.plusMinutes(Math.max(minutes, 1));
        Set<Program> programs = q.getPrograms() != null && !q.getPrograms().isEmpty() ? q.getPrograms()
                : (q.getCategory() != null && q.getCategory().getPrograms() != null ? q.getCategory().getPrograms() : Set.of());
        Set<Long> ids = programs.stream().map(Program::getId).collect(Collectors.toSet());
        return new Slot(q, start, end, ids, normLevel(q.getCategory() != null ? q.getCategory().getLevel() : null));
    }

    private static boolean deptMatches(Quiz q, Long deptId) {
        Category c = q.getCategory();
        return c != null && c.getPrograms() != null && c.getPrograms().stream()
                .anyMatch(p -> p.getDepartment() != null && deptId.equals(p.getDepartment().getId()));
    }

    private static List<String> programNames(Quiz q) {
        Set<Program> programs = q.getPrograms() != null && !q.getPrograms().isEmpty() ? q.getPrograms()
                : (q.getCategory() != null && q.getCategory().getPrograms() != null ? q.getCategory().getPrograms() : Set.of());
        return programs.stream().map(Program::getName).sorted().toList();
    }

    private static Map<String, Object> ref(Quiz q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quizId", q.getqId());
        m.put("title", q.getTitle());
        m.put("courseCode", q.getCategory() != null ? q.getCategory().getCourseCode() : null);
        return m;
    }

    /** Quiz duration is stored as minutes ("90") or "H:MM". */
    static int parseMinutes(String quizTime) {
        if (quizTime == null || quizTime.isBlank()) return 0;
        try {
            if (quizTime.contains(":")) {
                String[] p = quizTime.split(":");
                return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
            }
            return Integer.parseInt(quizTime.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String normLevel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.toLowerCase().startsWith("level ")) s = s.substring(6).trim();
        return s;
    }
}
