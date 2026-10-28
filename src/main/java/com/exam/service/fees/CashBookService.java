package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.CashUp;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FeeRefund;
import com.exam.repository.CashUpRepository;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.FeeRefundRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The cash book: what came in and went out on a day (by method, by cashier), what was cancelled or
 * refunded, and the cash-ups. A cash-up hands in everything a person recorded since their last one;
 * expected cash = their cash payments less cash refunds they paid; they declare what they counted.
 * The bursar confirms receiving it, but never their own (the Super Admin confirms the bursar's).
 */
@Service
public class CashBookService {

    @Autowired private FeePaymentRepository payments;
    @Autowired private FeeRefundRepository refunds;
    @Autowired private CashUpRepository cashUps;
    @Autowired private FeeLedger ledger;
    @Autowired private FeeScheduleService scheduleService;
    @Autowired private SystemSettingService settings;

    public record CashUpRequest(BigDecimal declaredCash, String note) {}
    public record ConfirmRequest(String note) {}

    @Transactional(readOnly = true)
    public Map<String, Object> day(User actor, LocalDate date) {
        FinanceAccess.requireFinance(actor);
        LocalDate d = date == null ? LocalDate.now() : date;
        if (d.isAfter(LocalDate.now())) throw new IllegalArgumentException("Choose today or an earlier day.");
        LocalDateTime from = d.atStartOfDay(), to = d.plusDays(1).atStartOfDay();

        List<FeePayment> paid = payments.paidBetween(from, to);
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<String, Object[]> byCashier = new LinkedHashMap<>();
        BigDecimal received = BigDecimal.ZERO;
        int count = 0;
        for (FeePayment p : paid) {
            if (p.getStatus() != FeePayment.Status.SUCCESS) continue;
            count++;
            received = received.add(p.getAmount());
            byMethod.merge(ReceiptService.methodName(p.getMethod()), p.getAmount(), BigDecimal::add);
            String who = p.getMethod() == FeePayment.Method.PAYSTACK ? "Online (Paystack)" : Objects.toString(p.getRecordedBy(), "Unknown");
            Object[] a = byCashier.computeIfAbsent(who, k -> new Object[]{0, BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = (int) a[0] + 1;
            a[1] = ((BigDecimal) a[1]).add(p.getAmount());
            if (p.getMethod() == FeePayment.Method.CASH) a[2] = ((BigDecimal) a[2]).add(p.getAmount());
        }
        BigDecimal refunded = BigDecimal.ZERO;
        List<Map<String, Object>> refundRows = new ArrayList<>();
        for (FeeRefund r : refunds.touchedBetween(from, to)) {
            boolean paidToday = !r.getPaidAt().isBefore(from) && r.getPaidAt().isBefore(to);
            if (paidToday && r.getStatus() == FeeRefund.Status.DONE) refunded = refunded.add(r.getAmount());
            Map<String, Object> m = RefundService.dto(r);
            m.put("voidedToday", r.getVoidedAt() != null && !r.getVoidedAt().isBefore(from) && r.getVoidedAt().isBefore(to));
            refundRows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", d);
        out.put("today", d.equals(LocalDate.now()));
        out.put("currency", scheduleService.currency());
        out.put("totals", Map.of("received", received, "payments", count, "refunded", refunded, "net", received.subtract(refunded)));
        out.put("byMethod", byMethod.entrySet().stream().map(e -> Map.of("label", e.getKey(), "amount", e.getValue())).toList());
        out.put("byCashier", byCashier.entrySet().stream().map(e -> Map.of("label", e.getKey(), "payments", e.getValue()[0],
                "amount", e.getValue()[1], "cash", e.getValue()[2])).toList());
        out.put("payments", paid.stream().map(CashBookService::paymentRow).toList());
        out.put("voids", payments.voidedBetween(from, to).stream().map(CashBookService::paymentRow).toList());
        out.put("refunds", refundRows);
        out.put("cashUps", cashUps.createdBetween(from, to).stream().map(this::cashUpDto).toList());
        out.put("awaitingConfirmation", cashUps.findByStatus(CashUp.Status.SUBMITTED).stream().map(this::cashUpDto).toList());
        out.put("notHandedIn", payments.notCashedUp().stream().map(r -> Map.of("cashierId", r[0], "cashier", Objects.toString(r[1], "Unknown"),
                "payments", r[2], "amount", r[3])).toList());
        out.put("mine", mine(actor));
        out.put("canConfirm", FinanceAccess.isBursar(actor));
        return out;
    }

    /** What the signed-in person has to hand in: their payments and cash refunds since their last cash-up. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine(User actor) {
        FinanceAccess.requireFinance(actor);
        Open o = open(actor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payments", o.payments.size());
        m.put("refunds", o.refunds.size());
        m.put("expectedCash", o.cash);
        m.put("nonCash", o.nonCash);
        m.put("since", o.payments.isEmpty() ? null : o.payments.get(0).getPaidAt());
        return m;
    }

    @Transactional
    public Map<String, Object> cashUp(User actor, CashUpRequest req) {
        FinanceAccess.requireFinance(actor);
        ledger.lock(actor);                          // one cash-up at a time per person
        Open o = open(actor);
        if (o.payments.isEmpty() && o.refunds.isEmpty()) throw new IllegalArgumentException("You have nothing to hand in since your last cash-up.");
        if (req == null || req.declaredCash() == null || req.declaredCash().signum() < 0)
            throw new IllegalArgumentException("Enter the cash you counted (0 if none).");
        String note = ConcessionService.clean(req.note(), 300);
        CashUp c = new CashUp();
        c.setCashierId(actor.getId());
        c.setCashierName(CurrentUserService.displayName(actor));
        c.setPayments(o.payments.size());
        c.setExpectedCash(o.cash);
        c.setDeclaredCash(req.declaredCash().setScale(2, RoundingMode.HALF_UP));
        c.setNonCash(o.nonCash);
        c.setNote(note);
        if (c.difference().signum() != 0 && note == null)
            throw new IllegalArgumentException("The cash counted differs from what is expected. Add a note explaining it.");
        // Nobody above to check it: the Super Admin's own, or a bursar's in a one-person accounts office
        boolean alone = actor.getRole() == Role.SUPER_ADMIN
                || (FinanceAccess.isBursar(actor) && settings.getBooleanSetting(SystemSettingService.FEES_SELF_CASHUP, false));
        if (alone) {
            c.setStatus(CashUp.Status.CONFIRMED);
            c.setConfirmedBy(actor.getRole() == Role.SUPER_ADMIN ? "Super Admin's own" : "No second check (one-person accounts office)");
            c.setConfirmedAt(LocalDateTime.now());
        }
        cashUps.save(c);
        o.payments.forEach(p -> p.setCashUp(c));
        o.refunds.forEach(r -> r.setCashUp(c));
        payments.saveAll(o.payments);
        refunds.saveAll(o.refunds);
        return cashUpDto(c);
    }

    @Transactional
    public Map<String, Object> confirm(User actor, Long id, ConfirmRequest req) {
        FinanceAccess.requireBursar(actor);
        CashUp c = cashUps.findById(id).orElseThrow(() -> new IllegalArgumentException("Cash-up not found."));
        if (c.getStatus() == CashUp.Status.CONFIRMED) throw new IllegalArgumentException("This cash-up was already confirmed.");
        if (c.getCashierId().equals(actor.getId()) && actor.getRole() != Role.SUPER_ADMIN)
            throw new AccessDeniedException("You can't confirm your own cash-up. Ask the Super Admin or another bursar.");
        c.setStatus(CashUp.Status.CONFIRMED);
        c.setConfirmedBy(CurrentUserService.displayName(actor));
        c.setConfirmedAt(LocalDateTime.now());
        c.setConfirmNote(ConcessionService.clean(req == null ? null : req.note(), 300));
        return cashUpDto(cashUps.save(c));
    }

    private record Open(List<FeePayment> payments, List<FeeRefund> refunds, BigDecimal cash, BigDecimal nonCash) {}

    private Open open(User actor) {
        List<FeePayment> ps = payments.openForCashier(actor.getId());
        List<FeeRefund> rs = refunds.openForCashier(actor.getId(), FeeRefund.Status.DONE);
        BigDecimal cash = BigDecimal.ZERO, other = BigDecimal.ZERO;
        for (FeePayment p : ps) {
            if (p.getMethod() == FeePayment.Method.CASH) cash = cash.add(p.getAmount());
            else other = other.add(p.getAmount());
        }
        for (FeeRefund r : rs) if (r.getMethod() == FeePayment.Method.CASH) cash = cash.subtract(r.getAmount());
        return new Open(ps, rs, cash, other);
    }

    private Map<String, Object> cashUpDto(CashUp c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("cashierId", c.getCashierId());
        m.put("cashier", c.getCashierName());
        m.put("payments", c.getPayments());
        m.put("expectedCash", c.getExpectedCash());
        m.put("declaredCash", c.getDeclaredCash());
        m.put("difference", c.difference());
        m.put("nonCash", c.getNonCash());
        m.put("note", c.getNote());
        m.put("status", c.getStatus().name());
        m.put("createdAt", c.getCreatedAt());
        m.put("confirmedBy", c.getConfirmedBy());
        m.put("confirmedAt", c.getConfirmedAt());
        m.put("confirmNote", c.getConfirmNote());
        return m;
    }

    private static Map<String, Object> paymentRow(FeePayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("receiptNo", p.getReceiptNo());
        m.put("reference", p.getReference());
        m.put("studentName", CurrentUserService.displayName(p.getStudent()));
        m.put("studentId", p.getStudent().getUsername());
        m.put("amount", p.getAmount());
        m.put("method", p.getMethod().name());
        m.put("methodName", ReceiptService.methodName(p.getMethod()));
        m.put("status", p.getStatus().name());
        m.put("paidAt", p.getPaidAt());
        m.put("recordedBy", p.getRecordedBy());
        m.put("voidedAt", p.getVoidedAt());
        m.put("voidedBy", p.getVoidedBy());
        m.put("note", p.getNote());
        m.put("cashedUp", p.getCashUp() != null);
        return m;
    }
}
