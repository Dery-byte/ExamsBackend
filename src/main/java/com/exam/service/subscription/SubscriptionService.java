package com.exam.service.subscription;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.monitoring.SubscriptionEvent;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.SubscriptionEventRepository;
import com.exam.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The institution's subscription: whether it has paid, and so how much of the portal works.
 * <p>
 * The dates come from a key signed by the vendor ({@link SubscriptionKeys}); the developer installs it,
 * can suspend the portal by hand, and can extend the grace period a little. The state is worked out
 * once a minute and kept in memory, so checking it costs nothing per request.
 * <p>
 * Whatever the state, a student already sitting an exam can always finish and submit it.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    public static final String KEY = "SUBSCRIPTION_KEY";
    public static final String FIRST_SEEN = "SUBSCRIPTION_FIRST_SEEN";
    /** Latest server time seen: setting the clock back can't win back days. */
    public static final String LAST_SEEN = "SUBSCRIPTION_LAST_SEEN";
    public static final String EXTENDED_UNTIL = "SUBSCRIPTION_EXTENDED_UNTIL";
    public static final String EXTENSION_REASON = "SUBSCRIPTION_EXTENSION_REASON";
    public static final String SUSPENDED_REASON = "SUBSCRIPTION_SUSPENDED_REASON";
    public static final String SUSPENDED_AT = "SUBSCRIPTION_SUSPENDED_AT";
    /** SUPER_ADMIN or ADMINS (Super Admins and HODs): who sees subscription notices and gets the emails. */
    public static final String NOTICE_AUDIENCE = "SUBSCRIPTION_NOTICE_AUDIENCE";
    /** How to reach the provider, shown with every notice (e.g. "Call 024 000 0000 or email billing@…"). */
    public static final String CONTACT = "SUBSCRIPTION_CONTACT";
    /** Extra addresses for the reminder emails (the institution's bursar, say), comma separated. */
    public static final String BILLING_EMAILS = "SUBSCRIPTION_BILLING_EMAILS";

    private static final List<String> STATE_KEYS = List.of(KEY, FIRST_SEEN, LAST_SEEN, EXTENDED_UNTIL, SUSPENDED_REASON, SUSPENDED_AT);

    /** The notice appears this many days before the paid period ends. */
    public static final int BANNER_DAYS_BEFORE_EXPIRY = 14;
    private static final int UNLICENSED_NOTICE_DAYS = 7;
    /** Students who submitted this recently still count as in an exam (theory marking calls follow submission). */
    private static final Duration EXAM_SESSION_TAIL = Duration.ofMinutes(15);
    private static final long CACHE_MS = 60_000;
    private static final long EXAM_CACHE_MS = 20_000;

    public enum Audience { SUPER_ADMIN, ADMINS }

    /** How much of the portal one person may use right now. */
    public enum Access { FULL, READ_ONLY, BLOCKED }

    @Autowired private SystemSettingService settings;
    @Autowired private SubscriptionKeys keys;
    @Autowired private SubscriptionEventRepository events;
    @Autowired private QuizAttemptRepository attempts;

    /** When set, keys must be issued for this institution (the code the vendor used when issuing them). */
    @Value("${app.subscription.institution-id:}")
    private String boundInstitution = "";

    private Clock clock = Clock.systemDefaultZone();

    private volatile SubscriptionStatus cached;
    private volatile long cachedAt;
    private final Map<Long, long[]> examSessions = new ConcurrentHashMap<>();

    /** For tests. */
    public void setClock(Clock clock) {
        this.clock = clock;
        cached = null;
    }

    // ── State ────────────────────────────────────────────────────────────

    public SubscriptionStatus status() {
        SubscriptionStatus s = cached;
        if (s == null || System.currentTimeMillis() - cachedAt > CACHE_MS) s = refresh();
        return s;
    }

    /** Works the state out again from the settings (after a change, and once a minute). */
    public synchronized SubscriptionStatus refresh() {
        Map<String, String> s = settings.getSettings(STATE_KEYS);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);

        LocalDateTime lastSeen = parseDateTime(s.get(LAST_SEEN));
        if (lastSeen == null || now.isAfter(lastSeen.plusHours(1))) settings.updateSetting(LAST_SEEN, now.toString());
        LocalDate today = (lastSeen != null && lastSeen.isAfter(now) ? lastSeen : now).toLocalDate();

        LocalDate firstSeen = parseDate(s.get(FIRST_SEEN));
        if (firstSeen == null) {
            firstSeen = today;
            settings.updateSetting(FIRST_SEEN, today.toString());
        }

        SubscriptionKey key = null;
        SubscriptionStatus.Source source = SubscriptionStatus.Source.NO_KEY;
        String problem = null;
        String raw = s.get(KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                key = keys.parse(raw);
                source = SubscriptionStatus.Source.KEY;
                if (!issuedForThisInstitution(key)) {
                    problem = "The installed key is for '" + key.institutionId() + "', not '" + boundInstitution + "'.";
                    source = SubscriptionStatus.Source.WRONG_INSTITUTION;
                    key = null;
                }
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
                source = SubscriptionStatus.Source.INVALID_KEY;
            }
        }

        SubscriptionStatus st = SubscriptionStatus.evaluate(key, source, problem, firstSeen, today,
                parseDate(s.get(EXTENDED_UNTIL)), blankToNull(s.get(SUSPENDED_REASON)), parseDateTime(s.get(SUSPENDED_AT)));
        cached = st;
        cachedAt = System.currentTimeMillis();
        return st;
    }

    private boolean issuedForThisInstitution(SubscriptionKey k) {
        return boundInstitution == null || boundInstitution.isBlank() || boundInstitution.trim().equalsIgnoreCase(k.institutionId());
    }

    // ── Enforcement ──────────────────────────────────────────────────────

    public Access accessFor(User u) {
        if (u == null || u.getRole() == Role.DEVELOPER) return Access.FULL;
        SubscriptionState state = status().state();
        if (state == SubscriptionState.ACTIVE || state == SubscriptionState.GRACE) return Access.FULL;
        if (u.getRole() == Role.NORMAL && inExam(u.getId())) return Access.FULL;
        if (state == SubscriptionState.RESTRICTED || u.getRole() == Role.SUPER_ADMIN) return Access.READ_ONLY;
        return Access.BLOCKED;
    }

    /** Suspended: only developers, Super Admins and students in the middle of an exam may sign in. */
    public boolean allowsSignIn(User u) {
        return accessFor(u) != Access.BLOCKED;
    }

    /** Public sign-up only while the subscription is in good standing. */
    public boolean allowsSelfSignUp() {
        return !status().limited();
    }

    /** Call before starting a brand-new quiz attempt (resuming one is always allowed). */
    public void assertNewAttemptsAllowed() {
        if (status().limited()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "New exams can't be started at the moment. Please contact your institution's administrator.");
    }

    /** Whether the student has an exam in progress, or just submitted one. Cached briefly per student. */
    public boolean inExam(Long userId) {
        if (userId == null) return false;
        long now = System.currentTimeMillis();
        long[] hit = examSessions.get(userId);
        if (hit != null && hit[0] > now) return hit[1] == 1;
        boolean in = attempts.existsByUser_IdAndStatus(userId, AttemptStatus.IN_PROGRESS)
                || attempts.existsByUser_IdAndStatusAndSubmittedAtAfter(userId, AttemptStatus.SUBMITTED,
                        LocalDateTime.now(clock).minus(EXAM_SESSION_TAIL));
        if (examSessions.size() > 10_000) examSessions.clear();
        examSessions.put(userId, new long[]{now + EXAM_CACHE_MS, in ? 1 : 0});
        return in;
    }

    // ── Notices ──────────────────────────────────────────────────────────

    public Audience audience() {
        return "ADMINS".equals(settings.getSetting(NOTICE_AUDIENCE)) ? Audience.ADMINS : Audience.SUPER_ADMIN;
    }

    /** Whether this person is told about the subscription (the rest only see that access is limited). */
    public boolean inAudience(User u) {
        if (u == null) return false;
        return u.getRole() == Role.DEVELOPER || u.getRole() == Role.SUPER_ADMIN
                || (u.getRole() == Role.ADMIN && audience() == Audience.ADMINS);
    }

    /** What the signed-in person's banner / blocked page shows. */
    public Map<String, Object> notice(User viewer) {
        SubscriptionStatus s = status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access", accessFor(viewer).name());
        if (!inAudience(viewer)) {
            m.put("show", false);
            return m;
        }
        Long days = s.daysLeft();
        boolean unlicensed = s.source() != SubscriptionStatus.Source.KEY;
        boolean show = s.state() != SubscriptionState.ACTIVE || (days != null && days <= BANNER_DAYS_BEFORE_EXPIRY);
        // A missing key is the vendor's to fix: the institution is only told near the end of the 30 days
        if (unlicensed && s.state() == SubscriptionState.GRACE) show = days != null && days <= UNLICENSED_NOTICE_DAYS;
        Map<String, Object> invoice = invoice();
        // An invoice waiting to be paid is always worth showing to those who pay it
        m.put("show", show || invoice != null);
        m.put("state", s.state().name());
        m.put("unlicensed", unlicensed);
        m.put("manual", s.manuallySuspended() || s.suspendedByProvider());
        m.put("paidUntil", s.paidUntil().toString());
        m.put("nextChange", s.nextChange() == null ? null : s.nextChange().toString());
        m.put("nextState", s.nextState() == null ? null : s.nextState().name());
        m.put("daysLeft", days);
        m.put("contact", blankToNull(settings.getSetting(CONTACT)));
        m.put("invoice", invoice);
        return m;
    }

    /** The open invoice from the vendor portal (number, amount, currency, dueDate, coversUntil, payUrl), or null. */
    public Map<String, Object> invoice() {
        String json = settings.getSetting(SubscriptionSync.INVOICE);
        if (json == null || json.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, LinkedHashMap.class);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Developer console ────────────────────────────────────────────────

    public Map<String, Object> details() {
        SubscriptionStatus s = refresh();
        Map<String, Object> m = describe(s);
        Map<String, String> cfg = settings.getSettings(List.of(NOTICE_AUDIENCE, CONTACT, BILLING_EMAILS, EXTENSION_REASON, FIRST_SEEN));
        m.put("audience", audience().name());
        m.put("contact", cfg.getOrDefault(CONTACT, ""));
        m.put("billingEmails", cfg.getOrDefault(BILLING_EMAILS, ""));
        m.put("extensionReason", s.extendedUntil() == null ? null : blankToNull(cfg.get(EXTENSION_REASON)));
        m.put("firstSeen", cfg.get(FIRST_SEEN));
        m.put("boundInstitution", blankToNull(boundInstitution));
        m.put("maxExtensionUntil", originalGraceEnd(s).plusDays(SubscriptionStatus.MAX_EXTENSION_DAYS).toString());
        m.put("invoice", invoice());
        m.put("events", events.findTop50ByOrderByAtDescIdDesc());
        return m;
    }

    /** What a key contains and whether it would be accepted, without installing it. */
    public Map<String, Object> checkKey(String raw) {
        SubscriptionKey k = keys.parse(raw);
        Map<String, Object> m = keyMap(k);
        List<String> warnings = new ArrayList<>();
        if (!issuedForThisInstitution(k))
            throw new IllegalArgumentException("This key is for '" + k.institutionId() + "', but this portal is set up for '" + boundInstitution.trim() + "'.");
        SubscriptionKey current = status().key();
        if (current != null && current.id().equals(k.id())) warnings.add("This key is already installed.");
        else if (current != null && current.isNewerThan(k))
            throw new IllegalArgumentException("This key is older than the one installed (issued " + k.issued() + " vs " + current.issued() + ").");
        if (k.suspended()) warnings.add("This key suspends the portal" + (k.note() == null ? "." : ": " + k.note()));
        if (k.paidUntil().isBefore(LocalDate.now(clock))) warnings.add("Its paid period ended on " + k.paidUntil() + ".");
        m.put("warnings", warnings);
        return m;
    }

    public Map<String, Object> installKey(String raw, String actor) {
        Map<String, Object> checked = checkKey(raw);
        SubscriptionState before = status().state();
        settings.updateSetting(KEY, raw.replaceAll("\\s+", ""));
        // A new key sets new terms: an extension given under the old one no longer applies
        settings.deleteSetting(EXTENDED_UNTIL);
        settings.deleteSetting(EXTENSION_REASON);
        SubscriptionStatus after = refresh();
        record("KEY_INSTALLED", before, after.state(), "Plan " + checked.get("plan") + ", paid until " + checked.get("paidUntil")
                + " (" + checked.get("institutionId") + ")" + (Boolean.TRUE.equals(checked.get("suspended"))
                ? "; suspends the portal" + (checked.get("note") == null ? "" : ": " + checked.get("note")) : ""), actor);
        return details();
    }

    public Map<String, Object> suspend(String reason, String actor) {
        String r = Objects.toString(reason, "").trim();
        if (r.isEmpty()) throw new IllegalArgumentException("Give a reason for suspending the portal.");
        if (r.length() > 300) throw new IllegalArgumentException("Keep the reason under 300 characters.");
        SubscriptionState before = status().state();
        settings.updateSetting(SUSPENDED_REASON, r);
        settings.updateSetting(SUSPENDED_AT, LocalDateTime.now(clock).withNano(0).toString());
        record("SUSPENDED", before, refresh().state(), r, actor);
        return details();
    }

    public Map<String, Object> resume(String actor) {
        if (!status().manuallySuspended()) throw new IllegalArgumentException("The portal is not suspended by hand.");
        SubscriptionState before = status().state();
        settings.deleteSetting(SUSPENDED_REASON);
        settings.deleteSetting(SUSPENDED_AT);
        record("RESUMED", before, refresh().state(), null, actor);
        return details();
    }

    /** Keeps the portal in grace until the given day (a promise to pay), at most 30 days past the key's grace. */
    public Map<String, Object> extend(LocalDate until, String reason, String actor) {
        if (until == null) throw new IllegalArgumentException("Choose the last day of the extension.");
        String r = Objects.toString(reason, "").trim();
        if (r.isEmpty()) throw new IllegalArgumentException("Give a reason for the extension.");
        if (r.length() > 300) throw new IllegalArgumentException("Keep the reason under 300 characters.");
        SubscriptionStatus s = status();
        LocalDate graceEnd = originalGraceEnd(s);
        LocalDate cap = graceEnd.plusDays(SubscriptionStatus.MAX_EXTENSION_DAYS);
        if (!until.isAfter(graceEnd))
            throw new IllegalArgumentException("Grace already runs to " + graceEnd + "; choose a later day.");
        if (until.isAfter(cap))
            throw new IllegalArgumentException("An extension can run to " + cap + " at the latest. For longer, issue a new key.");
        settings.updateSetting(EXTENDED_UNTIL, until.toString());
        settings.updateSetting(EXTENSION_REASON, r);
        record("EXTENDED", s.state(), refresh().state(), "Grace extended to " + until + ": " + r, actor);
        return details();
    }

    public Map<String, Object> clearExtension(String actor) {
        SubscriptionState before = status().state();
        settings.deleteSetting(EXTENDED_UNTIL);
        settings.deleteSetting(EXTENSION_REASON);
        record("EXTENSION_REMOVED", before, refresh().state(), null, actor);
        return details();
    }

    public Map<String, Object> updateSettings(Map<String, Object> body, String actor) {
        List<String> changed = new ArrayList<>();
        if (body.containsKey("audience")) {
            String a = Objects.toString(body.get("audience"), "");
            if (!a.equals("SUPER_ADMIN") && !a.equals("ADMINS"))
                throw new IllegalArgumentException("Choose who sees subscription notices.");
            if (!a.equals(audience().name())) changed.add("notices for " + (a.equals("ADMINS") ? "Super Admins and Admins" : "Super Admins only"));
            settings.updateSetting(NOTICE_AUDIENCE, a);
        }
        if (body.containsKey("contact")) {
            String c = Objects.toString(body.get("contact"), "").trim();
            if (c.length() > 300) throw new IllegalArgumentException("Keep the contact details under 300 characters.");
            if (!c.equals(Objects.toString(settings.getSetting(CONTACT), ""))) changed.add("contact details");
            settings.updateSetting(CONTACT, c);
        }
        if (body.containsKey("billingEmails")) {
            List<String> list = splitEmails(Objects.toString(body.get("billingEmails"), ""));
            for (String e : list)
                if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("'" + e + "' is not an email address.");
            if (list.size() > 10) throw new IllegalArgumentException("Add at most 10 billing email addresses.");
            String joined = String.join(", ", list);
            if (!joined.equals(Objects.toString(settings.getSetting(BILLING_EMAILS), ""))) changed.add("billing emails");
            settings.updateSetting(BILLING_EMAILS, joined);
        }
        if (!changed.isEmpty()) record("SETTINGS", null, null, "Changed " + String.join(", ", changed), actor);
        return details();
    }

    public String contact() { return blankToNull(settings.getSetting(CONTACT)); }

    public List<String> billingEmails() { return splitEmails(settings.getSetting(BILLING_EMAILS)); }

    public void record(String type, SubscriptionState from, SubscriptionState to, String details, String actor) {
        try {
            SubscriptionEvent e = new SubscriptionEvent();
            e.setAt(LocalDateTime.now(clock).withNano(0));
            e.setType(type);
            e.setFromState(from == null ? null : from.name());
            e.setToState(to == null ? null : to.name());
            e.setDetails(details == null ? null : details.length() > 500 ? details.substring(0, 499) + "…" : details);
            e.setActor(actor);
            events.save(e);
        } catch (Exception ex) {
            log.warn("Could not record subscription event {}: {}", type, ex.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    public static Map<String, Object> describe(SubscriptionStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state().name());
        m.put("source", s.source().name());
        m.put("keyProblem", s.keyProblem());
        m.put("key", s.key() == null ? null : keyMap(s.key()));
        m.put("today", s.today().toString());
        m.put("paidUntil", s.paidUntil().toString());
        m.put("graceEnd", s.graceEnd().toString());
        m.put("restrictedEnd", s.restrictedEnd().toString());
        m.put("extendedUntil", s.extendedUntil() == null ? null : s.extendedUntil().toString());
        m.put("suspendedReason", s.suspendedReason());
        m.put("providerSuspended", s.suspendedByProvider());
        m.put("suspendedAt", s.suspendedAt() == null ? null : s.suspendedAt().toString());
        m.put("nextChange", s.nextChange() == null ? null : s.nextChange().toString());
        m.put("nextState", s.nextState() == null ? null : s.nextState().name());
        m.put("daysLeft", s.daysLeft());
        return m;
    }

    private static Map<String, Object> keyMap(SubscriptionKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("institutionId", k.institutionId());
        m.put("institutionName", k.institutionName());
        m.put("plan", k.plan());
        m.put("issued", k.issued().toString());
        m.put("paidUntil", k.paidUntil().toString());
        m.put("graceDays", k.graceDays());
        m.put("restrictedDays", k.restrictedDays());
        m.put("seq", k.seq());
        m.put("suspended", k.suspended());
        m.put("note", k.note());
        return m;
    }

    /** End of grace before any extension. */
    private static LocalDate originalGraceEnd(SubscriptionStatus s) {
        int grace = s.key() != null ? s.key().graceDays() : SubscriptionStatus.UNLICENSED_GRACE_DAYS;
        return s.paidUntil().plusDays(grace);
    }

    private static List<String> splitEmails(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("[,;\\s]+")).map(String::trim).filter(e -> !e.isEmpty()).distinct().toList();
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v.trim()); } catch (DateTimeParseException e) { return null; }
    }

    private static LocalDateTime parseDateTime(String v) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDateTime.parse(v.trim()); } catch (DateTimeParseException e) { return null; }
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }
}
