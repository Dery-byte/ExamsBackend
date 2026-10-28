package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Concessions, the year end (closing sessions, arrears, opening balances), refunds and the cash book,
 * at the Super Admin's and the accounts office's paths (as FeeController). Rights are checked in the
 * services: the bursar for anything that changes what is owed or pays money back.
 */
@RestController
public class AccountsController {

    private static final String SA = "/api/v1/super-admin/fees";
    private static final String FIN = "/api/finance/fees";

    @Autowired private ConcessionService concessions;
    @Autowired private YearEndService yearEnd;
    @Autowired private RefundService refunds;
    @Autowired private CashBookService cashBook;
    @Autowired private StockService stock;
    @Autowired private ReminderService reminders;
    @Autowired private CurrentUserService currentUserService;

    // ── Concessions ─────────────────────────────────────────────────────

    @GetMapping({SA + "/concessions", FIN + "/concessions"})
    public Map<String, Object> concessions(@RequestParam(required = false) Long sessionId) {
        FinanceAccess.requireFinance(me());
        return concessions.list(sessionId);
    }

    @PostMapping({SA + "/concessions", FIN + "/concessions"})
    public Map<String, Object> grant(@RequestBody ConcessionService.ConcessionRequest body, HttpServletRequest request) {
        Map<String, Object> out = concessions.grant(me(), body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("title") + " for " + out.get("studentName") + ": " + out.get("value") + " " + out.get("mode"));
        return out;
    }

    @PostMapping({SA + "/concessions/{id}/revoke", FIN + "/concessions/{id}/revoke"})
    public Map<String, Object> revoke(@PathVariable Long id, @RequestBody ConcessionService.RevokeRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "concession " + id + ": " + body.reason());
        return concessions.revoke(me(), id, body);
    }

    // ── Year end ────────────────────────────────────────────────────────

    @GetMapping({SA + "/year-end", FIN + "/year-end"})
    public Map<String, Object> yearEnd() {
        FinanceAccess.requireFinance(me());
        return yearEnd.overview();
    }

    @PostMapping({SA + "/year-end/close", FIN + "/year-end/close"})
    public Map<String, Object> close(@RequestParam(required = false) Long sessionId, HttpServletRequest request) {
        Map<String, Object> out = yearEnd.close(me(), sessionId);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("sessionName") + ": " + out.get("students") + " students, " + out.get("total"));
        return out;
    }

    @PostMapping({SA + "/year-end/reopen", FIN + "/year-end/reopen"})
    public Map<String, Object> reopen(@RequestParam(required = false) Long sessionId) {
        return yearEnd.reopen(me(), sessionId);
    }

    @PostMapping({SA + "/opening-balances/check", FIN + "/opening-balances/check"})
    public Map<String, Object> checkOpening(@RequestBody YearEndService.OpeningRequest body) {
        return yearEnd.importOpening(me(), body.rows(), false);
    }

    @PostMapping({SA + "/opening-balances", FIN + "/opening-balances"})
    public Map<String, Object> importOpening(@RequestBody YearEndService.OpeningRequest body, HttpServletRequest request) {
        Map<String, Object> out = yearEnd.importOpening(me(), body.rows(), true);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("ok") + " opening balances, " + out.get("total"));
        return out;
    }

    public record OpeningBalance(BigDecimal amount, String note) {}

    @PutMapping({SA + "/students/{id}/opening-balance", FIN + "/students/{id}/opening-balance"})
    public Map<String, Object> setOpening(@PathVariable Long id, @RequestBody OpeningBalance body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "student " + id + ": " + body.amount());
        return yearEnd.setOpening(me(), id, body.amount(), body.note());
    }

    @PostMapping({SA + "/arrears/{id}/write-off", FIN + "/arrears/{id}/write-off"})
    public Map<String, Object> writeOff(@PathVariable Long id, @RequestBody YearEndService.ReasonRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "arrears " + id + ": " + body.reason());
        return yearEnd.writeOff(me(), id, body);
    }

    @PostMapping({SA + "/arrears/{id}/restore", FIN + "/arrears/{id}/restore"})
    public Map<String, Object> restore(@PathVariable Long id) {
        return yearEnd.restore(me(), id);
    }

    // ── Refunds ─────────────────────────────────────────────────────────

    @PostMapping({SA + "/refunds", FIN + "/refunds"})
    public Map<String, Object> refund(@RequestBody RefundService.RefundRequest body, HttpServletRequest request) {
        Map<String, Object> out = refunds.refund(me(), body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("number") + ": " + out.get("amount") + " to " + out.get("studentName"));
        return out;
    }

    @PostMapping({SA + "/refunds/{id}/void", FIN + "/refunds/{id}/void"})
    public Map<String, Object> voidRefund(@PathVariable Long id, @RequestBody YearEndService.ReasonRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "refund " + id + ": " + body.reason());
        return refunds.voidRefund(me(), id, body.reason());
    }

    // ── Cash book ───────────────────────────────────────────────────────

    @GetMapping({SA + "/cash-book", FIN + "/cash-book"})
    public Map<String, Object> cashBook(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cashBook.day(me(), date);
    }

    @PostMapping({SA + "/cash-ups", FIN + "/cash-ups"})
    public Map<String, Object> cashUp(@RequestBody CashBookService.CashUpRequest body, HttpServletRequest request) {
        Map<String, Object> out = cashBook.cashUp(me(), body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("payments") + " payments, expected " + out.get("expectedCash") + ", counted " + out.get("declaredCash"));
        return out;
    }

    @PostMapping({SA + "/cash-ups/{id}/confirm", FIN + "/cash-ups/{id}/confirm"})
    public Map<String, Object> confirm(@PathVariable Long id, @RequestBody(required = false) CashBookService.ConfirmRequest body) {
        return cashBook.confirm(me(), id, body);
    }

    // ── Stores ──────────────────────────────────────────────────────────

    @GetMapping({SA + "/stores", FIN + "/stores"})
    public Map<String, Object> stores() {
        FinanceAccess.requireFinance(me());
        return stock.stores();
    }

    @PutMapping({SA + "/stores/items/{id}", FIN + "/stores/items/{id}"})
    public Map<String, Object> setStocked(@PathVariable Long id, @RequestBody StockService.StockedRequest body) {
        return stock.setStocked(me(), id, body);
    }

    @PostMapping({SA + "/stores/items/{id}/receive", FIN + "/stores/items/{id}/receive"})
    public Map<String, Object> receive(@PathVariable Long id, @RequestBody StockService.ReceiveRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "item " + id + ": +" + body.quantity() + (body.reference() != null ? " (" + body.reference() + ")" : ""));
        return stock.receive(me(), id, body);
    }

    @PostMapping({SA + "/stores/items/{id}/adjust", FIN + "/stores/items/{id}/adjust"})
    public Map<String, Object> adjust(@PathVariable Long id, @RequestBody StockService.AdjustRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "item " + id + ": counted " + body.countedQuantity() + ", " + body.reason());
        return stock.adjust(me(), id, body);
    }

    @GetMapping({SA + "/stores/items/{id}/history", FIN + "/stores/items/{id}/history"})
    public java.util.List<Map<String, Object>> stockHistory(@PathVariable Long id) {
        FinanceAccess.requireFinance(me());
        return stock.history(id);
    }

    @GetMapping({SA + "/stores/to-hand-over", FIN + "/stores/to-hand-over"})
    public Map<String, Object> awaiting(@RequestParam(required = false) Long studentId) {
        FinanceAccess.requireFinance(me());
        return stock.awaiting(studentId);
    }

    @PostMapping({SA + "/stores/lines/{id}/hand-over", FIN + "/stores/lines/{id}/hand-over"})
    public Map<String, Object> handOver(@PathVariable Long id, @RequestBody StockService.HandOverRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "bill line " + id + ": " + body.quantity());
        return stock.handOver(me(), id, body);
    }

    @PostMapping({SA + "/stores/lines/{id}/take-back", FIN + "/stores/lines/{id}/take-back"})
    public Map<String, Object> takeBack(@PathVariable Long id, @RequestBody StockService.HandOverRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "bill line " + id + ": " + body.quantity() + ", " + body.note());
        return stock.takeBack(me(), id, body);
    }

    // ── Reminders ───────────────────────────────────────────────────────

    @GetMapping({SA + "/reminders", FIN + "/reminders"})
    public Map<String, Object> reminders() {
        return reminders.overview(me());
    }

    @PostMapping({SA + "/reminders/preview", FIN + "/reminders/preview"})
    public Map<String, Object> previewReminders(@RequestBody ReminderService.ReminderRequest body) {
        return reminders.preview(me(), body);
    }

    @PostMapping({SA + "/reminders", FIN + "/reminders"})
    public Map<String, Object> sendReminders(@RequestBody ReminderService.ReminderRequest body, HttpServletRequest request) {
        Map<String, Object> out = reminders.send(me(), body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("students") + " students, " + out.get("emails") + " emails, " + out.get("sms") + " SMS");
        return out;
    }

    @PutMapping({SA + "/reminders/automatic", FIN + "/reminders/automatic"})
    public Map<String, Object> automaticReminders(@RequestBody ReminderService.AutoRequest body) {
        return reminders.setAutomatic(me(), body);
    }

    private User me() {
        return currentUserService.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
    }
}
