package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.FeePaymentService;
import com.exam.service.fees.FeeScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Fees and payments.
 * <ul>
 *   <li>/api/v1/super-admin/fees/** — Super Admin: fee per programme + level, payments, cash / bank entries.</li>
 *   <li>/api/fees/** — students: their fee, balance and history; paying online through Paystack.</li>
 *   <li>/api/payments/paystack/webhook — public, checked by Paystack's signature.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*")
public class FeeController {

    private static final String SA = "/api/v1/super-admin/fees";

    @Autowired private FeeScheduleService scheduleService;
    @Autowired private FeePaymentService paymentService;
    @Autowired private CurrentUserService currentUserService;

    // ── Super Admin: fee schedules ──────────────────────────────────────

    @GetMapping(SA + "/overview")
    public Map<String, Object> overview(@RequestParam(required = false) Long sessionId) {
        return scheduleService.overview(sessionId);
    }

    @PutMapping(SA + "/schedules")
    public List<Map<String, Object>> saveSchedule(@RequestBody FeeScheduleService.ScheduleRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "programme " + body.programId() + ", level " + body.level()
                + (body.alsoApplyToLevels() != null && !body.alsoApplyToLevels().isEmpty() ? " (+ " + body.alsoApplyToLevels() + ")" : ""));
        return scheduleService.save(body, actorName());
    }

    @DeleteMapping(SA + "/schedules/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable Long id) {
        scheduleService.delete(id);
        return Map.of("message", "Fee removed.");
    }

    public record CopyRequest(Long fromSessionId, Long toSessionId, Boolean overwrite) {}

    @PostMapping(SA + "/schedules/copy")
    public Map<String, Object> copySchedules(@RequestBody CopyRequest body) {
        return scheduleService.copy(body.fromSessionId(), body.toSessionId(), Boolean.TRUE.equals(body.overwrite()), actorName());
    }

    // ── Super Admin: payments ───────────────────────────────────────────

    @GetMapping(SA + "/payments")
    public Map<String, Object> payments(@RequestParam(required = false) Long sessionId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long programId,
                                        @RequestParam(required = false) Integer level,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        return paymentService.search(sessionId, status, programId, level, q, page, size);
    }

    @GetMapping(SA + "/students")
    public List<Map<String, Object>> students(@RequestParam(required = false) String q) {
        return paymentService.searchStudents(q);
    }

    @PostMapping(SA + "/payments/manual")
    public Map<String, Object> recordManual(@RequestBody FeePaymentService.ManualPaymentRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "student " + body.studentId() + ", " + body.amount() + " by " + body.method());
        return paymentService.recordManual(body, actorName());
    }

    public record VoidRequest(String reason) {}

    @PostMapping(SA + "/payments/{id}/void")
    public Map<String, Object> voidPayment(@PathVariable Long id, @RequestBody VoidRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "payment " + id + ": " + body.reason());
        return paymentService.voidManual(id, body.reason(), actorName());
    }

    @PostMapping(SA + "/payments/{id}/recheck")
    public Map<String, Object> recheck(@PathVariable Long id) {
        return paymentService.recheck(id);
    }

    // ── Students ────────────────────────────────────────────────────────

    @GetMapping("/api/fees/me")
    public Map<String, Object> myFees() {
        return paymentService.statement(currentUser());
    }

    @PostMapping("/api/fees/pay")
    public Map<String, Object> pay(@RequestBody(required = false) FeePaymentService.PayRequest body) {
        return paymentService.startPayment(currentUser(), body);
    }

    @PostMapping("/api/fees/payments/{reference}/confirm")
    public Map<String, Object> confirm(@PathVariable String reference) {
        return paymentService.confirmForStudent(currentUser(), reference);
    }

    // ── Paystack ────────────────────────────────────────────────────────

    @PostMapping("/api/payments/paystack/webhook")
    public ResponseEntity<Void> paystackWebhook(@RequestBody byte[] body,
                                                @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        return paymentService.handleWebhook(body, signature)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private User currentUser() {
        return currentUserService.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
    }

    private String actorName() {
        return currentUserService.current().map(CurrentUserService::displayName).orElse("Super Admin");
    }
}
