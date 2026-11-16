package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.TokenRepository;
import com.exam.repository.UserRepository;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Issues new temporary passwords to many students or lecturers at once, for when the login
 * details from an import were lost or never arrived. Each new password is returned once (for the
 * admin to download and hand out) and must be changed at the next sign-in.
 * <p>
 * Super Admin: any active student or lecturer. HOD: active students and lecturers in their own
 * department. Never Super Admins, HODs, accountants or developers, and never a deactivated account.
 * A student who started an exam in the last few hours is skipped, because a forced password change
 * would lock them out of the exam they are writing.
 */
@Service
public class BulkPasswordService {

    /** Per request: hashing is deliberately slow, so the page sends bigger selections in batches. */
    public static final int MAX_PER_REQUEST = 200;
    /** An attempt started this recently and still open counts as "writing an exam now". */
    private static final int LIVE_EXAM_HOURS = 6;
    private static final String PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private UserRepository userRepository;
    @Autowired private TokenRepository tokenRepository;
    @Autowired private QuizAttemptRepository attemptRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CredentialEmailService credentialEmailService;

    /** The accounts this admin may reset. who = students | lecturers; the other filters are optional. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> accounts(User actor, String who, Long programId, Integer level, Long departmentId) {
        requireAdmin(actor);
        Role role = role(who);
        return userRepository.findByRole(role).stream()
                .filter(User::isEnabled)
                .filter(u -> allowed(actor, u))
                .filter(u -> programId == null || (u.getProgram() != null && programId.equals(u.getProgram().getId())))
                .filter(u -> level == null || level.equals(u.getCurrentLevel()))
                .filter(u -> departmentId == null || departmentOf(u) != null && departmentId.equals(departmentOf(u).getId()))
                .sorted(Comparator.comparing((User u) -> u.getProgram() == null ? "" : String.valueOf(u.getProgram().getName()))
                        .thenComparing(u -> CurrentUserService.displayName(u).toLowerCase(Locale.ROOT)))
                .map(BulkPasswordService::row)
                .toList();
    }

    /**
     * Gives each chosen account a new temporary password. Returns the new login details
     * (credentials) and the accounts that were left alone (skipped, with the reason).
     */
    @Transactional
    public Map<String, Object> reset(User actor, List<Long> ids, boolean notify) {
        requireAdmin(actor);
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Choose at least one account.");
        List<Long> unique = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (unique.size() > MAX_PER_REQUEST)
            throw new IllegalArgumentException("Reset at most " + MAX_PER_REQUEST + " accounts at a time.");

        LocalDateTime liveSince = LocalDateTime.now().minusHours(LIVE_EXAM_HOURS);
        List<Map<String, Object>> credentials = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, String>> toEmail = new ArrayList<>();
        Map<Role, Integer> roles = new EnumMap<>(Role.class);

        for (Long id : unique) {
            User u = userRepository.findById(id).orElse(null);
            if (u == null) { skipped.add(skip(id, null, "This account no longer exists.")); continue; }
            if (u.getId().equals(actor.getId())) { skipped.add(skip(id, u, "You can't reset your own password here.")); continue; }
            if (u.getRole() != Role.NORMAL && u.getRole() != Role.LECTURER) {
                skipped.add(skip(id, u, "Only student and lecturer passwords can be reset here.")); continue;
            }
            if (!allowed(actor, u)) throw new AccessDeniedException("You can only reset passwords in your own department.");
            if (!u.isEnabled()) { skipped.add(skip(id, u, "This account is deactivated.")); continue; }
            if (u.getRole() == Role.NORMAL
                    && attemptRepository.existsByUser_IdAndStatusAndStartedAtAfter(u.getId(), AttemptStatus.IN_PROGRESS, liveSince)) {
                skipped.add(skip(id, u, "Writing an exam right now. Try again after they submit.")); continue;
            }

            String password = tempPassword();
            u.setPassword(passwordEncoder.encode(password));
            u.setMustChangePassword(Boolean.TRUE);
            userRepository.save(u);
            revokeSessions(u);
            roles.merge(u.getRole(), 1, Integer::sum);

            Map<String, Object> c = row(u);
            c.put("password", password);
            c.put("emailed", false);
            credentials.add(c);
            if (notify && u.getEmail() != null && !u.getEmail().isBlank()) {
                Map<String, String> mail = new HashMap<>();
                mail.put("firstname", u.getFirstname());
                mail.put("username", u.getUsername());
                mail.put("email", u.getEmail());
                mail.put("password", password);
                mail.put("role", u.getRole().name());
                toEmail.add(mail);
                c.put("emailed", true);
            }
        }

        int emailed = 0;
        if (!toEmail.isEmpty()) {
            List<Map<String, String>> students = toEmail.stream().filter(m -> Role.NORMAL.name().equals(m.get("role"))).toList();
            List<Map<String, String>> staff = toEmail.stream().filter(m -> Role.LECTURER.name().equals(m.get("role"))).toList();
            if (!students.isEmpty()) emailed += credentialEmailService.queue(students, "student");
            if (!staff.isEmpty()) emailed += credentialEmailService.queue(staff, "lecturer");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", credentials.size());
        out.put("emailed", emailed);
        out.put("credentials", credentials);
        out.put("skipped", skipped);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void requireAdmin(User actor) {
        if (actor == null || (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.ADMIN))
            throw new AccessDeniedException("Only the Super Admin and HODs can reset passwords.");
    }

    private static Role role(String who) {
        if (who == null || who.equalsIgnoreCase("students")) return Role.NORMAL;
        if (who.equalsIgnoreCase("lecturers")) return Role.LECTURER;
        throw new IllegalArgumentException("Choose students or lecturers.");
    }

    /** Super Admin: any student or lecturer. HOD: those in their own department. */
    private static boolean allowed(User actor, User target) {
        if (target.getRole() != Role.NORMAL && target.getRole() != Role.LECTURER) return false;
        if (actor.getRole() == Role.SUPER_ADMIN) return true;
        return actor.getRole() == Role.ADMIN && NotificationService.inDepartment(target, actor.getDepartment());
    }

    private static com.exam.model.exam.Department departmentOf(User u) {
        if (u.getDepartment() != null) return u.getDepartment();
        return u.getProgram() != null ? u.getProgram().getDepartment() : null;
    }

    private static Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", CurrentUserService.displayName(u));
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("programId", u.getProgram() == null ? null : u.getProgram().getId());
        m.put("program", u.getProgram() == null ? null : u.getProgram().getName());
        m.put("level", u.getCurrentLevel());
        var dept = departmentOf(u);
        m.put("department", dept == null ? null : dept.getName());
        // Still on a password someone else set (never signed in and changed it)
        m.put("temporaryPassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        return m;
    }

    private static Map<String, Object> skip(Long id, User u, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", u == null ? "Account " + id : CurrentUserService.displayName(u));
        m.put("username", u == null ? null : u.getUsername());
        m.put("reason", reason);
        return m;
    }

    private void revokeSessions(User u) {
        var tokens = tokenRepository.findAllValidTokenByUser(Math.toIntExact(u.getId()));
        if (tokens.isEmpty()) return;
        tokens.forEach(t -> { t.setExpired(true); t.setRevoked(true); });
        tokenRepository.saveAll(tokens);
    }

    private static String tempPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        return sb.toString();
    }
}
