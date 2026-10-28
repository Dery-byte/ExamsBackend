package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.SystemMode;
import com.exam.model.fees.*;
import com.exam.repository.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * What a student owes, worked out the same way everywhere (their fees page, the cashier's screen,
 * results holds, reports). Their account holds, oldest first:
 * <ol>
 *   <li><b>arrears</b>: fees still owed when an earlier session was closed, and any opening balance;</li>
 *   <li><b>this session's fees</b> for their class: the whole-year fee, or each term (semester) up to the
 *       one they are in (later terms are "coming up"). An itemised fee counts only the items for them
 *       (boarders' or day students' items by residency; unknown residency pays the everyone items
 *       only), less their <b>concessions</b> (scholarship, discount, waiver). Once the session's fees
 *       are closed they are owed as arrears instead;</li>
 *   <li><b>bills</b> (uniform, books, an excursion) from any session, until paid.</li>
 * </ol>
 * What has been paid comes from allocations of successful payments ({@code FeeAllocation}); money
 * beyond everything owed is the student's credit.
 */
@Service
public class FeeDues {

    @Autowired private FeeScheduleRepository schedules;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private StudentBillRepository bills;
    @Autowired private FeeArrearsRepository arrearsRepository;
    @Autowired private FeeConcessionRepository concessions;
    @Autowired private FeeSessionCloseRepository closes;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private BoardingSettings boarding;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * One fee the student owes: gross (their items before concessions), amount (after), the items with
     * their amounts after concessions, what they have paid towards it, and the concessions applied.
     */
    public record Due(FeeSchedule schedule, BigDecimal gross, BigDecimal amount, List<FeeComponent> components,
                      BigDecimal paid, List<String> concessions) {
        public BigDecimal discount() { return gross.subtract(amount); }
        public BigDecimal balance() { return amount.subtract(paid).max(BigDecimal.ZERO); }
        /** Paid beyond this fee (after a concession, or a lower price): goes back to credit. */
        public BigDecimal credit() { return paid.subtract(amount).max(BigDecimal.ZERO); }
        public String status() { return balance().signum() <= 0 ? "PAID" : paid.signum() > 0 ? "PART_PAID" : "UNPAID"; }
    }

    /** One bill and what has been paid on it. */
    public record BillDue(StudentBill bill, BigDecimal paid) {
        public BigDecimal amount() { return bill.getAmount(); }
        public BigDecimal balance() { return bill.isOpen() ? amount().subtract(paid).max(BigDecimal.ZERO) : BigDecimal.ZERO; }
        public String status() {
            if (!bill.isOpen()) return bill.getStatus().name();
            return balance().signum() <= 0 ? "PAID" : paid.signum() > 0 ? "PART_PAID" : "UNPAID";
        }
    }

    /** Arrears and what has been paid on them. */
    public record ArrearsDue(FeeArrears arrears, BigDecimal paid) {
        public BigDecimal amount() { return arrears.getAmount(); }
        public BigDecimal balance() { return arrears.isOpen() ? amount().subtract(paid).max(BigDecimal.ZERO) : BigDecimal.ZERO; }
        public String status() {
            if (!arrears.isOpen()) return arrears.getStatus().name();
            return balance().signum() <= 0 ? "PAID" : paid.signum() > 0 ? "PART_PAID" : "UNPAID";
        }
    }

    /** Everything on a student's account. */
    public record Account(List<ArrearsDue> arrears, List<Due> fees, List<BillDue> bills, BigDecimal credit) {
        public BigDecimal arrearsBalance() { return arrears.stream().map(ArrearsDue::balance).reduce(BigDecimal.ZERO, BigDecimal::add); }
        public BigDecimal feesBalance() { return fees.stream().map(Due::balance).reduce(BigDecimal.ZERO, BigDecimal::add); }
        public BigDecimal billsBalance() { return bills.stream().map(BillDue::balance).reduce(BigDecimal.ZERO, BigDecimal::add); }
        /** Everything still owed, before credit. */
        public BigDecimal owed() { return arrearsBalance().add(feesBalance()).add(billsBalance()); }
        /** What the student still has to pay: owed less credit (never below zero). */
        public BigDecimal toPay() { return owed().subtract(credit).max(BigDecimal.ZERO); }
        public List<BillDue> openBills() { return bills.stream().filter(b -> b.bill().isOpen()).toList(); }
        public List<ArrearsDue> openArrears() { return arrears.stream().filter(a -> a.arrears().isOpen()).toList(); }
    }

    // ── Who pays what ────────────────────────────────────────────────────────

    public static boolean applies(FeeComponent c, Residency r) {
        return switch (c.appliesTo()) {
            case EVERYONE -> true;
            case BOARDERS -> r == Residency.BOARDING;
            case DAY -> r == Residency.DAY;
        };
    }

    public Residency residencyOf(User student) {
        return boarding.residencyOf(student);
    }

    /** DAY, BOARDING or BOTH: whether fee items can be for boarders or day students only. */
    public String residencyMode() {
        return boarding.mode().name();
    }

    public static List<FeeComponent> componentsFor(FeeSchedule s, Residency r) {
        return s.getComponents().stream().filter(c -> applies(c, r)).toList();
    }

    /** The fee for a residency, before concessions. */
    public static BigDecimal amountFor(FeeSchedule s, Residency r) {
        if (!s.isItemised()) return s.getAmount();
        return componentsFor(s, r).stream().map(FeeComponent::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether some items are for boarders or day students only, so students pay different totals. */
    public static boolean variesByResidency(FeeSchedule s) {
        return s.isItemised() && s.getComponents().stream().anyMatch(c -> c.appliesTo() != FeeAppliesTo.EVERYONE);
    }

    /** The term (semester) the student is in now, 1 when not recorded. */
    public static int currentTerm(User student) {
        Integer t = student.getCurrentSemester();
        return t == null || t < 1 ? 1 : t;
    }

    public static boolean isDue(FeeSchedule s, User student) {
        return s.getTerm() == null || s.getTerm() <= currentTerm(student);
    }

    /** "Term 2" / "Second Semester" / "Whole year". */
    public static String periodName(FeeSchedule s) {
        return periodName(s.getTerm());
    }

    public static String periodName(Integer term) {
        if (term == null) return "Whole year";
        return (SystemMode.current().isSchool() ? "Term " : "Semester ") + term;
    }

    // ── Concessions ──────────────────────────────────────────────────────────

    /**
     * The fee for this student: their items (by residency), each less their concessions for that
     * session and term, applied in the order given. A percentage takes that share of the items it
     * covers (all, or the ones named); a fixed amount is spread over those items in proportion, never
     * below zero. A whole-year fee (no items) is treated as one item.
     */
    public static Due price(FeeSchedule s, Residency r, List<FeeConcession> mine, BigDecimal paid) {
        List<FeeComponent> base = s.isItemised() ? componentsFor(s, r) : List.of();
        BigDecimal gross = s.isItemised() ? sum(base.stream().map(FeeComponent::getAmount).toList()) : s.getAmount();
        List<FeeConcession> apply = mine.stream()
                .filter(c -> c.getTerm() == null || Objects.equals(c.getTerm(), s.getTerm())).toList();
        if (apply.isEmpty()) return new Due(s, gross, gross, base, paid, List.of());

        String[] names = s.isItemised() ? base.stream().map(FeeComponent::getName).toArray(String[]::new) : new String[]{null};
        BigDecimal[] left = s.isItemised() ? base.stream().map(FeeComponent::getAmount).toArray(BigDecimal[]::new) : new BigDecimal[]{gross};
        List<String> applied = new ArrayList<>();
        for (FeeConcession c : apply) {
            Set<String> only = itemKeys(c.getItems());
            boolean[] in = new boolean[left.length];
            BigDecimal covered = BigDecimal.ZERO;
            for (int i = 0; i < left.length; i++) {
                in[i] = only.isEmpty() || (names[i] != null && only.contains(FeeBreakdown.key(names[i])));
                if (in[i]) covered = covered.add(left[i]);
            }
            if (covered.signum() <= 0) continue;          // nothing it covers on this fee
            BigDecimal cut = c.getMode() == FeeConcession.Mode.PERCENT
                    ? covered.multiply(c.getValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    : c.getValue().min(covered);
            if (cut.signum() <= 0) continue;
            // Spread the cut over the items it covers in proportion; the last one takes the rounding
            BigDecimal rest = cut;
            int last = -1;
            for (int i = 0; i < left.length; i++) if (in[i] && left[i].signum() > 0) last = i;
            for (int i = 0; i < left.length; i++) {
                if (!in[i] || left[i].signum() <= 0) continue;
                BigDecimal share = i == last ? rest : cut.multiply(left[i]).divide(covered, 2, RoundingMode.DOWN);
                share = share.min(left[i]);
                left[i] = left[i].subtract(share);
                rest = rest.subtract(share);
            }
            applied.add(c.getTitle());
        }
        if (!s.isItemised()) return new Due(s, gross, left[0], List.of(), paid, applied);
        List<FeeComponent> net = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            FeeComponent n = new FeeComponent();      // a copy for this student only, never saved
            n.setName(base.get(i).getName());
            n.setAmount(left[i]);
            n.setSortOrder(base.get(i).getSortOrder());
            n.setAppliesTo(base.get(i).appliesTo());
            net.add(n);
        }
        return new Due(s, gross, sum(Arrays.asList(left)), net, paid, applied);
    }

    static Set<String> itemKeys(String items) {
        if (items == null || items.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String n : items.split("\n")) if (!n.isBlank()) out.add(FeeBreakdown.key(n));
        return out;
    }

    private static BigDecimal sum(List<BigDecimal> list) {
        return list.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── One student ──────────────────────────────────────────────────────────

    /** The fees this student owes so far in the current session, oldest first (none once its fees are closed). */
    @Transactional(readOnly = true)
    public List<Due> dues(User student) {
        return forStudent(student, true);
    }

    /** Later terms' fees of the student's class: shown as coming up, not owed yet. */
    @Transactional(readOnly = true)
    public List<Due> upcoming(User student) {
        return forStudent(student, false);
    }

    private List<Due> forStudent(User student, boolean due) {
        if (student.getProgram() == null || student.getCurrentLevel() == null) return List.of();
        AcademicSession session = academicSessions.current();
        if (closes.existsBySession_Id(session.getId())) return List.of();
        Residency r = residencyOf(student);
        List<FeeSchedule> list = schedules.findForClass(student.getProgram().getId(), student.getCurrentLevel(), session.getId());
        if (list.isEmpty()) return List.of();
        List<FeeConcession> mine = concessions.findActive(student.getId(), session.getId());
        List<Due> out = new ArrayList<>();
        for (FeeSchedule s : list) {
            if (isDue(s, student) != due) continue;
            out.add(price(s, r, mine, due ? allocations.paidForSchedule(student.getId(), s.getId()) : BigDecimal.ZERO));
        }
        return out;
    }

    /** Every bill of the student (open, cancelled, declined), oldest first, with what was paid on each. */
    @Transactional(readOnly = true)
    public List<BillDue> bills(User student) {
        List<StudentBill> list = bills.findForStudent(student.getId());
        Map<Long, BigDecimal> paid = sums(allocations.paidByBill(list.stream().map(StudentBill::getId).toList()));
        return list.stream().map(b -> new BillDue(b, paid.getOrDefault(b.getId(), BigDecimal.ZERO))).toList();
    }

    /** The student's arrears (opening balance first, then oldest session), with what was paid on each. */
    @Transactional(readOnly = true)
    public List<ArrearsDue> arrears(User student) {
        List<FeeArrears> list = arrearsRepository.findForStudent(student.getId());
        Map<Long, BigDecimal> paid = sums(allocations.paidByArrears(list.stream().map(FeeArrears::getId).toList()));
        return list.stream().map(a -> new ArrearsDue(a, paid.getOrDefault(a.getId(), BigDecimal.ZERO))).toList();
    }

    @Transactional(readOnly = true)
    public Account account(User student) {
        return new Account(arrears(student), dues(student), bills(student), allocations.credit(student.getId()));
    }

    /** The fee a payment goes to: the one asked for (it must be owed now), else the oldest with a balance. */
    @Transactional(readOnly = true)
    public Due target(User student, Long scheduleId) {
        List<Due> list = dues(student);
        if (list.isEmpty()) throw new IllegalArgumentException("No fee is owed for this class in the current session.");
        if (scheduleId != null) {
            return list.stream().filter(d -> d.schedule().getId().equals(scheduleId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That fee isn't due yet for this student, or isn't theirs."));
        }
        return list.stream().filter(d -> d.balance().signum() > 0).findFirst().orElse(list.get(list.size() - 1));
    }

    static Map<Long, BigDecimal> sums(List<Object[]> rows) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] r : rows) m.put((Long) r[0], (BigDecimal) r[1]);
        return m;
    }

    // ── Many students at once ────────────────────────────────────────────────

    /**
     * Every class's fees, every student's payments and concessions in a session, loaded once (reports,
     * overviews, closing a session). Live (the current session, not closed): fees owed now, terms up to
     * each student's. Otherwise every term, for the class each student paid fees for that session
     * (else their class now, the best that is known).
     */
    public final class Book {
        private final AcademicSession session;
        private final boolean live;
        private final Map<String, List<FeeSchedule>> byClass = new HashMap<>();
        private final Map<List<Long>, BigDecimal> paid = new HashMap<>();
        private final Map<Long, List<FeeConcession>> concessionsOf = new HashMap<>();
        private final Map<Long, long[]> paidClass = new HashMap<>();

        Book(AcademicSession session, boolean everyTerm) {
            this.session = session;
            this.live = !everyTerm && session.isCurrent() && !closes.existsBySession_Id(session.getId());
            for (FeeSchedule s : schedules.findWithComponentsBySession(session.getId()))
                byClass.computeIfAbsent(s.getProgram().getId() + ":" + s.getLevel(), k -> new ArrayList<>()).add(s);
            byClass.values().forEach(l -> l.sort(Comparator.comparingInt(FeeSchedule::getPeriodKey)));
            for (Object[] row : allocations.paidByStudentAndSchedule(session.getId()))
                paid.put(List.of((Long) row[0], (Long) row[1]), (BigDecimal) row[2]);
            for (FeeConcession c : concessions.findActiveBySession(session.getId()))
                concessionsOf.computeIfAbsent(c.getStudent().getId(), k -> new ArrayList<>()).add(c);
            concessionsOf.values().forEach(l -> l.sort(Comparator.comparing(FeeConcession::getId)));
            if (!live) for (Object[] row : allocations.classesPaidFor(session.getId()))
                paidClass.putIfAbsent((Long) row[0], new long[]{(Long) row[1], (Integer) row[2]});
        }

        public AcademicSession session() { return session; }

        /** True when this is what students owe right now (not a closed or past session). */
        public boolean live() { return live; }

        public List<FeeSchedule> classFees(Long programId, Integer level) {
            return byClass.getOrDefault(programId + ":" + level, List.of());
        }

        public List<Due> dues(User student) {
            Long programId;
            Integer level;
            long[] then = paidClass.get(student.getId());
            if (then != null) { programId = then[0]; level = (int) then[1]; }
            else if (student.getProgram() == null || student.getCurrentLevel() == null) return List.of();
            else { programId = student.getProgram().getId(); level = student.getCurrentLevel(); }
            Residency r = residencyOf(student);
            List<FeeConcession> mine = concessionsOf.getOrDefault(student.getId(), List.of());
            List<Due> out = new ArrayList<>();
            for (FeeSchedule s : classFees(programId, level)) {
                if (live && !isDue(s, student)) continue;
                out.add(price(s, r, mine, paid.getOrDefault(List.of(student.getId(), s.getId()), BigDecimal.ZERO)));
            }
            return out;
        }

        public BigDecimal paid(Long studentId, Long scheduleId) {
            return paid.getOrDefault(List.of(studentId, scheduleId), BigDecimal.ZERO);
        }
    }

    /** Live for the current session, every term for a past or closed one. */
    @Transactional(readOnly = true)
    public Book book(AcademicSession session) {
        return new Book(session, false);
    }

    /** Every term of the session, whatever term students are in (closing a session). */
    @Transactional(readOnly = true)
    public Book wholeSession(AcademicSession session) {
        return new Book(session, true);
    }

    /** [arrears still owed, credit] per student, two queries for everyone. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal[]> arrearsAndCredit() {
        Map<Long, BigDecimal[]> out = new HashMap<>();
        for (Object[] r : arrearsRepository.openByStudent()) out.computeIfAbsent((Long) r[0], k -> zeros())[0] = (BigDecimal) r[1];
        for (Object[] r : allocations.paidOnOpenArrearsByStudent()) {
            BigDecimal[] v = out.computeIfAbsent((Long) r[0], k -> zeros());
            v[0] = v[0].subtract((BigDecimal) r[1]).max(BigDecimal.ZERO);
        }
        for (Object[] r : allocations.creditByStudent()) out.computeIfAbsent((Long) r[0], k -> zeros())[1] = (BigDecimal) r[1];
        return out;
    }

    /** [studentId → still owed on open bills], two queries for everyone. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> billsOwed() {
        Map<Long, BigDecimal> out = sums(bills.sumByStudent(StudentBill.Status.OPEN));
        for (Object[] r : allocations.paidOnOpenBillsByStudent()) out.computeIfPresent((Long) r[0], (k, v) -> v.subtract((BigDecimal) r[1]).max(BigDecimal.ZERO));
        return out;
    }

    private static BigDecimal[] zeros() { return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}; }
}
