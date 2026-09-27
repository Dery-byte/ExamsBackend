package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deactivating accounts instead of deleting them: a deactivated user can't sign in and their
 * open sessions are revoked, but their results, marks and history stay intact.
 * <p>
 * Super Admin: any student, lecturer or HOD (never themselves or another Super Admin).
 * HOD: students and lecturers in their own department.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final String MIGRATION_KEY = "ACCOUNT_STATUS_ENFORCED";

    @Autowired private UserRepository userRepository;
    @Autowired private TokenRepository tokenRepository;
    @Autowired private SystemSettingService systemSettingService;
    @Autowired private ReportRepository reportRepository;
    @Autowired private StudentCourseMarkRepository studentCourseMarkRepository;
    @Autowired private Registered_coursesRepository registeredCoursesRepository;
    @Autowired private CategoryRepository categoryRepository;

    /** One-time: before this feature 'enabled' was ignored, so accounts stored as disabled are re-enabled. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateOnce() {
        try {
            if ("true".equals(systemSettingService.getSetting(MIGRATION_KEY))) return;
            int n = userRepository.enableAllNeverDeactivated();
            systemSettingService.updateSetting(MIGRATION_KEY, "true");
            log.info("[Accounts] Account status is now enforced; {} existing account(s) marked active.", n);
        } catch (Exception e) {
            log.error("[Accounts] Status migration failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> deactivate(User actor, Long userId, String reason) {
        User target = manageable(actor, userId);
        if (!target.isEnabled()) throw new IllegalArgumentException("This account is already deactivated.");
        target.setEnabled(false);
        target.setDeactivatedAt(LocalDateTime.now());
        target.setDeactivationReason(reason == null || reason.isBlank() ? null : reason.trim().substring(0, Math.min(reason.trim().length(), 300)));
        userRepository.save(target);
        revokeSessions(target);
        return status(target);
    }

    @Transactional
    public Map<String, Object> reactivate(User actor, Long userId) {
        User target = manageable(actor, userId);
        if (target.isEnabled()) throw new IllegalArgumentException("This account is already active.");
        target.setEnabled(true);
        target.setDeactivatedAt(null);
        target.setDeactivationReason(null);
        userRepository.save(target);
        return status(target);
    }

    /**
     * What deleting this user would destroy (a User row cascades to its reports, quizzes and
     * courses), or null if nothing. Such accounts must be deactivated instead.
     */
    @Transactional(readOnly = true)
    public String historyBlockingDelete(Long userId) {
        User u = userRepository.findById(userId).orElse(null);
        if (u == null) return null;
        if (!reportRepository.findByUser_Id(userId).isEmpty()) return "quiz results";
        if (!studentCourseMarkRepository.findByStudent_Id(userId).isEmpty()) return "marks-sheet results";
        if (!registeredCoursesRepository.findRegistrationsByUserId(userId).isEmpty()) return "course enrolments";
        if (!u.getQuizzes().isEmpty()) return "quizzes they created";
        if (!categoryRepository.findByUser_Id(userId).isEmpty()) return "courses assigned to them";
        return null;
    }

    private void revokeSessions(User u) {
        var tokens = tokenRepository.findAllValidTokenByUser(Math.toIntExact(u.getId()));
        tokens.forEach(t -> { t.setExpired(true); t.setRevoked(true); });
        tokenRepository.saveAll(tokens);
    }

    private User manageable(User actor, Long userId) {
        User target = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (target.getId().equals(actor.getId())) throw new IllegalArgumentException("You can't change your own account status.");
        if (target.getRole() == Role.SUPER_ADMIN) throw new AccessDeniedException("Super Admin accounts can't be deactivated here.");
        boolean ok = switch (actor.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> (target.getRole() == Role.NORMAL || target.getRole() == Role.LECTURER)
                    && NotificationService.inDepartment(target, actor.getDepartment());
            default -> false;
        };
        if (!ok) throw new AccessDeniedException("You can't manage this account.");
        return target;
    }

    public static Map<String, Object> status(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("enabled", u.isEnabled());
        m.put("deactivatedAt", u.getDeactivatedAt());
        m.put("deactivationReason", u.getDeactivationReason());
        return m;
    }
}
