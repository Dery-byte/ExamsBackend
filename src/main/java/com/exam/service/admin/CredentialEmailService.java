package com.exam.service.admin;

import com.exam.service.Impl.EmailService;
import com.exam.service.Impl.EmailTemplateName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Emails newly imported users their username and temporary password.
 * <p>
 * Sends run one at a time on a single background thread: an import can create up to
 * {@link ImportService#MAX_ROWS} accounts, which must not hold up the admin's request or
 * burst the SMTP relay. A failed send is logged and skipped, never thrown.
 */
@Service
public class CredentialEmailService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEmailService.class);

    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "credential-mail");
        t.setDaemon(true);
        return t;
    });

    @Autowired private EmailService emailService;

    @Value("${application.mailing.frontend.baseUrl:http://localhost:4200}")
    private String frontendUrl;

    /**
     * Queues one email per credential (keys: firstname or name, username, email, password).
     * Entries without an email or password are skipped. Returns how many were queued.
     */
    public int queue(List<Map<String, String>> credentials, String roleLabel) {
        int queued = 0;
        for (Map<String, String> c : credentials) {
            String to = c.get("email");
            if (to == null || to.isBlank() || c.get("password") == null) continue;
            Map<String, Object> vars = new HashMap<>();
            vars.put("name", c.get("firstname") != null ? c.get("firstname") : c.get("name"));
            vars.put("username", c.get("username"));
            vars.put("password", c.get("password"));
            vars.put("roleLabel", roleLabel);
            vars.put("loginUrl", frontendUrl + "/login");
            sender.execute(() -> send(to, vars));
            queued++;
        }
        return queued;
    }

    private void send(String to, Map<String, Object> vars) {
        try {
            emailService.sendEmail(to, EmailTemplateName.ACCOUNT_CREDENTIALS, vars, "Your account login details");
            log.info("[CREDENTIAL-MAIL] Login details sent to {}", to);
        } catch (Exception e) {
            log.error("[CREDENTIAL-MAIL] Failed to send login details to {}", to, e);
        }
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}
