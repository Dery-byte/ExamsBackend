package com.exam.service.reports;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.*;
import com.exam.repository.*;
import com.exam.service.fees.ConcessionService;
import com.exam.service.fees.FeeDues;
import com.exam.service.fees.FeeScheduleService;
import com.exam.service.fees.ReceiptService;
import com.exam.service.reports.ReportQueries.StudentRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/**
 * The accounts office's reports beyond collections and debtors: every student's account, money in and
 * out day by day, concessions, arrears, refunds and cancelled payments. Each one loads what it needs in
 * a few queries for everyone (no query per student).
 */
@Service
@Transactional(readOnly = true)
public class AccountsReports {

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private FeeDues feeDues;
    @Autowired private FeeScheduleService feeScheduleService;
    @Autowired private UserRepository users;
    @Autowired private FeePaymentRepository payments;
    @Autowired private FeeRefundRepository refunds;
    @Autowired private FeeConcessionRepository concessions;
    @Autowired private FeeArrearsRepository arrears;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private PriceItemRepository priceItems;
    @Autowired private StockMovementRepository movements;
    @Autowired private BillLineRepository billLines;

    // ── Every account ────────────────────────────────────────────────────────

    public ReportResult accountBalances(ReportFilters f) {
        Map<String, String> t = support.terms();
        String cur = feeScheduleService.currency();
        ReportResult r = new ReportResult("account-balances", t.get("student") + " account balances",
                "Every " + t.get("student").toLowerCase() + "'s account: earlier years' fees, this session's fees, bills, credit, and what is left to pay.");
        Lookups l = support.lookups();
        r.scope("Session: " + support.currentSession().getName());
        support.placeScope(r, f, l);

        FeeDues.Book book = feeDues.book(support.currentSession());
        Map<Long, BigDecimal[]> ac = feeDues.arrearsAndCredit();
        Map<Long, BigDecimal> billsOwed = feeDues.billsOwed();
        Map<Long, User> byId = new HashMap<>();
        for (User u : users.findByRole(Role.NORMAL)) byId.put(u.getId(), u);

        Table tb = r.table("accounts", "Accounts").text("studentId", t.get("studentId")).text("name", "Name")
                .text("programme", t.get("program")).col("level", t.get("level"), INT).text("status", "Account")
                .col("arrears", "Earlier years (" + cur + ")", MONEY).col("fees", "This session (" + cur + ")", MONEY)
                .col("bills", "Bills (" + cur + ")", MONEY).col("credit", "Credit (" + cur + ")", MONEY)
                .col("toPay", "To pay (" + cur + ")", MONEY);
        BigDecimal tArrears = BigDecimal.ZERO, tFees = BigDecimal.ZERO, tBills = BigDecimal.ZERO, tCredit = BigDecimal.ZERO, tPay = BigDecimal.ZERO;
        int owing = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StudentRow s : queries.students()) {
            if (!l.studentMatches(s, f)) continue;
            User u = byId.get(s.id());
            BigDecimal[] v = ac.getOrDefault(s.id(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal fees = u == null || !s.enabled() ? BigDecimal.ZERO
                    : book.dues(u).stream().map(FeeDues.Due::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal bill = billsOwed.getOrDefault(s.id(), BigDecimal.ZERO);
            BigDecimal owed = v[0].add(fees).add(bill);
            BigDecimal toPay = owed.subtract(v[1]).max(BigDecimal.ZERO);
            if (owed.signum() == 0 && v[1].signum() == 0) continue;     // nothing on the account
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", s.username());
            row.put("name", s.name());
            row.put("programme", l.programName(s.programId()));
            row.put("level", s.level());
            row.put("status", s.enabled() ? "Active" : "Left / deactivated");
            row.put("arrears", v[0]);
            row.put("fees", fees);
            row.put("bills", bill);
            row.put("credit", v[1]);
            row.put("toPay", toPay);
            rows.add(row);
            tArrears = tArrears.add(v[0]); tFees = tFees.add(fees); tBills = tBills.add(bill); tCredit = tCredit.add(v[1]); tPay = tPay.add(toPay);
            if (toPay.signum() > 0) owing++;
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("toPay")).reversed());
        rows.forEach(tb::add);
        r.stat("Accounts", rows.size()).stat("Still owing", owing, null, owing > 0 ? "warn" : "good")
         .stat("To pay in all", money(cur, tPay), null, tPay.signum() > 0 ? "bad" : null)
         .stat("Earlier years", money(cur, tArrears)).stat("This session", money(cur, tFees))
         .stat("Bills", money(cur, tBills)).stat("Credit held", money(cur, tCredit));
        r.note("To pay = earlier years + this session's fees so far + open bills, less credit. Credit is used on what is owed automatically; "
                + "what is left is money held for the " + t.get("student").toLowerCase() + " (it can be refunded).");
        return r;
    }

    // ── Day by day ───────────────────────────────────────────────────────────

    public ReportResult dailyCollections(ReportFilters f) {
        String cur = feeScheduleService.currency();
        ReportResult r = new ReportResult("daily-collections", "Daily collections",
                "Money received each day by method, refunds paid, and the day's net, as in the cash book.");
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        LocalDate from = f.from() != null ? f.from() : to.withDayOfMonth(1);
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        if (from.plusDays(400).isBefore(to)) throw new IllegalArgumentException("Choose a period of a year or less.");
        r.scope("Period: " + day(from) + " – " + day(to));
        LocalDateTime a = from.atStartOfDay(), b = to.plusDays(1).atStartOfDay();

        Map<LocalDate, BigDecimal[]> days = new TreeMap<>(Comparator.reverseOrder());   // [count, cash, bank, online, other, refunds]
        for (Object[] row : payments.amountsBetween(a, b)) {
            BigDecimal[] d = days.computeIfAbsent(((LocalDateTime) row[0]).toLocalDate(), k -> zeros(6));
            d[0] = d[0].add(BigDecimal.ONE);
            int col = switch ((FeePayment.Method) row[1]) { case CASH -> 1; case BANK_TRANSFER -> 2; case PAYSTACK -> 3; default -> 4; };
            d[col] = d[col].add((BigDecimal) row[2]);
        }
        for (Object[] row : refunds.amountsBetween(FeeRefund.Status.DONE, a, b)) {
            BigDecimal[] d = days.computeIfAbsent(((LocalDateTime) row[0]).toLocalDate(), k -> zeros(6));
            d[5] = d[5].add((BigDecimal) row[2]);
        }
        Table tb = r.table("days", "By day").col("date", "Date", DATE).col("payments", "Payments", INT)
                .col("cash", "Cash (" + cur + ")", MONEY).col("bank", "Bank transfer (" + cur + ")", MONEY)
                .col("online", "Online (" + cur + ")", MONEY).col("other", "Other (" + cur + ")", MONEY)
                .col("received", "Received (" + cur + ")", MONEY).col("refunds", "Refunded (" + cur + ")", MONEY)
                .col("net", "Net (" + cur + ")", MONEY).chart("date", "received").emptyText("Nothing was received in this period.");
        BigDecimal[] tot = zeros(7);
        days.forEach((day, d) -> {
            BigDecimal received = d[1].add(d[2]).add(d[3]).add(d[4]);
            tb.add(day.toString(), d[0].intValue(), d[1], d[2], d[3], d[4], received, d[5], received.subtract(d[5]));
            for (int i = 0; i < 6; i++) tot[i] = tot[i].add(d[i]);
            tot[6] = tot[6].add(received);
        });
        r.stat("Received", money(cur, tot[6]), null, "good").stat("Payments", tot[0].intValue())
         .stat("Cash", money(cur, tot[1])).stat("Bank transfer", money(cur, tot[2])).stat("Online", money(cur, tot[3]))
         .stat("Refunded", money(cur, tot[5])).stat("Net", money(cur, tot[6].subtract(tot[5])));
        r.note("Counts successful payments by the day they were received; a payment cancelled later is not counted. Leave the dates empty for this month.");
        return r;
    }

    // ── Concessions ──────────────────────────────────────────────────────────

    public ReportResult concessions(ReportFilters f) {
        Map<String, String> t = support.terms();
        String cur = feeScheduleService.currency();
        var session = f.sessionId() == null ? support.currentSession() : support.session(f.sessionId());
        ReportResult r = new ReportResult("concessions", "Fee concessions",
                "Scholarships, bursaries, discounts and waivers on school fees, and what they are worth.");
        Lookups l = support.lookups();
        r.scope("Session: " + session.getName());
        support.placeScope(r, f, l);

        List<FeeConcession> all = concessions.findAllBySession(session.getId());
        FeeDues.Book book = feeDues.wholeSession(session);
        Map<Long, BigDecimal> saved = new HashMap<>();
        Table tb = r.table("concessions", "Concessions").text("studentId", t.get("studentId")).text("name", "Name")
                .text("programme", t.get("program")).col("level", t.get("level"), INT).text("kind", "Kind").text("title", "Title")
                .text("value", "Value").text("covers", "Covers").text("status", "Status").text("by", "Granted by").col("date", "Granted", DATE)
                .col("worth", t.get("student") + " saves this session (" + cur + ")", MONEY).text("note", "Reason");
        Map<String, BigDecimal> byKind = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int active = 0;
        for (FeeConcession c : all) {
            User u = c.getStudent();
            if (f.programId() != null && (u.getProgram() == null || !f.programId().equals(u.getProgram().getId()))) continue;
            if (f.level() != null && !f.level().equals(u.getCurrentLevel())) continue;
            BigDecimal worth = null;
            if (c.isActive()) {
                active++;
                if (!saved.containsKey(u.getId())) {     // per student, all their concessions together
                    BigDecimal s = book.dues(u).stream().map(FeeDues.Due::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    saved.put(u.getId(), s);
                    worth = s;
                    total = total.add(s);
                    byKind.merge(ConcessionService.kindLabel(c.getKind()), s, BigDecimal::add);
                }
            }
            tb.add(u.getUsername(), name(u), u.getProgram() != null ? u.getProgram().getName() : null, u.getCurrentLevel(),
                    ConcessionService.kindLabel(c.getKind()), c.getTitle(),
                    c.getMode() == FeeConcession.Mode.PERCENT ? c.getValue().stripTrailingZeros().toPlainString() + "%" : money(cur, c.getValue()) + " per fee",
                    (c.getItems() == null ? "Whole fee" : c.getItems().replace("\n", ", ")) + (c.getTerm() == null ? "" : " · " + FeeDues.periodName(c.getTerm())),
                    c.isActive() ? "Active" : "Revoked: " + Objects.toString(c.getRevokeReason(), ""), c.getCreatedBy(), day(c.getCreatedAt()), worth, c.getNote());
        }
        Table kinds = r.table("kinds", "By kind").text("kind", "Kind").col("amount", "Worth (" + cur + ")", MONEY).chart("kind", "amount");
        byKind.forEach(kinds::add);
        r.stat("Active concessions", active).stat(t.get("student") + "s", saved.size()).stat("Fees forgone", money(cur, total), null, "warn");
        r.note("Worth = what each " + t.get("student").toLowerCase() + "'s fees for the whole session come to less with their concessions (every "
                + t.get("semester").toLowerCase() + ", whether due yet or not). Several concessions for one " + t.get("student").toLowerCase()
                + " are counted together on their first line.");
        return r;
    }

    // ── Arrears ──────────────────────────────────────────────────────────────

    public ReportResult arrears(ReportFilters f) {
        Map<String, String> t = support.terms();
        String cur = feeScheduleService.currency();
        ReportResult r = new ReportResult("arrears", "Arrears",
                "Fees still owed from earlier sessions and opening balances, with what has been paid and written off.");
        Lookups l = support.lookups();
        support.placeScope(r, f, l);
        List<FeeArrears> all = arrears.findAllWithStudent();
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Object[] row : allocations.paidByArrears(all.stream().map(FeeArrears::getId).toList())) paid.put((Long) row[0], (BigDecimal) row[1]);
        Table tb = r.table("arrears", "Arrears").text("studentId", t.get("studentId")).text("name", "Name").text("account", "Account")
                .text("from", "From").text("class", "Owed for").col("amount", "Carried (" + cur + ")", MONEY).col("paid", "Paid (" + cur + ")", MONEY)
                .col("writtenOff", "Written off (" + cur + ")", MONEY).col("balance", "Balance (" + cur + ")", MONEY).text("note", "Note");
        Map<String, BigDecimal[]> bySource = new TreeMap<>();
        BigDecimal owed = BigDecimal.ZERO, got = BigDecimal.ZERO, gone = BigDecimal.ZERO;
        for (FeeArrears a : all) {
            User u = a.getStudent();
            if (f.programId() != null && (u.getProgram() == null || !f.programId().equals(u.getProgram().getId()))) continue;
            if (f.level() != null && !f.level().equals(u.getCurrentLevel())) continue;
            FeeDues.ArrearsDue d = new FeeDues.ArrearsDue(a, paid.getOrDefault(a.getId(), BigDecimal.ZERO));
            BigDecimal off = a.isOpen() ? BigDecimal.ZERO : Objects.requireNonNullElse(a.getWrittenOff(), BigDecimal.ZERO);
            String from = a.getSession() != null ? a.getSession().getName() : "Opening balance";
            tb.add(u.getUsername(), name(u), u.isEnabled() ? "Active" : "Left / deactivated", from,
                    a.getProgramName() == null ? null : a.getProgramName() + (a.getLevel() == null || support.isSchool() ? "" : " · " + t.get("level") + " " + a.getLevel()),
                    a.getAmount(), d.paid(), off, d.balance(), a.isOpen() ? a.getNote() : "Written off: " + Objects.toString(a.getStatusNote(), ""));
            BigDecimal[] s = bySource.computeIfAbsent(from, k -> zeros(3));
            s[0] = s[0].add(d.balance()); s[1] = s[1].add(d.paid()); s[2] = s[2].add(off);
            owed = owed.add(d.balance()); got = got.add(d.paid()); gone = gone.add(off);
        }
        Table src = r.table("sources", "By session").text("from", "From").col("owed", "Still owed (" + cur + ")", MONEY)
                .col("paid", "Paid (" + cur + ")", MONEY).col("writtenOff", "Written off (" + cur + ")", MONEY).chart("from", "owed");
        bySource.forEach((k, v) -> src.add(k, v[0], v[1], v[2]));
        r.stat("Still owed", money(cur, owed), null, owed.signum() > 0 ? "bad" : null).stat("Paid since", money(cur, got), null, "good")
         .stat("Written off", money(cur, gone));
        r.note("Arrears are paid before this session's fees. Closing a session carries what each " + t.get("student").toLowerCase()
                + " still owed for it; opening balances are debts from before the school used this system.");
        return r;
    }

    // ── Refunds and cancelled payments ───────────────────────────────────────

    public ReportResult refundsAndVoids(ReportFilters f) {
        String cur = feeScheduleService.currency();
        ReportResult r = new ReportResult("refunds-voids", "Refunds and cancelled payments",
                "Money paid back to students, and recorded payments cancelled, with who did it and why.");
        Period p = support.period(f);
        support.periodScope(r, p);
        LocalDateTime a = p.from() != null ? p.from() : Period.EARLIEST, b = p.to() != null ? p.to() : Period.LATEST;
        Table rf = r.table("refunds", "Refunds").text("number", "Voucher").col("date", "Paid", DATETIME).text("studentId", support.terms().get("studentId"))
                .text("name", "Name").text("method", "Method").col("amount", "Amount (" + cur + ")", MONEY).text("paidTo", "Paid to")
                .text("reason", "Reason").text("by", "By").text("status", "Status").emptyText("No refunds in this period.");
        BigDecimal refunded = BigDecimal.ZERO;
        for (FeeRefund x : refunds.touchedBetween(a, b)) {
            rf.add(x.getNumber(), minute(x.getPaidAt()), x.getStudent().getUsername(), name(x.getStudent()), ReceiptService.methodName(x.getMethod()),
                    x.getAmount(), x.getPaidTo(), x.getReason(), x.getPaidBy(),
                    x.getStatus() == FeeRefund.Status.VOIDED ? "Cancelled by " + x.getVoidedBy() + ": " + x.getVoidReason() : "Paid");
            if (x.getStatus() == FeeRefund.Status.DONE) refunded = refunded.add(x.getAmount());
        }
        Table vd = r.table("voids", "Cancelled payments").text("receiptNo", "Receipt").col("received", "Received", DATETIME)
                .col("voided", "Cancelled", DATETIME).text("studentId", support.terms().get("studentId")).text("name", "Name")
                .text("method", "Method").col("amount", "Amount (" + cur + ")", MONEY).text("recordedBy", "Recorded by")
                .text("voidedBy", "Cancelled by").text("note", "Reason").emptyText("No payments were cancelled in this period.");
        BigDecimal voided = BigDecimal.ZERO;
        for (FeePayment x : payments.voidedBetween(a, b)) {
            vd.add(x.getReceiptNo(), minute(x.getPaidAt()), minute(x.getVoidedAt()), x.getStudent().getUsername(), name(x.getStudent()),
                    ReceiptService.methodName(x.getMethod()), x.getAmount(), x.getRecordedBy(), x.getVoidedBy(), x.getNote());
            voided = voided.add(x.getAmount());
        }
        r.stat("Refunded", money(cur, refunded)).stat("Refunds", rf.getRows().size())
         .stat("Cancelled payments", vd.getRows().size(), null, vd.isEmpty() ? null : "warn").stat("Cancelled amount", money(cur, voided));
        r.note("Payments cancelled before records began to show who cancelled them have no cancellation date and are not listed.");
        return r;
    }

    // ── Stores ───────────────────────────────────────────────────────────────

    public ReportResult stock(ReportFilters f) {
        String cur = feeScheduleService.currency();
        ReportResult r = new ReportResult("stock", "Stores",
                "What the store holds of each item, what came in and went out in the period, what is billed but not handed over, and its value.");
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        LocalDate from = f.from() != null ? f.from() : to.withDayOfMonth(1);
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        r.scope("Period: " + day(from) + " – " + day(to));
        Map<Long, Map<StockMovement.Kind, Long>> moved = new HashMap<>();
        for (Object[] row : movements.totalsBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
            moved.computeIfAbsent((Long) row[0], k -> new EnumMap<>(StockMovement.Kind.class)).put((StockMovement.Kind) row[1], ((Number) row[2]).longValue());
        Map<Long, Long> owed = new HashMap<>();
        for (Object[] row : billLines.owedByItem(StudentBill.Status.OPEN)) owed.put((Long) row[0], ((Number) row[1]).longValue());
        Table tb = r.table("items", "Items").text("item", "Item").col("received", "Received", INT).col("handedOver", "Handed over", INT)
                .col("returned", "Returned", INT).col("adjusted", "Corrected", INT).col("onHand", "In the store", INT)
                .col("toHandOver", "Billed, not handed over", INT).col("short", "Short", INT).text("low", "Reorder")
                .col("value", "Value at cost (" + cur + ")", MONEY).emptyText("No item is kept in the store. Turn on stock for items on the price list.");
        BigDecimal value = BigDecimal.ZERO;
        int low = 0;
        for (PriceItem i : priceItems.findByStockedTrueOrderByNameAsc()) {
            Map<StockMovement.Kind, Long> m = moved.getOrDefault(i.getId(), Map.of());
            long due = owed.getOrDefault(i.getId(), 0L);
            boolean isLow = i.getReorderLevel() != null && i.getStockOnHand() <= i.getReorderLevel();
            BigDecimal v = i.getUnitCost() == null ? null : i.getUnitCost().multiply(BigDecimal.valueOf(i.getStockOnHand()));
            if (v != null) value = value.add(v);
            if (isLow) low++;
            tb.add(i.getName(), m.getOrDefault(StockMovement.Kind.RECEIVED, 0L), -m.getOrDefault(StockMovement.Kind.ISSUED, 0L),
                    m.getOrDefault(StockMovement.Kind.RETURNED, 0L), m.getOrDefault(StockMovement.Kind.ADJUSTED, 0L), i.getStockOnHand(),
                    due, Math.max(0, due - i.getStockOnHand()), isLow ? "Reorder" : "", v);
        }
        r.stat("Items kept", tb.getRows().size()).stat("To reorder", low, null, low > 0 ? "warn" : null).stat("Value at cost", money(cur, value));
        r.note("Short = billed but not handed over, beyond what the store holds. Value uses each item's cost at its last delivery.");
        return r;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String money(String cur, BigDecimal v) {
        return FinanceReports.money(cur, v);
    }
}
