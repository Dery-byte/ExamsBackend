package com.exam.service.monitoring;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.monitoring.DeveloperEmail;
import com.exam.repository.DeveloperEmailRepository;
import com.exam.repository.UserRepository;
import com.exam.service.AuthenticationService;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Developer sign-in. Who is a developer is decided only by the {@code developer_email} table, which is
 * edited directly in the database; nothing in the application can add or remove developers.
 * A developer signs in without a password: a 6-digit code is emailed to their address (10 minutes,
 * 5 tries). Their user account (role DEVELOPER) is created the first time they sign in.
 * The same addresses receive error alerts.
 */
@Slf4j
@Service
public class DeveloperAuthService {

    private static final long CODE_TTL_SECONDS = 10 * 60;
    private static final long RESEND_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(byte[] hash, Instant expires, Instant sentAt, int attempts) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Autowired private UserRepository userRepository;
    @Autowired private DeveloperEmailRepository developerEmailRepository;
    @Autowired private AuthenticationService authenticationService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired(required = false) private JavaMailSender mailSender;

    /** Local development without email: print codes to the server log. Never enable in production. */
    @Value("${app.developer.log-codes:false}") private boolean logCodes;
    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;
    @Value("${app.mail.from-name:}") private String fromName;

    /** Addresses in the developer_email table (sign-in and error alerts). */
    public Set<String> developerEmails() {
        Set<String> out = new LinkedHashSet<>();
        for (DeveloperEmail d : developerEmailRepository.findAll())
            if (d.getEmail() != null && !d.getEmail().isBlank()) out.add(normalise(d.getEmail()));
        return out;
    }

    public boolean isDeveloper(String email) {
        return email != null && developerEmails().contains(normalise(email));
    }

    /** For the dashboard: the table's rows (read-only). */
    public List<DeveloperEmail> developerList() {
        List<DeveloperEmail> list = new ArrayList<>(developerEmailRepository.findAll());
        list.sort(Comparator.comparing(d -> normalise(d.getEmail())));
        return list;
    }

    /** Sends a code if the email belongs to a developer. Callers always show the same reply. */
    public void requestCode(String rawEmail) {
        String email = normalise(rawEmail);
        if (email.isEmpty() || !developerEmails().contains(email)) return;
        Pending prev = pending.get(email);
        if (prev != null && prev.sentAt().plusSeconds(RESEND_AFTER_SECONDS).isAfter(Instant.now())) return;

        String code = issue(email);
        if (logCodes) log.warn("[DEV-LOGIN] Code for {}: {} (app.developer.log-codes=true)", email, code);
        CompletableFuture.runAsync(() -> sendCode(email, code));
    }

    /** Creates and remembers a new code for an (already checked) developer email. */
    String issue(String email) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        pending.put(email, new Pending(hash(code), Instant.now().plusSeconds(CODE_TTL_SECONDS), Instant.now(), 0));
        return code;
    }

    /** Returns a session token when the code is right; otherwise throws with a generic message. */
    public String verify(String rawEmail, String rawCode) {
        String email = normalise(rawEmail);
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        Pending p = pending.get(email);
        IllegalArgumentException wrong = new IllegalArgumentException("That code is wrong or has expired. Request a new one.");
        if (p == null || !developerEmails().contains(email)) throw wrong;
        if (Instant.now().isAfter(p.expires()) || p.attempts() >= MAX_ATTEMPTS) {
            pending.remove(email);
            throw wrong;
        }
        if (!MessageDigest.isEqual(p.hash(), hash(code))) {
            pending.put(email, new Pending(p.hash(), p.expires(), p.sentAt(), p.attempts() + 1));
            throw wrong;
        }
        pending.remove(email);
        return authenticationService.startSession(developerAccount(email));
    }

    /** The user account a listed developer signs in with; created the first time. */
    private User developerAccount(String email) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.getRole() != Role.DEVELOPER)
                throw new IllegalArgumentException("This email belongs to a " + u.getRole().name().toLowerCase()
                        + " account, so it can't be used as a developer address.");
            if (!u.isEnabled()) { u.setEnabled(true); u = userRepository.save(u); }
            return u;
        }
        String local = email.substring(0, email.indexOf('@'));
        String name = developerEmailRepository.findAll().stream()
                .filter(d -> email.equals(normalise(d.getEmail())) && d.getName() != null && !d.getName().isBlank())
                .map(d -> d.getName().trim()).findFirst().orElse(null);
        User u = new User();
        u.setEmail(email);
        u.setUsername(uniqueUsername("dev-" + local.replaceAll("[^a-z0-9._-]", "")));
        u.setFirstname(name != null ? name : "Developer");
        u.setLastname(name != null ? "(developer)" : local);
        // Never used: developers sign in with emailed codes only
        u.setPassword(passwordEncoder.encode(UUID.randomUUID() + "" + UUID.randomUUID()));
        u.setRole(Role.DEVELOPER);
        u.setEnabled(true);
        return userRepository.save(u);
    }

    private String uniqueUsername(String base) {
        String name = base;
        for (int i = 2; userRepository.findByUsername(name).isPresent(); i++) name = base + i;
        return name;
    }

    private void sendCode(String email, String code) {
        if (mailSender == null) { log.error("[DEV-LOGIN] No mail sender configured; cannot send the code."); return; }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress, fromName == null || fromName.isBlank() ? "Exam Portal" : fromName);
            helper.setTo(email);
            helper.setSubject("Your developer sign-in code: " + code);
            helper.setText("<p>Your developer sign-in code is</p>"
                    + "<p style=\"font-size:26px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                    + "<p>It expires in 10 minutes. If you didn't ask for it, ignore this email.</p>", true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("[DEV-LOGIN] Could not send the code to {}: {}", email, e.getMessage());
        }
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
