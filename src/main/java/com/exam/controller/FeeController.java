package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.FeePaymentService;
import com.exam.service.fees.FeeScheduleService;
import com.exam.service.fees.FinanceAccess;
import com.exam.service.fees.FinanceDashboardService;
import com.exam.service.fees.ResultsHoldService;
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
 *   <li>/api/v1/super-admin/fees/** — the Super Admin, and the same under /api/finance/fees/** for the
 *       accounts office: the bursar sets fees (whole year or per term), fee switches and results holds and
 *       voids payments; a cashier reads them and records payments.</li>
 *   <li>/api/fees/** — students: their fees, balance and history; paying online through Paystack.</li>
 *   <li>/api/payments/paystack/webhook — public, checked by Paystack's signature.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*")
public class FeeController {

    private static final String SA = "/api/v1/super-admin/fees";
    private static final String FIN = "/api/finance/fees";

    @Autowired private FeeScheduleService scheduleService;
    @Autowired private FeePaymentService paymentService;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private ResultsHoldService resultsHoldService;
    @Autowired private FinanceDashboardService dashboardService;

    // ── Accounts office dashboard ────────────────────────────────────────

    @GetMapping({SA + "/dashboard", FIN + "/dashboard"})
    public Map<String, Object> dashboard() {
        return dashboardService.dashboard(currentUser());
    }

    // ── Fee schedules ────────────────────────────────────────────────────

    @GetMapping({SA + "/overview", FIN + "/overview"})
    public Map<String, Object> overview(@RequestParam(required = false) Long sessionId, @RequestParam(required = false) Integer term) {
        FinanceAccess.requireFinance(currentUser());
        return scheduleService.overview(sessionId, term == null || term == 0 ? null : term);
    }

    @PutMapping({SA + "/schedules", FIN + "/schedules"})
    public List<Map<String, Object>> saveSchedule(@RequestBody FeeScheduleService.ScheduleRequest body, HttpServletRequest request) {
        FinanceAccess.requireBursar(currentUser());
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "programme " + body.programId() + ", level " + body.level()
                + (body.term() != null && body.term() > 0 ? ", term " + body.term() : "")
                + (body.alsoApplyToLevels() != null && !body.alsoApplyToLevels().isEmpty() ? " (+ " + body.alsoApplyToLevels() + ")" : ""));
        return scheduleService.save(body, actorName());
    }

    @DeleteMapping({SA + "/schedules/{id}", FIN + "/schedules/{id}"})
    public Map<String, Object> deleteSchedule(@PathVariable Long id) {
        FinanceAccess.requireBursar(currentUser());
        scheduleService.delete(id);
        return Map.of("message", "Fee removed.");
    }

    public record CopyRequest(Long fromSessionId, Long toSessionId, Boolean overwrite) {}

    @PostMapping({SA + "/schedules/copy", FIN + "/schedules/copy"})
    public Map<String, Object> copySchedules(@RequestBody CopyRequest body) {
        FinanceAccess.requireBursar(currentUser());
        return scheduleService.copy(body.fromSessionId(), body.toSessionId(), Boolean.TRUE.equals(body.overwrite()), actorName());
    }

    // ── Fee switches (students see fees, online / part / item payment, results hold) ──

    @GetMapping({SA + "/settings", FIN + "/settings"})
    public Map<String, Object> settings() {
        FinanceAccess.requireFinance(currentUser());
        return Map.of("settings", scheduleService.feeSettings(), "paystack", scheduleService.paystackStatus());
    }

    @PutMapping({SA + "/settings", FIN + "/settings"})
    public Map<String, Object> saveSettings(@RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        FinanceAccess.requireBursar(currentUser());
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, String.valueOf(body));
        return Map.of("settings", scheduleService.saveFeeSettings(body), "paystack", scheduleService.paystackStatus());
    }

    // ── Holding results for unpaid fees ─────────────────────────────────

    @GetMapping({SA + "/results-holds", FIN + "/results-holds"})
    public Map<String, Object> resultsHolds() {
        FinanceAccess.requireFinance(currentUser());
        return resultsHoldService.overview();
    }

    @PutMapping({SA + "/results-holds", FIN + "/results-holds"})
    public Map<String, Object> saveResultsHold(@RequestBody ResultsHoldService.HoldRequest body, HttpServletRequest request) {
        FinanceAccess.requireBursar(currentUser());
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "programme " + body.programId() + ": " + body.mode()
                + (body.minPercent() != null ? " " + body.minPercent() + "%" : "")
                + (body.alsoApplyToPrograms() != null && !body.alsoApplyToPrograms().isEmpty() ? " (+ " + body.alsoApplyToPrograms() + ")" : ""));
        return resultsHoldService.save(body, actorName());
    }

    @DeleteMapping({SA + "/results-holds/{programId}", FIN + "/results-holds/{programId}"})
    public Map<String, Object> removeResultsHold(@PathVariable Long programId) {
        FinanceAccess.requireBursar(currentUser());
        resultsHoldService.remove(programId);
        return Map.of("message", "Hold lifted.");
    }

    // ── Payments ────────────────────────────────────────────────────────

    @GetMapping({SA + "/payments", FIN + "/payments"})
    public Map<String, Object> payments(@RequestParam(required = false) Long sessionId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long programId,
                                        @RequestParam(required = false) Integer level,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        FinanceAccess.requireFinance(currentUser());
        return paymentService.search(sessionId, status, programId, level, q, page, size);
    }

    @GetMapping({SA + "/students", FIN + "/students"})
    public List<Map<String, Object>> students(@RequestParam(required = false) String q) {
        FinanceAccess.requireFinance(currentUser());
        return paymentService.searchStudents(q);
    }

    @PostMapping({SA + "/payments/manual", FIN + "/payments/manual"})
    public Map<String, Object> recordManual(@RequestBody FeePaymentService.ManualPaymentRequest body, HttpServletRequest request) {
        FinanceAccess.requireFinance(currentUser());
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "student " + body.studentId() + ", " + body.amount() + " by " + body.method());
        return paymentService.recordManual(body, currentUser());
    }

    public record VoidRequest(String reason) {}

    @PostMapping({SA + "/payments/{id}/void", FIN + "/payments/{id}/void"})
    public Map<String, Object> voidPayment(@PathVariable Long id, @RequestBody VoidRequest body, HttpServletRequest request) {
        User me = currentUser();
        FinanceAccess.requireFinance(me);      // a cashier only their own, until handed in; checked inside
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "payment " + id + ": " + body.reason());
        return paymentService.voidManual(id, body.reason(), me);
    }

    @PostMapping({SA + "/payments/{id}/recheck", FIN + "/payments/{id}/recheck"})
    public Map<String, Object> recheck(@PathVariable Long id) {
        FinanceAccess.requireFinance(currentUser());
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
