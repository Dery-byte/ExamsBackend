package com.exam.service.subscription;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.comms.AuditService;
import com.exam.service.monitoring.DeveloperAuthService;
import com.exam.service.monitoring.ErrorMonitorService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Checks the subscription every hour: records each change of state (in the subscription history and
 * the audit log) and emails the institution — Super Admins, Admins too if the developer chose so, and
 * the billing contacts — with the developers copied in. Reminders go out once per milestone.
 */
@Component
public class SubscriptionMonitor {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionMonitor.class);

    static final String LAST_STATE = "SUBSCRIPTION_LAST_STATE";
    static final String REMINDERS = "SUBSCRIPTION_REMINDERS_SENT";

    /** Days before the next stage on which a reminder is sent. */
    private static final Map<SubscriptionState, List<Integer>> MILESTONES = Map.of(
            SubscriptionState.ACTIVE, List.of(30, 14, 7, 3, 1),
            SubscriptionState.GRACE, List.of(7, 3, 1),
            SubscriptionState.RESTRICTED, List.of(3, 1));

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);

    @Autowired private SubscriptionService subscription;
    @Autowired private SystemSettingService settings;
    @Autowired private UserRepository users;
    @Autowired private InstitutionService institution;
    @Autowired private AuditService audit;
    @Autowired private DeveloperAuthService developers;
    @Autowired private ErrorMonitorService errorMonitor;
    @Autowired(required = false) private JavaMailSender mailSender;

    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;
    @Value("${app.mail.from-name:}") private String fromName;

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void scheduledCheck() {
        try {
            check();
        } catch (Exception e) {
            errorMonitor.recordBackground("Subscription check", e);
        }
    }

    /** Also called straight after the developer changes something, so emails go out at once. */
    public synchronized void check() {
        SubscriptionStatus s = subscription.refresh();
        String last = settings.getSetting(LAST_STATE);
        boolean emailed = false;

        if (last == null) {
            // First run on this deployment: remember the state, nothing has changed yet
            settings.updateSetting(LAST_STATE, s.state().name());
        } else if (!last.equals(s.state().name())) {
            SubscriptionState from = parse(last);
            settings.updateSetting(LAST_STATE, s.state().name());
            String what = "Subscription is now " + label(s.state()).toLowerCase(Locale.ROOT);
            subscription.record("STATE_CHANGED", from, s.state(), describeChange(s), null);
            audit.record(null, what, "SYSTEM", "subscription", null,
                    (from == null ? last : label(from)) + " → " + label(s.state()) + ". " + describeChange(s), null, null);
            emailed = notifyChange(from, s);
        }

        Long days = s.daysLeft();
        List<Integer> milestones = MILESTONES.getOrDefault(s.state(), List.of());
        if (days == null || milestones.isEmpty()) return;
        String period = s.state() + "|" + s.nextChange();
        String sentRaw = Objects.toString(settings.getSetting(REMINDERS), "");
        Set<String> sent = new HashSet<>();
        if (sentRaw.startsWith(period + ":")) sent.addAll(Arrays.asList(sentRaw.substring(period.length() + 1).split(",")));
        List<Integer> due = milestones.stream().filter(m -> m >= days && !sent.contains(String.valueOf(m))).toList();
        if (due.isEmpty()) return;
        due.forEach(m -> sent.add(String.valueOf(m)));
        sent.remove("");
        settings.updateSetting(REMINDERS, period + ":" + String.join(",", sent));
        if (!emailed) {
            sendReminder(s, days);
            subscription.record("REMINDER", null, s.state(), days + " day(s) before " + s.nextChange(), null);
        }
    }

    // ── Emails ───────────────────────────────────────────────────────────

    private boolean notifyChange(SubscriptionState from, SubscriptionStatus s) {
        String inst = institution.name();
        String contact = contactLine();
        String subject, body;
        switch (s.state()) {
            case ACTIVE -> {
                if (from == null) return false;
                subject = "Subscription renewed: full access restored";
                body = "<p>Thank you. The subscription for <b>" + esc(inst) + "</b> is active and paid until <b>"
                        + day(s.paidUntil()) + "</b>. Everyone can use the portal as normal again.</p>";
            }
            case GRACE -> {
                subject = s.source() == SubscriptionStatus.Source.KEY ? "Subscription payment overdue" : "Subscription key needed";
                body = "<p>The subscription for <b>" + esc(inst) + "</b> " + (s.source() == SubscriptionStatus.Source.KEY
                        ? "ended on <b>" + day(s.paidUntil()) + "</b>." : "has no valid key installed.") + "</p>"
                        + "<p>The portal keeps working normally until <b>" + day(s.graceEnd()) + "</b>. If it is not renewed by then, it becomes "
                        + "<b>read-only</b> from " + day(s.graceEnd().plusDays(1)) + ": staff and students can still sign in, view and export, "
                        + "but no new exams can start and nothing can be changed.</p>";
            }
            case RESTRICTED -> {
                subject = "Portal is now read-only: subscription overdue";
                body = "<p>The subscription for <b>" + esc(inst) + "</b> has not been renewed, so the portal is now <b>read-only</b>. "
                        + "Results, reports and records can still be viewed and exported, but no new exams can start and nothing can be changed. "
                        + "Exams already in progress can still be finished.</p>"
                        + "<p>If it is not renewed, the portal will be <b>suspended</b> from <b>" + day(s.restrictedEnd().plusDays(1))
                        + "</b>: only the Super Admin will be able to sign in.</p>";
            }
            default -> {
                subject = "Portal suspended";
                body = "<p>Access to the portal for <b>" + esc(inst) + "</b> has been <b>suspended</b>"
                        + (s.manuallySuspended() || s.suspendedByProvider() ? " by the service provider." : " because the subscription was not renewed.") + "</p>"
                        + "<p>Only the Super Admin can sign in, to view and export the institution's data. "
                        + "Full access returns as soon as the subscription is renewed.</p>";
            }
        }
        send(subject, body + contact, s);
        return true;
    }

    private void sendReminder(SubscriptionStatus s, long days) {
        String inst = institution.name();
        String in = days <= 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
        String subject, body;
        switch (s.state()) {
            case ACTIVE -> {
                subject = "Subscription ends " + (days <= 1 ? "today" : "in " + days + " days");
                body = "<p>The subscription for <b>" + esc(inst) + "</b> is paid until <b>" + day(s.paidUntil())
                        + "</b>. Please arrange renewal before then to avoid any interruption.</p>";
            }
            case GRACE -> {
                subject = "Portal becomes read-only " + in;
                body = "<p>The subscription for <b>" + esc(inst) + "</b> is overdue. Unless it is renewed, the portal becomes <b>read-only</b> on <b>"
                        + day(s.nextChange()) + "</b>: no new exams can start and nothing can be changed.</p>";
            }
            default -> {
                subject = "Portal will be suspended " + in;
                body = "<p>The portal for <b>" + esc(inst) + "</b> is read-only because the subscription is overdue. Unless it is renewed, it will be "
                        + "<b>suspended</b> on <b>" + day(s.nextChange()) + "</b> and only the Super Admin will be able to sign in.</p>";
            }
        }
        send(subject, body + contactLine(), s);
    }

    private void send(String subject, String html, SubscriptionStatus s) {
        if (mailSender == null) return;
        // A missing key is the vendor's to fix; the institution is only told in the last week before limits start
        boolean toInstitution = s.source() == SubscriptionStatus.Source.KEY || s.limited()
                || (s.daysLeft() != null && s.daysLeft() <= 7);
        Set<String> to = new LinkedHashSet<>();
        if (toInstitution) {
            List<Role> roles = subscription.audience() == SubscriptionService.Audience.ADMINS
                    ? List.of(Role.SUPER_ADMIN, Role.ADMIN) : List.of(Role.SUPER_ADMIN);
            for (Role r : roles)
                for (User u : users.findByRole(r))
                    if (u.isEnabled() && u.getEmail() != null && !u.getEmail().isBlank()) to.add(u.getEmail().trim());
            to.addAll(subscription.billingEmails());
        }
        Set<String> devs = new LinkedHashSet<>(developers.developerEmails());
        devs.removeAll(to);
        if (to.isEmpty() && devs.isEmpty()) return;

        String fullSubject = "[" + Objects.toString(institution.shortName(), institution.name()) + "] " + subject;
        String page = "<div style=\"font-family:Arial,sans-serif;font-size:14px;line-height:1.55;color:#1f2937\">" + html
                + "<p style=\"color:#6b7280;font-size:12px\">This is an automatic message about the portal's subscription.</p></div>";
        CompletableFuture.runAsync(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setFrom(fromAddress, fromName == null || fromName.isBlank() ? "Exam Portal" : fromName);
                if (to.isEmpty()) helper.setTo(devs.toArray(new String[0]));
                else {
                    helper.setTo(to.toArray(new String[0]));
                    if (!devs.isEmpty()) helper.setBcc(devs.toArray(new String[0]));
                }
                helper.setSubject(fullSubject);
                helper.setText(page, true);
                mailSender.send(message);
            } catch (Exception ex) {
                log.warn("[SUBSCRIPTION] Could not send '{}': {}", subject, ex.getMessage());
            }
        });
    }

    private String contactLine() {
        String c = subscription.contact();
        Map<String, Object> inv = subscription.invoice();
        String pay = inv == null ? "" : "<p><b>Pay online:</b> invoice " + esc(String.valueOf(inv.get("number"))) + ", "
                + esc(inv.get("currency") + " " + inv.get("amount")) + ", due " + esc(String.valueOf(inv.get("dueDate")))
                + ". <a href=\"" + esc(String.valueOf(inv.get("payUrl"))) + "\">Pay with Paystack</a> (card, Mobile Money or bank transfer).</p>";
        return pay + (c == null ? "" : "<p><b>To renew or ask about this:</b> " + esc(c) + "</p>");
    }

    private static String describeChange(SubscriptionStatus s) {
        if (s.manuallySuspended()) return "Suspended by the developer: " + s.suspendedReason();
        if (s.suspendedByProvider()) return "Suspended by the vendor portal" + (s.key().note() == null ? "." : ": " + s.key().note());
        String src = switch (s.source()) {
            case KEY -> "Paid until " + s.paidUntil();
            case NO_KEY -> "No subscription key installed";
            case INVALID_KEY, WRONG_INSTITUTION -> "Installed key not accepted: " + s.keyProblem();
        };
        return s.nextChange() == null ? src + "." : src + "; next change on " + s.nextChange() + ".";
    }

    static String label(SubscriptionState s) {
        return switch (s) {
            case ACTIVE -> "Active";
            case GRACE -> "In grace period";
            case RESTRICTED -> "Read-only";
            case SUSPENDED -> "Suspended";
        };
    }

    private static SubscriptionState parse(String v) {
        try { return SubscriptionState.valueOf(v); } catch (Exception e) { return null; }
    }

    private static String day(LocalDate d) { return d == null ? "" : d.format(DAY); }

    private static String esc(String s) { return HtmlUtils.htmlEscape(Objects.toString(s, "")); }
}
