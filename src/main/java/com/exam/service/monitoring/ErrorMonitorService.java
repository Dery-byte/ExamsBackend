package com.exam.service.monitoring;

import com.exam.model.monitoring.ErrorEvent;
import com.exam.repository.ErrorEventRepository;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Records failures so they show up on the developer dashboard (and in the developer's inbox)
 * without anyone having to report them. Never throws: monitoring must not break the request.
 * <p>
 * Alerts go out for a new kind of error, one that comes back after being marked resolved, and at
 * most once an hour for an error that keeps happening.
 */
@Slf4j
@Service
public class ErrorMonitorService {

    /** Request attribute set once a failure has been recorded, so it isn't counted twice. */
    public static final String RECORDED = ErrorMonitorService.class.getName() + ".recorded";

    private static final Duration ALERT_EVERY = Duration.ofHours(1);

    @Autowired private ErrorEventRepository repository;
    @Autowired private DeveloperAuthService developerAuthService;
    @Autowired(required = false) private JavaMailSender mailSender;
    @Autowired private PlatformTransactionManager transactionManager;

    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;
    @Value("${app.mail.from-name:}") private String fromName;
    @Value("${app.monitoring.email-alerts:true}") private boolean emailAlerts;

    /** A request that ended in a server error. */
    public void recordRequest(HttpServletRequest request, int status, Throwable ex) {
        if (request != null) {
            if (request.getAttribute(RECORDED) != null) return;
            request.setAttribute(RECORDED, Boolean.TRUE);
        }
        if (ex != null && isClientDisconnect(ex)) return;
        String location = request == null ? "?" : request.getMethod() + " " + normalisePath(request.getRequestURI());
        String message = ex != null ? rootMessage(ex) : "The server answered HTTP " + status + " (the error was handled inside the controller).";
        save(ErrorEvent.Source.SERVER, location, status, ex, message, null, currentUser());
    }

    /** A scheduled job or other background work that failed. */
    public void recordBackground(String job, Throwable ex) {
        save(ErrorEvent.Source.BACKGROUND, job, null, ex, ex == null ? job + " failed" : rootMessage(ex), null, null);
    }

    /** A crash reported by a user's browser. */
    public void recordBrowser(String page, String message, String stack, String username) {
        save(ErrorEvent.Source.BROWSER, cut(normalisePath(page), 300), null, null,
                message == null || message.isBlank() ? "Unknown browser error" : message, stack, username);
    }

    private void save(ErrorEvent.Source source, String location, Integer status, Throwable ex,
                      String message, String clientStack, String user) {
        try {
            String type = ex != null ? root(ex).getClass().getName() : (source == ErrorEvent.Source.BROWSER ? "BrowserError" : "HTTP " + status);
            String stack = ex != null ? stackOf(ex) : clientStack;
            String fingerprint = sha256(source + "|" + location + "|" + type + "|"
                    + (ex != null ? firstAppFrame(ex) : firstLine(message)));

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            ErrorEvent saved = tx.execute(s -> {
                Instant now = Instant.now();
                ErrorEvent e = repository.findByFingerprint(fingerprint).orElse(null);
                boolean alert;
                if (e == null) {
                    e = new ErrorEvent();
                    e.setFingerprint(fingerprint);
                    e.setSource(source);
                    e.setLocation(cut(location, 300));
                    e.setExceptionType(cut(type, 200));
                    e.setFirstSeen(now);
                    alert = true;
                } else {
                    alert = e.isResolved()
                            || e.getLastAlertAt() == null
                            || e.getLastAlertAt().plus(ALERT_EVERY).isBefore(now);
                }
                e.setResolved(false);
                e.setResolvedAt(null);
                e.setHttpStatus(status);
                e.setMessage(cut(message, 1000));
                e.setStack(cut(stack, 8000));
                e.setLastUser(cut(user, 80));
                e.setOccurrences(e.getOccurrences() + 1);
                e.setLastSeen(now);
                if (alert) e.setLastAlertAt(now);
                ErrorEvent out = repository.save(e);
                return alert ? out : null;
            });
            log.error("[MONITOR] {} {} — {}", source, location, message, ex);
            if (saved != null) sendAlertAsync(saved);
        } catch (Exception failure) {
            // Two identical errors at once can race on the unique fingerprint; the other one was counted
            log.warn("[MONITOR] Could not record an error: {}", failure.getMessage());
        }
    }

    // ── Dashboard ───────────────────────────────────────────────────────

    public List<ErrorEvent> list(String filter) {
        PageRequest page = PageRequest.of(0, 200);
        return switch (filter == null ? "open" : filter) {
            case "resolved" -> repository.findByResolvedOrderByLastSeenDesc(true, page);
            case "all" -> repository.findAllByOrderByLastSeenDesc(page);
            default -> repository.findByResolvedOrderByLastSeenDesc(false, page);
        };
    }

    public ErrorEvent setResolved(Long id, boolean resolved) {
        ErrorEvent e = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Error not found."));
        e.setResolved(resolved);
        e.setResolvedAt(resolved ? Instant.now() : null);
        return repository.save(e);
    }

    public long clearResolved() { return repository.deleteByResolvedTrue(); }

    public Map<String, Object> summary() {
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", repository.countByResolvedFalse());
        m.put("openLastHour", repository.countByResolvedFalseAndLastSeenAfter(now.minus(Duration.ofHours(1))));
        m.put("seenLast24h", repository.countByLastSeenAfter(now.minus(Duration.ofHours(24))));
        m.put("alertRecipients", developerAuthService.developerEmails());
        m.put("emailAlerts", emailAlerts && mailSender != null);
        return m;
    }

    /** Sends a sample alert so the developer can check emails arrive. */
    public void sendTestAlert() {
        ErrorEvent e = new ErrorEvent();
        e.setSource(ErrorEvent.Source.SERVER);
        e.setLocation("Test alert from the developer dashboard");
        e.setExceptionType("None");
        e.setMessage("This is a test. Real alerts look like this one.");
        e.setOccurrences(1);
        e.setFirstSeen(Instant.now());
        e.setLastSeen(Instant.now());
        sendAlertAsync(e);
    }

    // ── Alerts ──────────────────────────────────────────────────────────

    private void sendAlertAsync(ErrorEvent e) {
        if (!emailAlerts || mailSender == null) return;
        Set<String> to = developerAuthService.developerEmails();
        if (to.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setFrom(fromAddress, fromName == null || fromName.isBlank() ? "Exam Portal" : fromName);
                helper.setTo(to.toArray(new String[0]));
                helper.setSubject("[Exam portal] " + (e.getOccurrences() > 1 ? "Error again (" + e.getOccurrences() + "×): " : "New error: ")
                        + cut(e.getLocation(), 90));
                helper.setText("<p><b>" + HtmlUtils.htmlEscape(String.valueOf(e.getSource())) + "</b> · "
                        + HtmlUtils.htmlEscape(String.valueOf(e.getLocation())) + "</p>"
                        + "<p>" + HtmlUtils.htmlEscape(String.valueOf(e.getMessage())) + "</p>"
                        + "<p style=\"color:#666\">Type: " + HtmlUtils.htmlEscape(String.valueOf(e.getExceptionType()))
                        + "<br>Seen " + e.getOccurrences() + " time(s); last at " + e.getLastSeen()
                        + (e.getLastUser() != null ? "<br>Last user: " + HtmlUtils.htmlEscape(e.getLastUser()) : "") + "</p>"
                        + (e.getStack() != null ? "<pre style=\"font-size:11px;background:#f6f6f6;padding:8px\">"
                            + HtmlUtils.htmlEscape(cut(e.getStack(), 2500)) + "</pre>" : "")
                        + "<p>Open the developer dashboard to see all errors and mark this one resolved.</p>", true);
                mailSender.send(message);
            } catch (Exception ex) {
                log.warn("[MONITOR] Could not email the alert: {}", ex.getMessage());
            }
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** /api/remarks/42/respond → /api/remarks/{id}/respond, so the same failure groups together. */
    static String normalisePath(String path) {
        if (path == null) return "";
        String p = path.split("[?#]")[0];
        return p.replaceAll("/\\d+(?=/|$)", "/{id}")
                .replaceAll("/[0-9a-fA-F-]{32,36}(?=/|$)", "/{uuid}");
    }

    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            String m = String.valueOf(t.getMessage());
            if (n.contains("ClientAbort") || n.contains("AsyncRequestNotUsable") || m.contains("Broken pipe")
                    || m.contains("Connection reset by peer")) return true;
        }
        return false;
    }

    private static Throwable root(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private static String rootMessage(Throwable ex) {
        Throwable r = root(ex);
        String m = r.getMessage() != null ? r.getMessage() : ex.getMessage();
        return r.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    private static String firstAppFrame(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            for (StackTraceElement f : t.getStackTrace())
                if (f.getClassName().startsWith("com.exam.")) return f.getClassName() + "." + f.getMethodName();
        }
        return "";
    }

    private static String stackOf(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || "anonymousUser".equals(a.getName()) ? null : a.getName();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).replaceAll("\\d+", "#");
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
