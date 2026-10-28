package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.fees.FeePayment;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The accounts office at a glance: money received today (by method and by who took it), this week and
 * this month, what students owe so far this session, what their wallets hold, and the latest payments.
 */
@Service
public class FinanceDashboardService {

    @Autowired private FeePaymentRepository payments;
    @Autowired private UserRepository users;
    @Autowired private FeeDues dues;
    @Autowired private FeeScheduleService schedules;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private CashBookService cashBook;
    @Autowired private com.exam.repository.FeeAllocationRepository allocations;

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(User me) {
        FinanceAccess.requireFinance(me);
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay(), tomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        List<FeePayment> month = payments.receivedBetween(FeePayment.Status.SUCCESS, monthStart.isBefore(weekStart) ? monthStart : weekStart, tomorrow);
        List<FeePayment> todays = month.stream().filter(p -> !p.getPaidAt().isBefore(dayStart)).toList();
        String myName = CurrentUserService.displayName(me);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", schedules.currency());
        out.put("me", Map.of("name", myName, "duty", me.getRole() == Role.SUPER_ADMIN ? "SUPER_ADMIN"
                : me.getFinanceDuty() == null ? "CASHIER" : me.getFinanceDuty().name(), "bursar", FinanceAccess.isBursar(me)));
        out.put("today", Map.of("total", sum(todays), "count", todays.size(),
                "mine", sum(todays.stream().filter(p -> me.getId().equals(p.getRecordedById())
                        || (p.getRecordedById() == null && myName.equals(p.getRecordedBy()))).toList())));
        out.put("handIn", cashBook.mine(me));
        out.put("week", Map.of("total", sum(month.stream().filter(p -> !p.getPaidAt().isBefore(weekStart)).toList())));
        out.put("month", Map.of("total", sum(month.stream().filter(p -> !p.getPaidAt().isBefore(monthStart)).toList())));

        Map<String, BigDecimal> byMethod = new TreeMap<>();
        Map<String, BigDecimal> byCashier = new TreeMap<>();
        for (FeePayment p : todays) {
            byMethod.merge(ReceiptService.methodName(p.getMethod()), p.getAmount(), BigDecimal::add);
            byCashier.merge(p.getMethod() == FeePayment.Method.PAYSTACK ? "Online (no cashier)" : Objects.toString(p.getRecordedBy(), "Unknown"),
                    p.getAmount(), BigDecimal::add);
        }
        out.put("todayByMethod", byMethod.entrySet().stream().map(e -> Map.of("label", e.getKey(), "amount", e.getValue())).toList());
        out.put("todayByCashier", byCashier.entrySet().stream().map(e -> Map.of("label", e.getKey(), "amount", e.getValue())).toList());

        // What students owe so far this session
        AcademicSession session = academicSessions.current();
        FeeDues.Book book = dues.book(session);
        BigDecimal owed = BigDecimal.ZERO, outstanding = BigDecimal.ZERO, discounts = BigDecimal.ZERO;
        long debtors = 0, billed = 0, residencyUnknown = 0;
        for (User u : users.findByRole(Role.NORMAL)) {
            if (!u.isEnabled()) continue;
            List<FeeDues.Due> list = book.dues(u);
            if (list.isEmpty()) continue;
            billed++;
            BigDecimal bal = BigDecimal.ZERO;
            boolean unknown = false;
            for (FeeDues.Due d : list) {
                owed = owed.add(d.amount());
                bal = bal.add(d.balance());
                discounts = discounts.add(d.discount());
                unknown |= FeeDues.variesByResidency(d.schedule()) && dues.residencyOf(u) == null;
            }
            if (unknown) residencyUnknown++;   // pays the everyone items only until recorded
            outstanding = outstanding.add(bal);
            if (bal.signum() > 0) debtors++;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", session.getName());
        s.put("owed", owed);
        s.put("outstanding", outstanding);
        s.put("paid", owed.subtract(outstanding));
        s.put("students", billed);
        s.put("debtors", debtors);
        s.put("residencyUnknown", residencyUnknown);
        s.put("discounts", discounts);
        BigDecimal arrears = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        long withArrears = 0;
        for (BigDecimal[] v : dues.arrearsAndCredit().values()) {
            arrears = arrears.add(v[0]);
            credit = credit.add(v[1]);
            if (v[0].signum() > 0) withArrears++;
        }
        s.put("arrears", arrears);
        s.put("withArrears", withArrears);
        s.put("credit", credit);
        s.put("closed", !book.live());
        out.put("session", s);
        // Money students paid in ahead and haven't used: the school holds it for them
        Object[] wallets = allocations.walletTotals();
        out.put("wallets", Map.of("students", ((Number) wallets[0]).longValue(), "held", wallets[1]));

        out.put("recent", month.stream().limit(10).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("receiptNo", p.getReceiptNo());
            m.put("reference", p.getReference());
            m.put("studentName", CurrentUserService.displayName(p.getStudent()));
            m.put("studentId", p.getStudent().getUsername());
            m.put("amount", p.getAmount());
            m.put("method", p.getMethod().name());
            m.put("paidAt", p.getPaidAt());
            m.put("recordedBy", p.getRecordedBy());
            return m;
        }).toList());
        out.put("settings", schedules.feeSettings());
        out.put("paystack", schedules.paystackStatus());
        return out;
    }

    private static BigDecimal sum(List<FeePayment> list) {
        return list.stream().map(FeePayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
