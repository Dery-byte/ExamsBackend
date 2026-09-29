package com.exam.controller;

import com.exam.config.RateLimiter;
import com.exam.model.academic.SystemMode;
import com.exam.model.monitoring.ErrorEvent;
import com.exam.service.academic.InstitutionService;
import com.exam.service.monitoring.DeveloperAuthService;
import com.exam.service.monitoring.ErrorMonitorService;
import com.exam.service.monitoring.HealthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The developer: signs in with an emailed code, chooses the system mode, and watches health and errors.
 * Also the public status check and the endpoint browsers report crashes to.
 */
@RestController
@CrossOrigin(origins = "*")
public class DeveloperController {

    @Autowired private DeveloperAuthService developerAuthService;
    @Autowired private InstitutionService institutionService;
    @Autowired private HealthService healthService;
    @Autowired private ErrorMonitorService errorMonitor;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private com.exam.service.comms.CurrentUserService currentUserService;

    // ── Sign-in (public, rate-limited) ──────────────────────────────────

    @PostMapping("/api/v1/auth/developer/request-code")
    public Map<String, Object> requestCode(@RequestBody Map<String, String> body) {
        developerAuthService.requestCode(body.get("email"));
        // Same answer whether or not the address is a developer's, so it can't be used to find them
        return Map.of("message", "If this email belongs to a developer, a 6-digit code is on its way. It expires in 10 minutes.");
    }

    @PostMapping("/api/v1/auth/developer/verify")
    public Map<String, Object> verify(@RequestBody Map<String, String> body) {
        return Map.of("token", developerAuthService.verify(body.get("email"), body.get("code")));
    }

    // ── Developers (read-only: rows are added/removed directly in the developer_email table) ──

    @GetMapping("/api/v1/developer/developers")
    public List<Map<String, Object>> developers() {
        String me = currentUserService.current().map(com.exam.model.User::getEmail).orElse("");
        return developerAuthService.developerList().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", d.getEmail());
            m.put("name", d.getName());
            m.put("you", d.getEmail() != null && d.getEmail().trim().equalsIgnoreCase(me));
            return m;
        }).toList();
    }

    // ── System mode ─────────────────────────────────────────────────────

    @GetMapping("/api/v1/developer/mode")
    public Map<String, Object> mode() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (SystemMode m : SystemMode.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", m.name());
            o.put("label", m.label());
            o.put("periodsPerLevel", m.defaultPeriodsPerLevel());
            o.put("terms", m.terms());
            options.add(o);
        }
        return Map.of("mode", institutionService.mode().name(), "options", options);
    }

    @PutMapping("/api/v1/developer/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, String> body, HttpServletRequest request) {
        SystemMode mode = SystemMode.parse(body.get("mode"));
        if (mode == null) throw new IllegalArgumentException("Unknown system mode.");
        request.setAttribute(com.exam.config.AuditInterceptor.AUDIT_DETAILS, "mode = " + mode.name());
        institutionService.setMode(mode);
        return mode();
    }

    // ── Health and errors ───────────────────────────────────────────────

    @GetMapping("/api/v1/auth/status")
    public Map<String, Object> publicStatus() {
        return healthService.publicStatus();
    }

    @GetMapping("/api/v1/developer/health")
    public Map<String, Object> health() {
        return healthService.details();
    }

    @GetMapping("/api/v1/developer/errors")
    public List<Map<String, Object>> errors(@RequestParam(defaultValue = "open") String filter) {
        return errorMonitor.list(filter).stream().map(DeveloperController::toDto).toList();
    }

    @PostMapping("/api/v1/developer/errors/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Boolean> body) {
        boolean resolved = body == null || !Boolean.FALSE.equals(body.get("resolved"));
        return toDto(errorMonitor.setResolved(id, resolved));
    }

    @DeleteMapping("/api/v1/developer/errors/resolved")
    public Map<String, Object> clearResolved() {
        return Map.of("deleted", errorMonitor.clearResolved());
    }

    @PostMapping("/api/v1/developer/alerts/test")
    public Map<String, Object> testAlert() {
        errorMonitor.sendTestAlert();
        return Map.of("message", "A test alert is on its way to " + String.join(", ", developerAuthService.developerEmails()) + ".");
    }

    /** Any signed-in user's browser reports crashes here (limited to 20 per user per 10 minutes). */
    @PostMapping("/api/v1/auth/client-errors")
    public ResponseEntity<?> clientError(@RequestBody Map<String, Object> body, Authentication auth) {
        String who = auth != null ? auth.getName() : "anonymous";
        if (!rateLimiter.tryAcquire("client-errors:" + who, 20, 10 * 60 * 1000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        errorMonitor.recordBrowser(Objects.toString(body.get("page"), null), Objects.toString(body.get("message"), null),
                Objects.toString(body.get("stack"), null), who);
        return ResponseEntity.accepted().build();
    }

    private static Map<String, Object> toDto(ErrorEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("source", e.getSource().name());
        m.put("location", e.getLocation());
        m.put("httpStatus", e.getHttpStatus());
        m.put("type", e.getExceptionType());
        m.put("message", e.getMessage());
        m.put("stack", e.getStack());
        m.put("lastUser", e.getLastUser());
        m.put("occurrences", e.getOccurrences());
        m.put("firstSeen", e.getFirstSeen().toString());
        m.put("lastSeen", e.getLastSeen().toString());
        m.put("resolved", e.isResolved());
        return m;
    }
}
