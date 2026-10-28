package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.features.Feature;
import com.exam.model.fees.*;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import com.exam.service.features.FeatureService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Student wallets: money paid in ahead (online or at the accounts office) and kept until the student
 * uses it on a fee or bill, or asks for it back. Wallet money is the unspent part of wallet deposits
 * (see {@link FeeLedger}), so the hourly sweep never spends it and it is never counted as credit.
 * <p>
 * Taking money out: the student asks → the bursar reviews → the Super Admin approves → the accounts
 * office pays it out (a refund voucher, RF-…, in the payer's cash-up). Each step is a different person;
 * the bursar or Super Admin may reject it, the student may cancel it until it is paid, and its amount is
 * held from the request on. Two exceptions for a small office: with no bursar the Super Admin reviews and
 * approves in one step; with no other accountant to pay it out, the reviewer (in a one-person office,
 * see FEES_SELF_CASHUP) or the Super Admin (with no accountant at all) may.
 * <p>
 * The student decides what their wallet pays. The developer may also let the Super Admin and/or the
 * bursar pay a student's fees and bills from it ({@link #staffSpend}: with a reason, and the student is
 * told); cashiers never may.
 * <p>
 * The wallet is switched on and off under Feature Controls ({@link Feature#STUDENT_WALLET}, off at
 * first). Off, nobody can add or spend wallet money, but a student with a balance still sees it and can
 * ask for it back.
 */
@Service
public class WalletService {

    /** The most a wallet may hold (blank or 0: no limit). */
    public static final String MAX_BALANCE = "WALLET_MAX_BALANCE";
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("1.00");
    private static final String NOTIFY_TYPE = "WALLET";
    /** What the Super Admin or a bursar is told while the developer's switch for them is off. */
    static final String NOT_ALLOWED_TO_SPEND = "Currently you are not allowed to pay from students' wallets.";

    @Autowired private UserRepository users;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private FeePaymentRepository payments;
    @Autowired private WalletSpendRepository spends;
    @Autowired private WalletWithdrawalRepository withdrawals;
    @Autowired private FeeRefundRepository refunds;
    @Autowired private FeePaymentService paymentService;
    @Autowired private FeeLedger ledger;
    @Autowired private FeeDues feeDues;
    @Autowired private ReceiptService numbers;
    @Autowired private FeeScheduleService scheduleService;
    @Autowired private SystemSettingService settings;
    @Autowired private FeatureService features;
    @Autowired private NotificationService notifications;

    public record AmountRequest(BigDecimal amount) {}
    /** scheduleId / billId: that fee (term) or bill only; neither: everything owed, oldest first. amount null: as much as the wallet and the debt allow. */
    public record SpendRequest(BigDecimal amount, Long scheduleId, Long billId) {}
    /** As SpendRequest, with the reason staff pay it from the student's wallet. */
    public record StaffSpendRequest(BigDecimal amount, Long scheduleId, Long billId, String note) {}
    public record WithdrawalRequest(BigDecimal amount, String reason, String payout, String payoutDetails) {}
    public record DepositRequest(BigDecimal amount, String method, LocalDate paidOn, String note) {}
    public record NoteRequest(String note) {}
    public record PayOutRequest(String method, String paidTo) {}
    public record SettingsRequest(BigDecimal maxBalance) {}

    /** New, and the school decides whether to offer it, so it starts switched off. */
    @PostConstruct
    void startSwitchedOff() {
        String key = Feature.STUDENT_WALLET.settingKey();
        if (settings.getSetting(key) == null) settings.updateSetting(key, "false");
    }

    // ── Student ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> mine(User principal) {
        return view(student(principal), false);
    }

    /** Opens a Paystack checkout for a deposit; Paystack sends the student back to the wallet page. */
    public Map<String, Object> startDeposit(User principal, AmountRequest req) {
        User student = student(principal);
        features.require(Feature.STUDENT_WALLET, student);
        BigDecimal amount = amount(req == null ? null : req.amount());
        checkRoom(student, amount, true);
        return paymentService.startDeposit(student, amount);
    }

    @Transactional
    public Map<String, Object> spend(User principal, SpendRequest req) {
        User student = student(principal);
        features.require(Feature.STUDENT_WALLET, student);
        if (!settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fees are not available right now.");
        pay(student, req, student, null);
        return view(student, false);
    }

    /**
     * The Super Admin or the bursar pays a student's fee or bill from the student's wallet, when the
     * developer allows it for them (cashiers never may). Same rules as the student paying, plus a reason;
     * the student is told.
     */
    @Transactional
    public Map<String, Object> staffSpend(User actor, Long studentId, StaffSpendRequest req) {
        requireStaffSpend(actor);
        if (!features.isOnSystemWide(Feature.STUDENT_WALLET))
            throw new IllegalArgumentException("The student wallet is switched off under Feature Controls.");
        String note = ConcessionService.clean(req == null ? null : req.note(), 300);
        if (note == null) throw new IllegalArgumentException("Give the reason for paying from the student's wallet.");
        User student = studentById(studentId);
        WalletSpend s = pay(student, new SpendRequest(req.amount(), req.scheduleId(), req.billId()), actor, note);
        notifications.notify(student, NOTIFY_TYPE, "Paid from your wallet",
                s.getCurrency() + " " + s.getAmount().toPlainString() + " from your wallet paid " + s.getPaidFor() + " (by " + s.getSpentBy() + ", " + s.getNumber() + ").",
                "/user-dashboard/wallet");
        Map<String, Object> out = view(student, true);
        out.put("canSpend", true);
        return out;
    }

    /** Whether this person may pay students' fees and bills from their wallets (the developer's switches). */
    public boolean canStaffSpend(User actor) {
        if (actor == null) return false;
        if (actor.getRole() == Role.SUPER_ADMIN)
            return settings.getBooleanSetting(SystemSettingService.WALLET_STAFF_SPEND_SUPER_ADMIN, false);
        return actor.getRole() == Role.ACCOUNTANT && actor.getFinanceDuty() == FinanceDuty.BURSAR
                && settings.getBooleanSetting(SystemSettingService.WALLET_STAFF_SPEND_BURSAR, false);
    }

    private void requireStaffSpend(User actor) {
        if (canStaffSpend(actor)) return;
        boolean sa = actor != null && actor.getRole() == Role.SUPER_ADMIN;
        boolean bursar = actor != null && actor.getRole() == Role.ACCOUNTANT && actor.getFinanceDuty() == FinanceDuty.BURSAR;
        throw new AccessDeniedException(sa || bursar ? NOT_ALLOWED_TO_SPEND
                : "Only the Super Admin or the bursar can pay from a student's wallet.");
    }

    /**
     * Moves wallet money to what the student owes: only the chosen fee or bill, else everything oldest
     * first; amount null = as much as the wallet and the debt allow. Records who did it (and why, for staff).
     */
    private WalletSpend pay(User student, SpendRequest req, User actor, String note) {
        if (req == null) throw new IllegalArgumentException("Choose what to pay.");
        if (req.scheduleId() != null && req.billId() != null) throw new IllegalArgumentException("Choose a fee or a bill, not both.");
        boolean self = actor.getId().equals(student.getId());
        String whose = self ? "your" : "the student's";

        ledger.lock(student);
        ledger.applyCredit(student);               // credit first, so the wallet pays only what is really owed
        FeeSchedule fee = null;
        StudentBill bill = null;
        BigDecimal owed;
        if (req.billId() != null) {
            FeeDues.BillDue b = feeDues.bills(student).stream().filter(x -> x.bill().getId().equals(req.billId()) && x.bill().isOpen()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(self ? "That bill isn't open, or isn't yours." : "That bill isn't open, or isn't this student's."));
            bill = b.bill();
            owed = b.balance();
        } else if (req.scheduleId() != null) {
            FeeDues.Due d = feeDues.target(student, req.scheduleId());
            fee = d.schedule();
            owed = d.balance();
        } else {
            owed = feeDues.account(student).toPay();
        }
        if (owed.signum() <= 0) throw new IllegalArgumentException("Nothing is owed there.");

        BigDecimal available = available(student.getId());
        if (available.signum() <= 0) throw new IllegalArgumentException("There is no money free in " + whose + " wallet.");
        BigDecimal amount = req.amount() == null ? owed.min(available) : FeeScheduleService.money(req.amount(), "The amount");
        String cur = scheduleService.currency();
        if (amount.compareTo(available) > 0)
            throw new IllegalArgumentException(Character.toUpperCase(whose.charAt(0)) + whose.substring(1) + " wallet has " + cur + " " + available.toPlainString() + " free to use.");
        if (amount.compareTo(owed) > 0)
            throw new IllegalArgumentException("That is more than the " + cur + " " + owed.toPlainString() + " owed.");
        if (amount.compareTo(owed) != 0 && !settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true))
            throw new IllegalArgumentException("Part payments are switched off: pay the full " + cur + " " + owed.toPlainString() + ".");

        Map<String, BigDecimal> paid = ledger.spendWallet(student, fee, bill, amount);
        WalletSpend s = new WalletSpend();
        s.setStudent(student);
        s.setNumber(numbers.nextNumber("WP"));
        s.setAmount(amount);
        s.setCurrency(cur);
        StringJoiner what = new StringJoiner(", ");
        paid.forEach((label, money) -> what.add(paid.size() == 1 ? label : label + " (" + cur + " " + money.toPlainString() + ")"));
        s.setPaidFor(trim(what.toString(), 500));
        s.setSpentBy(CurrentUserService.displayName(actor));
        s.setSpentById(actor.getId());
        s.setNote(note);
        return spends.save(s);
    }

    @Transactional
    public Map<String, Object> requestWithdrawal(User principal, WithdrawalRequest req) {
        User student = student(principal);
        if (req == null) throw new IllegalArgumentException("Fill in the request.");
        BigDecimal amount = amount(req.amount());
        String reason = ConcessionService.clean(req.reason(), 300);
        if (reason == null) throw new IllegalArgumentException("Say why you want the money back.");
        WalletWithdrawal.Payout payout = ConcessionService.parse(WalletWithdrawal.Payout.class, req.payout() == null ? "CASH" : req.payout(),
                "Choose how you'd like to receive the money.");
        String details = ConcessionService.clean(req.payoutDetails(), 200);
        if (payout != WalletWithdrawal.Payout.CASH && details == null)
            throw new IllegalArgumentException(payout == WalletWithdrawal.Payout.MOBILE_MONEY
                    ? "Give the Mobile Money number and the name on it." : "Give the bank, account number and account name.");

        ledger.lock(student);
        if (withdrawals.findForStudent(student.getId()).stream().anyMatch(WalletWithdrawal::isOpen))
            throw new IllegalArgumentException("You already have a withdrawal request in progress. Wait for it to finish, or cancel it first.");
        BigDecimal available = available(student.getId());
        if (amount.compareTo(available) > 0)
            throw new IllegalArgumentException(available.signum() <= 0 ? "There is no money in your wallet to withdraw."
                    : "You can withdraw up to " + scheduleService.currency() + " " + available.toPlainString() + ".");

        WalletWithdrawal w = new WalletWithdrawal();
        w.setStudent(student);
        w.setNumber(numbers.nextNumber("WD"));
        w.setAmount(amount);
        w.setCurrency(scheduleService.currency());
        w.setReason(reason);
        w.setPayout(payout);
        w.setPayoutDetails(payout == WalletWithdrawal.Payout.CASH ? null : details);
        withdrawals.save(w);

        boolean bursars = !activeBursars().isEmpty();
        tellStaff(bursars ? activeBursars() : superAdmins(), "Wallet withdrawal to review",
                CurrentUserService.displayName(student) + " asks for " + money(w) + " back (" + w.getNumber() + ")."
                        + (bursars ? "" : " There is no bursar, so the Super Admin reviews and approves it."));
        return view(student, false);
    }

    /** The student cancels their own request, any time before it is paid. */
    @Transactional
    public Map<String, Object> cancel(User principal, Long id) {
        User student = student(principal);
        ledger.lock(student);
        WalletWithdrawal w = withdrawals.findById(id).filter(x -> x.getStudent().getId().equals(student.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));
        if (!w.isOpen()) throw new IllegalArgumentException("This request is already " + w.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        w.setStatus(WalletWithdrawal.Status.CANCELLED);
        w.setCancelledAt(LocalDateTime.now());
        withdrawals.save(w);
        return view(student, false);
    }

    // ── Accounts office and Super Admin ─────────────────────────────────

    /** Requests waiting on each step, the latest finished ones, money held in wallets, and what the signed-in person may do. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(User actor) {
        FinanceAccess.requireFinance(actor);
        Object[] totals = allocations.walletTotals();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", features.isOnSystemWide(Feature.STUDENT_WALLET));
        out.put("currency", scheduleService.currency());
        out.put("maxBalance", maxBalance());
        out.put("students", ((Number) totals[0]).longValue());
        out.put("held", totals[1]);
        List<Map<String, Object>> open = withdrawals.findByStatuses(WalletWithdrawal.OPEN).stream().map(w -> {
            Map<String, Object> m = withdrawalDto(w, true);
            m.put("walletBalance", allocations.wallet(w.getStudent().getId()));
            return m;
        }).toList();
        out.put("open", open);
        out.put("closed", withdrawals.findClosed(WalletWithdrawal.OPEN, PageRequest.of(0, 100)).stream().map(w -> withdrawalDto(w, true)).toList());
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", actor.getId());
        me.put("review", FinanceAccess.isBursar(actor));
        me.put("approve", actor.getRole() == Role.SUPER_ADMIN);
        me.put("payOut", true);
        me.put("settings", FinanceAccess.isBursar(actor));
        me.put("spend", canStaffSpend(actor));
        out.put("can", me);
        out.put("noBursar", activeBursars().isEmpty());
        return out;
    }

    @Transactional
    public Map<String, Object> studentWallet(User actor, Long studentId) {
        FinanceAccess.requireFinance(actor);
        Map<String, Object> out = view(studentById(studentId), true);
        out.put("canSpend", canStaffSpend(actor));
        return out;
    }

    /** Money paid into a student's wallet at the counter; it goes into the cashier's cash-up like any payment. */
    @Transactional
    public Map<String, Object> deposit(User actor, Long studentId, DepositRequest req) {
        FinanceAccess.requireFinance(actor);
        if (!features.isOnSystemWide(Feature.STUDENT_WALLET))
            throw new IllegalArgumentException("The student wallet is switched off under Feature Controls.");
        if (req == null) throw new IllegalArgumentException("Enter the amount.");
        User student = studentById(studentId);
        BigDecimal amount = amount(req.amount());
        ledger.lock(student);
        checkRoom(student, amount, false);
        return paymentService.recordDeposit(student, amount, req.method(), req.paidOn(), req.note(), actor);
    }

    @Transactional
    public Map<String, Object> review(User actor, Long id, NoteRequest req) {
        FinanceAccess.requireBursar(actor);
        WalletWithdrawal w = locked(id);
        step(w, WalletWithdrawal.Status.REQUESTED, "reviewed");
        w.setStatus(WalletWithdrawal.Status.REVIEWED);
        w.setReviewedBy(CurrentUserService.displayName(actor));
        w.setReviewedById(actor.getId());
        w.setReviewedAt(LocalDateTime.now());
        w.setReviewNote(ConcessionService.clean(req == null ? null : req.note(), 300));
        withdrawals.save(w);
        tellStaff(superAdmins(), "Wallet withdrawal to approve",
                w.getReviewedBy() + " reviewed " + w.getNumber() + ": " + money(w) + " for " + CurrentUserService.displayName(w.getStudent()) + ".");
        tellStudent(w, "Withdrawal reviewed", "The bursar has checked your request " + w.getNumber() + " for " + money(w) + ". It now waits for the Super Admin's approval.");
        return withdrawalDto(w, true);
    }

    @Transactional
    public Map<String, Object> approve(User actor, Long id, NoteRequest req) {
        if (actor.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin approves wallet withdrawals.");
        WalletWithdrawal w = locked(id);
        String name = CurrentUserService.displayName(actor);
        if (w.getStatus() == WalletWithdrawal.Status.REQUESTED && activeBursars().isEmpty()) {
            // No bursar to review it: the Super Admin does both
            w.setReviewedBy(name);
            w.setReviewedById(actor.getId());
            w.setReviewedAt(LocalDateTime.now());
            w.setReviewNote("No bursar account: reviewed with the approval.");
        } else {
            step(w, WalletWithdrawal.Status.REVIEWED, "approved");
            if (actor.getId().equals(w.getReviewedById()))
                throw new IllegalArgumentException("You reviewed this request, so someone else must approve it.");
        }
        w.setStatus(WalletWithdrawal.Status.APPROVED);
        w.setApprovedBy(name);
        w.setApprovedById(actor.getId());
        w.setApprovedAt(LocalDateTime.now());
        w.setApprovalNote(ConcessionService.clean(req == null ? null : req.note(), 300));
        withdrawals.save(w);
        tellStaff(activeAccountants(), "Wallet withdrawal to pay out",
                w.getNumber() + " is approved: pay " + money(w) + " to " + CurrentUserService.displayName(w.getStudent()) + " (" + payoutText(w) + ").");
        tellStudent(w, "Withdrawal approved", "Your request " + w.getNumber() + " for " + money(w) + " is approved. The accounts office will pay it out "
                + (w.getPayout() == WalletWithdrawal.Payout.CASH ? "at the counter." : "to " + payoutText(w) + "."));
        return withdrawalDto(w, true);
    }

    /** Pays the approved amount out of the wallet: a refund voucher, in the payer's cash-up like any refund. */
    @Transactional
    public Map<String, Object> payOut(User actor, Long id, PayOutRequest req) {
        FinanceAccess.requireFinance(actor);
        WalletWithdrawal w = locked(id);
        step(w, WalletWithdrawal.Status.APPROVED, "paid out");
        boolean anyAccountant = !activeAccountants().isEmpty();
        if (actor.getId().equals(w.getApprovedById()) && anyAccountant)
            throw new IllegalArgumentException("You approved this request, so someone else in the accounts office must pay it out.");
        if (actor.getId().equals(w.getReviewedById()) && !actor.getId().equals(w.getApprovedById())
                && !settings.getBooleanSetting(SystemSettingService.FEES_SELF_CASHUP, false)
                && activeAccountants().stream().anyMatch(u -> !u.getId().equals(actor.getId())))
            throw new IllegalArgumentException("You reviewed this request, so someone else in the accounts office must pay it out.");
        FeePayment.Method method = ConcessionService.parse(FeePayment.Method.class, req == null || req.method() == null ? defaultMethod(w).name() : req.method(),
                "Choose how the money is paid out.");
        if (method == FeePayment.Method.PAYSTACK) throw new IllegalArgumentException("Pay it out by cash, bank transfer or Mobile Money (Other).");
        BigDecimal balance = allocations.wallet(w.getStudent().getId());
        if (w.getAmount().compareTo(balance) > 0)
            throw new IllegalArgumentException("The wallet now holds only " + w.getCurrency() + " " + balance.toPlainString() + ". Reject this request and ask the student to ask again.");

        String paidTo = ConcessionService.clean(req == null ? null : req.paidTo(), 200);
        FeeRefund r = new FeeRefund();
        r.setStudent(w.getStudent());
        r.setNumber(numbers.nextNumber("RF"));
        r.setAmount(w.getAmount());
        r.setCurrency(w.getCurrency());
        r.setMethod(method);
        r.setPaidTo(paidTo != null ? paidTo : trim(CurrentUserService.displayName(w.getStudent()) + (w.getPayoutDetails() != null ? ", " + w.getPayoutDetails() : ""), 200));
        r.setReason(trim("Wallet withdrawal " + w.getNumber() + ": " + w.getReason(), 300));
        r.setPaidBy(CurrentUserService.displayName(actor));
        r.setPaidById(actor.getId());
        refunds.saveAndFlush(r);
        ledger.payOutWallet(w.getStudent(), r, w.getAmount());

        w.setStatus(WalletWithdrawal.Status.PAID);
        w.setRefund(r);
        w.setPaidBy(r.getPaidBy());
        w.setPaidById(actor.getId());
        w.setPaidAt(r.getPaidAt());
        withdrawals.save(w);
        tellStudent(w, "Withdrawal paid", money(w) + " from your wallet has been paid out (" + ReceiptService.methodName(method) + ", voucher " + r.getNumber() + ").");
        return withdrawalDto(w, true);
    }

    /** The bursar or the Super Admin turns a request down at any step before it is paid; the money stays in the wallet. */
    @Transactional
    public Map<String, Object> reject(User actor, Long id, NoteRequest req) {
        FinanceAccess.requireBursar(actor);
        String why = ConcessionService.clean(req == null ? null : req.note(), 300);
        if (why == null) throw new IllegalArgumentException("Give the reason, so the student knows why.");
        WalletWithdrawal w = locked(id);
        if (!w.isOpen()) throw new IllegalArgumentException("This request is already " + w.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        w.setRejectedAtStep(w.getStatus());
        w.setStatus(WalletWithdrawal.Status.REJECTED);
        w.setRejectedBy(CurrentUserService.displayName(actor));
        w.setRejectedAt(LocalDateTime.now());
        w.setRejectReason(why);
        withdrawals.save(w);
        tellStudent(w, "Withdrawal not approved", "Your request " + w.getNumber() + " for " + money(w) + " was turned down: " + why + ". The money is still in your wallet.");
        return withdrawalDto(w, true);
    }

    @Transactional
    public Map<String, Object> saveSettings(User actor, SettingsRequest req) {
        FinanceAccess.requireBursar(actor);
        BigDecimal max = req == null || req.maxBalance() == null || req.maxBalance().signum() == 0 ? null
                : FeeScheduleService.money(req.maxBalance(), "The wallet limit");
        settings.updateSetting(MAX_BALANCE, max == null ? "" : max.toPlainString());
        return overview(actor);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** What is in the wallet, what is held, what it can pay, its history and withdrawal requests. */
    private Map<String, Object> view(User student, boolean forStaff) {
        Long id = student.getId();
        BigDecimal balance = allocations.wallet(id), held = withdrawals.held(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", features.isOnFor(Feature.STUDENT_WALLET, student));
        out.put("currency", scheduleService.currency());
        out.put("balance", balance);
        out.put("held", held);
        out.put("available", balance.subtract(held).max(BigDecimal.ZERO));
        out.put("maxBalance", maxBalance());
        out.put("minimum", MIN_AMOUNT);
        out.put("onlineDeposit", scheduleService.paystackStatus().get("configured") == Boolean.TRUE
                && settings.getBooleanSetting(SystemSettingService.FEES_ONLINE_PAYMENT, true));
        out.put("partPayment", settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true));
        out.put("feesVisible", settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false));
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("id", id);
        who.put("name", CurrentUserService.displayName(student));
        who.put("studentId", student.getUsername());
        who.put("email", student.getEmail());
        out.put("student", who);
        out.put("targets", targets(student));
        out.put("history", history(student, forStaff));
        out.put("withdrawals", withdrawals.findForStudent(id).stream().map(w -> withdrawalDto(w, forStaff)).toList());
        return out;
    }

    /** What the wallet can pay: everything owed (oldest first), each fee (term) and each bill with a balance. */
    private List<Map<String, Object>> targets(User student) {
        FeeDues.Account account = ledger.settled(student);
        List<Map<String, Object>> out = new ArrayList<>();
        if (account.toPay().signum() > 0) out.add(target("ALL", null, "Everything I owe, oldest first", account.toPay()));
        for (FeeDues.Due d : account.fees())
            if (d.balance().signum() > 0)
                out.add(target("FEE", d.schedule().getId(), FeeDues.periodName(d.schedule()) + " fees · " + d.schedule().getSession().getName(), d.balance()));
        for (FeeDues.BillDue b : account.openBills())
            if (b.balance().signum() > 0) out.add(target("BILL", b.bill().getId(), b.bill().getTitle(), b.balance()));
        return out;
    }

    private static Map<String, Object> target(String kind, Long id, String label, BigDecimal balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id);
        m.put("label", label);
        m.put("balance", balance);
        return m;
    }

    /** Deposits (with their receipts), uses and withdrawals paid, newest first. */
    private List<Map<String, Object>> history(User student, boolean forStaff) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FeePayment p : payments.findForStudent(student.getId())) {
            if (!p.isWalletDeposit() || p.getStatus() == FeePayment.Status.FAILED || p.getStatus() == FeePayment.Status.ABANDONED) continue;
            if (p.getStatus() == FeePayment.Status.VOIDED && !forStaff) continue;
            Map<String, Object> m = entry("DEPOSIT", p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt(), p.getAmount(),
                    p.getReceiptNo() != null ? p.getReceiptNo() : p.getReference(), "Deposit");
            m.put("status", p.getStatus().name());
            m.put("payment", paymentService.dto(p, forStaff));
            out.add(m);
        }
        for (WalletSpend s : spends.findForStudent(student.getId())) {
            Map<String, Object> m = entry("SPEND", s.getSpentAt(), s.getAmount(), s.getNumber(), s.getPaidFor());
            if (s.getSpentById() != null && !s.getSpentById().equals(student.getId())) {
                m.put("by", s.getSpentBy());
                m.put("note", s.getNote());
            }
            out.add(m);
        }
        for (WalletWithdrawal w : withdrawals.findForStudent(student.getId()))
            if (w.getStatus() == WalletWithdrawal.Status.PAID || w.getStatus() == WalletWithdrawal.Status.REVERSED) {
                Map<String, Object> m = entry("WITHDRAWAL", w.getPaidAt(), w.getAmount(), w.getNumber(), "Paid out to you");
                m.put("status", w.getStatus().name());
                out.add(m);
            }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("at"), Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    private static Map<String, Object> entry(String type, LocalDateTime at, BigDecimal amount, String reference, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("at", at);
        m.put("amount", amount);
        m.put("reference", reference);
        m.put("label", label);
        m.put("status", "SUCCESS");
        return m;
    }

    private Map<String, Object> withdrawalDto(WalletWithdrawal w, boolean forStaff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("number", w.getNumber());
        m.put("amount", w.getAmount());
        m.put("currency", w.getCurrency());
        m.put("reason", w.getReason());
        m.put("payout", w.getPayout().name());
        m.put("payoutDetails", w.getPayoutDetails());
        m.put("status", w.getStatus().name());
        m.put("requestedAt", w.getRequestedAt());
        m.put("reviewedBy", w.getReviewedBy());
        m.put("reviewedAt", w.getReviewedAt());
        m.put("reviewNote", w.getReviewNote());
        m.put("approvedBy", w.getApprovedBy());
        m.put("approvedAt", w.getApprovedAt());
        m.put("approvalNote", w.getApprovalNote());
        m.put("paidBy", w.getPaidBy());
        m.put("paidAt", w.getPaidAt());
        if (w.getRefund() != null) {
            m.put("voucher", w.getRefund().getNumber());
            m.put("paidMethod", ReceiptService.methodName(w.getRefund().getMethod()));
            m.put("paidTo", w.getRefund().getPaidTo());
        }
        m.put("rejectedBy", w.getRejectedBy());
        m.put("rejectedAt", w.getRejectedAt());
        m.put("rejectReason", w.getRejectReason());
        m.put("rejectedAtStep", w.getRejectedAtStep() == null ? null : w.getRejectedAtStep().name());
        m.put("cancelledAt", w.getCancelledAt());
        if (forStaff) {
            User s = w.getStudent();
            m.put("studentId", s.getId());
            m.put("studentName", CurrentUserService.displayName(s));
            m.put("username", s.getUsername());
            m.put("programName", s.getProgram() != null ? s.getProgram().getName() : null);
            m.put("level", s.getCurrentLevel());
            m.put("reviewedById", w.getReviewedById());
            m.put("approvedById", w.getApprovedById());
        }
        return m;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Wallet money not held by a withdrawal request. */
    private BigDecimal available(Long studentId) {
        return allocations.wallet(studentId).subtract(withdrawals.held(studentId)).max(BigDecimal.ZERO);
    }

    private BigDecimal maxBalance() {
        String v = settings.getSetting(MAX_BALANCE);
        if (v == null || v.isBlank()) return null;
        try {
            BigDecimal max = new BigDecimal(v.trim());
            return max.signum() > 0 ? max : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void checkRoom(User student, BigDecimal amount, boolean byStudent) {
        BigDecimal max = maxBalance();
        if (max == null) return;
        BigDecimal room = max.subtract(allocations.wallet(student.getId())).max(BigDecimal.ZERO);
        if (amount.compareTo(room) > 0) {
            String cur = scheduleService.currency();
            throw new IllegalArgumentException((byStudent ? "Your wallet" : "A wallet") + " can hold at most " + cur + " " + max.toPlainString()
                    + (room.signum() > 0 ? "; up to " + cur + " " + room.toPlainString() + " more can go in." : ", and " + (byStudent ? "yours" : "this one") + " is full."));
        }
    }

    private static BigDecimal amount(BigDecimal raw) {
        BigDecimal a = FeeScheduleService.money(raw, "The amount");
        if (a.compareTo(MIN_AMOUNT) < 0) throw new IllegalArgumentException("The smallest amount is " + MIN_AMOUNT.toPlainString() + ".");
        return a;
    }

    /** The request, with its student's account locked (every wallet change happens under that lock). */
    private WalletWithdrawal locked(Long id) {
        // Read only after the lock, so its status is the one every other step sees
        Long studentId = withdrawals.studentIdOf(id).orElseThrow(() -> new IllegalArgumentException("Request not found."));
        users.lockById(studentId);
        return withdrawals.findById(id).orElseThrow(() -> new IllegalArgumentException("Request not found."));
    }

    private static void step(WalletWithdrawal w, WalletWithdrawal.Status expected, String verb) {
        if (w.getStatus() == expected) return;
        String now = switch (w.getStatus()) {
            case REQUESTED -> "still waiting for the bursar's review";
            case REVIEWED -> "waiting for the Super Admin's approval";
            case APPROVED -> "approved and waiting to be paid out";
            default -> w.getStatus().name().toLowerCase(Locale.ROOT);
        };
        throw new IllegalArgumentException("This request can't be " + verb + ": it is " + now + ".");
    }

    private static FeePayment.Method defaultMethod(WalletWithdrawal w) {
        return switch (w.getPayout()) {
            case CASH -> FeePayment.Method.CASH;
            case BANK -> FeePayment.Method.BANK_TRANSFER;
            case MOBILE_MONEY -> FeePayment.Method.OTHER;
        };
    }

    private static String payoutText(WalletWithdrawal w) {
        return switch (w.getPayout()) {
            case CASH -> "cash";
            case MOBILE_MONEY -> "Mobile Money" + (w.getPayoutDetails() != null ? " " + w.getPayoutDetails() : "");
            case BANK -> "bank" + (w.getPayoutDetails() != null ? " " + w.getPayoutDetails() : "");
        };
    }

    private static String money(WalletWithdrawal w) {
        return w.getCurrency() + " " + w.getAmount().toPlainString();
    }

    private List<User> activeAccountants() {
        return users.findByRole(Role.ACCOUNTANT).stream().filter(User::isEnabled).toList();
    }

    private List<User> activeBursars() {
        return activeAccountants().stream().filter(u -> u.getFinanceDuty() == FinanceDuty.BURSAR).toList();
    }

    private List<User> superAdmins() {
        return users.findByRole(Role.SUPER_ADMIN).stream().filter(User::isEnabled).toList();
    }

    /** Accountants open the accounts office's page, the Super Admin theirs. */
    private void tellStaff(List<User> who, String title, String message) {
        List<User> office = who.stream().filter(u -> u.getRole() == Role.ACCOUNTANT).toList();
        List<User> admins = who.stream().filter(u -> u.getRole() == Role.SUPER_ADMIN).toList();
        notifications.notify(office, NOTIFY_TYPE, title, message, "/accounts/wallets");
        notifications.notify(admins, NOTIFY_TYPE, title, message, "/super-admin/wallets");
    }

    private void tellStudent(WalletWithdrawal w, String title, String message) {
        notifications.notify(w.getStudent(), NOTIFY_TYPE, title, message, "/user-dashboard/wallet");
    }

    private User student(User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        User u = users.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
        if (u.getRole() != Role.NORMAL) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students have a wallet.");
        return u;
    }

    private User studentById(Long id) {
        return users.findById(id).filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
