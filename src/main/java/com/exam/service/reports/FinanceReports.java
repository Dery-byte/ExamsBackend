package com.exam.service.reports;

import com.exam.model.academic.AcademicSession;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FeeSchedule;
import com.exam.repository.FeeScheduleRepository;
import com.exam.repository.UserRepository;
import com.exam.service.fees.FeeScheduleService;
import com.exam.service.fees.ResultsHoldService;
import com.exam.service.reports.ReportQueries.PaymentRow;
import com.exam.service.reports.ReportQueries.StudentRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/** School fees: what was billed and collected, and who still owes. */
@Service
@Transactional
public class FinanceReports {

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private FeeScheduleService feeScheduleService;
    @Autowired private FeeScheduleRepository scheduleRepository;
    @Autowired private ResultsHoldService resultsHoldService;
    @Autowired private UserRepository userRepository;

    // ── Collections ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public ReportResult feeCollections(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("fee-collections", "Fee collections",
                "Fees billed and collected for each " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase()
                        + ", how students paid, and payments entered or voided by staff.");
        Lookups l = support.lookups();
        Map<String, Object> ov = feeScheduleService.overview(f.sessionId());
        Map<String, Object> session = (Map<String, Object>) ov.get("session");
        Long sessionId = ((Number) session.get("id")).longValue();
        boolean current = Boolean.TRUE.equals(session.get("current"));
        String cur = String.valueOf(ov.get("currency"));
        r.scope("Session: " + session.get("name") + (current ? " (current)" : ""));
        support.placeScope(r, f, l);

        Table tb = r.table("levels", "By " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase())
                .text("department", "Department").text("programme", t.get("program")).col("level", t.get("level"), INT)
                .col("fee", "Fee (" + cur + ")", MONEY).col("students", t.get("student") + "s", INT)
                .col("billed", "Billed (" + cur + ")", MONEY).col("collected", "Collected (" + cur + ")", MONEY)
                .col("outstanding", "Outstanding (" + cur + ")", MONEY).col("rate", "Collected", PERCENT);
        BigDecimal billed = BigDecimal.ZERO, collected = BigDecimal.ZERO, outstanding = BigDecimal.ZERO;
        int levels = 0, levelsSet = 0;
        for (Map<String, Object> p : (List<Map<String, Object>>) ov.get("programs")) {
            Long pid = ((Number) p.get("id")).longValue();
            if (f.programId() != null && !f.programId().equals(pid)) continue;
            if (f.departmentId() != null && !f.departmentId().equals(l.deptOfProgram(pid))) continue;
            for (Map<String, Object> lv : (List<Map<String, Object>>) p.get("levels")) {
                Integer level = ((Number) lv.get("level")).intValue();
                if (f.level() != null && !f.level().equals(level)) continue;
                Map<String, Object> sched = (Map<String, Object>) lv.get("schedule");
                BigDecimal got = (BigDecimal) lv.get("collected");
                BigDecimal bill = (BigDecimal) lv.get("billed");
                BigDecimal owe = (BigDecimal) lv.get("outstanding");
                levels++;
                if (sched != null) levelsSet++;
                collected = collected.add(got == null ? BigDecimal.ZERO : got);
                if (bill != null) billed = billed.add(bill);
                if (owe != null) outstanding = outstanding.add(owe);
                tb.add(p.get("departmentName"), p.get("name"), level, sched == null ? null : sched.get("amount"),
                        lv.get("students"), bill, got, owe, bill == null || bill.signum() == 0 ? null : pct(got.doubleValue(), bill.doubleValue()));
            }
        }

        Table methods = r.table("methods", "How students paid")
                .text("method", "Method").col("payments", "Payments", INT).col("amount", "Amount (" + cur + ")", MONEY).chart("method", "amount");
        for (Object[] row : queries.paymentsByMethod(sessionId)) {
            String m = methodName((FeePayment.Method) row[0]);
            String channel = row[1] == null || row[1].toString().isBlank() ? null : row[1].toString().replace('_', ' ');
            methods.add(channel == null ? m : m + " · " + channel, ((Number) row[2]).longValue(), row[3]);
        }

        String programName = f.programId() == null ? null : l.programName(f.programId());
        Table manual = r.table("manual", "Payments entered or voided by staff")
                .subtitle("Cash, bank transfer and other payments recorded by hand, and every voided payment.")
                .col("date", "Entered", DATETIME).text("reference", "Reference").text("studentId", t.get("studentId"))
                .text("name", "Name").text("programme", t.get("program")).col("level", t.get("level"), INT).text("method", "Method")
                .col("amount", "Amount (" + cur + ")", MONEY).text("status", "Status").text("recordedBy", "Recorded by").text("note", "Note")
                .emptyText("No payments were entered or voided by staff.");
        for (PaymentRow p : queries.manualAndVoidedPayments(sessionId)) {
            if (programName != null && !programName.equals(p.programName())) continue;
            if (f.level() != null && !f.level().equals(p.level())) continue;
            manual.add(minute(p.createdAt()), p.reference(), p.studentUsername(),
                    (Objects.toString(p.studentFirstname(), "") + " " + Objects.toString(p.studentLastname(), "")).trim(),
                    p.programName(), p.level(), methodName(p.method()), p.amount(),
                    p.status() == FeePayment.Status.VOIDED ? "Voided" : p.status() == FeePayment.Status.SUCCESS ? "Paid" : p.status().name(),
                    p.recordedBy(), p.note());
        }

        r.stat("Collected", money(cur, collected), null, "good");
        if (current) {
            r.stat("Billed", money(cur, billed))
             .stat("Outstanding", money(cur, outstanding), null, outstanding.signum() > 0 ? "warn" : null)
             .stat("Collection rate", AcademicReports.fmtPct(billed.signum() == 0 ? null : pct(collected.doubleValue(), billed.doubleValue())));
        }
        r.stat(t.get("level") + "s with a fee set", levelsSet + " of " + levels);
        if (!current) r.note("Billed and outstanding are only worked out for the current session: " + t.get("student").toLowerCase()
                + "s have moved " + t.get("level").toLowerCase() + " since, so a past session's class sizes are not known.");
        r.note("Billed = the fee × active " + t.get("student").toLowerCase() + "s now at that " + t.get("level").toLowerCase()
                + ". Collected counts successful payments only. \"How students paid\" covers the whole session.");
        return r;
    }

    private static String methodName(FeePayment.Method m) {
        if (m == null) return "Unknown";
        return switch (m) {
            case PAYSTACK -> "Online (Paystack)";
            case CASH -> "Cash";
            case BANK_TRANSFER -> "Bank transfer";
            case OTHER -> "Other";
        };
    }

    static String money(String currency, BigDecimal v) {
        return currency + " " + String.format("%,.2f", v == null ? BigDecimal.ZERO : v);
    }

    // ── Results on hold ──────────────────────────────────────────────────────

    /** The debtors whose report cards or transcript are held for fees (same figures as {@link #feeDebtors}). */
    public ReportResult resultsOnHold(ReportFilters f) {
        Map<String, String> t = support.terms();
        String cur = feeScheduleService.currency();
        ReportResult debtors = feeDebtors(f);
        ReportResult r = new ReportResult("results-on-hold", "Results on hold for fees",
                t.get("student") + "s whose " + t.get("reportCard").toLowerCase() + "s or transcript are held until they pay more of this session's fee.");
        debtors.getScope().forEach(r::scope);

        Table src = debtors.getTables().stream().filter(x -> x.getId().equals("debtors")).findFirst().orElseThrow();
        List<String> keep = List.of("studentId", "name", "phone", "email", "programme", "level", "fee", "paid", "balance");
        Table tb = r.table("held", "On hold").emptyText(resultsHoldService.programsWithRule().isEmpty()
                ? "Holding results for unpaid fees is switched off, so nothing is held."
                : "No " + t.get("student").toLowerCase() + " has results on hold.");
        src.getColumns().stream().filter(c -> keep.contains(c.key())).forEach(c -> tb.col(c.key(), c.label(), c.type()));
        BigDecimal owed = BigDecimal.ZERO;
        for (Map<String, Object> row : src.getRows()) {
            if (!"Yes".equals(row.get("held"))) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            keep.forEach(k -> out.put(k, row.get(k)));
            tb.add(out);
            owed = owed.add((BigDecimal) row.get("balance"));
        }
        r.stat("Results on hold", tb.getRows().size(), null, tb.isEmpty() ? null : "warn")
         .stat("Owed by them", money(cur, owed));
        r.note("A " + t.get("student").toLowerCase() + "'s results are held by the Super Admin's rule for their "
                + t.get("program").toLowerCase() + " (Fees › Results hold); they are released as soon as enough is paid.");
        return r;
    }

    // ── Debtors ──────────────────────────────────────────────────────────────

    public ReportResult feeDebtors(ReportFilters f) {
        Map<String, String> t = support.terms();
        String cur = feeScheduleService.currency();
        AcademicSession session = support.currentSession();
        ReportResult r = new ReportResult("fee-debtors", "Fee debtors",
                "Active " + t.get("student").toLowerCase() + "s who still owe part of this session's fee, largest balance first.");
        Lookups l = support.lookups();
        r.scope("Session: " + session.getName() + " (current)");
        support.placeScope(r, f, l);

        Map<List<Object>, FeeSchedule> schedules = new HashMap<>();
        for (FeeSchedule s : scheduleRepository.findWithComponentsBySession(session.getId()))
            schedules.put(List.of(s.getProgram().getId(), s.getLevel()), s);
        Map<List<Long>, BigDecimal> paid = queries.paidByStudentAndSchedule(session.getId());
        Set<Long> holdPrograms = resultsHoldService.programsWithRule();

        Table tb = r.table("debtors", "Debtors")
                .text("studentId", t.get("studentId")).text("name", "Name").text("phone", "Phone").text("email", "Email")
                .text("department", "Department").text("programme", t.get("program")).col("level", t.get("level"), INT)
                .col("fee", "Fee (" + cur + ")", MONEY).col("paid", "Paid (" + cur + ")", MONEY)
                .col("balance", "Balance (" + cur + ")", MONEY).col("paidShare", "Paid", PERCENT);
        if (!holdPrograms.isEmpty()) tb.text("held", "Results on hold");
        Table byLevel = r.table("levels", "By " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase())
                .text("department", "Department").text("programme", t.get("program")).col("level", t.get("level"), INT)
                .col("billed", t.get("student") + "s billed", INT).col("paidUp", "Paid in full", INT).col("debtors", "Debtors", INT)
                .col("owed", "Owed (" + cur + ")", MONEY).chart("programme", "owed");

        List<Map<String, Object>> debtors = new ArrayList<>();
        Map<List<Object>, Object[]> agg = new TreeMap<>(Comparator.comparing((List<Object> k) -> Objects.toString(l.programName((Long) k.get(0)), ""))
                .thenComparing(k -> (Integer) k.get(1)));
        long billedCount = 0, paidUp = 0, held = 0, noFee = 0;
        BigDecimal owedTotal = BigDecimal.ZERO;
        for (StudentRow s : queries.students()) {
            if (!s.enabled() || s.programId() == null || s.level() == null || !l.studentMatches(s, f)) continue;
            FeeSchedule sched = schedules.get(List.of(s.programId(), s.level()));
            if (sched == null) { noFee++; continue; }
            BigDecimal fee = sched.getAmount();
            BigDecimal got = paid.getOrDefault(List.of(s.id(), sched.getId()), BigDecimal.ZERO);
            BigDecimal balance = fee.subtract(got).max(BigDecimal.ZERO);
            billedCount++;
            Object[] a = agg.computeIfAbsent(List.of(s.programId(), s.level()), k -> new Object[]{0L, 0L, 0L, BigDecimal.ZERO});
            a[0] = (Long) a[0] + 1;
            if (balance.signum() <= 0) { paidUp++; a[1] = (Long) a[1] + 1; continue; }
            a[2] = (Long) a[2] + 1;
            a[3] = ((BigDecimal) a[3]).add(balance);
            owedTotal = owedTotal.add(balance);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", s.username());
            row.put("name", s.name());
            row.put("phone", s.phone());
            row.put("email", s.email());
            row.put("department", l.deptName(l.studentDept(s)));
            row.put("programme", l.programName(s.programId()));
            row.put("level", s.level());
            row.put("fee", fee);
            row.put("paid", got);
            row.put("balance", balance);
            row.put("paidShare", fee.signum() == 0 ? null : pct(got.doubleValue(), fee.doubleValue()));
            if (!holdPrograms.isEmpty()) {
                boolean h = holdPrograms.contains(s.programId())
                        && userRepository.findById(s.id()).map(resultsHoldService::isHeld).orElse(false);
                row.put("held", yesNo(h));
                if (h) held++;
            }
            debtors.add(row);
        }
        debtors.sort(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("balance")).reversed());
        debtors.forEach(tb::add);
        agg.forEach((k, a) -> byLevel.add(l.deptName(l.deptOfProgram((Long) k.get(0))), l.programName((Long) k.get(0)), k.get(1),
                a[0], a[1], a[2], a[3]));

        r.stat(t.get("student") + "s billed", billedCount)
         .stat("Paid in full", paidUp, null, "good")
         .stat("Debtors", debtors.size(), null, debtors.isEmpty() ? null : "warn")
         .stat("Total owed", money(cur, owedTotal), null, owedTotal.signum() > 0 ? "bad" : null);
        if (!holdPrograms.isEmpty()) r.stat("Results on hold", held);
        if (noFee > 0) r.note(noFee + " active " + t.get("student").toLowerCase() + "s are in a " + t.get("program").toLowerCase()
                + " and " + t.get("level").toLowerCase() + " with no fee set this session and are not counted.");
        r.note("Each " + t.get("student").toLowerCase() + " is billed the fee for their current " + t.get("program").toLowerCase()
                + " and " + t.get("level").toLowerCase() + ". Paid counts successful payments only.");
        return r;
    }
}
