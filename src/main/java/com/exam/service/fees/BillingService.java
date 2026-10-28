package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.SystemMode;
import com.exam.model.boarding.Hostel;
import com.exam.model.boarding.ResidencyMode;
import com.exam.model.exam.Program;
import com.exam.model.fees.*;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingService;
import com.exam.service.boarding.BoardingSettings;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bills for what the school charges apart from fees: the bursar's price list (uniform, books, PTA
 * dues, an excursion), and bills issued from it to one student, a class, a house, all boarders, all
 * day students or everyone. Each student gets their own bill, owed until paid. An optional bill (an
 * excursion, an extra book) can be declined by the student while nothing is paid on it.
 * <p>
 * Rights: the bursar (and the Super Admin) keeps the price list, bills groups, and cancels bills;
 * a cashier bills one student at a time from the price list (selling a sweater at the counter).
 * Cancelling a bill that was partly paid turns what was paid into credit for the student's other debts.
 */
@Service
public class BillingService {

    public static final String NOTIFY_TYPE = "FEE_BILL";
    private static final int MAX_LINES = 30;
    private static final int MAX_STUDENTS_BY_NAME = 200;

    @Autowired private PriceItemRepository priceItems;
    @Autowired private BillBatchRepository batches;
    @Autowired private StudentBillRepository bills;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private UserRepository users;
    @Autowired private ProgramRepository programs;
    @Autowired private HostelRepository hostels;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private BoardingSettings boardingSettings;
    @Autowired private BoardingService boarding;
    @Autowired private FeeScheduleService scheduleService;
    @Autowired private FeeDues feeDues;
    @Autowired private FeeLedger ledger;
    @Autowired private NotificationService notifications;
    @Autowired private SystemSettingService settings;

    public enum Target { STUDENT, CLASS, HOSTEL, BOARDERS, DAY, EVERYONE }

    public record PriceItemRequest(String name, String category, BigDecimal price, String description) {}

    /** itemId: from the price list (its name and price are used); otherwise a one-off line (bursar only). */
    public record LineRequest(Long itemId, String name, Integer quantity, BigDecimal unitPrice) {}

    /**
     * target: STUDENT (studentIds), CLASS (programId, level optional = every level), HOSTEL (hostelId),
     * BOARDERS, DAY, EVERYONE. skipDuplicates (default true): leave out students who already have an
     * open bill with this title this session.
     */
    public record BillRequest(String title, String target, Long programId, Integer level, Long hostelId, List<Long> studentIds,
                              List<LineRequest> lines, Boolean optional, LocalDate dueDate, String note, Boolean skipDuplicates) {}

    public record CancelRequest(String reason) {}

    // ── Price list ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> priceList() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("categories", Arrays.stream(PriceItem.Category.values()).map(Enum::name).toList());
        out.put("items", priceItems.findAllByOrderByActiveDescCategoryAscNameAsc().stream().map(BillingService::itemDto).toList());
        // What the "who to bill" choices need
        ResidencyMode mode = boardingSettings.mode();
        out.put("residencyMode", mode.name());
        out.put("houseWord", boardingSettings.word());
        out.put("hostels", !mode.hasBoarding() ? List.of() : hostels.findAllByOrderByNameAsc().stream().filter(Hostel::isActive)
                .map(h -> Map.of("id", h.getId(), "name", h.getName())).toList());
        // Classes with active students: [programme, levels with their student counts]
        Map<Long, Map<Integer, Long>> byProgram = new TreeMap<>();
        for (Object[] r : users.countActiveStudentsByProgramAndLevel())
            byProgram.computeIfAbsent((Long) r[0], k -> new TreeMap<>()).put((Integer) r[1], (Long) r[2]);
        out.put("classes", programs.findAllById(byProgram.keySet()).stream().sorted(Comparator.comparing(Program::getName)).map(p -> Map.of(
                "id", p.getId(), "name", p.getName(),
                "levels", byProgram.get(p.getId()).entrySet().stream().map(e -> Map.of("level", e.getKey(), "students", e.getValue())).toList())).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> savePriceItem(User actor, Long id, PriceItemRequest req) {
        FinanceAccess.requireBursar(actor);
        if (req == null) throw new IllegalArgumentException("Fill in the item.");
        String name = clean(req.name(), 80, "The name");
        if (name == null) throw new IllegalArgumentException("Give the item a name.");
        PriceItem item = id == null ? new PriceItem() : priceItems.findById(id).orElseThrow(() -> new IllegalArgumentException("Item not found."));
        String key = name.toLowerCase(Locale.ROOT);
        if (item.isActive() || id == null) {
            priceItems.findByNameKey(key).filter(o -> !o.getId().equals(item.getId()))
                    .ifPresent(o -> { throw new IllegalArgumentException("\"" + o.getName() + "\" is already on the price list."); });
            item.setNameKey(key);
        }
        item.setName(name);
        item.setCategory(parseCategory(req.category()));
        item.setPrice(FeeScheduleService.money(req.price(), "The price"));
        item.setDescription(clean(req.description(), 200, "The description"));
        item.setUpdatedBy(CurrentUserService.displayName(actor));
        item.setUpdatedAt(LocalDateTime.now());
        return itemDto(priceItems.save(item));
    }

    /** Retire (kept on old bills, not offered for new ones) or bring back an item. */
    @Transactional
    public Map<String, Object> setPriceItemActive(User actor, Long id, boolean active) {
        FinanceAccess.requireBursar(actor);
        PriceItem item = priceItems.findById(id).orElseThrow(() -> new IllegalArgumentException("Item not found."));
        if (active && !item.isActive()) {
            String key = item.getName().toLowerCase(Locale.ROOT);
            if (priceItems.findByNameKey(key).isPresent())
                throw new IllegalArgumentException("Another item called \"" + item.getName() + "\" is on the price list. Rename one of them first.");
            item.setNameKey(key);
        } else if (!active) {
            item.setNameKey(null);
        }
        item.setActive(active);
        item.setUpdatedBy(CurrentUserService.displayName(actor));
        item.setUpdatedAt(LocalDateTime.now());
        return itemDto(priceItems.save(item));
    }

    // ── Issuing ──────────────────────────────────────────────────────────────

    /** What issuing would do: how many students, the amount each, who is left out and why. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(User actor, BillRequest req) {
        Plan plan = plan(actor, req);
        Map<String, Object> out = planDto(plan);
        out.put("sample", plan.students.stream().limit(8).map(CurrentUserService::displayName).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> issue(User actor, BillRequest req) {
        Plan plan = plan(actor, req);
        if (plan.students.isEmpty())
            throw new IllegalArgumentException(plan.skipped > 0 ? "Every student chosen already has this bill." : "No students match. Choose who to bill.");
        AcademicSession session = academicSessions.current();
        String by = CurrentUserService.displayName(actor);

        BillBatch batch = new BillBatch();
        batch.setTitle(plan.title);
        batch.setAudience(trim(plan.audience, 200));
        batch.setAmountEach(plan.amountEach);
        batch.setStudents(plan.students.size());
        batch.setOptional(plan.optional);
        batch.setDueDate(plan.dueDate);
        batch.setNote(plan.note);
        batch.setSession(session);
        batch.setIssuedBy(by);
        batch = batches.save(batch);

        List<StudentBill> made = new ArrayList<>(plan.students.size());
        for (User s : plan.students) {
            StudentBill b = new StudentBill();
            b.setStudent(s);
            b.setBatch(batch);
            b.setSession(session);
            b.setTitle(plan.title);
            b.setAmount(plan.amountEach);
            b.setOptional(plan.optional);
            b.setDueDate(plan.dueDate);
            for (PlannedLine pl : plan.lines) {
                BillLine l = new BillLine();
                l.setBill(b);
                l.setItem(pl.item);
                l.setName(pl.name);
                l.setQuantity(pl.quantity);
                l.setUnitPrice(pl.unitPrice);
                l.setAmount(pl.amount);
                b.getLines().add(l);
            }
            made.add(b);
        }
        bills.saveAll(made);
        bills.flush();

        // Students who had money in credit: it goes to the new bill now
        Set<Long> withCredit = new HashSet<>(allocations.studentsWithCredit());
        for (User s : plan.students) if (withCredit.contains(s.getId())) ledger.applyCredit(s);

        if (settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false)) {
            String money = scheduleService.currency() + " " + plan.amountEach.toPlainString();
            notifications.notify(plan.students, NOTIFY_TYPE, "New bill: " + plan.title,
                    money + (plan.dueDate != null ? ", due " + plan.dueDate : "") + (plan.optional ? ". This one is optional." : "."),
                    "/user-dashboard/fees");
        }
        Map<String, Object> out = planDto(plan);
        out.put("batchId", batch.getId());
        return out;
    }

    // ── Batches and bills ───────────────────────────────────────────────────

    /** Bills issued, newest first (sessionId null = the current session; 0 = every session). */
    @Transactional(readOnly = true)
    public Map<String, Object> batches(Long sessionId) {
        Long sid = sessionId == null ? academicSessions.current().getId() : sessionId == 0 ? null : sessionId;
        List<BillBatch> list = batches.findRecent(sid, PageRequest.of(0, 300));
        List<Long> ids = list.stream().map(BillBatch::getId).toList();
        Map<Long, Map<StudentBill.Status, Long>> counts = new HashMap<>();
        Map<Long, BigDecimal> paid = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] r : bills.countByBatchAndStatus(ids))
                counts.computeIfAbsent((Long) r[0], k -> new EnumMap<>(StudentBill.Status.class)).put((StudentBill.Status) r[1], (Long) r[2]);
            for (Object[] r : bills.paidByBatch(ids, FeePayment.Status.SUCCESS)) paid.put((Long) r[0], (BigDecimal) r[1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("session", FeeScheduleService.sessionDto(academicSessions.current()));
        out.put("batches", list.stream().map(b -> batchDto(b, counts.getOrDefault(b.getId(), Map.of()), paid.getOrDefault(b.getId(), BigDecimal.ZERO))).toList());
        return out;
    }

    /** One batch: its lines and every student on it with what they've paid. */
    @Transactional(readOnly = true)
    public Map<String, Object> batch(Long id) {
        BillBatch b = batches.findById(id).orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        List<StudentBill> list = bills.findForBatch(id);
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Object[] r : allocations.paidByBill(list.stream().map(StudentBill::getId).toList())) paid.put((Long) r[0], (BigDecimal) r[1]);
        Map<StudentBill.Status, Long> counts = list.stream().collect(Collectors.groupingBy(StudentBill::getStatus, () -> new EnumMap<>(StudentBill.Status.class), Collectors.counting()));
        Map<String, Object> out = batchDto(b, counts, paid.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("lines", list.isEmpty() ? List.of() : list.get(0).getLines().stream().map(BillingService::lineDto).toList());
        out.put("roster", list.stream().map(sb -> {
            FeeDues.BillDue d = new FeeDues.BillDue(sb, paid.getOrDefault(sb.getId(), BigDecimal.ZERO));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("billId", sb.getId());
            m.put("studentId", sb.getStudent().getId());
            m.put("name", CurrentUserService.displayName(sb.getStudent()));
            m.put("username", sb.getStudent().getUsername());
            m.put("programName", sb.getStudent().getProgram() != null ? sb.getStudent().getProgram().getName() : null);
            m.put("level", sb.getStudent().getCurrentLevel());
            m.put("paid", d.paid());
            m.put("balance", d.balance());
            m.put("status", d.status());
            m.put("statusNote", sb.getStatusNote());
            return m;
        }).toList());
        out.put("currency", scheduleService.currency());
        return out;
    }

    /** Bursar: cancels every open bill of the batch (what was paid on them becomes credit). */
    @Transactional
    public Map<String, Object> cancelBatch(User actor, Long id, CancelRequest req) {
        FinanceAccess.requireBursar(actor);
        String reason = reason(req);
        BillBatch b = batches.findById(id).orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        List<StudentBill> list = bills.findForBatch(id).stream().filter(StudentBill::isOpen)
                .sorted(Comparator.comparing(sb -> sb.getStudent().getId())).toList();   // lock students in id order
        BigDecimal released = BigDecimal.ZERO;
        for (StudentBill sb : list) released = released.add(cancel(sb, actor, reason));
        b.setCancelledAt(LocalDateTime.now());
        b.setCancelledBy(CurrentUserService.displayName(actor));
        batches.save(b);
        for (StudentBill sb : list) ledger.applyCredit(sb.getStudent());
        Map<String, Object> out = batch(id);
        out.put("cancelled", list.size());
        out.put("creditReleased", released);
        return out;
    }

    /** Bursar: cancels one student's bill. */
    @Transactional
    public Map<String, Object> cancelBill(User actor, Long billId, CancelRequest req) {
        FinanceAccess.requireBursar(actor);
        String reason = reason(req);
        StudentBill sb = bills.findById(billId).orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        if (!sb.isOpen()) throw new IllegalArgumentException("This bill is already " + sb.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        BigDecimal released = cancel(sb, actor, reason);
        ledger.applyCredit(sb.getStudent());
        Map<String, Object> out = billDto(new FeeDues.BillDue(sb, BigDecimal.ZERO));
        out.put("creditReleased", released);
        return out;
    }

    /** The student turns down an optional bill they haven't paid anything on. */
    @Transactional
    public Map<String, Object> decline(User principal, Long billId) {
        if (principal == null || principal.getRole() != Role.NORMAL) throw new IllegalArgumentException("Only the student can decline their bill.");
        StudentBill sb = bills.findById(billId).filter(b -> b.getStudent().getId().equals(principal.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        if (!sb.isOpen()) throw new IllegalArgumentException("This bill is already " + sb.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        if (!sb.isOptional()) throw new IllegalArgumentException("This bill isn't optional. Please speak to the accounts office.");
        StockService.requireNothingOut(sb);
        ledger.lock(sb.getStudent());
        if (!allocations.paidToBill(sb.getId()).isEmpty())
            throw new IllegalArgumentException("You have already paid towards this bill. Please speak to the accounts office.");
        sb.setStatus(StudentBill.Status.DECLINED);
        sb.setStatusBy(CurrentUserService.displayName(principal));
        sb.setStatusAt(LocalDateTime.now());
        sb.setStatusNote("Declined by the student");
        return billDto(new FeeDues.BillDue(bills.save(sb), BigDecimal.ZERO));
    }

    private BigDecimal cancel(StudentBill sb, User actor, String reason) {
        StockService.requireNothingOut(sb);
        ledger.lock(sb.getStudent());
        sb.setStatus(StudentBill.Status.CANCELLED);
        sb.setStatusBy(CurrentUserService.displayName(actor));
        sb.setStatusAt(LocalDateTime.now());
        sb.setStatusNote(trim(reason, 200));
        bills.save(sb);
        return ledger.releaseBill(sb);
    }

    // ── Working out who and what ─────────────────────────────────────────────

    private record PlannedLine(PriceItem item, String name, int quantity, BigDecimal unitPrice, BigDecimal amount) {}

    private static final class Plan {
        String title, audience, note;
        List<PlannedLine> lines;
        BigDecimal amountEach;
        boolean optional;
        LocalDate dueDate;
        List<User> students = List.of();
        int skipped, residencyUnknown;
    }

    private Plan plan(User actor, BillRequest req) {
        FinanceAccess.requireFinance(actor);
        if (req == null) throw new IllegalArgumentException("Fill in the bill.");
        boolean bursar = FinanceAccess.isBursar(actor);
        Target target = parseTarget(req.target());
        if (!bursar && target != Target.STUDENT)
            throw new org.springframework.security.access.AccessDeniedException("A cashier can bill one student at a time. Ask the bursar to bill a group.");

        Plan plan = new Plan();
        plan.lines = lines(req.lines(), bursar);
        plan.amountEach = plan.lines.stream().map(PlannedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (plan.amountEach.signum() <= 0) throw new IllegalArgumentException("The bill comes to nothing. Add what is being charged.");
        String title = clean(req.title(), 120, "The title");
        if (title == null) {
            title = plan.lines.size() == 1 ? plan.lines.get(0).name()
                    : plan.lines.get(0).name() + " and " + (plan.lines.size() - 1) + " more";
        }
        plan.title = trim(title, 120);
        plan.optional = Boolean.TRUE.equals(req.optional());
        plan.dueDate = req.dueDate();
        if (plan.dueDate != null && plan.dueDate.isBefore(LocalDate.now().minusYears(1))) throw new IllegalArgumentException("Check the due date.");
        plan.note = clean(req.note(), 300, "The note");

        List<User> who = students(target, req, plan);
        if (!bursar && who.size() > 1) throw new org.springframework.security.access.AccessDeniedException("A cashier can bill one student at a time.");
        if (req.skipDuplicates() == null || req.skipDuplicates()) {
            Set<Long> dup = who.isEmpty() ? Set.of() : new HashSet<>(bills.studentsWithSameBill(who.stream().map(User::getId).toList(),
                    plan.title.toLowerCase(Locale.ROOT), academicSessions.current().getId(), StudentBill.Status.OPEN));
            plan.skipped = dup.size();
            who = who.stream().filter(u -> !dup.contains(u.getId())).toList();
        }
        plan.students = who.stream().sorted(Comparator.comparing((User u) -> Objects.toString(u.getFirstname(), ""))
                .thenComparing(u -> Objects.toString(u.getLastname(), ""))).toList();
        return plan;
    }

    private List<PlannedLine> lines(List<LineRequest> req, boolean bursar) {
        if (req == null || req.isEmpty()) throw new IllegalArgumentException("Add at least one thing to the bill.");
        if (req.size() > MAX_LINES) throw new IllegalArgumentException("A bill can have " + MAX_LINES + " lines at most.");
        List<PlannedLine> out = new ArrayList<>();
        for (LineRequest l : req) {
            if (l == null) continue;
            int qty = l.quantity() == null ? 1 : l.quantity();
            if (qty < 1 || qty > 1000) throw new IllegalArgumentException("Check the quantities (1 to 1000).");
            PriceItem item = null;
            String name;
            BigDecimal unit;
            if (l.itemId() != null) {
                item = priceItems.findById(l.itemId()).filter(PriceItem::isActive)
                        .orElseThrow(() -> new IllegalArgumentException("An item on the bill is no longer on the price list."));
                name = item.getName();
                unit = item.getPrice();
            } else {
                if (!bursar) throw new org.springframework.security.access.AccessDeniedException("A cashier bills from the price list. Ask the bursar to add the item.");
                name = clean(l.name(), 80, "A line's name");
                if (name == null) throw new IllegalArgumentException("Give every line a name.");
                unit = FeeScheduleService.money(l.unitPrice(), "The price of " + name);
            }
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            FeeScheduleService.money(amount, "The amount for " + name);
            out.add(new PlannedLine(item, name, qty, unit, amount));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Add at least one thing to the bill.");
        return out;
    }

    private List<User> students(Target target, BillRequest req, Plan plan) {
        ResidencyMode mode = boardingSettings.mode();
        switch (target) {
            case STUDENT -> {
                List<Long> ids = req.studentIds() == null ? List.of() : req.studentIds().stream().filter(Objects::nonNull).distinct().toList();
                if (ids.isEmpty()) throw new IllegalArgumentException("Choose the student to bill.");
                if (ids.size() > MAX_STUDENTS_BY_NAME) throw new IllegalArgumentException("Choose " + MAX_STUDENTS_BY_NAME + " students at most, or bill a class.");
                List<User> found = users.findAllById(ids).stream().filter(u -> u.getRole() == Role.NORMAL).toList();
                if (found.size() != ids.size()) throw new IllegalArgumentException("A student chosen was not found.");
                List<User> inactive = found.stream().filter(u -> !u.isEnabled()).toList();
                if (!inactive.isEmpty()) throw new IllegalArgumentException(CurrentUserService.displayName(inactive.get(0)) + "'s account is deactivated.");
                plan.audience = found.size() == 1 ? CurrentUserService.displayName(found.get(0)) + " (" + found.get(0).getUsername() + ")" : found.size() + " students";
                return found;
            }
            case CLASS -> {
                if (req.programId() == null) throw new IllegalArgumentException("Choose the class.");
                Program p = programs.findById(req.programId()).orElseThrow(() -> new IllegalArgumentException("Programme not found."));
                // Schools: a class is one year, so the class name is the whole audience
                plan.audience = SystemMode.current().isSchool() ? p.getName() : p.getName() + (req.level() != null ? " · " + SystemMode.current().terms().get("level") + " " + req.level() : " · every " + SystemMode.current().terms().get("level").toLowerCase(Locale.ROOT));
                return users.findActiveStudents(p.getId(), req.level());
            }
            case HOSTEL -> {
                if (!mode.hasBoarding()) throw new IllegalArgumentException("This institution has no boarding students.");
                if (req.hostelId() == null) throw new IllegalArgumentException("Choose the " + boardingSettings.word().toLowerCase(Locale.ROOT) + ".");
                Hostel h = hostels.findById(req.hostelId()).orElseThrow(() -> new IllegalArgumentException(boardingSettings.word() + " not found."));
                plan.audience = h.getName() + " (" + boardingSettings.word().toLowerCase(Locale.ROOT) + ")";
                List<Long> ids = boarding.audience().hostelOfStudent().entrySet().stream()
                        .filter(e -> h.getId().equals(e.getValue())).map(Map.Entry::getKey).toList();
                return users.findAllById(ids).stream().filter(u -> u.getRole() == Role.NORMAL && u.isEnabled()).toList();
            }
            case BOARDERS -> {
                if (!mode.hasBoarding()) throw new IllegalArgumentException("This institution has no boarding students.");
                plan.audience = mode == ResidencyMode.BOARDING ? "Every student" : "All boarders";
                List<User> all = users.findActiveStudents(null, null);
                if (mode == ResidencyMode.BOTH) plan.residencyUnknown = (int) all.stream().filter(u -> u.getResidency() == null).count();
                return all.stream().filter(boardingSettings::isBoarder).toList();
            }
            case DAY -> {
                if (mode != ResidencyMode.BOTH && mode != ResidencyMode.DAY) throw new IllegalArgumentException("This institution has no day students.");
                plan.audience = mode == ResidencyMode.DAY ? "Every student" : "All day students";
                List<User> all = users.findActiveStudents(null, null);
                if (mode == ResidencyMode.BOTH) plan.residencyUnknown = (int) all.stream().filter(u -> u.getResidency() == null).count();
                return all.stream().filter(u -> boardingSettings.residencyOf(u) == Residency.DAY).toList();
            }
            default -> {
                plan.audience = "Every student";
                return users.findActiveStudents(null, null);
            }
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    private Map<String, Object> planDto(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", plan.title);
        m.put("audience", plan.audience);
        m.put("students", plan.students.size());
        m.put("amountEach", plan.amountEach);
        m.put("total", plan.amountEach.multiply(BigDecimal.valueOf(plan.students.size())));
        m.put("skipped", plan.skipped);
        m.put("residencyUnknown", plan.residencyUnknown);
        m.put("optional", plan.optional);
        m.put("dueDate", plan.dueDate);
        m.put("currency", scheduleService.currency());
        m.put("lines", plan.lines.stream().map(l -> {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("name", l.name());
            lm.put("quantity", l.quantity());
            lm.put("unitPrice", l.unitPrice());
            lm.put("amount", l.amount());
            return lm;
        }).toList());
        return m;
    }

    private static Map<String, Object> batchDto(BillBatch b, Map<StudentBill.Status, Long> counts, BigDecimal paid) {
        long open = counts.getOrDefault(StudentBill.Status.OPEN, 0L);
        BigDecimal billed = b.getAmountEach().multiply(BigDecimal.valueOf(open));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("title", b.getTitle());
        m.put("audience", b.getAudience());
        m.put("amountEach", b.getAmountEach());
        m.put("students", b.getStudents());
        m.put("open", open);
        m.put("cancelled", counts.getOrDefault(StudentBill.Status.CANCELLED, 0L));
        m.put("declined", counts.getOrDefault(StudentBill.Status.DECLINED, 0L));
        m.put("billed", billed);
        m.put("paid", paid);
        m.put("outstanding", billed.subtract(paid).max(BigDecimal.ZERO));
        m.put("optional", b.isOptional());
        m.put("dueDate", b.getDueDate());
        m.put("note", b.getNote());
        m.put("sessionName", b.getSession() != null ? b.getSession().getName() : null);
        m.put("issuedBy", b.getIssuedBy());
        m.put("issuedAt", b.getIssuedAt());
        m.put("cancelledAt", b.getCancelledAt());
        m.put("cancelledBy", b.getCancelledBy());
        return m;
    }

    /** A student's bill as their statement shows it. */
    public static Map<String, Object> billDto(FeeDues.BillDue d) {
        StudentBill b = d.bill();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("batchId", b.getBatch().getId());
        m.put("title", b.getTitle());
        m.put("amount", b.getAmount());
        m.put("paid", d.paid());
        m.put("balance", d.balance());
        m.put("status", d.status());
        m.put("optional", b.isOptional());
        m.put("dueDate", b.getDueDate());
        m.put("createdAt", b.getCreatedAt());
        m.put("sessionName", b.getSession() != null ? b.getSession().getName() : null);
        m.put("note", b.getBatch().getNote());
        m.put("statusNote", b.getStatusNote());
        m.put("lines", b.getLines().stream().map(BillingService::lineDto).toList());
        return m;
    }

    private static Map<String, Object> lineDto(BillLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.getId());
        m.put("name", l.getName());
        m.put("stocked", l.getItem() != null && l.getItem().isStocked());
        m.put("issued", l.getIssuedQty());
        m.put("quantity", l.getQuantity());
        m.put("unitPrice", l.getUnitPrice());
        m.put("amount", l.getAmount());
        return m;
    }

    private static Map<String, Object> itemDto(PriceItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("name", i.getName());
        m.put("category", i.getCategory().name());
        m.put("price", i.getPrice());
        m.put("description", i.getDescription());
        m.put("active", i.isActive());
        m.put("stocked", i.isStocked());
        m.put("onHand", i.isStocked() ? i.getStockOnHand() : null);
        m.put("updatedBy", i.getUpdatedBy());
        m.put("updatedAt", i.getUpdatedAt());
        return m;
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private static Target parseTarget(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Choose who to bill.");
        try { return Target.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose who to bill."); }
    }

    private static PriceItem.Category parseCategory(String s) {
        if (s == null || s.isBlank()) return PriceItem.Category.OTHER;
        try { return PriceItem.Category.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown category " + s + "."); }
    }

    private static String reason(CancelRequest req) {
        String r = req == null || req.reason() == null ? "" : req.reason().trim();
        if (r.isEmpty()) throw new IllegalArgumentException("Give a reason for cancelling.");
        if (r.length() > 200) throw new IllegalArgumentException("The reason is too long (200 characters at most).");
        return r;
    }

    private static String clean(String s, int max, String what) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() > max) throw new IllegalArgumentException(what + " is too long (" + max + " characters at most).");
        return t;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
