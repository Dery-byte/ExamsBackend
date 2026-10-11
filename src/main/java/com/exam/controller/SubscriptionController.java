package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.subscription.SubscriptionMonitor;
import com.exam.service.subscription.SubscriptionService;
import com.exam.service.subscription.SubscriptionSync;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * The institution's subscription. The developer installs keys, suspends / resumes, extends grace and
 * chooses who sees notices (/api/v1/developer is DEVELOPER-only, see EndpointRules); every signed-in
 * user can read their own notice.
 */
@RestController
@CrossOrigin(origins = "*")
public class SubscriptionController {

    private static final String DEV = "/api/v1/developer/subscription";

    @Autowired private SubscriptionService subscription;
    @Autowired private SubscriptionMonitor monitor;
    @Autowired private SubscriptionSync sync;
    @Autowired private CurrentUserService currentUser;

    /** The banner / blocked page: what this person may do, and the details if they are in the audience. */
    @GetMapping("/api/v1/auth/subscription/notice")
    public Map<String, Object> notice() {
        return noticeFor(currentUser.current().orElse(null));
    }

    private volatile long lastPaymentCheck;

    /**
     * "I've paid, check now": checks in with the vendor portal straight away, so a payment made online
     * shows without waiting for the next scheduled check. For those who see the notice; once every 30 s.
     */
    @PostMapping("/api/v1/auth/subscription/check-payment")
    public ResponseEntity<Map<String, Object>> checkPayment() {
        User viewer = currentUser.current().orElse(null);
        if (!subscription.inAudience(viewer)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String result = null;
        long now = System.currentTimeMillis();
        if (sync.configured() && now - lastPaymentCheck > 30_000) {
            lastPaymentCheck = now;
            result = sync.sync();
        }
        Map<String, Object> out = noticeFor(viewer);
        out.put("checked", result != null);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> noticeFor(User viewer) {
        Map<String, Object> m = subscription.notice(viewer);
        if (subscription.inAudience(viewer)) m.put("canCheckPayment", sync.configured());
        return m;
    }

    // ── Developer ────────────────────────────────────────────────────────

    @GetMapping(DEV)
    public Map<String, Object> details() {
        return full();
    }

    /** Reads a key and says what it contains, without installing it. */
    @PostMapping(DEV + "/key/check")
    public Map<String, Object> checkKey(@RequestBody Map<String, String> body) {
        return subscription.checkKey(body.get("key"));
    }

    @PostMapping(DEV + "/key")
    public Map<String, Object> installKey(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> out = afterChange(() -> subscription.installKey(body.get("key"), actor()));
        audit(request, "paid until " + subscription.status().paidUntil());
        return out;
    }

    @PostMapping(DEV + "/suspend")
    public Map<String, Object> suspend(@RequestBody Map<String, String> body, HttpServletRequest request) {
        audit(request, "reason: " + body.get("reason"));
        return afterChange(() -> subscription.suspend(body.get("reason"), actor()));
    }

    @PostMapping(DEV + "/resume")
    public Map<String, Object> resume() {
        return afterChange(() -> subscription.resume(actor()));
    }

    @PutMapping(DEV + "/extension")
    public Map<String, Object> extend(@RequestBody Map<String, String> body, HttpServletRequest request) {
        LocalDate until;
        try { until = LocalDate.parse(Objects.toString(body.get("until"), "").trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Choose the last day of the extension."); }
        audit(request, "until " + until + ", reason: " + body.get("reason"));
        return afterChange(() -> subscription.extend(until, body.get("reason"), actor()));
    }

    @DeleteMapping(DEV + "/extension")
    public Map<String, Object> clearExtension() {
        return afterChange(() -> subscription.clearExtension(actor()));
    }

    @PutMapping(DEV + "/settings")
    public Map<String, Object> settings(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        audit(request, "notices for " + body.get("audience"));
        subscription.updateSettings(body, actor());
        return full();
    }

    // ── Link to the vendor portal ────────────────────────────────────────

    @PutMapping(DEV + "/vendor")
    public Map<String, Object> linkVendor(@RequestBody Map<String, String> body, HttpServletRequest request) {
        audit(request, "vendor portal " + body.get("url"));
        sync.link(body.get("url"), body.get("token"), actor());
        return full();
    }

    @DeleteMapping(DEV + "/vendor")
    public Map<String, Object> unlinkVendor() {
        sync.unlink(actor());
        return full();
    }

    /** Checks in with the vendor portal now instead of waiting for the next scheduled check. */
    @PostMapping(DEV + "/sync")
    public Map<String, Object> syncNow() {
        String result = sync.sync();
        Map<String, Object> out = full();
        out.put("syncResult", result);
        return out;
    }

    /** Records the change of state and sends its email now rather than at the next hourly check. */
    private Map<String, Object> afterChange(Runnable change) {
        change.run();
        monitor.check();
        return full();
    }

    private Map<String, Object> full() {
        Map<String, Object> m = subscription.details();
        m.put("vendor", sync.describe());
        return m;
    }

    private String actor() {
        return currentUser.current().map(CurrentUserService::displayName).map(n -> n + " (developer)").orElse("Developer");
    }

    private static void audit(HttpServletRequest request, String details) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, details);
    }
}
