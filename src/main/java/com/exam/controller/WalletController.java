package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Student wallets.
 * <ul>
 *   <li>/api/fees/wallet/** — students: their wallet, paying in through Paystack, using it on fees and
 *       bills, asking for money back.</li>
 *   <li>/api/v1/super-admin/fees/wallets/** and /api/finance/fees/wallets/** — counter deposits and the
 *       withdrawal steps (rights checked in WalletService).</li>
 * </ul>
 */
@RestController
public class WalletController {

    private static final String SA = "/api/v1/super-admin/fees/wallets";
    private static final String FIN = "/api/finance/fees/wallets";

    @Autowired private WalletService wallets;
    @Autowired private CurrentUserService currentUserService;

    // ── Students ────────────────────────────────────────────────────────

    @GetMapping("/api/fees/wallet")
    public Map<String, Object> mine() {
        return wallets.mine(me());
    }

    @PostMapping("/api/fees/wallet/deposit")
    public Map<String, Object> deposit(@RequestBody WalletService.AmountRequest body) {
        return wallets.startDeposit(me(), body);
    }

    @PostMapping("/api/fees/wallet/spend")
    public Map<String, Object> spend(@RequestBody WalletService.SpendRequest body) {
        return wallets.spend(me(), body);
    }

    @PostMapping("/api/fees/wallet/withdrawals")
    public Map<String, Object> requestWithdrawal(@RequestBody WalletService.WithdrawalRequest body) {
        return wallets.requestWithdrawal(me(), body);
    }

    @PostMapping("/api/fees/wallet/withdrawals/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        return wallets.cancel(me(), id);
    }

    // ── Accounts office and Super Admin ─────────────────────────────────

    @GetMapping({SA, FIN})
    public Map<String, Object> overview() {
        return wallets.overview(me());
    }

    @PutMapping({SA + "/settings", FIN + "/settings"})
    public Map<String, Object> settings(@RequestBody WalletService.SettingsRequest body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "wallet limit: " + (body.maxBalance() == null ? "none" : body.maxBalance()));
        return wallets.saveSettings(me(), body);
    }

    @GetMapping({SA + "/students/{id}", FIN + "/students/{id}"})
    public Map<String, Object> student(@PathVariable Long id) {
        return wallets.studentWallet(me(), id);
    }

    @PostMapping({SA + "/students/{id}/deposits", FIN + "/students/{id}/deposits"})
    public Map<String, Object> counterDeposit(@PathVariable Long id, @RequestBody WalletService.DepositRequest body, HttpServletRequest request) {
        Map<String, Object> out = wallets.deposit(me(), id, body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, out.get("receiptNo") + ": " + out.get("amount") + " into the wallet of " + out.get("studentName"));
        return out;
    }

    /** The Super Admin or the bursar pays a fee or bill from the student's wallet (when the developer allows it). */
    @PostMapping({SA + "/students/{id}/spend", FIN + "/students/{id}/spend"})
    public Map<String, Object> staffSpend(@PathVariable Long id, @RequestBody WalletService.StaffSpendRequest body, HttpServletRequest request) {
        Map<String, Object> out = wallets.staffSpend(me(), id, body);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "student " + id + ": " + (body.amount() == null ? "all it could" : body.amount())
                + (body.billId() != null ? " on bill " + body.billId() : body.scheduleId() != null ? " on fee " + body.scheduleId() : " on everything owed")
                + " · " + body.note());
        return out;
    }

    @PostMapping({SA + "/withdrawals/{id}/review", FIN + "/withdrawals/{id}/review"})
    public Map<String, Object> review(@PathVariable Long id, @RequestBody(required = false) WalletService.NoteRequest body, HttpServletRequest request) {
        return audited(request, wallets.review(me(), id, body));
    }

    @PostMapping({SA + "/withdrawals/{id}/approve", FIN + "/withdrawals/{id}/approve"})
    public Map<String, Object> approve(@PathVariable Long id, @RequestBody(required = false) WalletService.NoteRequest body, HttpServletRequest request) {
        return audited(request, wallets.approve(me(), id, body));
    }

    @PostMapping({SA + "/withdrawals/{id}/pay", FIN + "/withdrawals/{id}/pay"})
    public Map<String, Object> pay(@PathVariable Long id, @RequestBody(required = false) WalletService.PayOutRequest body, HttpServletRequest request) {
        return audited(request, wallets.payOut(me(), id, body));
    }

    @PostMapping({SA + "/withdrawals/{id}/reject", FIN + "/withdrawals/{id}/reject"})
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody WalletService.NoteRequest body, HttpServletRequest request) {
        return audited(request, wallets.reject(me(), id, body));
    }

    private static Map<String, Object> audited(HttpServletRequest request, Map<String, Object> w) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, w.get("number") + ": " + w.get("currency") + " " + w.get("amount") + " for " + w.get("studentName"));
        return w;
    }

    private User me() {
        return currentUserService.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
    }
}
