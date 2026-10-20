package com.exam.service.subscription;

import com.exam.model.Role;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.monitoring.ErrorMonitorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Keeps this portal's key up to date from the vendor portal. Every few hours it checks in (state, key
 * in use, version, user counts) and installs whatever newer key the vendor portal has issued: a renewal
 * after payment, new terms, or a suspension. The key's signature is checked as for a pasted key, so a
 * fake vendor portal can't grant anything. When the vendor portal can't be reached nothing changes:
 * the portal keeps running on the key it has.
 */
@Component
public class SubscriptionSync {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSync.class);

    public static final String VENDOR_URL = "SUBSCRIPTION_VENDOR_URL";
    public static final String VENDOR_TOKEN = "SUBSCRIPTION_VENDOR_TOKEN";
    static final String LAST_ATTEMPT = "SUBSCRIPTION_SYNC_AT";
    static final String LAST_SUCCESS = "SUBSCRIPTION_SYNC_OK_AT";
    static final String LAST_RESULT = "SUBSCRIPTION_SYNC_RESULT";
    static final String LAST_OK = "SUBSCRIPTION_SYNC_OK";
    /** The open invoice the vendor portal last reported (JSON), shown with a Pay online button. */
    public static final String INVOICE = "SUBSCRIPTION_INVOICE";

    public static final String ACTOR = "Vendor portal (automatic)";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private SubscriptionService subscription;
    @Autowired private SubscriptionKeys keys;
    @Autowired private SubscriptionMonitor monitor;
    @Autowired private SystemSettingService settings;
    @Autowired private UserRepository users;
    @Autowired private ErrorMonitorService errorMonitor;

    /** Set on the server, these win over what the developer enters in the console. */
    @Value("${app.subscription.vendor-url:}") private String envUrl = "";
    @Value("${app.subscription.vendor-token:}") private String envToken = "";
    @Value("${app.frontend-url:}") private String portalUrl = "";

    private HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** For tests. */
    void setHttpClient(HttpClient http) { this.http = http; }

    @Scheduled(initialDelayString = "${app.subscription.sync-initial-delay-ms:120000}",
               fixedDelayString = "${app.subscription.sync-interval-ms:21600000}")
    public void scheduledSync() {
        try {
            if (configured()) sync();
        } catch (Exception e) {
            errorMonitor.recordBackground("Subscription check-in", e);
        }
    }

    public boolean configured() { return url() != null && token() != null; }

    /** Whether the last check-in reached the vendor portal. */
    public boolean lastOk() { return Boolean.parseBoolean(settings.getSetting(LAST_OK)); }

    public boolean fromEnvironment() { return notBlank(envUrl) || notBlank(envToken); }

    String url() {
        String u = notBlank(envUrl) ? envUrl : settings.getSetting(VENDOR_URL);
        return notBlank(u) ? u.trim().replaceAll("/+$", "") : null;
    }

    private String token() {
        String t = notBlank(envToken) ? envToken : settings.getSetting(VENDOR_TOKEN);
        return notBlank(t) ? t.trim() : null;
    }

    /** Checks in now. Returns a one-line result; never throws for network or vendor problems. */
    public synchronized String sync() {
        if (!configured()) return "Not linked to a vendor portal.";
        LocalDateTime now = LocalDateTime.now().withNano(0);
        settings.updateSetting(LAST_ATTEMPT, now.toString());
        String result;
        boolean ok = false;
        try {
            result = checkIn();
            ok = true;
            settings.updateSetting(LAST_SUCCESS, now.toString());
        } catch (SyncException e) {
            result = e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            result = "The vendor portal did not answer in time.";
        } catch (java.io.IOException e) {
            result = "Could not reach the vendor portal (" + Objects.toString(e.getMessage(), e.getClass().getSimpleName()) + ").";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "Interrupted.";
        } catch (RuntimeException e) {
            // e.g. the database refusing to save the key: report it here and in Errors, not as a failed request
            errorMonitor.recordBackground("Subscription check-in", e);
            result = "Checked in, but this portal could not save what it received. See Errors in the developer console.";
        }
        if (!ok) log.warn("[SUBSCRIPTION] Check-in failed: {}", result);
        settings.updateSetting(LAST_RESULT, cut(result, 300));
        settings.updateSetting(LAST_OK, String.valueOf(ok));
        return result;
    }

    private String checkIn() throws java.io.IOException, InterruptedException {
        String base = url();
        if (!secure(base)) throw new SyncException("The vendor portal address must start with https:// (http is only allowed for localhost).");

        SubscriptionStatus s = subscription.refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionId", s.key() != null ? s.key().institutionId() : null);
        body.put("installedKeyId", s.key() != null ? s.key().id() : null);
        body.put("installedSeq", s.key() != null ? s.key().seq() : 0);
        body.put("state", s.state().name());
        body.put("paidUntil", s.paidUntil().toString());
        body.put("keyProblem", s.keyProblem());
        body.put("appVersion", Objects.toString(getClass().getPackage().getImplementationVersion(), "dev"));
        body.put("portalUrl", portalUrl);
        body.put("students", users.countByRole(Role.NORMAL));
        body.put("staff", users.countByRole(Role.LECTURER) + users.countByRole(Role.ADMIN) + users.countByRole(Role.SUPER_ADMIN));

        // Generous: a vendor portal on a sleeping free host takes up to a minute to wake, and it re-checks
        // unfinished Paystack payments before answering
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/check-in"))
                .timeout(Duration.ofSeconds(75))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .header("User-Agent", "ExamPortal/" + body.get("appVersion"))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 || res.statusCode() == 403)
            throw new SyncException("The vendor portal refused this portal's token. Check it, or ask for a new one.");
        if (res.statusCode() == 429) throw new SyncException("The vendor portal asked to slow down; will try again later.");
        if (res.statusCode() / 100 != 2) throw new SyncException("The vendor portal answered HTTP " + res.statusCode() + ".");

        JsonNode n;
        try { n = JSON.readTree(res.body()); }
        catch (Exception e) { throw new SyncException("The vendor portal sent an answer this portal can't read."); }
        storeInvoice(n.path("invoice"));
        String raw = n.path("key").asText("");
        if (raw.isBlank()) return "Checked in; the vendor portal has no key for this portal yet.";

        SubscriptionKey offered;
        try { offered = keys.parse(raw); }
        catch (IllegalArgumentException e) { throw new SyncException("The vendor portal sent a key that was not accepted: " + e.getMessage()); }

        SubscriptionKey installed = s.key();
        if (installed != null && installed.id().equals(offered.id())) return "Checked in; the key is up to date.";
        if (installed != null && !offered.isNewerThan(installed))
            return "Checked in; kept the installed key (the vendor portal's is older).";
        try {
            subscription.installKey(raw, ACTOR);
        } catch (IllegalArgumentException e) {
            throw new SyncException("The vendor portal's key could not be installed: " + e.getMessage());
        }
        monitor.check();
        return offered.suspended() ? "Checked in; the vendor portal suspended this portal."
                : "Checked in; installed a new key (paid until " + offered.paidUntil() + ").";
    }

    /**
     * Keeps the vendor's open invoice, field by field and checked: its pay link is shown as a button, so
     * only an https address (or localhost) is accepted.
     */
    private void storeInvoice(JsonNode inv) {
        if (inv == null || !inv.isObject()) {
            settings.deleteSetting(INVOICE);
            return;
        }
        String number = inv.path("number").asText(""), amount = inv.path("amount").asText(""), currency = inv.path("currency").asText("");
        String due = inv.path("dueDate").asText(""), covers = inv.path("coversUntil").asText(""), payUrl = inv.path("payUrl").asText("");
        boolean ok = number.matches("[A-Za-z0-9_-]{1,30}") && amount.matches("\\d{1,10}(\\.\\d{1,2})?") && currency.matches("[A-Z]{3}")
                && isDate(due) && isDate(covers) && payUrl.length() <= 300 && secure(payUrl) && !payUrl.matches(".*[\\s\"'<>].*");
        if (!ok) {
            log.warn("[SUBSCRIPTION] Ignored an invoice from the vendor portal that didn't look right.");
            settings.deleteSetting(INVOICE);
            return;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("number", number);
        m.put("amount", amount);
        m.put("currency", currency);
        m.put("dueDate", due);
        m.put("coversUntil", covers);
        m.put("payUrl", payUrl);
        try { settings.updateSetting(INVOICE, JSON.writeValueAsString(m)); }
        catch (Exception e) { settings.deleteSetting(INVOICE); }
    }

    private static boolean isDate(String v) {
        try { java.time.LocalDate.parse(v); return true; } catch (Exception e) { return false; }
    }

    /** What the console shows about the link. */
    public Map<String, Object> describe() {
        Map<String, String> s = settings.getSettings(List.of(VENDOR_URL, VENDOR_TOKEN, LAST_ATTEMPT, LAST_SUCCESS, LAST_RESULT, LAST_OK));
        String token = token();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url());
        m.put("tokenSet", token != null);
        m.put("tokenHint", token == null ? null : "…" + token.substring(Math.max(0, token.length() - 4)));
        m.put("fromEnvironment", fromEnvironment());
        m.put("lastAttempt", s.get(LAST_ATTEMPT));
        m.put("lastSuccess", s.get(LAST_SUCCESS));
        m.put("lastResult", s.get(LAST_RESULT));
        m.put("lastOk", s.get(LAST_OK) == null ? null : Boolean.parseBoolean(s.get(LAST_OK)));
        return m;
    }

    /** Saves the link from the console (blank token keeps the current one) and checks in at once. */
    public Map<String, Object> link(String url, String token, String actor) {
        if (fromEnvironment()) throw new IllegalArgumentException("The vendor portal link is set on the server (SUBSCRIPTION_VENDOR_URL / SUBSCRIPTION_VENDOR_TOKEN).");
        String u = Objects.toString(url, "").trim().replaceAll("/+$", "");
        if (u.isEmpty()) throw new IllegalArgumentException("Enter the vendor portal's address.");
        if (u.length() > 300 || !u.matches("https?://[^\\s]+")) throw new IllegalArgumentException("Enter a web address such as https://vendor.example.com.");
        if (!secure(u)) throw new IllegalArgumentException("Use https:// (http is only allowed for localhost).");
        String t = Objects.toString(token, "").trim();
        if (t.isEmpty() && !notBlank(settings.getSetting(VENDOR_TOKEN))) throw new IllegalArgumentException("Enter this portal's token from the vendor portal.");
        if (t.length() > 200) throw new IllegalArgumentException("That token is too long.");
        settings.updateSetting(VENDOR_URL, u);
        if (!t.isEmpty()) settings.updateSetting(VENDOR_TOKEN, t);
        subscription.record("VENDOR_LINKED", null, null, "Linked to " + u, actor);
        sync();
        return describe();
    }

    public Map<String, Object> unlink(String actor) {
        if (fromEnvironment()) throw new IllegalArgumentException("The vendor portal link is set on the server and can't be removed here.");
        for (String k : List.of(VENDOR_URL, VENDOR_TOKEN, LAST_ATTEMPT, LAST_SUCCESS, LAST_RESULT, LAST_OK, INVOICE)) settings.deleteSetting(k);
        subscription.record("VENDOR_UNLINKED", null, null, "The installed key stays in force.", actor);
        return describe();
    }

    static boolean secure(String url) {
        if (url == null) return false;
        if (url.startsWith("https://")) return true;
        try {
            String host = URI.create(url).getHost();
            return url.startsWith("http://") && ("localhost".equals(host) || "127.0.0.1".equals(host));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    private static final class SyncException extends RuntimeException {
        SyncException(String message) { super(message); }
    }
}
