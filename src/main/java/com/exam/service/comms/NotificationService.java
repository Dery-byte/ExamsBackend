package com.exam.service.comms;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.comms.Notification;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Registered_courses;
import com.exam.model.exam.SemesterSheet;
import com.exam.model.exam.StudentCourseMark;
import com.exam.repository.NotificationRepository;
import com.exam.repository.Registered_coursesRepository;
import com.exam.repository.StudentCourseMarkRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * In-app notifications. Every notify method is fire-and-forget: it never throws, so callers
 * can use it after any business operation without putting that operation at risk.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String QUIZ_PUBLISHED  = "QUIZ_PUBLISHED";
    public static final String RESULT_RELEASED = "RESULT_RELEASED";
    public static final String SHEET_SUBMITTED = "SHEET_SUBMITTED";
    public static final String SHEET_APPROVED  = "SHEET_APPROVED";
    public static final String SHEET_RETURNED  = "SHEET_RETURNED";
    public static final String SHEET_PUBLISHED = "SHEET_PUBLISHED";
    public static final String ANNOUNCEMENT    = "ANNOUNCEMENT";

    @Autowired private NotificationWriter writer;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private Registered_coursesRepository registeredCoursesRepository;
    @Autowired private StudentCourseMarkRepository studentCourseMarkRepository;
    @Autowired private SystemSettingService systemSettingService;

    // ── Sending ───────────────────────────────────────────────────────────────

    public void notify(Collection<User> recipients, String type, String title, String message, String link) {
        if (recipients == null || recipients.isEmpty()) return;
        try {
            Map<Long, User> unique = new LinkedHashMap<>();
            for (User u : recipients) if (u != null && u.getId() != null) unique.putIfAbsent(u.getId(), u);
            if (!unique.isEmpty()) writer.write(unique.values(), type, title, message, link);
        } catch (Exception e) {
            log.warn("Could not store {} notifications: {}", type, e.getMessage());
        }
    }

    public void notify(User recipient, String type, String title, String message, String link) {
        if (recipient != null) notify(List.of(recipient), type, title, message, link);
    }

    /** A quiz just went live: tell every student registered for its course. */
    public void quizPublished(Quiz quiz) {
        try {
            Category course = quiz.getCategory();
            if (course == null) return;
            List<User> students = registeredCoursesRepository.findByCategory(course).stream()
                    .map(Registered_courses::getUser).filter(Objects::nonNull).toList();
            notify(students, QUIZ_PUBLISHED, "New assessment: " + quiz.getTitle(),
                    "A new assessment is now available for " + course.getTitle() + ".",
                    "/user-dashboard/quizzes");
        } catch (Exception e) {
            log.warn("quizPublished notification failed: {}", e.getMessage());
        }
    }

    /** A lecturer finished reviewing a student's attempt. */
    public void resultReleased(User student, Quiz quiz) {
        if (quiz == null) return;
        notify(student, RESULT_RELEASED, "Result released: " + quiz.getTitle(),
                "Your script has been reviewed. Your final score is now available.",
                "/user-dashboard/history");
    }

    public void sheetSubmitted(SemesterSheet sheet, String actorName) {
        try {
            Department dept = sheet.getProgram() != null ? sheet.getProgram().getDepartment() : null;
            List<User> hods = userRepository.findByRole(Role.ADMIN).stream()
                    .filter(u -> inDepartment(u, dept)).toList();
            String msg = sheetLabel(sheet) + " was submitted"
                    + (actorName != null ? " by " + actorName : "") + " and is awaiting approval.";
            notify(hods, SHEET_SUBMITTED, "Marks sheet submitted", msg, "/admin/marks-sheets");
            notify(userRepository.findByRole(Role.SUPER_ADMIN), SHEET_SUBMITTED, "Marks sheet submitted", msg,
                    "/super-admin/marks-sheets");
        } catch (Exception e) {
            log.warn("sheetSubmitted notification failed: {}", e.getMessage());
        }
    }

    public void sheetApproved(SemesterSheet sheet) {
        try {
            notify(sheetLecturers(sheet), SHEET_APPROVED, "Marks sheet approved",
                    sheetLabel(sheet) + " has been approved.", "/lect/manual-marks");
        } catch (Exception e) {
            log.warn("sheetApproved notification failed: {}", e.getMessage());
        }
    }

    public void sheetReturned(SemesterSheet sheet) {
        try {
            notify(sheetLecturers(sheet), SHEET_RETURNED, "Marks sheet returned for corrections",
                    sheetLabel(sheet) + " was sent back to you for corrections.", "/lect/manual-marks");
        } catch (Exception e) {
            log.warn("sheetReturned notification failed: {}", e.getMessage());
        }
    }

    /** Results published: tell the sheet's lecturers, and its students if they can see report cards. */
    public void sheetPublished(SemesterSheet sheet) {
        try {
            notify(sheetLecturers(sheet), SHEET_PUBLISHED, "Results published",
                    sheetLabel(sheet) + " results have been published.", "/lect/manual-marks");
            if (!systemSettingService.getBooleanSetting(SystemSettingService.MARKS_SHEET_VISIBLE_STUDENT, true)) return;
            List<User> students = studentCourseMarkRepository.findBySemesterSheetId(sheet.getId()).stream()
                    .map(StudentCourseMark::getStudent).filter(Objects::nonNull).toList();
            notify(students, SHEET_PUBLISHED, "Semester results published",
                    "Your Level " + sheet.getLevel() + " Semester " + sheet.getSemester() + " results are now available.",
                    "/user-dashboard/report-cards");
        } catch (Exception e) {
            log.warn("sheetPublished notification failed: {}", e.getMessage());
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> recentFor(Long userId, int limit) {
        return notificationRepository.findRecent(userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(NotificationService::toDto).toList();
    }

    public long unreadCount(Long userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long id, Long userId) { notificationRepository.markRead(id, userId); }

    @Transactional
    public void markAllRead(Long userId) { notificationRepository.markAllRead(userId); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> toDto(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("type", n.getType());
        m.put("title", n.getTitle());
        m.put("message", n.getMessage());
        m.put("link", n.getLink());
        m.put("read", n.isRead());
        m.put("createdAt", n.getCreatedAt());
        return m;
    }

    private static String sheetLabel(SemesterSheet s) {
        String prog = s.getProgram() != null ? s.getProgram().getName() : "Marks sheet";
        return prog + " · Level " + s.getLevel() + " · Semester " + s.getSemester();
    }

    /** Class teacher plus the lecturer of every course on the sheet. */
    private static List<User> sheetLecturers(SemesterSheet s) {
        List<User> out = new ArrayList<>();
        if (s.getClassTeacher() != null) out.add(s.getClassTeacher());
        if (s.getCourses() != null) s.getCourses().forEach(c -> { if (c.getUser() != null) out.add(c.getUser()); });
        return out;
    }

    /** True when the user belongs to the department (directly, via their program, or as a secondary department). */
    public static boolean inDepartment(User u, Department dept) {
        if (dept == null || u == null) return false;
        if (u.getDepartment() != null && dept.getId().equals(u.getDepartment().getId())) return true;
        if (u.getProgram() != null && u.getProgram().getDepartment() != null
                && dept.getId().equals(u.getProgram().getDepartment().getId())) return true;
        return u.getSecondaryDepartments() != null
                && u.getSecondaryDepartments().stream().anyMatch(d -> dept.getId().equals(d.getId()));
    }
}
