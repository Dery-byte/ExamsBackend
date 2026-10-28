package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.fees.FeeConcession;
import com.exam.repository.AcademicSessionRepository;
import com.exam.repository.FeeConcessionRepository;
import com.exam.repository.FeeSessionCloseRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Concessions: what the school takes off a student's school fees for a session (scholarship it funds,
 * bursary, sibling or staff-child discount, waiver). The bursar grants and revokes them, always with a
 * name and a reason; the change shows on the student's fees at once, and anything they had already
 * paid beyond the new amount becomes credit. A closed session's fees are arrears and no longer change.
 */
@Service
public class ConcessionService {

    @Autowired private FeeConcessionRepository concessions;
    @Autowired private FeeSessionCloseRepository closes;
    @Autowired private UserRepository users;
    @Autowired private AcademicSessionRepository sessions;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private FeeDues feeDues;
    @Autowired private FeeLedger ledger;
    @Autowired private FeeScheduleService scheduleService;

    public record ConcessionRequest(Long studentId, Long sessionId, String kind, String title, String mode, BigDecimal value,
                                    List<String> items, Integer term, String note) {}

    public record RevokeRequest(String reason) {}

    private static final Map<FeeConcession.Kind, String> KIND = Map.of(
            FeeConcession.Kind.SCHOLARSHIP, "Scholarship", FeeConcession.Kind.BURSARY, "Bursary",
            FeeConcession.Kind.SIBLING, "Sibling discount", FeeConcession.Kind.STAFF_CHILD, "Staff child discount",
            FeeConcession.Kind.WAIVER, "Waiver", FeeConcession.Kind.OTHER, "Concession");

    @Transactional
    public Map<String, Object> grant(User actor, ConcessionRequest req) {
        FinanceAccess.requireBursar(actor);
        if (req == null || req.studentId() == null) throw new IllegalArgumentException("Choose the student.");
        User student = users.findById(req.studentId()).filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        AcademicSession session = req.sessionId() == null ? academicSessions.current()
                : sessions.findById(req.sessionId()).orElseThrow(() -> new IllegalArgumentException("Session not found."));
        requireOpen(session);

        FeeConcession.Kind kind = parse(FeeConcession.Kind.class, req.kind(), "Choose the kind of concession.");
        FeeConcession.Mode mode = parse(FeeConcession.Mode.class, req.mode(), "Choose a percentage or an amount.");
        BigDecimal value = req.value() == null ? null : req.value().setScale(2, RoundingMode.HALF_UP);
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Give the concession a value above zero.");
        if (mode == FeeConcession.Mode.PERCENT && value.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("A percentage can't be more than 100.");
        if (mode == FeeConcession.Mode.AMOUNT) FeeScheduleService.money(value, "The amount");
        if (req.term() != null && (req.term() < 1 || req.term() > 12)) throw new IllegalArgumentException("Choose a valid " + com.exam.model.academic.SystemMode.current().terms().get("semester").toLowerCase(Locale.ROOT) + ".");
        List<String> items = req.items() == null ? List.of() : req.items().stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (items.size() > 20) throw new IllegalArgumentException("Choose 20 items at most.");
        String joined = items.isEmpty() ? null : String.join("\n", items);
        if (joined != null && joined.length() > 500) throw new IllegalArgumentException("The item names are too long.");
        String title = clean(req.title(), 120);
        String note = clean(req.note(), 300);
        if (note == null) throw new IllegalArgumentException("Say why it is given (e.g. the scholarship letter, or whose child).");

        FeeConcession c = new FeeConcession();
        c.setStudent(student);
        c.setSession(session);
        c.setKind(kind);
        c.setTitle(title != null ? title : KIND.get(kind));
        c.setMode(mode);
        c.setValue(value);
        c.setItems(joined);
        c.setTerm(req.term());
        c.setNote(note);
        c.setCreatedBy(CurrentUserService.displayName(actor));
        concessions.saveAndFlush(c);
        ledger.applyCredit(student);    // what they paid beyond the new amount becomes credit
        return dto(c, student);
    }

    @Transactional
    public Map<String, Object> revoke(User actor, Long id, RevokeRequest req) {
        FinanceAccess.requireBursar(actor);
        FeeConcession c = concessions.findById(id).orElseThrow(() -> new IllegalArgumentException("Concession not found."));
        if (!c.isActive()) throw new IllegalArgumentException("This concession was already revoked.");
        requireOpen(c.getSession());
        String reason = clean(req == null ? null : req.reason(), 200);
        if (reason == null) throw new IllegalArgumentException("Give a reason for revoking it.");
        c.setActive(false);
        c.setRevokedAt(LocalDateTime.now());
        c.setRevokedBy(CurrentUserService.displayName(actor));
        c.setRevokeReason(reason);
        concessions.saveAndFlush(c);
        ledger.applyCredit(c.getStudent());   // their fee is higher again: any credit goes to it
        return dto(c, c.getStudent());
    }

    /** Every concession of a session (current by default), with what each student saves in all this session. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long sessionId) {
        AcademicSession session = sessionId == null ? academicSessions.current()
                : sessions.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Session not found."));
        List<FeeConcession> all = concessions.findAllBySession(session.getId());
        // What each student saves: one pass over the session's fees (every term)
        FeeDues.Book book = feeDues.wholeSession(session);
        Map<Long, BigDecimal> saved = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (FeeConcession c : all) {
            if (!c.isActive() || saved.containsKey(c.getStudent().getId())) continue;
            BigDecimal s = book.dues(c.getStudent()).stream().map(FeeDues.Due::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
            saved.put(c.getStudent().getId(), s);
            total = total.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("session", FeeScheduleService.sessionDto(session));
        out.put("closed", closes.existsBySession_Id(session.getId()));
        out.put("kinds", Arrays.stream(FeeConcession.Kind.values()).map(k -> Map.of("value", k.name(), "label", KIND.get(k))).toList());
        out.put("students", saved.size());
        out.put("totalSaved", total);
        out.put("concessions", all.stream().map(c -> {
            Map<String, Object> m = dto(c, c.getStudent());
            m.put("studentSaves", saved.get(c.getStudent().getId()));
            return m;
        }).toList());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forStudent(Long studentId) {
        return concessions.findForStudent(studentId).stream().map(c -> dto(c, null)).toList();
    }

    private void requireOpen(AcademicSession session) {
        if (closes.existsBySession_Id(session.getId()))
            throw new IllegalArgumentException(session.getName() + " fees are closed and now owed as arrears. Write off arrears instead.");
    }

    public static String kindLabel(FeeConcession.Kind k) { return KIND.get(k); }

    private static Map<String, Object> dto(FeeConcession c, User student) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("kind", c.getKind().name());
        m.put("kindLabel", KIND.get(c.getKind()));
        m.put("title", c.getTitle());
        m.put("mode", c.getMode().name());
        m.put("value", c.getValue());
        m.put("items", c.getItems() == null ? List.of() : List.of(c.getItems().split("\n")));
        m.put("term", c.getTerm());
        m.put("termName", c.getTerm() == null ? null : FeeDues.periodName(c.getTerm()));
        m.put("note", c.getNote());
        m.put("active", c.isActive());
        m.put("sessionName", c.getSession().getName());
        m.put("createdBy", c.getCreatedBy());
        m.put("createdAt", c.getCreatedAt());
        m.put("revokedBy", c.getRevokedBy());
        m.put("revokedAt", c.getRevokedAt());
        m.put("revokeReason", c.getRevokeReason());
        if (student != null) {
            m.put("studentId", student.getId());
            m.put("studentName", CurrentUserService.displayName(student));
            m.put("username", student.getUsername());
            m.put("programName", student.getProgram() != null ? student.getProgram().getName() : null);
            m.put("level", student.getCurrentLevel());
        }
        return m;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String s, String error) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(error);
        try { return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(error); }
    }

    static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() > max) throw new IllegalArgumentException("That text is too long (" + max + " characters at most).");
        return t;
    }
}
