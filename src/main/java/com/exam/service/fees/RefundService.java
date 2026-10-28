package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FeeRefund;
import com.exam.repository.FeeAllocationRepository;
import com.exam.repository.FeeRefundRepository;
import com.exam.repository.UserRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paying money back. Only credit can be refunded: the student's credit is first used on anything they
 * owe, so what is left is truly theirs. To give back money already counted against a charge, remove
 * the charge first (cancel the bill, grant a concession): the money becomes credit, then refund it.
 * The bursar refunds and voids refunds; each refund gets a voucher number (RF-2026-000001).
 */
@Service
public class RefundService {

    @Autowired private FeeRefundRepository refunds;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private UserRepository users;
    @Autowired private FeeLedger ledger;
    @Autowired private ReceiptService numbers;
    @Autowired private FeeScheduleService scheduleService;

    public record RefundRequest(Long studentId, BigDecimal amount, String method, String paidTo, String reason) {}

    @Transactional
    public Map<String, Object> refund(User actor, RefundRequest req) {
        FinanceAccess.requireBursar(actor);
        if (req == null || req.studentId() == null) throw new IllegalArgumentException("Choose the student.");
        User student = users.findById(req.studentId()).filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        BigDecimal amount = FeeScheduleService.money(req.amount(), "The amount");
        FeePayment.Method method = ConcessionService.parse(FeePayment.Method.class, req.method() == null ? "CASH" : req.method(), "Choose how the money is paid back.");
        String reason = ConcessionService.clean(req.reason(), 300);
        if (reason == null) throw new IllegalArgumentException("Give the reason for the refund.");
        String paidTo = ConcessionService.clean(req.paidTo(), 200);

        ledger.lock(student);
        ledger.applyCredit(student);               // credit goes to anything owed first
        BigDecimal credit = allocations.credit(student.getId());
        if (amount.compareTo(credit) > 0)
            throw new IllegalArgumentException(credit.signum() <= 0
                    ? "This student has no credit to refund. Remove the charge first (cancel the bill or grant a concession) to turn what they paid into credit."
                    : "Only " + scheduleService.currency() + " " + credit.toPlainString() + " of credit can be refunded.");

        FeeRefund r = new FeeRefund();
        r.setStudent(student);
        r.setNumber(numbers.nextNumber("RF"));
        r.setAmount(amount);
        r.setCurrency(scheduleService.currency());
        r.setMethod(method);
        r.setPaidTo(paidTo);
        r.setReason(reason);
        r.setPaidBy(CurrentUserService.displayName(actor));
        r.setPaidById(actor.getId());
        refunds.saveAndFlush(r);
        ledger.refund(student, r, amount);
        return dto(r);
    }

    @Transactional
    public Map<String, Object> voidRefund(User actor, Long id, String reason) {
        FinanceAccess.requireBursar(actor);
        FeeRefund r = refunds.findById(id).orElseThrow(() -> new IllegalArgumentException("Refund not found."));
        String why = ConcessionService.clean(reason, 200);
        if (why == null) throw new IllegalArgumentException("Give a reason for cancelling the refund.");
        ledger.lock(r.getStudent());
        if (r.getStatus() != FeeRefund.Status.DONE) throw new IllegalArgumentException("This refund was already cancelled.");
        r.setStatus(FeeRefund.Status.VOIDED);
        r.setVoidedAt(LocalDateTime.now());
        r.setVoidedBy(CurrentUserService.displayName(actor));
        r.setVoidReason(why);
        refunds.save(r);
        ledger.unrefund(r);                        // the money is credit again …
        ledger.applyCredit(r.getStudent());        // … and goes to anything owed
        return dto(r);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forStudent(Long studentId) {
        return refunds.findForStudent(studentId).stream().map(RefundService::dto).toList();
    }

    public static Map<String, Object> dto(FeeRefund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("number", r.getNumber());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("method", r.getMethod().name());
        m.put("methodName", ReceiptService.methodName(r.getMethod()));
        m.put("paidTo", r.getPaidTo());
        m.put("reason", r.getReason());
        m.put("status", r.getStatus().name());
        m.put("paidAt", r.getPaidAt());
        m.put("paidBy", r.getPaidBy());
        m.put("voidedAt", r.getVoidedAt());
        m.put("voidedBy", r.getVoidedBy());
        m.put("voidReason", r.getVoidReason());
        m.put("cashedUp", r.getCashUp() != null);
        if (r.getStudent() != null) {
            m.put("studentId", r.getStudent().getId());
            m.put("studentName", CurrentUserService.displayName(r.getStudent()));
            m.put("username", r.getStudent().getUsername());
        }
        return m;
    }
}
