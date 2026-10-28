package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.BillingService;
import com.exam.service.fees.FeePaymentService;
import com.exam.service.fees.FinanceAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Price list, bills and student accounts: at the Super Admin's and the accounts office's paths (as FeeController). */
@RestController
public class BillingController {

    private static final String SA = "/api/v1/super-admin/fees";
    private static final String FIN = "/api/finance/fees";

    @Autowired private BillingService billing;
    @Autowired private FeePaymentService payments;
    @Autowired private com.exam.service.fees.ConcessionService concessions;
    @Autowired private com.exam.service.fees.RefundService refunds;
    @Autowired private CurrentUserService currentUserService;

    // ── Price list ──────────────────────────────────────────────────────

    @GetMapping({SA + "/price-items", FIN + "/price-items"})
    public Map<String, Object> priceList() {
        FinanceAccess.requireFinance(me());
        return billing.priceList();
    }

    @PostMapping({SA + "/price-items", FIN + "/price-items"})
    public Map<String, Object> addItem(@RequestBody BillingService.PriceItemRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, body.name() + " at " + body.price());
        return billing.savePriceItem(me(), null, body);
    }

    @PutMapping({SA + "/price-items/{id}", FIN + "/price-items/{id}"})
    public Map<String, Object> saveItem(@PathVariable Long id, @RequestBody BillingService.PriceItemRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, body.name() + " at " + body.price());
        return billing.savePriceItem(me(), id, body);
    }

    @PostMapping({SA + "/price-items/{id}/active", FIN + "/price-items/{id}/active"})
    public Map<String, Object> setItemActive(@PathVariable Long id, @RequestParam boolean on) {
        return billing.setPriceItemActive(me(), id, on);
    }

    // ── Bills ───────────────────────────────────────────────────────────

    @PostMapping({SA + "/bills/preview", FIN + "/bills/preview"})
    public Map<String, Object> preview(@RequestBody BillingService.BillRequest body) {
        return billing.preview(me(), body);
    }

    @PostMapping({SA + "/bills", FIN + "/bills"})
    public Map<String, Object> issue(@RequestBody BillingService.BillRequest body, HttpServletRequest request) {
        Map<String, Object> out = billing.issue(me(), body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("title") + " to " + out.get("audience") + " (" + out.get("students") + " students)");
        return out;
    }

    @GetMapping({SA + "/bills", FIN + "/bills"})
    public Map<String, Object> batches(@RequestParam(required = false) Long sessionId) {
        FinanceAccess.requireFinance(me());
        return billing.batches(sessionId);
    }

    @GetMapping({SA + "/bills/{id}", FIN + "/bills/{id}"})
    public Map<String, Object> batch(@PathVariable Long id) {
        FinanceAccess.requireFinance(me());
        return billing.batch(id);
    }

    @PostMapping({SA + "/bills/{id}/cancel", FIN + "/bills/{id}/cancel"})
    public Map<String, Object> cancelBatch(@PathVariable Long id, @RequestBody BillingService.CancelRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "bill " + id + ": " + body.reason());
        return billing.cancelBatch(me(), id, body);
    }

    @PostMapping({SA + "/student-bills/{id}/cancel", FIN + "/student-bills/{id}/cancel"})
    public Map<String, Object> cancelBill(@PathVariable Long id, @RequestBody BillingService.CancelRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "student bill " + id + ": " + body.reason());
        return billing.cancelBill(me(), id, body);
    }

    @GetMapping({SA + "/students/{id}/statement", FIN + "/students/{id}/statement"})
    public Map<String, Object> statement(@PathVariable Long id) {
        FinanceAccess.requireFinance(me());
        Map<String, Object> out = payments.staffStatement(id);
        out.put("concessions", concessions.forStudent(id));
        out.put("refunds", refunds.forStudent(id));
        return out;
    }

    // ── Student ─────────────────────────────────────────────────────────

    @PostMapping("/api/fees/bills/{id}/decline")
    public Map<String, Object> decline(@PathVariable Long id) {
        return billing.decline(me(), id);
    }

    private User me() {
        return currentUserService.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
    }
}
