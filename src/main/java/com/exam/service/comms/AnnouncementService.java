package com.exam.service.comms;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.comms.Announcement;
import com.exam.model.comms.AnnouncementAudience;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.AnnouncementRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Announcements. The Super Admin can address anyone; an HOD is always limited to their own
 * department. Posting also drops a notification into each recipient's bell.
 */
@Service
public class AnnouncementService {

    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private com.exam.service.features.FeatureService featureService;

    public static class CreateRequest {
        public String title;
        public String body;
        public String audience;       // AnnouncementAudience name
        public Long departmentId;
        public Long programId;
        public Integer level;
        public Boolean pinned;
        public LocalDate expiresOn;
    }

    public Map<String, Object> create(User author, CreateRequest req) {
        if (author.getRole() != Role.SUPER_ADMIN && author.getRole() != Role.ADMIN)
            throw new AccessDeniedException("Only the Super Admin and HODs can post announcements.");
        featureService.require(com.exam.model.features.Feature.HOD_ANNOUNCEMENTS, author);
        if (req.title == null || req.title.isBlank() || req.body == null || req.body.isBlank())
            throw new IllegalArgumentException("Title and message are required.");

        Announcement a = new Announcement();
        a.setTitle(req.title.trim());
        a.setBody(req.body.trim());
        a.setAuthor(author);
        a.setAudience(req.audience == null ? AnnouncementAudience.ALL : AnnouncementAudience.valueOf(req.audience));
        a.setPinned(Boolean.TRUE.equals(req.pinned));
        a.setExpiresOn(req.expiresOn);

        Program program = req.programId == null ? null : programRepository.findById(req.programId)
                .orElseThrow(() -> new IllegalArgumentException("Program not found."));
        Department dept = req.departmentId == null ? null : departmentRepository.findById(req.departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found."));
        if (dept == null && program != null) dept = program.getDepartment();

        if (author.getRole() == Role.ADMIN) {
            // An HOD can only address their own department
            Department own = author.getDepartment();
            if (own == null) throw new AccessDeniedException("Your account is not linked to a department.");
            if (dept != null && !own.getId().equals(dept.getId()))
                throw new AccessDeniedException("You can only post announcements to your own department.");
            dept = own;
        }
        a.setDepartment(dept);
        a.setProgram(program);
        a.setLevel(req.level);

        Announcement saved = announcementRepository.save(a);

        List<User> recipients = userRepository.findAll().stream()
                .filter(u -> !u.getId().equals(author.getId()) && isVisibleTo(saved, u)).toList();
        notificationService.notify(recipients, NotificationService.ANNOUNCEMENT, "Announcement: " + saved.getTitle(),
                preview(saved.getBody()), null); // null link: the bell opens the reader's own Announcements page
        return toDto(saved);
    }

    /** Announcements the user should see (not expired, addressed to them). */
    public List<Map<String, Object>> visibleTo(User user) {
        return announcementRepository.findAllByOrderByPinnedDescCreatedAtDesc().stream()
                .filter(a -> !isExpired(a) && (isVisibleTo(a, user) || isAuthor(a, user)))
                .map(AnnouncementService::toDto).toList();
    }

    /** Announcements the user may manage: all for the Super Admin, own department for an HOD. */
    public List<Map<String, Object>> manageableBy(User user) {
        return announcementRepository.findAllByOrderByPinnedDescCreatedAtDesc().stream()
                .filter(a -> canManage(a, user))
                .map(AnnouncementService::toDto).toList();
    }

    public void delete(User user, Long id) {
        Announcement a = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Announcement not found."));
        if (!canManage(a, user)) throw new AccessDeniedException("You cannot delete this announcement.");
        announcementRepository.delete(a);
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    private static boolean canManage(Announcement a, User user) {
        if (user.getRole() == Role.SUPER_ADMIN) return true;
        if (user.getRole() != Role.ADMIN) return false;
        if (isAuthor(a, user)) return true;
        return a.getDepartment() != null && user.getDepartment() != null
                && a.getDepartment().getId().equals(user.getDepartment().getId());
    }

    private static boolean isAuthor(Announcement a, User u) {
        return a.getAuthor() != null && a.getAuthor().getId().equals(u.getId());
    }

    private static boolean isExpired(Announcement a) {
        return a.getExpiresOn() != null && a.getExpiresOn().isBefore(LocalDate.now());
    }

    static boolean isVisibleTo(Announcement a, User u) {
        Role role = u.getRole();
        if (role == null || role == Role.SUPER_ADMIN) return role == Role.SUPER_ADMIN;
        boolean roleOk = switch (a.getAudience()) {
            case ALL -> true;
            case STUDENTS -> role == Role.NORMAL;
            case LECTURERS -> role == Role.LECTURER;
            case ADMINS -> role == Role.ADMIN;
            case STAFF -> role == Role.LECTURER || role == Role.ADMIN;
        };
        if (!roleOk) return false;
        if (a.getDepartment() != null && !NotificationService.inDepartment(u, a.getDepartment())) return false;
        if (role == Role.NORMAL) {
            if (a.getProgram() != null && (u.getProgram() == null || !a.getProgram().getId().equals(u.getProgram().getId())))
                return false;
            if (a.getLevel() != null && !a.getLevel().equals(u.getCurrentLevel())) return false;
        }
        return true;
    }

    private static String preview(String body) {
        String plain = body.replaceAll("\\s+", " ").trim();
        return plain.length() > 160 ? plain.substring(0, 157) + "…" : plain;
    }

    private static Map<String, Object> toDto(Announcement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("body", a.getBody());
        m.put("audience", a.getAudience().name());
        m.put("departmentId", a.getDepartment() != null ? a.getDepartment().getId() : null);
        m.put("departmentName", a.getDepartment() != null ? a.getDepartment().getName() : null);
        m.put("programId", a.getProgram() != null ? a.getProgram().getId() : null);
        m.put("programName", a.getProgram() != null ? a.getProgram().getName() : null);
        m.put("level", a.getLevel());
        m.put("pinned", a.isPinned());
        m.put("expiresOn", a.getExpiresOn());
        m.put("createdAt", a.getCreatedAt());
        m.put("authorName", CurrentUserService.displayName(a.getAuthor()));
        m.put("authorRole", a.getAuthor() != null && a.getAuthor().getRole() != null ? a.getAuthor().getRole().name() : null);
        return m;
    }
}
