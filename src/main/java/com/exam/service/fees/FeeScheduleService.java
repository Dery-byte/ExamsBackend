package com.exam.service.fees;

import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Program;
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
 * Super Admin: what each programme + level pays per academic session, as a lump sum or an itemised
 * breakdown, and how much of it has been collected.
 */
@Service
public class FeeScheduleService {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    private static final int MAX_COMPONENTS = 40;

    @Autowired private FeeScheduleRepository scheduleRepository;
    @Autowired private FeePaymentRepository paymentRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AcademicSessionRepository sessionRepository;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private SystemSettingService settings;
    @Autowired private PaystackClient paystack;

    @Value("${paystack.currency:GHS}")
    private String currency;

    public record ComponentRequest(String name, BigDecimal amount) {}

    public record ScheduleRequest(Long programId, Integer level, Long sessionId, Boolean itemised, BigDecimal amount,
                                  List<ComponentRequest> components, LocalDate dueDate, String note,
                                  List<Integer> alsoApplyToLevels) {}

    public String currency() { return currency; }

    // ── Overview ────────────────────────────────────────────────────────

    /** Every programme and level with its fee for the session, students billed and money collected. */
    @Transactional
    public Map<String, Object> overview(Long sessionId) {
        AcademicSession session = resolveSession(sessionId);
        boolean current = session.isCurrent();

        Map<String, FeeSchedule> schedules = new HashMap<>();
        for (FeeSchedule s : scheduleRepository.findWithComponentsBySession(session.getId())) {
            schedules.put(key(s.getProgram().getId(), s.getLevel()), s);
        }
        Map<Long, BigDecimal> collected = new HashMap<>();
        for (Object[] row : paymentRepository.collectedBySchedule(session.getId())) {
            collected.put((Long) row[0], (BigDecimal) row[1]);
        }
        Map<String, Long> students = new HashMap<>();
        for (Object[] row : userRepository.countActiveStudentsByProgramAndLevel()) {
            students.put(key((Long) row[0], (Integer) row[1]), (Long) row[2]);
        }

        BigDecimal billedTotal = BigDecimal.ZERO, collectedTotal = BigDecimal.ZERO, outstandingTotal = BigDecimal.ZERO;
        long studentsTotal = 0, levelsSet = 0, levelsTotal = 0;

        List<Program> programs = new ArrayList<>(programRepository.findAll());
        programs.sort(Comparator.comparing((Program p) -> p.getDepartment() != null ? p.getDepartment().getName() : "")
                .thenComparing(Program::getName, String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> programRows = new ArrayList<>();
        for (Program p : programs) {
            // Configured levels, plus any level that still has a fee after the programme was shortened
            TreeSet<Integer> levels = new TreeSet<>(p.getConfiguredLevels());
            schedules.values().stream().filter(s -> s.getProgram().getId().equals(p.getId())).forEach(s -> levels.add(s.getLevel()));

            List<Map<String, Object>> levelRows = new ArrayList<>();
            for (int level : levels) {
                FeeSchedule s = schedules.get(key(p.getId(), level));
                long count = current ? students.getOrDefault(key(p.getId(), level), 0L) : 0L;
                BigDecimal got = s == null ? BigDecimal.ZERO : collected.getOrDefault(s.getId(), BigDecimal.ZERO);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("level", level);
                row.put("students", current ? count : null);
                row.put("schedule", s == null ? null : toDto(s));
                row.put("collected", got);
                if (current && s != null) {
                    BigDecimal billed = s.getAmount().multiply(BigDecimal.valueOf(count));
                    BigDecimal outstanding = billed.subtract(got).max(BigDecimal.ZERO);
                    row.put("billed", billed);
                    row.put("outstanding", outstanding);
                    billedTotal = billedTotal.add(billed);
                    outstandingTotal = outstandingTotal.add(outstanding);
                }
                collectedTotal = collectedTotal.add(got);
                studentsTotal += count;
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionDto(session));
        out.put("sessions", sessionRepository.findAllByOrderByStartDateDescIdDesc().stream().map(FeeScheduleService::sessionDto).toList());
        out.put("currency", currency);
        out.put("paystack", paystackStatus());
        out.put("settings", feeSettings());
        out.put("totals", totals);
        out.put("programs", programRows);
        return out;
    }

    public Map<String, Object> paystackStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", paystack.isConfigured());
        m.put("mode", paystack.mode());
        return m;
    }

    public Map<String, Object> feeSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibleToStudents", settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false));
        m.put("onlinePayment", settings.getBooleanSetting(SystemSettingService.FEES_ONLINE_PAYMENT, true));
        m.put("partPayment", settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true));
        m.put("itemPayment", settings.getBooleanSetting(SystemSettingService.FEES_ITEM_PAYMENT, true));
        return m;
    }

    // ── Create / update / delete ────────────────────────────────────────

    /** Sets the fee for a programme + level (and, optionally, the same fee for other levels of it). */
    @Transactional
    public List<Map<String, Object>> save(ScheduleRequest req, String actor) {
        if (req == null || req.programId() == null) throw new IllegalArgumentException("Choose a programme.");
        if (req.level() == null) throw new IllegalArgumentException("Choose a level.");
        Program program = programRepository.findById(req.programId())
                .orElseThrow(() -> new IllegalArgumentException("Programme not found."));
        AcademicSession session = resolveSession(req.sessionId());

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
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Integer level : levels) {
            FeeSchedule s = scheduleRepository.findByProgram_IdAndLevelAndSession_Id(program.getId(), level, session.getId())
                    .orElseGet(() -> {
                        FeeSchedule n = new FeeSchedule();
                        n.setProgram(program);
                        n.setLevel(level);
                        n.setSession(session);
                        return n;
                    });
            apply(s, itemised, total, components, req.dueDate(), note, actor);
            saved.add(toDto(scheduleRepository.save(s)));
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        FeeSchedule s = scheduleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee not found."));
        if (paymentRepository.countBySchedule_Id(id) > 0)
            throw new IllegalArgumentException("Students have already paid towards this fee, so it can't be removed. Edit the amount instead.");
        scheduleRepository.delete(s);
    }

    /** Copies every fee from one session into another (e.g. last year's fees as a start for this year). */
    @Transactional
    public Map<String, Object> copy(Long fromSessionId, Long toSessionId, boolean overwrite, String actor) {
        if (fromSessionId == null) throw new IllegalArgumentException("Choose the session to copy from.");
        AcademicSession to = resolveSession(toSessionId);
        if (fromSessionId.equals(to.getId())) throw new IllegalArgumentException("Choose a different session to copy from.");
        resolveSession(fromSessionId);

        int copied = 0, skipped = 0;
        for (FeeSchedule src : scheduleRepository.findWithComponentsBySession(fromSessionId)) {
            Optional<FeeSchedule> existing = scheduleRepository.findByProgram_IdAndLevelAndSession_Id(src.getProgram().getId(), src.getLevel(), to.getId());
            if (existing.isPresent() && (!overwrite || paymentRepository.countBySchedule_Id(existing.get().getId()) > 0)) {
                skipped++;
                continue;
            }
            FeeSchedule target = existing.orElseGet(() -> {
                FeeSchedule n = new FeeSchedule();
                n.setProgram(src.getProgram());
                n.setLevel(src.getLevel());
                n.setSession(to);
                return n;
            });
            List<ComponentRequest> comps = src.getComponents().stream().map(c -> new ComponentRequest(c.getName(), c.getAmount())).toList();
            apply(target, src.isItemised(), src.getAmount(), comps, null, src.getNote(), actor);
            scheduleRepository.save(target);
            copied++;
        }
        return Map.of("copied", copied, "skipped", skipped);
    }

    // ── Helpers shared with FeePaymentService ───────────────────────────

    /** The fee a student with this programme and level pays in the current session, if one is set. */
    @Transactional
    public Optional<FeeSchedule> currentScheduleFor(Program program, Integer level) {
        if (program == null || level == null) return Optional.empty();
        AcademicSession session = academicSessionService.current();
        return scheduleRepository.findByProgram_IdAndLevelAndSession_Id(program.getId(), level, session.getId())
                .flatMap(s -> scheduleRepository.findWithComponents(s.getId()));
    }

    public Map<String, Object> toDto(FeeSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("programId", s.getProgram().getId());
        m.put("level", s.getLevel());
        m.put("sessionId", s.getSession().getId());
        m.put("amount", s.getAmount());
        m.put("itemised", s.isItemised());
        m.put("components", s.getComponents().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getName());
            cm.put("amount", c.getAmount());
            return cm;
        }).toList());
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
            out.add(new ComponentRequest(name, money(c.amount(), "\"" + name + "\"")));
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
