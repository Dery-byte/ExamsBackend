package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.SystemMode;
import com.exam.model.fees.FeeArrears;
import com.exam.model.fees.FeeSessionClose;
import com.exam.repository.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.CurrentSessionChanged;
import com.exam.service.comms.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The year end: unpaid fees never disappear when a new session starts.
 * <ul>
 *   <li><b>Closing</b> a session's fees turns what each student still owes for it (every term, after
 *       concessions) into arrears, paid before anything newer. Done by the bursar, or by the system
 *       the moment the next session is made current. The class used is the one the student paid fees
 *       for that session, else their class now, so close before promoting students when you can.</li>
 *   <li><b>Reopening</b> undoes it, only while the session is still current and nobody has paid
 *       towards those arrears.</li>
 *   <li><b>Opening balances</b>: what students owed before the school used this system, imported once.</li>
 *   <li><b>Write-off</b>: the bursar gives up what is left of an arrears line (recorded; can be restored).</li>
 * </ul>
 */
@Service
public class YearEndService {

    private static final Logger log = LoggerFactory.getLogger(YearEndService.class);
    private static final int MAX_ROWS = 5000;

    @Autowired private FeeSessionCloseRepository closes;
    @Autowired private FeeArrearsRepository arrearsRepository;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private FeeScheduleRepository schedules;
    @Autowired private AcademicSessionRepository sessions;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private UserRepository users;
    @Autowired private FeeDues feeDues;
    @Autowired private FeeLedger ledger;
    @Autowired private FeeScheduleService scheduleService;

    public record ReasonRequest(String reason) {}
    public record OpeningRequest(List<Map<String, String>> rows) {}

    // ── Overview ─────────────────────────────────────────────────────────────

    /** The year-end page: the current session ready to close (or closed), every close, and all arrears with what is paid. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        AcademicSession current = academicSessions.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("session", FeeScheduleService.sessionDto(current));
        out.put("closes", closes.findAllByOrderByClosedAtDesc().stream().map(this::closeDto).toList());
        out.put("preview", closes.existsBySession_Id(current.getId()) ? null : preview(current));

        List<FeeArrears> all = arrearsRepository.findAllWithStudent();
        Map<Long, BigDecimal> paid = FeeDues.sums(allocations.paidByArrears(all.stream().map(FeeArrears::getId).toList()));
        BigDecimal owed = BigDecimal.ZERO, written = BigDecimal.ZERO, received = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FeeArrears a : all) {
            FeeDues.ArrearsDue d = new FeeDues.ArrearsDue(a, paid.getOrDefault(a.getId(), BigDecimal.ZERO));
            owed = owed.add(d.balance());
            received = received.add(d.paid());
            if (a.getWrittenOff() != null && !a.isOpen()) written = written.add(a.getWrittenOff());
            Map<String, Object> m = arrearsDto(d);
            m.put("studentId", a.getStudent().getId());
            m.put("studentName", CurrentUserService.displayName(a.getStudent()));
            m.put("username", a.getStudent().getUsername());
            m.put("enabled", a.getStudent().isEnabled());
            rows.add(m);
        }
        out.put("arrears", rows);
        out.put("totals", Map.of("owed", owed, "paid", received, "writtenOff", written, "lines", all.size()));
        return out;
    }

    /** What closing would carry: per class, from every term of the session. */
    private Map<String, Object> preview(AcademicSession session) {
        FeeDues.Book book = feeDues.wholeSession(session);
        Map<String, Object[]> byClass = new TreeMap<>();
        int debtors = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (User u : users.findByRole(Role.NORMAL)) {
            List<FeeDues.Due> dues = book.dues(u);
            if (dues.isEmpty()) continue;
            BigDecimal bal = dues.stream().map(FeeDues.Due::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            // Schools: the class name says it all (a class is one year)
            String cls = dues.get(0).schedule().getProgram().getName()
                    + (SystemMode.current().isSchool() ? "" : " · " + levelWord() + " " + dues.get(0).schedule().getLevel());
            Object[] a = byClass.computeIfAbsent(cls, k -> new Object[]{0, 0, BigDecimal.ZERO});
            a[0] = (int) a[0] + 1;
            if (bal.signum() > 0) { a[1] = (int) a[1] + 1; a[2] = ((BigDecimal) a[2]).add(bal); debtors++; total = total.add(bal); }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", session.getId());
        m.put("sessionName", session.getName());
        m.put("hasFees", !schedules.findWithComponentsBySession(session.getId()).isEmpty());
        m.put("debtors", debtors);
        m.put("total", total);
        m.put("classes", byClass.entrySet().stream().map(e -> Map.of("name", e.getKey(), "students", e.getValue()[0],
                "debtors", e.getValue()[1], "owed", e.getValue()[2])).toList());
        return m;
    }

    // ── Closing and reopening ────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> close(User actor, Long sessionId) {
        FinanceAccess.requireBursar(actor);
        AcademicSession s = session(sessionId);
        FeeSessionClose c = doClose(s, false, CurrentUserService.displayName(actor));
        if (c == null) throw new IllegalArgumentException("No fees were set for " + s.getName() + ", so there is nothing to close.");
        return closeDto(c);
    }

    /** After a newer session is made current (and that change is saved), closes the old one's fees if still open. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionChanged(CurrentSessionChanged e) {
        try {
            AcademicSession prev = sessions.findById(e.previousId()).orElse(null), next = sessions.findById(e.currentId()).orElse(null);
            if (prev == null || next == null || closes.existsBySession_Id(prev.getId())) return;
            boolean forward = prev.getStartDate() == null || next.getStartDate() == null || next.getStartDate().isAfter(prev.getStartDate());
            if (!forward) return;                      // going back to an older session closes nothing
            FeeSessionClose c = doClose(prev, true, "System (new session made current)");
            if (c != null) log.info("[Fees] Closed {} fees: {} students carried {} as arrears", prev.getName(), c.getStudents(), c.getTotal());
        } catch (Exception ex) {
            log.error("[Fees] Could not close the fees of session {} automatically: {}", e.previousId(), ex.getMessage(), ex);
        }
    }

    /**
     * Carries every student's unpaid fees for the session into arrears. Students who owe are locked (in
     * id order) and their balances read again under the lock, so a payment arriving meanwhile is either
     * counted here or goes to the arrears afterwards, never both. Null when the session has no fees.
     */
    private FeeSessionClose doClose(AcademicSession s, boolean automatic, String by) {
        if (closes.existsBySession_Id(s.getId())) throw new IllegalArgumentException(s.getName() + " fees are already closed.");
        if (schedules.findWithComponentsBySession(s.getId()).isEmpty()) return null;
        List<User> students = users.findByRole(Role.NORMAL);
        FeeDues.Book first = feeDues.wholeSession(s);
        List<User> owing = students.stream().filter(u -> first.dues(u).stream().anyMatch(d -> d.balance().signum() > 0 || d.credit().signum() > 0))
                .sorted(Comparator.comparing(User::getId)).toList();
        owing.forEach(ledger::lock);
        FeeDues.Book book = feeDues.wholeSession(s);            // read again under the locks

        List<FeeArrears> made = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (User u : owing) {
            List<FeeDues.Due> dues = book.dues(u);
            ledger.releaseExcess(u, dues);                     // overpaid on a fee: back to credit
            BigDecimal bal = dues.stream().map(FeeDues.Due::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (bal.signum() <= 0) continue;
            FeeArrears a = new FeeArrears();
            a.setStudent(u);
            a.setSession(s);
            a.setKind(FeeArrears.Kind.CARRIED);
            a.setAmount(bal);
            a.setProgramName(dues.get(0).schedule().getProgram().getName());
            a.setLevel(dues.get(0).schedule().getLevel());
            a.setNote("Unpaid " + s.getName() + " fees: " + String.join(", ", dues.stream().filter(d -> d.balance().signum() > 0)
                    .map(d -> FeeDues.periodName(d.schedule())).toList()));
            a.setCreatedBy(by);
            made.add(a);
            total = total.add(bal);
        }
        arrearsRepository.saveAll(made);
        FeeSessionClose c = new FeeSessionClose();
        c.setSession(s);
        c.setStudents(made.size());
        c.setTotal(total);
        c.setAutomatic(automatic);
        c.setClosedBy(by);
        closes.saveAndFlush(c);
        // Credit waiting (an overpayment released above, or older credit) goes to the new arrears
        for (User u : owing) ledger.applyCredit(u);
        return c;
    }

    @Transactional
    public Map<String, Object> reopen(User actor, Long sessionId) {
        FinanceAccess.requireBursar(actor);
        AcademicSession s = session(sessionId);
        FeeSessionClose c = closes.findBySession_Id(s.getId()).orElseThrow(() -> new IllegalArgumentException(s.getName() + " fees are not closed."));
        if (!s.isCurrent())
            throw new IllegalArgumentException(s.getName() + " is no longer the current session, so its fees stay closed. Write off arrears one by one instead.");
        List<FeeArrears> carried = arrearsRepository.findBySession(s.getId());
        int paidOn = allocations.paidByArrears(carried.stream().map(FeeArrears::getId).toList()).size();
        if (paidOn > 0)
            throw new IllegalArgumentException(paidOn + " student" + (paidOn == 1 ? " has" : "s have") + " already paid towards these arrears, so the session can't be reopened.");
        arrearsRepository.deleteAll(carried);
        closes.delete(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reopened", s.getName());
        out.put("removed", carried.size());
        return out;
    }

    // ── Opening balances ─────────────────────────────────────────────────────

    /**
     * Opening balances from a sheet: student ID, amount, note. apply=false checks every row; apply=true
     * saves the good ones. A student's earlier opening balance is replaced, unless they already paid on it.
     */
    @Transactional
    public Map<String, Object> importOpening(User actor, List<Map<String, String>> rows, boolean apply) {
        FinanceAccess.requireBursar(actor);
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("The sheet has no rows.");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("Import " + MAX_ROWS + " rows at most at a time.");
        List<String[]> parsed = rows.stream().map(YearEndService::columns).toList();
        Map<String, User> byId = new HashMap<>();
        for (User u : users.findByRoleAndUsernameIn(Role.NORMAL, parsed.stream().map(p -> p[0]).filter(Objects::nonNull).toList()))
            byId.put(u.getUsername().toLowerCase(Locale.ROOT), u);
        Map<Long, FeeArrears> existing = new HashMap<>();
        for (FeeArrears a : arrearsRepository.findByStudentsAndKind(byId.values().stream().map(User::getId).toList(), FeeArrears.Kind.OPENING))
            existing.put(a.getStudent().getId(), a);
        Set<Long> paidOn = new HashSet<>();
        for (Object[] r : allocations.paidByArrears(existing.values().stream().map(FeeArrears::getId).toList())) paidOn.add((Long) r[0]);

        String by = CurrentUserService.displayName(actor);
        List<Map<String, Object>> report = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<FeeArrears> save = new ArrayList<>();
        List<User> touched = new ArrayList<>();
        int ok = 0, bad = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < parsed.size(); i++) {
            String[] p = parsed.get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("row", i + 2);           // the sheet's header is row 1
            line.put("studentId", p[0]);
            String problem = null;
            User u = p[0] == null ? null : byId.get(p[0].toLowerCase(Locale.ROOT));
            BigDecimal amount = null;
            try { amount = p[1] == null ? null : FeeScheduleService.money(new BigDecimal(p[1].replace(",", "").trim()), "The amount"); }
            catch (NumberFormatException e) { problem = "The amount isn't a number."; }
            catch (IllegalArgumentException e) { problem = e.getMessage(); }
            if (p[0] == null) problem = "No " + SystemMode.current().terms().get("studentId") + ".";
            else if (u == null) problem = "No student with this ID.";
            else if (amount == null && problem == null) problem = "No amount.";
            else if (!seen.add(u.getId())) problem = "This student is in the sheet twice.";
            else if (existing.containsKey(u.getId()) && paidOn.contains(existing.get(u.getId()).getId())) problem = "Already paying an opening balance; change it on their account.";
            if (u != null) line.put("name", CurrentUserService.displayName(u));
            line.put("amount", amount);
            if (problem != null) { line.put("status", "ERROR"); line.put("message", problem); bad++; report.add(line); continue; }
            FeeArrears a = existing.get(u.getId());
            line.put("status", a == null ? "NEW" : "REPLACE");
            if (a != null) line.put("message", "Replaces " + scheduleService.currency() + " " + a.getAmount().toPlainString());
            ok++;
            total = total.add(amount);
            report.add(line);
            if (!apply) continue;
            if (a == null) {
                a = new FeeArrears();
                a.setStudent(u);
                a.setKind(FeeArrears.Kind.OPENING);
                a.setProgramName(u.getProgram() != null ? u.getProgram().getName() : null);
                a.setLevel(u.getCurrentLevel());
                a.setCreatedBy(by);
            }
            a.setAmount(amount);
            a.setNote(p[2] != null ? trim(p[2], 300) : "Opening balance");
            a.setStatus(FeeArrears.Status.OPEN);
            save.add(a);
            touched.add(u);
        }
        if (apply && !save.isEmpty()) {
            arrearsRepository.saveAll(save);
            arrearsRepository.flush();
            touched.stream().sorted(Comparator.comparing(User::getId)).forEach(ledger::applyCredit);   // money they already had goes to it
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("errors", bad);
        out.put("total", total);
        out.put("applied", apply);
        out.put("rows", report);
        return out;
    }

    /** One student's opening balance, from their account page. */
    @Transactional
    public Map<String, Object> setOpening(User actor, Long studentId, BigDecimal amount, String note) {
        User u = users.findById(studentId).filter(x -> x.getRole() == Role.NORMAL).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        Map<String, String> row = new HashMap<>();
        row.put("studentid", u.getUsername());
        row.put("amount", amount == null ? null : amount.toPlainString());
        row.put("note", note);
        Map<String, Object> r = importOpening(actor, List.of(row), true);
        @SuppressWarnings("unchecked") Map<String, Object> line = ((List<Map<String, Object>>) r.get("rows")).get(0);
        if ("ERROR".equals(line.get("status"))) throw new IllegalArgumentException((String) line.get("message"));
        return line;
    }

    // ── Write-off ────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> writeOff(User actor, Long id, ReasonRequest req) {
        FinanceAccess.requireBursar(actor);
        String reason = ConcessionService.clean(req == null ? null : req.reason(), 200);
        if (reason == null) throw new IllegalArgumentException("Give a reason for writing it off.");
        FeeArrears a = arrearsRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Arrears not found."));
        ledger.lock(a.getStudent());
        if (!a.isOpen()) throw new IllegalArgumentException("This was already written off.");
        BigDecimal paid = FeeDues.sums(allocations.paidByArrears(List.of(a.getId()))).getOrDefault(a.getId(), BigDecimal.ZERO);
        BigDecimal left = a.getAmount().subtract(paid);
        if (left.signum() <= 0) throw new IllegalArgumentException("Nothing is left to write off: it is paid.");
        a.setStatus(FeeArrears.Status.WRITTEN_OFF);
        a.setWrittenOff(left);
        mark(a, actor, reason);
        return arrearsDto(new FeeDues.ArrearsDue(arrearsRepository.save(a), paid));
    }

    @Transactional
    public Map<String, Object> restore(User actor, Long id) {
        FinanceAccess.requireBursar(actor);
        FeeArrears a = arrearsRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Arrears not found."));
        ledger.lock(a.getStudent());
        if (a.isOpen()) throw new IllegalArgumentException("This is not written off.");
        a.setStatus(FeeArrears.Status.OPEN);
        a.setWrittenOff(null);
        mark(a, actor, "Restored");
        arrearsRepository.saveAndFlush(a);
        ledger.applyCredit(a.getStudent());
        BigDecimal paid = FeeDues.sums(allocations.paidByArrears(List.of(a.getId()))).getOrDefault(a.getId(), BigDecimal.ZERO);
        return arrearsDto(new FeeDues.ArrearsDue(a, paid));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public static Map<String, Object> arrearsDto(FeeDues.ArrearsDue d) {
        FeeArrears a = d.arrears();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("label", a.label());
        m.put("kind", a.getKind().name());
        m.put("sessionName", a.getSession() != null ? a.getSession().getName() : null);
        m.put("programName", a.getProgramName());
        m.put("level", a.getLevel());
        m.put("amount", a.getAmount());
        m.put("paid", d.paid());
        m.put("balance", d.balance());
        m.put("status", d.status());
        m.put("writtenOff", a.getWrittenOff());
        m.put("note", a.getNote());
        m.put("statusNote", a.getStatusNote());
        m.put("statusBy", a.getStatusBy());
        m.put("statusAt", a.getStatusAt());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private Map<String, Object> closeDto(FeeSessionClose c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", c.getSession().getId());
        m.put("sessionName", c.getSession().getName());
        m.put("current", c.getSession().isCurrent());
        m.put("students", c.getStudents());
        m.put("total", c.getTotal());
        m.put("automatic", c.isAutomatic());
        m.put("closedBy", c.getClosedBy());
        m.put("closedAt", c.getClosedAt());
        return m;
    }

    private static void mark(FeeArrears a, User actor, String note) {
        a.setStatusBy(CurrentUserService.displayName(actor));
        a.setStatusAt(LocalDateTime.now());
        a.setStatusNote(note);
    }

    private AcademicSession session(Long id) {
        if (id == null) return academicSessions.current();
        return sessions.findById(id).orElseThrow(() -> new IllegalArgumentException("Session not found."));
    }

    private static String levelWord() {
        return SystemMode.current().terms().get("level");
    }

    /** [student ID, amount, note] from a sheet row, whatever the headings are called. */
    private static String[] columns(Map<String, String> row) {
        String[] out = new String[3];
        for (Map.Entry<String, String> e : row.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            String v = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue().trim();
            if (v == null) continue;
            if (out[0] == null && (k.equals("studentid") || k.equals("admissionno") || k.equals("admissionnumber") || k.equals("username") || k.equals("id") || k.equals("indexno"))) out[0] = v;
            else if (out[1] == null && (k.equals("amount") || k.equals("balance") || k.equals("owed") || k.equals("openingbalance"))) out[1] = v;
            else if (out[2] == null && (k.equals("note") || k.equals("description") || k.equals("remarks"))) out[2] = v;
        }
        return out;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
