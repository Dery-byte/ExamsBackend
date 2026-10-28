package com.exam.service.fees;

import com.exam.model.User;
import com.exam.model.fees.*;
import com.exam.repository.FeeAllocationRepository;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * Where a student's money goes. Every amount of a successful payment sits on one target (a fee term,
 * a bill, arrears, a refund) or is credit. The rules, the same for every path:
 * <ul>
 *   <li>a payment goes to the fee or bill the payer chose first, then oldest first: arrears (opening
 *       balance, then by session), this session's fees by term, bills by date; the rest is credit;</li>
 *   <li>money counted against a charge beyond what it now comes to (after a concession, a lower price
 *       or residency, a cancelled bill) goes back to credit, newest payment first;</li>
 *   <li>credit goes to what is owed, oldest first, straight away ({@link #applyCredit});</li>
 *   <li>a refund pays back credit only, oldest payment first.</li>
 * </ul>
 * A student's money only moves under a row lock on the student, so two changes never count the same
 * balance twice. A sweep each hour settles accounts nobody opened (a new term, a new concession).
 */
@Service
public class FeeLedger {

    private static final Logger log = LoggerFactory.getLogger(FeeLedger.class);

    @Autowired private FeeDues feeDues;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private FeePaymentRepository payments;
    @Autowired private UserRepository users;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Where money sits: exactly one of these, or none for credit. */
    private record Target(FeeSchedule schedule, StudentBill bill, FeeArrears arrears, FeeRefund refund) {
        static final Target CREDIT = new Target(null, null, null, null);
        boolean credit() { return schedule == null && bill == null && arrears == null && refund == null; }
        /** Compared by id: an allocation may hold a lazy proxy of the same row. */
        boolean is(FeeAllocation a) {
            return same(a.getSchedule() == null ? null : a.getSchedule().getId(), schedule == null ? null : schedule.getId())
                    && same(a.getBill() == null ? null : a.getBill().getId(), bill == null ? null : bill.getId())
                    && same(a.getArrears() == null ? null : a.getArrears().getId(), arrears == null ? null : arrears.getId())
                    && same(a.getRefund() == null ? null : a.getRefund().getId(), refund == null ? null : refund.getId());
        }
        private static boolean same(Long x, Long y) { return Objects.equals(x, y); }
    }

    /** Something owed and its balance. */
    private record Slot(Target target, BigDecimal balance) {}

    /** Locks the student's account until the transaction ends. */
    public void lock(User student) {
        users.lockById(student.getId());
    }

    /**
     * Splits a payment that has just succeeded (once: a payment with allocations is left as it is).
     * A payment for chosen items of a fee goes wholly to that fee. Call in the transaction that marks it
     * SUCCESS, after {@link #applyCredit} (which locks the student).
     */
    public void allocate(FeePayment p) {
        if (!p.getAllocations().isEmpty()) return;
        if (!p.getItems().isEmpty() && p.getSchedule() != null) {
            add(p, new Target(p.getSchedule(), null, null, null), p.getAmount());
            return;
        }
        BigDecimal left = p.getAmount();
        for (Slot s : slots(feeDues.account(p.getStudent()), p.getSchedule(), p.getBill())) {
            if (left.signum() <= 0) break;
            BigDecimal take = left.min(s.balance());
            add(p, s.target(), take);
            left = left.subtract(take);
        }
        if (left.signum() > 0) add(p, Target.CREDIT, left);
    }

    /**
     * Settles the student's account: money counted beyond a charge goes back to credit, then credit goes
     * to what is owed, oldest first. Returns true when anything moved. When nothing waits to move it
     * neither locks nor writes; otherwise it locks the student and works from a fresh read.
     */
    @Transactional
    public boolean applyCredit(User student) {
        return settle(student, feeDues.account(student)) != null;
    }

    /** The student's account after settling it: the same account when nothing moved, else read again. */
    @Transactional
    public FeeDues.Account settled(User student) {
        FeeDues.Account before = feeDues.account(student);
        FeeDues.Account after = settle(student, before);
        return after == null ? before : after;
    }

    /** Null when nothing moved. */
    private FeeDues.Account settle(User student, FeeDues.Account seen) {
        boolean waiting = seen.credit().signum() > 0 || seen.fees().stream().anyMatch(d -> d.credit().signum() > 0);
        if (!waiting) return null;
        lock(student);
        FeeDues.Account account = feeDues.account(student);       // under the lock
        boolean moved = releaseExcess(student, account.fees());
        List<FeeAllocation> credit = allocations.openCredit(student.getId());
        List<Slot> slots = credit.isEmpty() ? List.of() : slots(account, null, null);
        if (slots.isEmpty()) return moved ? feeDues.account(student) : null;
        BigDecimal[] left = slots.stream().map(Slot::balance).toArray(BigDecimal[]::new);
        int i = 0;
        for (FeeAllocation c : credit) {
            if (i >= slots.size()) break;
            while (c.getAmount().signum() > 0 && i < slots.size()) {
                BigDecimal take = c.getAmount().min(left[i]);
                add(c.getPayment(), slots.get(i).target(), take);
                c.setAmount(c.getAmount().subtract(take));
                left[i] = left[i].subtract(take);
                if (left[i].signum() <= 0) i++;
            }
            if (c.getAmount().signum() <= 0) c.getPayment().getAllocations().remove(c);
            payments.save(c.getPayment());
        }
        allocations.flush();
        return feeDues.account(student);
    }

    /**
     * Fees now smaller than what was counted against them: the excess goes back to credit, newest
     * payment first. The student must be locked.
     */
    public boolean releaseExcess(User student, List<FeeDues.Due> fees) {
        Map<Long, BigDecimal> excess = new HashMap<>();
        for (FeeDues.Due d : fees) if (d.credit().signum() > 0) excess.put(d.schedule().getId(), d.credit());
        if (excess.isEmpty()) return false;
        for (FeeAllocation a : allocations.spent(student.getId())) {
            BigDecimal over = a.getSchedule() == null ? null : excess.get(a.getSchedule().getId());
            if (over == null || over.signum() <= 0) continue;
            BigDecimal back = over.min(a.getAmount());
            a.setAmount(a.getAmount().subtract(back));
            add(a.getPayment(), Target.CREDIT, back);
            if (a.getAmount().signum() <= 0) a.getPayment().getAllocations().remove(a);
            excess.put(a.getSchedule().getId(), over.subtract(back));
            payments.save(a.getPayment());
        }
        allocations.flush();     // the credit just made is read straight after
        return true;
    }

    /** A bill was cancelled: what was paid on it becomes credit. Then call {@link #applyCredit}. */
    public BigDecimal releaseBill(StudentBill bill) {
        BigDecimal released = BigDecimal.ZERO;
        for (FeeAllocation a : allocations.paidToBill(bill.getId())) {
            a.setBill(null);
            released = released.add(a.getAmount());
        }
        allocations.flush();
        return released;
    }

    /**
     * Pays back {@code amount} of the student's credit through this refund, oldest payment first.
     * The caller has locked the student and checked there is enough credit.
     */
    public void refund(User student, FeeRefund refund, BigDecimal amount) {
        BigDecimal left = amount;
        Target t = new Target(null, null, null, refund);
        for (FeeAllocation c : allocations.openCredit(student.getId())) {
            if (left.signum() <= 0) break;
            BigDecimal take = left.min(c.getAmount());
            add(c.getPayment(), t, take);
            c.setAmount(c.getAmount().subtract(take));
            if (c.getAmount().signum() <= 0) c.getPayment().getAllocations().remove(c);
            left = left.subtract(take);
            payments.save(c.getPayment());
        }
        if (left.signum() > 0) throw new IllegalStateException("Not enough credit to refund.");
    }

    /** A refund was voided: the money is credit again. Then call {@link #applyCredit}. */
    public void unrefund(FeeRefund refund) {
        for (FeeAllocation a : allocations.findForRefund(refund.getId())) a.setRefund(null);
        allocations.flush();
    }

    /**
     * Hourly: settles every account where money waits to move: students with credit, and students
     * who paid more towards a fee than it now comes to. Found with one pass over the current session.
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    public void sweep() {
        Set<Long> ids = new TreeSet<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(st -> {
                ids.addAll(allocations.studentsWithCredit());
                FeeDues.Book book = feeDues.book(academicSessions.current());
                if (!book.live()) return;
                for (User u : users.findActiveStudents(null, null))
                    if (book.dues(u).stream().anyMatch(d -> d.credit().signum() > 0)) ids.add(u.getId());
            });
        } catch (Exception e) {
            log.warn("[Fees] Could not find accounts to settle: {}", e.getMessage());
            return;
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Long id : ids) {
            try {
                tx.executeWithoutResult(st -> users.findById(id).ifPresent(this::applyCredit));
            } catch (Exception e) {
                log.warn("[Fees] Could not settle the account of student {}: {}", id, e.getMessage());
            }
        }
    }

    /** What the student owes, in the order money goes to it: the chosen fee or bill first, then oldest first. */
    private static List<Slot> slots(FeeDues.Account account, FeeSchedule chosenFee, StudentBill chosenBill) {
        List<Slot> out = new ArrayList<>();
        for (FeeDues.ArrearsDue a : account.openArrears())
            if (a.balance().signum() > 0) out.add(new Slot(new Target(null, null, a.arrears(), null), a.balance()));
        for (FeeDues.Due d : account.fees())
            if (d.balance().signum() > 0) out.add(new Slot(new Target(d.schedule(), null, null, null), d.balance()));
        for (FeeDues.BillDue b : account.openBills())
            if (b.balance().signum() > 0) out.add(new Slot(new Target(null, b.bill(), null, null), b.balance()));
        if (chosenFee != null || chosenBill != null)   // stable sort: oldest-first order kept after the chosen one
            out.sort(Comparator.comparingInt(s -> (chosenFee != null && s.target().schedule() != null && s.target().schedule().getId().equals(chosenFee.getId()))
                    || (chosenBill != null && s.target().bill() != null && s.target().bill().getId().equals(chosenBill.getId())) ? 0 : 1));
        return out;
    }

    private static void add(FeePayment p, Target t, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        // One allocation per target on a payment (credit applied in parts merges into it)
        for (FeeAllocation a : p.getAllocations()) {
            if (!t.credit() && t.is(a)) {
                a.setAmount(a.getAmount().add(amount));
                return;
            }
        }
        FeeAllocation a = new FeeAllocation();
        a.setPayment(p);
        a.setSchedule(t.schedule());
        a.setBill(t.bill());
        a.setArrears(t.arrears());
        a.setRefund(t.refund());
        a.setAmount(amount);
        p.getAllocations().add(a);
    }

    // ── How it reads ─────────────────────────────────────────────────────────

    /** "Term 1 fees · 2026/2027", "School uniform", "2025/2026 fees (arrears)", "Refunded", "Credit". */
    public static String label(FeeAllocation a) {
        if (a.getSchedule() != null)
            return FeeDues.periodName(a.getSchedule()) + " fees" + (a.getSchedule().getSession() != null ? " · " + a.getSchedule().getSession().getName() : "");
        if (a.getBill() != null) return a.getBill().getTitle();
        if (a.getArrears() != null) return a.getArrears().label();
        if (a.getRefund() != null) return "Refunded (" + a.getRefund().getNumber() + ")";
        return "Credit (kept for what is billed next)";
    }

    public static List<Map<String, Object>> describe(FeePayment p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FeeAllocation a : p.getAllocations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", a.getSchedule() != null ? "FEE" : a.getBill() != null ? "BILL" : a.getArrears() != null ? "ARREARS"
                    : a.getRefund() != null ? "REFUND" : "CREDIT");
            m.put("label", label(a));
            if (a.getSchedule() != null) m.put("scheduleId", a.getSchedule().getId());
            if (a.getBill() != null) m.put("billId", a.getBill().getId());
            if (a.getArrears() != null) m.put("arrearsId", a.getArrears().getId());
            m.put("amount", a.getAmount());
            out.add(m);
        }
        return out;
    }

    /** One line for the verification page: "Term 1 fees, School uniform". */
    public static String summary(FeePayment p) {
        if (p.getAllocations().isEmpty())
            return p.getSchedule() != null ? FeeDues.periodName(p.getSchedule()) + " fees" : p.getBill() != null ? p.getBill().getTitle() : "Fees";
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (FeeAllocation a : p.getAllocations())
            parts.add(a.getSchedule() != null ? FeeDues.periodName(a.getSchedule()) + " fees" : a.isCredit() ? "credit" : label(a));
        return String.join(", ", parts);
    }
}
