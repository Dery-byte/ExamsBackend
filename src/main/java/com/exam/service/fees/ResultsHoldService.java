package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.SystemMode;
import com.exam.model.exam.Program;
import com.exam.model.fees.FeeComponent;
import com.exam.model.fees.FeeSchedule;
import com.exam.model.fees.ResultsFeeHold;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.FeeScheduleRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.ResultsFeeHoldRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Holds students' report cards and transcript until they have paid enough of the current session's
 * fee, by the Super Admin's rule for their programme: the full fee, a minimum percentage of it, or
 * chosen items of the breakdown. Staff (HOD, Super Admin) can always open a student's record, and
 * document verification is never affected. A student whose class has no fee set is never held.
 */
@Service
public class ResultsHoldService {

    public enum Document { REPORT_CARDS, TRANSCRIPT }

    private static final int MAX_ITEMS = 40;

    @Autowired private ResultsFeeHoldRepository holdRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FeeScheduleRepository scheduleRepository;
    @Autowired private FeePaymentRepository paymentRepository;
    @Autowired private FeeScheduleService scheduleService;
    @Autowired private FeeDues feeDues;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private SystemSettingService settings;

    /** reportCards / transcript: which documents are held; alsoApplyToPrograms: the same rule for these programmes too. */
    public record HoldRequest(Long programId, String mode, Integer minPercent, List<String> requiredItems,
                              Boolean reportCards, Boolean transcript, List<Long> alsoApplyToPrograms) {}

    /** A student asked for a document that is on hold; {@link #body()} is what their page shows. */
    public static class ResultsHeldException extends RuntimeException {
        private final Map<String, Object> body;

        public ResultsHeldException(Map<String, Object> body) {
            super((String) body.get("message"));
            this.body = body;
        }

        public Map<String, Object> body() { return body; }
    }

    // ── Super Admin ─────────────────────────────────────────────────────

    /** Every programme with its rule (if any) and the items its current-session fees are broken into. */
    @Transactional
    public Map<String, Object> overview() {
        Map<Long, ResultsFeeHold> rules = new HashMap<>();
        for (ResultsFeeHold h : holdRepository.findAllWithProgram()) rules.put(h.getProgram().getId(), h);

        Map<Long, LinkedHashMap<String, String>> items = new HashMap<>();
        Map<Long, Integer> levelsWithFee = new HashMap<>();
        for (FeeSchedule s : scheduleRepository.findWithComponentsBySession(academicSessionService.current().getId())) {
            Long pid = s.getProgram().getId();
            levelsWithFee.merge(pid, 1, Integer::sum);
            LinkedHashMap<String, String> names = items.computeIfAbsent(pid, k -> new LinkedHashMap<>());
            if (s.isItemised()) for (FeeComponent c : s.getComponents()) names.putIfAbsent(FeeBreakdown.key(c.getName()), c.getName());
        }

        List<Program> programs = new ArrayList<>(programRepository.findAll());
        programs.sort(Comparator.comparing((Program p) -> p.getDepartment() != null ? p.getDepartment().getName() : "")
                .thenComparing(Program::getName, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Program p : programs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("code", p.getCode());
            m.put("departmentName", p.getDepartment() != null ? p.getDepartment().getName() : null);
            m.put("enabled", p.isEnabled());
            m.put("levelsWithFee", levelsWithFee.getOrDefault(p.getId(), 0));
            m.put("items", new ArrayList<>(items.getOrDefault(p.getId(), new LinkedHashMap<>()).values()));
            ResultsFeeHold h = rules.get(p.getId());
            m.put("rule", h == null ? null : toDto(h));
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", isOn());
        out.put("feesVisibleToStudents", settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false));
        out.put("session", FeeScheduleService.sessionDto(academicSessionService.current()));
        out.put("programs", rows);
        return out;
    }

    @Transactional
    public Map<String, Object> save(HoldRequest req, String actor) {
        if (req == null || req.programId() == null) throw new IllegalArgumentException("Choose a programme.");
        ResultsFeeHold.Mode mode;
        try { mode = ResultsFeeHold.Mode.valueOf(req.mode() == null ? "" : req.mode().trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose what students must pay."); }
        boolean reportCards = Boolean.TRUE.equals(req.reportCards());
        boolean transcript = Boolean.TRUE.equals(req.transcript());
        if (!reportCards && !transcript) throw new IllegalArgumentException("Choose what to hold: report cards, the transcript or both.");

        Integer percent = null;
        if (mode == ResultsFeeHold.Mode.PERCENT) {
            if (req.minPercent() == null || req.minPercent() < 1 || req.minPercent() > 100)
                throw new IllegalArgumentException("The minimum must be between 1% and 100%.");
            percent = req.minPercent();
            if (percent == 100) mode = ResultsFeeHold.Mode.FULL;
        }
        List<String> names = mode == ResultsFeeHold.Mode.ITEMS ? cleanItems(req.requiredItems()) : List.of();

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.add(req.programId());
        if (req.alsoApplyToPrograms() != null) req.alsoApplyToPrograms().stream().filter(Objects::nonNull).forEach(ids::add);

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Long id : ids) {
            Program program = programRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Programme not found."));
            ResultsFeeHold h = holdRepository.findByProgram_Id(id).orElseGet(() -> {
                ResultsFeeHold n = new ResultsFeeHold();
                n.setProgram(program);
                return n;
            });
            h.setMode(mode);
            h.setMinPercent(mode == ResultsFeeHold.Mode.PERCENT ? percent : null);
            h.setRequiredItems(names.isEmpty() ? null : String.join("\n", names));
            h.setHoldReportCards(reportCards);
            h.setHoldTranscript(transcript);
            h.setUpdatedAt(LocalDateTime.now());
            h.setUpdatedBy(actor);
            Map<String, Object> dto = toDto(holdRepository.save(h));
            dto.put("programId", id);
            saved.add(dto);
        }
        return Map.of("saved", saved);
    }

    /** Lifts the hold for a programme. */
    @Transactional
    public void remove(Long programId) {
        holdRepository.findByProgram_Id(programId).ifPresent(holdRepository::delete);
    }

    // ── Students ────────────────────────────────────────────────────────

    /** Throws {@link ResultsHeldException} when this student can't have the document yet. */
    @Transactional
    public void requireReleased(User principal, Document document) {
        Map<String, Object> hold = check(principal, EnumSet.of(document));
        if (hold != null) throw new ResultsHeldException(hold);
    }

    /**
     * What is held for this student and what they must still pay, or null when nothing in
     * {@code documents} is held. Shown on the student's page instead of the document.
     */
    @Transactional
    public Map<String, Object> check(User principal, Set<Document> documents) {
        if (principal == null || !isOn()) return null;
        User student = userRepository.findById(principal.getId()).orElse(null);
        if (student == null || student.getRole() != Role.NORMAL || student.getProgram() == null) return null;
        ResultsFeeHold rule = holdRepository.findByProgram_Id(student.getProgram().getId()).orElse(null);
        if (rule == null) return null;
        BigDecimal arrears = feeDues.arrears(student).stream().map(FeeDues.ArrearsDue::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return evaluate(rule, documents, feeDues.dues(student), arrears,
                scheduleId -> sums(paymentRepository.paidItems(student.getId(), scheduleId)));
    }

    /**
     * The rule applied to what the student owes: this session's fees so far (all, a percentage, or
     * chosen items) and, always in full, fees from earlier years (arrears). No queries of its own, so
     * reports can check many students from data loaded once.
     */
    Map<String, Object> evaluate(ResultsFeeHold rule, Set<Document> documents, List<FeeDues.Due> dues, BigDecimal arrears,
                                 java.util.function.Function<Long, Map<String, BigDecimal>> paidItems) {
        List<Document> held = new ArrayList<>();
        if (rule.isHoldReportCards() && documents.contains(Document.REPORT_CARDS)) held.add(Document.REPORT_CARDS);
        if (rule.isHoldTranscript() && documents.contains(Document.TRANSCRIPT)) held.add(Document.TRANSCRIPT);
        if (held.isEmpty()) return null;
        if (dues.isEmpty() && arrears.signum() <= 0) return null;    // nothing owed for their class: nothing to hold them for

        String currency = scheduleService.currency();
        BigDecimal fee = dues.stream().map(FeeDues.Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = dues.stream().map(d -> d.paid().min(d.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = fee.subtract(paid).max(BigDecimal.ZERO);
        String sessionName = dues.isEmpty() ? academicSessionService.current().getName() : dues.get(0).schedule().getSession().getName();
        String what = describe(held);

        BigDecimal toPay;
        List<Map<String, Object>> unpaidItems = new ArrayList<>();
        String condition;
        ResultsFeeHold.Mode mode = rule.getMode();
        if (mode == ResultsFeeHold.Mode.ITEMS && dues.stream().noneMatch(d -> d.schedule().isItemised()))
            mode = ResultsFeeHold.Mode.FULL;   // no breakdown to pick items from

        switch (mode) {
            case PERCENT -> {
                int pct = rule.getMinPercent() == null ? 100 : rule.getMinPercent();
                BigDecimal needed = fee.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.UP);
                toPay = needed.subtract(paid).max(BigDecimal.ZERO);
                condition = "you have paid at least " + pct + "% of your " + sessionName + " fees (" + money(currency, needed) + ")";
            }
            case ITEMS -> {
                Set<String> required = new HashSet<>();
                for (String n : splitItems(rule.getRequiredItems())) required.add(FeeBreakdown.key(n));
                toPay = BigDecimal.ZERO;
                for (FeeDues.Due d : dues) {
                    if (!d.schedule().isItemised()) continue;
                    for (FeeBreakdown.Line line : FeeBreakdown.compute(d.components(), paidItems.apply(d.schedule().getId()), d.paid())) {
                        if (!required.contains(FeeBreakdown.key(line.name())) || line.balance().signum() <= 0) continue;
                        toPay = toPay.add(line.balance());
                        Map<String, Object> m = line.toMap();
                        if (dues.size() > 1) m.put("name", line.name() + " (" + FeeDues.periodName(d.schedule()) + ")");
                        unpaidItems.add(m);
                    }
                }
                condition = "you have paid for " + String.join(", ", unpaidItems.stream().map(i -> (String) i.get("name")).toList());
            }
            default -> {
                toPay = balance;
                condition = "your " + sessionName + " fees are paid in full";
            }
        }
        if (arrears.signum() > 0) {
            condition = (toPay.signum() > 0 ? condition + " and " : "") + "your fees from earlier years (" + money(currency, arrears) + ") are paid";
            toPay = toPay.add(arrears);
        }
        if (toPay.signum() <= 0) return null;

        boolean feesPage = settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false);
        String message = "Your " + what + (held.size() > 1 || what.endsWith("s") ? " are" : " is") + " on hold until " + condition
                + ". Pay " + money(currency, toPay) + " more to release " + (held.size() > 1 || what.endsWith("s") ? "them" : "it") + "."
                + (feesPage ? "" : " Please see the accounts office.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", "FEES_HOLD");
        out.put("message", message);
        out.put("documents", held.stream().map(Enum::name).toList());
        out.put("mode", mode.name());
        out.put("currency", currency);
        out.put("sessionName", sessionName);
        out.put("fee", fee);
        out.put("paid", paid);
        out.put("balance", balance.add(arrears));
        out.put("arrears", arrears);
        out.put("amountToRelease", toPay);
        out.put("unpaidItems", unpaidItems);
        out.put("feesPage", feesPage);
        return out;
    }

    /**
     * For a report: tells from data already loaded (a live {@link FeeDues.Book} and each student's
     * arrears) whose results are held. Loads the rules and the session's item payments once.
     */
    @Transactional(readOnly = true)
    public java.util.function.BiPredicate<User, List<FeeDues.Due>> heldTester(FeeDues.Book book, Map<Long, BigDecimal[]> arrearsAndCredit) {
        if (!isOn()) return (u, d) -> false;
        Map<Long, ResultsFeeHold> rules = new HashMap<>();
        for (ResultsFeeHold h : holdRepository.findAllWithProgram()) rules.put(h.getProgram().getId(), h);
        if (rules.isEmpty()) return (u, d) -> false;
        Map<List<Long>, Map<String, BigDecimal>> items = new HashMap<>();
        for (Object[] r : paymentRepository.paidItemsBySession(book.session().getId()))
            items.computeIfAbsent(List.of((Long) r[0], (Long) r[1]), k -> new HashMap<>()).merge((String) r[2], (BigDecimal) r[3], BigDecimal::add);
        Set<Document> all = EnumSet.allOf(Document.class);
        return (u, dues) -> {
            ResultsFeeHold rule = u.getProgram() == null ? null : rules.get(u.getProgram().getId());
            if (rule == null) return false;
            BigDecimal[] ac = arrearsAndCredit.get(u.getId());
            return evaluate(rule, all, dues, ac == null ? BigDecimal.ZERO : ac[0],
                    scheduleId -> items.getOrDefault(List.of(u.getId(), scheduleId), Map.of())) != null;
        };
    }

    private static Map<String, BigDecimal> sums(List<Object[]> rows) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Object[] r : rows) m.merge((String) r[0], (BigDecimal) r[1], BigDecimal::add);
        return m;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean isOn() {
        return settings.getBooleanSetting(SystemSettingService.FEES_RESULTS_HOLD, false);
    }

    // ── Reports ─────────────────────────────────────────────────────────

    /** Programmes with a hold rule, or none while holding results is switched off. */
    @Transactional(readOnly = true)
    public Set<Long> programsWithRule() {
        if (!isOn()) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (ResultsFeeHold h : holdRepository.findAllWithProgram()) ids.add(h.getProgram().getId());
        return ids;
    }

    /** Whether any of this student's results documents are on hold for fees right now. */
    @Transactional
    public boolean isHeld(User student) {
        return check(student, EnumSet.allOf(Document.class)) != null;
    }

    private static String describe(List<Document> held) {
        String cards = SystemMode.current().isSchool() ? "terminal reports" : "report cards";
        if (held.size() > 1) return cards + " and transcript";
        return held.get(0) == Document.REPORT_CARDS ? cards : "transcript";
    }

    private static String money(String currency, BigDecimal amount) {
        return currency + " " + String.format(Locale.ROOT, "%,.2f", amount);
    }

    private Map<String, Object> toDto(ResultsFeeHold h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", h.getMode().name());
        m.put("minPercent", h.getMinPercent());
        m.put("requiredItems", splitItems(h.getRequiredItems()));
        m.put("reportCards", h.isHoldReportCards());
        m.put("transcript", h.isHoldTranscript());
        m.put("updatedAt", h.getUpdatedAt());
        m.put("updatedBy", h.getUpdatedBy());
        return m;
    }

    private static List<String> splitItems(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        return Arrays.stream(stored.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> cleanItems(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (raw != null) {
            for (String r : raw) {
                String name = r == null ? "" : r.trim().replaceAll("\\s+", " ");
                if (name.isEmpty() || !seen.add(FeeBreakdown.key(name))) continue;
                if (name.length() > 80) throw new IllegalArgumentException("\"" + name.substring(0, 30) + "…\" is too long (80 characters at most).");
                out.add(name);
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Choose at least one item students must pay for.");
        if (out.size() > MAX_ITEMS) throw new IllegalArgumentException("Choose at most " + MAX_ITEMS + " items.");
        return out;
    }
}
