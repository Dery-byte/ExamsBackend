package com.exam.service.fees;

import com.exam.config.MNotifyV2Config;
import com.exam.model.User;
import com.exam.model.academic.SystemMode;
import com.exam.model.fees.FeeReminder;
import com.exam.repository.FeeReminderRepository;
import com.exam.repository.UserRepository;
import com.exam.service.MNotifyV2SmsService;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import com.exam.service.Impl.EmailService;
import com.exam.service.Impl.EmailTemplateName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reminders to students who owe (arrears, fees so far, bills, less credit). Always in the portal;
 * by email to those with an address, and by SMS when SMS is set up. Nobody is reminded twice within
 * the chosen number of days, and every reminder is logged. The bursar sends them, or the system does
 * every N days when the bursar turns that on (portal and email only: SMS costs money).
 * Emails and SMS go out one at a time on a background thread, never holding up the request.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    public static final String NOTIFY_TYPE = "FEE_REMINDER";
    private static final int MAX_MESSAGE = 400;
    private static final String DEFAULT_MESSAGE =
            "Dear {name}, this is a reminder that your school fees balance is {amount}. Please pay at the accounts office or online. Thank you. {school}";

    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fee-reminders");
        t.setDaemon(true);
        return t;
    });

    @Autowired private FeeDues feeDues;
    @Autowired private FeeReminderRepository reminders;
    @Autowired private UserRepository users;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private NotificationService notifications;
    @Autowired private EmailService emailService;
    @Autowired private MNotifyV2SmsService sms;
    @Autowired private MNotifyV2Config smsConfig;
    @Autowired private InstitutionService institution;
    @Autowired private SystemSettingService settings;
    @Autowired private FeeScheduleService scheduleService;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    /** Who: a class (or everyone), owing at least minOwed, not reminded in the last skipDays; how; and the message. */
    public record ReminderRequest(Long programId, Integer level, BigDecimal minOwed, Integer skipDays,
                                  Boolean email, Boolean sms, String message) {}

    public record AutoRequest(Integer everyDays) {}

    private record Target(User student, BigDecimal owed) {}

    // ── Who owes ─────────────────────────────────────────────────────────────

    /** Students who owe, from data loaded once for everyone (no query per student). */
    private List<Target> targets(Long programId, Integer level, BigDecimal minOwed, int skipDays, int[] skipped) {
        FeeDues.Book book = feeDues.book(academicSessions.current());
        Map<Long, BigDecimal[]> ac = feeDues.arrearsAndCredit();
        Map<Long, BigDecimal> bills = feeDues.billsOwed();
        Set<Long> recent = skipDays <= 0 ? Set.of() : new HashSet<>(reminders.remindedSince(LocalDateTime.now().minusDays(skipDays)));
        BigDecimal min = minOwed == null || minOwed.signum() <= 0 ? BigDecimal.ONE : minOwed;
        List<Target> out = new ArrayList<>();
        for (User u : users.findActiveStudents(programId, level)) {
            BigDecimal[] v = ac.getOrDefault(u.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal fees = book.dues(u).stream().map(FeeDues.Due::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal owed = v[0].add(fees).add(bills.getOrDefault(u.getId(), BigDecimal.ZERO)).subtract(v[1]);
            if (owed.compareTo(min) < 0) continue;
            if (recent.contains(u.getId())) { skipped[0]++; continue; }
            out.add(new Target(u, owed));
        }
        out.sort(Comparator.comparing(Target::owed).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(User actor, ReminderRequest req) {
        FinanceAccess.requireBursar(actor);
        int[] skipped = {0};
        List<Target> list = targets(req.programId(), req.level(), req.minOwed(), skipDays(req), skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", list.size());
        out.put("total", list.stream().map(Target::owed).reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("skipped", skipped[0]);
        out.put("withEmail", list.stream().filter(t -> hasEmail(t.student())).count());
        out.put("withPhone", list.stream().filter(t -> hasPhone(t.student())).count());
        out.put("smsReady", smsReady());
        out.put("sample", list.stream().limit(6).map(t -> Map.of("name", CurrentUserService.displayName(t.student()), "owed", t.owed())).toList());
        out.put("example", list.isEmpty() ? null : render(message(req), list.get(0)));
        return out;
    }

    @Transactional
    public Map<String, Object> send(User actor, ReminderRequest req) {
        FinanceAccess.requireBursar(actor);
        if (Boolean.TRUE.equals(req.sms()) && !smsReady()) throw new IllegalArgumentException("SMS is not set up on the server, so reminders can't go by SMS.");
        int[] skipped = {0};
        List<Target> list = targets(req.programId(), req.level(), req.minOwed(), skipDays(req), skipped);
        if (list.isEmpty()) throw new IllegalArgumentException(skipped[0] > 0 ? "Everyone who owes was reminded recently." : "Nobody owes that much.");
        Map<String, Object> out = dispatch(list, message(req), Boolean.TRUE.equals(req.email()), Boolean.TRUE.equals(req.sms()), false,
                CurrentUserService.displayName(actor));
        out.put("skipped", skipped[0]);
        return out;
    }

    /** Daily at 08:00: when automatic reminders are on and it is time, reminds everyone who owes (portal and email). */
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void automatic() {
        int every = autoDays();
        if (every <= 0) return;
        LocalDateTime last = reminders.lastAutomatic().orElse(null);
        if (last != null && last.isAfter(LocalDateTime.now().minusDays(every).plusHours(1))) return;
        try {
            List<Target> list = targets(null, null, BigDecimal.ONE, every, new int[]{0});
            if (list.isEmpty()) return;
            dispatch(list, DEFAULT_MESSAGE, true, false, true, "Automatic");
            log.info("[Fees] Sent {} automatic fee reminders", list.size());
        } catch (Exception e) {
            log.error("[Fees] Automatic fee reminders failed: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> dispatch(List<Target> list, String template, boolean email, boolean bySms, boolean automatic, String by) {
        String batch = UUID.randomUUID().toString();
        boolean feesPage = settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false);
        String link = feesPage ? "/user-dashboard/fees" : null;
        String feesUrl = feesPage ? frontendUrl.replaceAll("/+$", "") + "/user-dashboard/fees" : null;
        List<FeeReminder> rows = new ArrayList<>(list.size());
        int emails = 0, texts = 0;
        for (Target t : list) {
            String text = render(template, t);
            notifications.notify(t.student(), NOTIFY_TYPE, "Fee reminder: " + money(t.owed()) + " to pay", text, link);
            boolean e = email && hasEmail(t.student()), s = bySms && hasPhone(t.student());
            String to = t.student().getEmail(), phone = t.student().getPhone();
            if (e) {
                emails++;
                Map<String, Object> vars = new HashMap<>(Map.of("message", text, "amount", money(t.owed())));
                vars.put("feesUrl", feesUrl);
                sender.execute(() -> {
                    try { emailService.sendEmail(to, EmailTemplateName.FEE_REMINDER, vars, "Fee reminder"); }
                    catch (Exception ex) { log.warn("[Fees] Reminder email to {} failed: {}", to, ex.getMessage()); }
                });
            }
            if (s) {
                texts++;
                sender.execute(() -> sms.sendSms(phone, text));
            }
            FeeReminder r = new FeeReminder();
            r.setStudent(t.student());
            r.setBatch(batch);
            r.setOwed(t.owed());
            r.setByEmail(e);
            r.setBySms(s);
            r.setAutomatic(automatic);
            r.setMessage(text.length() > 500 ? text.substring(0, 500) : text);
            r.setSentBy(by);
            rows.add(r);
        }
        reminders.saveAll(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("students", list.size());
        out.put("emails", emails);
        out.put("sms", texts);
        out.put("total", list.stream().map(Target::owed).reduce(BigDecimal.ZERO, BigDecimal::add));
        return out;
    }

    // ── Settings and history ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> overview(User actor) {
        FinanceAccess.requireFinance(actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("autoEveryDays", autoDays());
        out.put("smsReady", smsReady());
        out.put("defaultMessage", DEFAULT_MESSAGE);
        out.put("studentWord", SystemMode.current().terms().get("student"));
        out.put("history", reminders.batches(PageRequest.of(0, 30)).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batch", r[0]);
            m.put("sentAt", r[1]);
            m.put("sentBy", r[2]);
            m.put("automatic", ((Number) r[3]).intValue() == 1);
            m.put("email", ((Number) r[4]).intValue() == 1);
            m.put("sms", ((Number) r[5]).intValue() == 1);
            m.put("students", r[6]);
            m.put("total", r[7]);
            return m;
        }).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> setAutomatic(User actor, AutoRequest req) {
        FinanceAccess.requireBursar(actor);
        Integer d = req == null ? null : req.everyDays();
        if (d == null || d < 0 || d > 90) throw new IllegalArgumentException("Choose every 1 to 90 days, or 0 for off.");
        settings.updateSetting(SystemSettingService.FEES_AUTO_REMINDER_DAYS, String.valueOf(d));
        return overview(actor);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int autoDays() {
        try { return Integer.parseInt(Objects.toString(settings.getSetting(SystemSettingService.FEES_AUTO_REMINDER_DAYS), "0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private boolean smsReady() {
        return smsConfig.getKey() != null && !smsConfig.getKey().isBlank() && smsConfig.getUrl() != null;
    }

    private static int skipDays(ReminderRequest req) {
        Integer d = req.skipDays();
        if (d == null) return 7;
        if (d < 0 || d > 90) throw new IllegalArgumentException("Choose 0 to 90 days between reminders.");
        return d;
    }

    private static String message(ReminderRequest req) {
        String m = req.message() == null || req.message().isBlank() ? DEFAULT_MESSAGE : req.message().trim();
        if (m.length() > MAX_MESSAGE) throw new IllegalArgumentException("Keep the message to " + MAX_MESSAGE + " characters.");
        return m;
    }

    private String render(String template, Target t) {
        User u = t.student();
        return template.replace("{name}", Objects.toString(u.getFirstname(), CurrentUserService.displayName(u)))
                .replace("{amount}", money(t.owed()))
                .replace("{studentId}", Objects.toString(u.getUsername(), ""))
                .replace("{school}", Objects.toString(institution.shortName(), ""))
                .trim();
    }

    private String money(BigDecimal v) {
        return scheduleService.currency() + " " + String.format(Locale.ROOT, "%,.2f", v);
    }

    private static boolean hasEmail(User u) { return u.getEmail() != null && u.getEmail().contains("@"); }
    private static boolean hasPhone(User u) { return u.getPhone() != null && u.getPhone().replaceAll("\\D", "").length() >= 9; }

    @PreDestroy
    void shutdown() { sender.shutdown(); }
}
