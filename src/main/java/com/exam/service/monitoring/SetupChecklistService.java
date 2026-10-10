package com.exam.service.monitoring;

import com.exam.model.Role;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.academic.ThemeService;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * What a fresh installation still needs before the institution can use it, for the developer's
 * Setup tab. Each item says who does it (developer or Super Admin) and where.
 */
@Service
public class SetupChecklistService {

    /** When a test email last went out successfully from the Setup tab. */
    public static final String EMAIL_VERIFIED_AT = "SETUP_EMAIL_VERIFIED_AT";

    @Autowired private SystemSettingService settings;
    @Autowired private InstitutionService institution;
    @Autowired private UserRepository users;
    @Autowired private DepartmentRepository departments;
    @Autowired private ProgramRepository programs;
    @Autowired private AcademicSessionRepository sessions;
    @Autowired private GradeBandRepository gradeBands;
    @Autowired private com.exam.service.AuthenticationService authenticationService;
    @Autowired(required = false) private JavaMailSender mailSender;

    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;

    public Map<String, Object> checklist() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, String> s = settings.getAllSettings();
        boolean school = institution.isSchool();

        items.add(item("mode", "Choose the system mode", "developer", "mode",
                s.get(InstitutionService.MODE) != null,
                "Currently " + institution.mode().label() + "."));
        boolean named = notBlank(s.get(InstitutionService.NAME));
        items.add(item("name", "Enter the institution's name", "superAdmin", "Institution settings",
                named, named ? institution.name() : "The portal still shows the built-in default name."));
        boolean logo = institution.logo().isPresent();
        items.add(item("logo", "Upload the institution's logo", "superAdmin", "Institution settings",
                logo, logo ? "Used on the sign-in page, browser tab, PDFs and emails." : "Shown on the sign-in page, browser tab, PDFs and emails."));
        boolean themed = s.containsKey(ThemeService.VERSION);
        items.add(item("theme", "Set the institution's colours", "developer", "branding",
                themed, themed ? "Custom colours are applied." : "The built-in indigo theme is in use."));
        boolean portal = notBlank(s.get(InstitutionService.PORTAL_URL));
        items.add(item("portalUrl", "Set the portal web address", "superAdmin", "Institution settings",
                portal, portal ? s.get(InstitutionService.PORTAL_URL) : "Links in emails and on printed documents point to the default address."));

        long superAdmins = users.countByRole(Role.SUPER_ADMIN);
        items.add(item("superAdmin", "Create a Super Admin account", "developer", "setup",
                superAdmins > 0, superAdmins > 0 ? superAdmins + " Super Admin account(s)." : "Nobody can run the institution's settings yet."));
        String session = sessions.findFirstByCurrentTrue().map(a -> a.getName()).orElse(null);
        items.add(item("session", "Set the current academic year", "superAdmin", "Academic settings",
                session != null, session != null ? session + " is current." : "Results and reports need a current academic year."));
        long depts = departments.count();
        items.add(item("departments", school ? "Add departments" : "Add departments", "superAdmin", "Departments",
                depts > 0, depts + " department(s)."));
        long progs = programs.count();
        items.add(item("programs", school ? "Add programmes / classes" : "Add programmes", "superAdmin", "Programmes",
                progs > 0, progs + " programme(s)."));
        long bands = gradeBands.count();
        items.add(item("grading", "Review the grading scale", "superAdmin", "Academic settings",
                bands > 0, bands > 0 ? bands + " grade bands (a default scale is created automatically — check it matches the institution's)." : "No grade bands yet."));
        String verified = s.get(EMAIL_VERIFIED_AT);
        items.add(item("email", "Send a test email", "developer", "setup",
                verified != null, verified != null ? "Last sent successfully " + verified.replace('T', ' ') + "."
                        : mailSender == null ? "Email is not configured on the server (spring.mail.*)." : "Confirms password resets and result emails can be delivered."));

        long done = items.stream().filter(i -> Boolean.TRUE.equals(i.get("done"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("done", done);
        m.put("total", items.size());
        return m;
    }

    /** Sends a plain email now (not in the background) so the developer sees whether delivery works. */
    public Map<String, Object> sendTestEmail(String to) {
        if (to == null || !to.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw new IllegalArgumentException("Enter a valid email address.");
        if (mailSender == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Email is not configured on the server (spring.mail.*).");
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            h.setFrom(fromAddress, institution.name());
            h.setTo(to.trim());
            h.setSubject(institution.shortName() + " portal: test email");
            h.setText("This is a test email from the " + institution.name() + " exam portal.\n\n"
                    + "If you are reading it, the portal can send password resets, account details and results.");
            mailSender.send(msg);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The email could not be sent: " + reason);
        }
        settings.updateSetting(EMAIL_VERIFIED_AT, LocalDateTime.now().withNano(0).toString());
        return Map.of("message", "Test email sent to " + to.trim() + ".");
    }

    /**
     * The developer creates a Super Admin (the first one on a new installation, or a replacement).
     * The password is temporary: the account must choose its own at first sign-in.
     */
    public Map<String, Object> createSuperAdmin(Map<String, String> body) {
        String first = required(body, "firstname", "first name"), last = required(body, "lastname", "last name");
        String email = required(body, "email", "email address"), username = required(body, "username", "username");
        String password = body.getOrDefault("password", "");
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Enter a valid email address.");
        if (password.length() < 8) throw new IllegalArgumentException("The temporary password needs at least 8 characters.");
        com.exam.auth.RegisterRequest r = new com.exam.auth.RegisterRequest();
        r.setFirstname(first);
        r.setLastname(last);
        r.setEmail(email);
        r.setUsername(username);
        r.setPassword(password);
        r.setPhone(Objects.toString(body.get("phone"), "").trim());
        try {
            authenticationService.registerAsSuperAdmin(r);
        } catch (com.exam.helper.UserFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        users.findByUsername(username).ifPresent(u -> { u.setMustChangePassword(Boolean.TRUE); users.save(u); });
        return Map.of("message", "Super Admin " + username + " created. They must choose a new password when they first sign in.");
    }

    private static String required(Map<String, String> body, String field, String label) {
        String v = Objects.toString(body.get(field), "").trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Enter the " + label + ".");
        if (v.length() > 120) throw new IllegalArgumentException("The " + label + " is too long.");
        return v;
    }

    private static Map<String, Object> item(String key, String label, String owner, String where, boolean done, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("owner", owner);
        m.put("where", where);
        m.put("done", done);
        m.put("detail", detail);
        return m;
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }
}
