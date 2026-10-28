package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.SystemMode;
import com.exam.model.exam.Program;
import com.exam.model.fees.FeeAppliesTo;
import com.exam.model.fees.FeeComponent;
import com.exam.model.fees.FeeSchedule;
import com.exam.repository.AcademicSessionRepository;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.FeeScheduleRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * What each programme + level pays per academic session: one whole-year fee, or one fee per term
 * (semester), each a lump sum or an itemised breakdown whose items can be for everyone, boarders only
 * or day students only; and how much has been collected. Set by the bursar (or the Super Admin).
 */
@Service
public class FeeScheduleService {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    private static final int MAX_COMPONENTS = 40;

    @Autowired private FeeScheduleRepository scheduleRepository;
    @Autowired private FeePaymentRepository paymentRepository;
    @Autowired private com.exam.repository.FeeAllocationRepository allocationRepository;
    @Autowired private com.exam.repository.FeeConcessionRepository concessionRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AcademicSessionRepository sessionRepository;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private SystemSettingService settings;
    @Autowired private PaystackClient paystack;
    @Autowired private FeeDues dues;

    @Value("${paystack.currency:GHS}")
    private String currency;

    /** appliesTo: EVERYONE (default), BOARDERS or DAY. */
    public record ComponentRequest(String name, BigDecimal amount, String appliesTo) {
        public ComponentRequest(String name, BigDecimal amount) { this(name, amount, null); }
    }

    /** term: 1, 2, 3 … for that term's fee; null for a whole-year fee. */
    public record ScheduleRequest(Long programId, Integer level, Long sessionId, Boolean itemised, BigDecimal amount,
                                  List<ComponentRequest> components, LocalDate dueDate, String note,
                                  List<Integer> alsoApplyToLevels, Integer term) {
        public ScheduleRequest(Long programId, Integer level, Long sessionId, Boolean itemised, BigDecimal amount,
                               List<ComponentRequest> components, LocalDate dueDate, String note, List<Integer> alsoApplyToLevels) {
            this(programId, level, sessionId, itemised, amount, components, dueDate, note, alsoApplyToLevels, null);
        }
    }

    public String currency() { return currency; }

    // ── Overview ────────────────────────────────────────────────────────

    /**
     * Every programme and level with its fee for the session and period (term 0 = the whole year),
     * students billed (each by the items that apply to them) and money collected.
     */
    @Transactional
    public Map<String, Object> overview(Long sessionId, Integer term) {
        AcademicSession session = resolveSession(sessionId);
        boolean current = session.isCurrent();
        int periodKey = term == null ? 0 : term;

        List<FeeSchedule> all = scheduleRepository.findWithComponentsBySession(session.getId());
        Map<String, FeeSchedule> schedules = new HashMap<>();
        Map<String, Set<Integer>> periodsByClass = new HashMap<>();
        for (FeeSchedule s : all) {
            periodsByClass.computeIfAbsent(key(s.getProgram().getId(), s.getLevel()), k -> new TreeSet<>()).add(s.getPeriodKey());
            if (s.getPeriodKey() == periodKey) schedules.put(key(s.getProgram().getId(), s.getLevel()), s);
        }
        Map<Long, BigDecimal> collected = new HashMap<>();
        for (Object[] row : allocationRepository.collectedBySchedule(session.getId())) {
            collected.put((Long) row[0], (BigDecimal) row[1]);
        }
        // Active students per class, with their residency (boarders and day students can pay different totals)
        Map<String, List<User>> students = new HashMap<>();
        Map<Long, List<com.exam.model.fees.FeeConcession>> concessionsOf = new HashMap<>();
        if (current) {
            for (User u : userRepository.findByRole(Role.NORMAL)) {
                if (!u.isEnabled() || u.getProgram() == null || u.getCurrentLevel() == null) continue;
                students.computeIfAbsent(key(u.getProgram().getId(), u.getCurrentLevel()), k -> new ArrayList<>()).add(u);
            }
            for (com.exam.model.fees.FeeConcession c : concessionRepository.findActiveBySession(session.getId()))
                concessionsOf.computeIfAbsent(c.getStudent().getId(), k -> new ArrayList<>()).add(c);
        }

        BigDecimal billedTotal = BigDecimal.ZERO, collectedTotal = BigDecimal.ZERO, outstandingTotal = BigDecimal.ZERO;
        long studentsTotal = 0, levelsSet = 0, levelsTotal = 0, residencyUnknown = 0;

        List<Program> programs = new ArrayList<>(programRepository.findAll());
        programs.sort(Comparator.comparing((Program p) -> p.getDepartment() != null ? p.getDepartment().getName() : "")
                .thenComparing(Program::getName, String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> programRows = new ArrayList<>();
        for (Program p : programs) {
            // Configured levels, plus any level that still has a fee after the programme was shortened
            TreeSet<Integer> levels = new TreeSet<>(p.getConfiguredLevels());
            all.stream().filter(s -> s.getProgram().getId().equals(p.getId())).forEach(s -> levels.add(s.getLevel()));

            List<Map<String, Object>> levelRows = new ArrayList<>();
            for (int level : levels) {
                String k = key(p.getId(), level);
                FeeSchedule s = schedules.get(k);
                List<User> who = students.getOrDefault(k, List.of());
                BigDecimal got = s == null ? BigDecimal.ZERO : collected.getOrDefault(s.getId(), BigDecimal.ZERO);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("level", level);
                row.put("students", current ? (long) who.size() : null);
                row.put("schedule", s == null ? null : toDto(s));
                row.put("collected", got);
                row.put("periods", periodsFor(p, level));
                // How this class's fees are set for the session: YEAR, TERM or none yet
                Set<Integer> set = periodsByClass.getOrDefault(k, Set.of());
                row.put("setAs", set.isEmpty() ? null : set.contains(0) ? "YEAR" : "TERM");
                row.put("termsSet", set.stream().filter(x -> x > 0).toList());
                if (current && s != null) {
                    BigDecimal billed = BigDecimal.ZERO;
                    for (User u : who)
                        billed = billed.add(FeeDues.price(s, dues.residencyOf(u), concessionsOf.getOrDefault(u.getId(), List.of()), BigDecimal.ZERO).amount());
                    if (FeeDues.variesByResidency(s)) residencyUnknown += who.stream().filter(u -> dues.residencyOf(u) == null).count();
                    BigDecimal outstanding = billed.subtract(got).max(BigDecimal.ZERO);
                    row.put("billed", billed);
                    row.put("outstanding", outstanding);
                    billedTotal = billedTotal.add(billed);
                    outstandingTotal = outstandingTotal.add(outstanding);
                }
                collectedTotal = collectedTotal.add(got);
                studentsTotal += who.size();
                levelsTotal++;
                if (s != null) levelsSet++;
                levelRows.add(row);
            }
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("id", p.getId());
            pr.put("name", p.getName());
            pr.put("code", p.getCode());
            pr.put("departmentName", p.getDepartment() != null ? p.getDepartment().getName() : null);
            pr.put("enabled", p.isEnabled());
            pr.put("levels", levelRows);
            programRows.add(pr);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("billed", current ? billedTotal : null);
        totals.put("collected", collectedTotal);
        totals.put("outstanding", current ? outstandingTotal : null);
        totals.put("students", current ? studentsTotal : null);
        totals.put("levelsSet", levelsSet);
        totals.put("levelsTotal", levelsTotal);
        totals.put("residencyUnknown", residencyUnknown);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionDto(session));
        out.put("sessions", sessionRepository.findAllByOrderByStartDateDescIdDesc().stream().map(FeeScheduleService::sessionDto).toList());
        out.put("term", term);
        out.put("periods", periodOptions(programs));
        out.put("defaultTerm", defaultTerm(all));
        out.put("currency", currency);
        out.put("paystack", paystackStatus());
        out.put("settings", feeSettings());
        out.put("residencyMode", dues.residencyMode());
        out.put("totals", totals);
        out.put("programs", programRows);
        return out;
    }

    public Map<String, Object> overview(Long sessionId) {
        return overview(sessionId, null);
    }

    /** Whole year, then Term 1 … Term n (n = the most terms any programme has in a year). */
    private List<Map<String, Object>> periodOptions(List<Program> programs) {
        int max = SystemMode.current().defaultPeriodsPerLevel();
        for (Program p : programs) for (int n : p.getSemestersPerLevelMap().values()) max = Math.max(max, n);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("value", 0, "label", FeeDues.periodName((Integer) null)));
        for (int t = 1; t <= Math.min(max, 6); t++) out.add(Map.of("value", t, "label", FeeDues.periodName(t)));
        return out;
    }

    /** Where the fees page opens: per term for schools (and wherever fees are already set per term), else the whole year. */
    private static int defaultTerm(List<FeeSchedule> all) {
        if (all.stream().anyMatch(s -> s.getPeriodKey() > 0)) return 1;
        if (!all.isEmpty()) return 0;
        return SystemMode.current().isSchool() ? 1 : 0;
    }

    static int periodsFor(Program p, int level) {
        return p.getSemestersPerLevelMap().getOrDefault(level, SystemMode.current().defaultPeriodsPerLevel());
    }

    public Map<String, Object> paystackStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", paystack.isConfigured());
        m.put("mode", paystack.mode());
        return m;
    }

    /** The fee switches; the bursar and the Super Admin change them. */
    public static final List<String> SETTING_KEYS = List.of(SystemSettingService.FEES_VISIBLE_STUDENT, SystemSettingService.FEES_ONLINE_PAYMENT,
            SystemSettingService.FEES_PART_PAYMENT, SystemSettingService.FEES_ITEM_PAYMENT, SystemSettingService.FEES_RESULTS_HOLD,
            SystemSettingService.FEES_STOCK_ISSUE_UNPAID);

    public Map<String, Object> feeSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibleToStudents", settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false));
        m.put("onlinePayment", settings.getBooleanSetting(SystemSettingService.FEES_ONLINE_PAYMENT, true));
        m.put("partPayment", settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true));
        m.put("itemPayment", settings.getBooleanSetting(SystemSettingService.FEES_ITEM_PAYMENT, true));
        m.put("resultsHold", settings.getBooleanSetting(SystemSettingService.FEES_RESULTS_HOLD, false));
        m.put("stockIssueUnpaid", settings.getBooleanSetting(SystemSettingService.FEES_STOCK_ISSUE_UNPAID, false));
        return m;
    }

    /** body: visibleToStudents / onlinePayment / partPayment / itemPayment / resultsHold → true/false (send what changes). */
    public Map<String, Object> saveFeeSettings(Map<String, Boolean> body) {
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Nothing to save.");
        Map<String, String> keys = Map.of("visibleToStudents", SystemSettingService.FEES_VISIBLE_STUDENT,
                "onlinePayment", SystemSettingService.FEES_ONLINE_PAYMENT, "partPayment", SystemSettingService.FEES_PART_PAYMENT,
                "itemPayment", SystemSettingService.FEES_ITEM_PAYMENT, "resultsHold", SystemSettingService.FEES_RESULTS_HOLD,
                "stockIssueUnpaid", SystemSettingService.FEES_STOCK_ISSUE_UNPAID);
        body.forEach((k, v) -> {
            String key = keys.get(k);
            if (key == null || v == null) throw new IllegalArgumentException("Unknown fee setting: " + k);
            settings.updateSetting(key, String.valueOf(v));
        });
        return feeSettings();
    }

    // ── Create / update / delete ────────────────────────────────────────

    /** Sets the fee for a programme + level (and, optionally, the same fee for other levels of it) for a session or one term. */
    @Transactional
    public List<Map<String, Object>> save(ScheduleRequest req, String actor) {
        if (req == null || req.programId() == null) throw new IllegalArgumentException("Choose a programme.");
        if (req.level() == null) throw new IllegalArgumentException("Choose a level.");
        Program program = programRepository.findById(req.programId())
                .orElseThrow(() -> new IllegalArgumentException("Programme not found."));
        AcademicSession session = resolveSession(req.sessionId());
        Integer term = req.term() == null || req.term() == 0 ? null : req.term();

        boolean itemised = Boolean.TRUE.equals(req.itemised());
        List<ComponentRequest> components = itemised ? cleanComponents(req.components()) : List.of();
        BigDecimal total = itemised
                ? components.stream().map(ComponentRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                : money(req.amount(), "The fee");
        if (total.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException("The total fee is too large.");
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (note != null && note.length() > 500) throw new IllegalArgumentException("The note is too long (500 characters at most).");

        List<Integer> allowed = program.getConfiguredLevels();
        LinkedHashSet<Integer> levels = new LinkedHashSet<>();
        levels.add(req.level());
        if (req.alsoApplyToLevels() != null) levels.addAll(req.alsoApplyToLevels());
        for (Integer level : levels) {
            if (level == null || !allowed.contains(level))
                throw new IllegalArgumentException("Level " + level + " is not part of " + program.getName() + ".");
            if (term != null && (term < 1 || term > periodsFor(program, level)))
                throw new IllegalArgumentException(program.getName() + " level " + level + " has " + periodsFor(program, level)
                        + (SystemMode.current().isSchool() ? " terms" : " semesters") + " a year.");
            requireSamePeriodKind(program, level, session, term);
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Integer level : levels) {
            FeeSchedule s = scheduleRepository.findByProgram_IdAndLevelAndSession_IdAndPeriodKey(program.getId(), level, session.getId(), term == null ? 0 : term)
                    .orElseGet(() -> {
                        FeeSchedule n = new FeeSchedule();
                        n.setProgram(program);
                        n.setLevel(level);
                        n.setSession(session);
                        n.setTerm(term);
                        return n;
                    });
            if (s.getId() != null && itemised) requireItemsStillPaidFor(s, components);
            apply(s, itemised, total, components, req.dueDate(), note, actor);
            saved.add(toDto(scheduleRepository.save(s)));
        }
        return saved;
    }

    /** A class's fees for a session are either one whole-year fee or one per term, never both (students would pay twice). */
    private void requireSamePeriodKind(Program program, int level, AcademicSession session, Integer term) {
        for (FeeSchedule other : scheduleRepository.findForClass(program.getId(), level, session.getId())) {
            if (term == null && other.getTerm() != null)
                throw new IllegalArgumentException(program.getName() + " level " + level + " already has fees per term for " + session.getName()
                        + ". Set this fee for a term, or remove the term fees first.");
            if (term != null && other.getTerm() == null)
                throw new IllegalArgumentException(program.getName() + " level " + level + " already has a whole-year fee for " + session.getName()
                        + ". Remove it before setting fees per term, so nobody pays twice.");
        }
    }

    /** Items students have paid for separately can't be dropped from the breakdown (their payments would point at nothing). */
    private void requireItemsStillPaidFor(FeeSchedule s, List<ComponentRequest> components) {
        Set<String> kept = new HashSet<>();
        components.forEach(c -> kept.add(FeeBreakdown.key(c.name())));
        for (Object[] row : paymentRepository.paidItemsForSchedule(s.getId())) {
            String name = (String) row[0];
            if (!kept.contains(FeeBreakdown.key(name)))
                throw new IllegalArgumentException("Students have paid for \"" + name + "\" on its own, so it must stay in the breakdown.");
        }
    }

    @Transactional
    public void delete(Long id) {
        FeeSchedule s = scheduleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee not found."));
        if (inUse(id))
            throw new IllegalArgumentException("Students have already paid towards this fee, so it can't be removed. Edit the amount instead.");
        scheduleRepository.delete(s);
    }

    /** Paid towards, or chosen by a payment (even one still pending). */
    private boolean inUse(Long scheduleId) {
        return allocationRepository.countBySchedule_Id(scheduleId) > 0 || paymentRepository.countBySchedule_Id(scheduleId) > 0;
    }

    /** Copies every fee (whole-year or per term) from one session into another, e.g. last year's fees as a start for this year. */
    @Transactional
    public Map<String, Object> copy(Long fromSessionId, Long toSessionId, boolean overwrite, String actor) {
        if (fromSessionId == null) throw new IllegalArgumentException("Choose the session to copy from.");
        AcademicSession to = resolveSession(toSessionId);
        if (fromSessionId.equals(to.getId())) throw new IllegalArgumentException("Choose a different session to copy from.");
        resolveSession(fromSessionId);

        int copied = 0, skipped = 0;
        for (FeeSchedule src : scheduleRepository.findWithComponentsBySession(fromSessionId)) {
            Optional<FeeSchedule> existing = scheduleRepository.findByProgram_IdAndLevelAndSession_IdAndPeriodKey(
                    src.getProgram().getId(), src.getLevel(), to.getId(), src.getPeriodKey());
            if (existing.isPresent() && (!overwrite || inUse(existing.get().getId()))) {
                skipped++;
                continue;
            }
            // Never mix whole-year and per-term fees for one class
            boolean clash = scheduleRepository.findForClass(src.getProgram().getId(), src.getLevel(), to.getId()).stream()
                    .anyMatch(o -> (o.getTerm() == null) != (src.getTerm() == null));
            if (clash) { skipped++; continue; }
            FeeSchedule target = existing.orElseGet(() -> {
                FeeSchedule n = new FeeSchedule();
                n.setProgram(src.getProgram());
                n.setLevel(src.getLevel());
                n.setSession(to);
                n.setTerm(src.getTerm());
                return n;
            });
            List<ComponentRequest> comps = src.getComponents().stream()
                    .map(c -> new ComponentRequest(c.getName(), c.getAmount(), c.appliesTo().name())).toList();
            apply(target, src.isItemised(), src.getAmount(), comps, null, src.getNote(), actor);
            scheduleRepository.save(target);
            copied++;
        }
        return Map.of("copied", copied, "skipped", skipped);
    }

    // ── Helpers shared with the payments ─────────────────────────────────

    public Map<String, Object> toDto(FeeSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("programId", s.getProgram().getId());
        m.put("level", s.getLevel());
        m.put("sessionId", s.getSession().getId());
        m.put("term", s.getTerm());
        m.put("periodName", FeeDues.periodName(s));
        m.put("amount", s.getAmount());
        m.put("itemised", s.isItemised());
        m.put("components", s.getComponents().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getName());
            cm.put("amount", c.getAmount());
            cm.put("appliesTo", c.appliesTo().name());
            return cm;
        }).toList());
        if (FeeDues.variesByResidency(s)) {
            m.put("boarderAmount", FeeDues.amountFor(s, Residency.BOARDING));
            m.put("dayAmount", FeeDues.amountFor(s, Residency.DAY));
        }
        m.put("dueDate", s.getDueDate());
        m.put("note", s.getNote());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("updatedBy", s.getUpdatedBy());
        return m;
    }

    public static Map<String, Object> sessionDto(AcademicSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("current", s.isCurrent());
        return m;
    }

    /** A positive amount of money, rounded to 2 decimal places. */
    static BigDecimal money(BigDecimal value, String what) {
        if (value == null) throw new IllegalArgumentException(what + " is required.");
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        if (v.signum() <= 0) throw new IllegalArgumentException(what + " must be more than zero.");
        if (v.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(what + " is too large.");
        return v;
    }

    private AcademicSession resolveSession(Long sessionId) {
        if (sessionId == null) return academicSessionService.current();
        return sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Academic session not found."));
    }

    private static List<ComponentRequest> cleanComponents(List<ComponentRequest> raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Add at least one item to the breakdown, or switch to a lump sum.");
        if (raw.size() > MAX_COMPONENTS) throw new IllegalArgumentException("A breakdown can have at most " + MAX_COMPONENTS + " items.");
        List<ComponentRequest> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ComponentRequest c : raw) {
            String name = c == null || c.name() == null ? "" : c.name().trim().replaceAll("\\s+", " ");
            if (name.isEmpty()) throw new IllegalArgumentException("Every item in the breakdown needs a name.");
            if (name.length() > 80) throw new IllegalArgumentException("\"" + name.substring(0, 30) + "…\" is too long (80 characters at most).");
            if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("\"" + name + "\" appears twice in the breakdown.");
            out.add(new ComponentRequest(name, money(c.amount(), "\"" + name + "\""), FeeAppliesTo.parse(c.appliesTo()).name()));
        }
        return out;
    }

    private static void apply(FeeSchedule s, boolean itemised, BigDecimal total, List<ComponentRequest> components,
                              LocalDate dueDate, String note, String actor) {
        s.setItemised(itemised);
        s.setAmount(total);
        s.getComponents().clear();
        if (itemised) {
            int i = 0;
            for (ComponentRequest c : components) {
                FeeComponent fc = new FeeComponent();
                fc.setSchedule(s);
                fc.setName(c.name());
                fc.setAmount(c.amount());
                fc.setAppliesTo(FeeAppliesTo.parse(c.appliesTo()));
                fc.setSortOrder(i++);
                s.getComponents().add(fc);
            }
        }
        s.setDueDate(dueDate);
        s.setNote(note);
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(actor);
    }

    private static String key(Long programId, Integer level) {
        return programId + ":" + level;
    }
}
