package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.CategoryRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.Registered_coursesRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.GradingService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Bulk enrolment (every active student at a program + level into that level's courses) and the
 * per-student results summary used for spreadsheet export.
 */
@Service
public class BulkEnrollService {

    @Autowired private ProgramRepository programRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private Registered_coursesRepository registeredCoursesRepository;
    @Autowired private AcademicSessionService sessionService;
    @Autowired private AcademicRecordService recordService;
    @Autowired private GradingService gradingService;

    /**
     * Preview ({@code commit=false}) or perform the enrolment. Semester is optional; when given,
     * only that semester's courses are used. Existing enrolments are left alone.
     */
    @Transactional
    public Map<String, Object> enroll(User actor, Long programId, Integer level, Integer semester, boolean commit) {
        Program program = scopedProgram(actor, programId);
        if (level == null) throw new IllegalArgumentException("Choose a level.");

        List<Category> courses = categoryRepository.findByProgramsContaining(program).stream()
                .filter(c -> String.valueOf(level).equals(normLevel(c.getLevel())))
                .filter(c -> semester == null || semester.equals(c.getSemester()))
                .sorted(Comparator.comparing(c -> Objects.toString(c.getCourseCode(), "")))
                .toList();
        List<User> students = userRepository.findByProgramAndCurrentLevel(program, level).stream()
                .filter(u -> u.getRole() == Role.NORMAL && u.isEnabled())
                .toList();

        Set<String> existing = new HashSet<>();
        for (Category c : courses)
            registeredCoursesRepository.findByCategory(c).forEach(r -> { if (r.getUser() != null) existing.add(c.getCid() + ":" + r.getUser().getId()); });

        int toCreate = 0, already = 0, created = 0;
        var session = commit ? sessionService.current() : null;
        List<Registered_courses> batch = new ArrayList<>();
        for (Category c : courses) {
            for (User s : students) {
                if (existing.contains(c.getCid() + ":" + s.getId())) { already++; continue; }
                toCreate++;
                if (commit) {
                    Registered_courses r = new Registered_courses();
                    r.setCategory(c);
                    r.setUser(s);
                    r.setRegDate(new Date());
                    r.setSession(session);
                    batch.add(r);
                }
            }
        }
        if (commit && !batch.isEmpty()) {
            registeredCoursesRepository.saveAll(batch);
            created = batch.size();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("program", program.getName());
        out.put("level", level);
        out.put("semester", semester);
        out.put("students", students.size());
        out.put("courses", courses.stream().map(c -> Map.of("courseId", c.getCid(),
                "courseCode", Objects.toString(c.getCourseCode(), ""), "title", Objects.toString(c.getTitle(), ""),
                "semester", c.getSemester() == null ? 0 : c.getSemester())).toList());
        out.put("toCreate", toCreate);
        out.put("alreadyEnrolled", already);
        out.put("created", created);
        out.put("committed", commit);
        return out;
    }

    /** One row per student of a program (optionally one level): credits, CGPA, class, carry-overs, promotion standing. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> resultsSummary(User actor, Long programId, Integer level) {
        Program program = scopedProgram(actor, programId);
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(u -> u.getProgram() != null && u.getProgram().getId().equals(program.getId()))
                .filter(u -> level == null || level.equals(u.getCurrentLevel()))
                .sorted(Comparator.comparing((User u) -> Objects.toString(u.getUsername(), "")))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User s : students) {
            var attempts = recordService.attempts(s.getId(), AcademicRecordService.STAFF_VISIBLE);
            BigDecimal cgpa = AcademicRecordService.gpa(attempts);
            var owing = recordService.outstanding(attempts);
            Map<String, Object> elig = recordService.eligibility(s);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getUsername());
            m.put("name", CurrentUserService.displayName(s));
            m.put("email", s.getEmail());
            m.put("program", program.getName());
            m.put("level", s.getCurrentLevel());
            m.put("semester", s.getCurrentSemester());
            m.put("creditsAttempted", attempts.stream().mapToInt(AcademicRecordService.Attempt::credits).sum());
            m.put("creditsEarned", attempts.stream().filter(AcademicRecordService.Attempt::passed).mapToInt(AcademicRecordService.Attempt::credits).sum());
            m.put("cgpa", cgpa);
            m.put("class", gradingService.classFor(cgpa));
            m.put("outstandingCourses", String.join(", ", owing.stream().map(a -> Objects.toString(a.course().getCourseCode(), "")).toList()));
            m.put("meetsPromotionRules", Boolean.TRUE.equals(elig.get("eligible")) ? "Yes" : "No");
            m.put("accountStatus", s.isEnabled() ? "Active" : "Deactivated");
            rows.add(m);
        }
        return rows;
    }

    private Program scopedProgram(User actor, Long programId) {
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.ADMIN) throw new AccessDeniedException("Staff only.");
        if (programId == null) throw new IllegalArgumentException("Choose a program.");
        Program p = programRepository.findById(programId).orElseThrow(() -> new IllegalArgumentException("Program not found."));
        if (actor.getRole() == Role.ADMIN && (p.getDepartment() == null || actor.getDepartment() == null
                || !p.getDepartment().getId().equals(actor.getDepartment().getId())))
            throw new AccessDeniedException("That program is not in your department.");
        return p;
    }

    private static String normLevel(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.toLowerCase().startsWith("level ") ? s.substring(6).trim() : s;
    }
}
