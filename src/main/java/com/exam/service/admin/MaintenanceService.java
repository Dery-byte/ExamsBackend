package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Maintenance mode, switched by the developer before an upgrade. While it is on nobody new can sign
 * in (developers always can; Super Admins too if allowed) and no new quiz attempt can start —
 * students already in an exam keep going and can submit. Everyone signed in sees a banner.
 */
@Service
public class MaintenanceService {

    public static final String ON = "MAINTENANCE_ON";
    public static final String MESSAGE = "MAINTENANCE_MESSAGE";
    /** Optional "expected back by" time, shown to users; it does not switch maintenance off by itself. */
    public static final String UNTIL = "MAINTENANCE_UNTIL";
    public static final String ALLOW_ADMINS = "MAINTENANCE_ALLOW_ADMINS";
    public static final String SINCE = "MAINTENANCE_SINCE";
    private static final List<String> KEYS = List.of(ON, MESSAGE, UNTIL, ALLOW_ADMINS, SINCE);

    public static final String DEFAULT_MESSAGE = "The portal is being updated. Please try again shortly.";

    @Autowired private SystemSettingService settings;

    public boolean isOn() { return settings.getBooleanSetting(ON, false); }

    /** Full state for the developer console. */
    public Map<String, Object> status() {
        Map<String, String> s = settings.getSettings(KEYS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", Boolean.parseBoolean(s.get(ON)));
        m.put("message", message(s));
        m.put("until", blankToNull(s.get(UNTIL)));
        m.put("allowAdmins", Boolean.parseBoolean(s.getOrDefault(ALLOW_ADMINS, "true")));
        m.put("since", blankToNull(s.get(SINCE)));
        return m;
    }

    /** What every visitor may know (sent with the public institution details). */
    public Map<String, Object> publicStatus() {
        Map<String, Object> full = status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", full.get("enabled"));
        if (Boolean.TRUE.equals(full.get("enabled"))) {
            m.put("message", full.get("message"));
            m.put("until", full.get("until"));
            m.put("since", full.get("since"));
            // Lets the sign-in page offer the Super Admin a way in; developers use their own sign-in
            m.put("adminSignIn", full.get("allowAdmins"));
        }
        return m;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        boolean wasOn = isOn();
        if (body.containsKey("message")) {
            String msg = Objects.toString(body.get("message"), "").trim();
            if (msg.length() > 300) throw new IllegalArgumentException("Keep the message under 300 characters.");
            settings.updateSetting(MESSAGE, msg);
        }
        if (body.containsKey("until")) {
            String until = Objects.toString(body.get("until"), "").trim();
            if (!until.isEmpty()) {
                try { LocalDateTime.parse(until); }
                catch (DateTimeParseException e) { throw new IllegalArgumentException("Give the end time as a date and time."); }
            }
            settings.updateSetting(UNTIL, until);
        }
        if (body.containsKey("allowAdmins") && body.get("allowAdmins") != null)
            settings.updateSetting(ALLOW_ADMINS, String.valueOf(Boolean.TRUE.equals(body.get("allowAdmins"))));
        if (body.containsKey("enabled") && body.get("enabled") != null) {
            boolean on = Boolean.TRUE.equals(body.get("enabled"));
            settings.updateSetting(ON, String.valueOf(on));
            if (on && !wasOn) settings.updateSetting(SINCE, LocalDateTime.now().withNano(0).toString());
            if (!on) settings.deleteSetting(SINCE);
        }
        return status();
    }

    /** Whether someone with this role may sign in right now. */
    public boolean allowsSignIn(Role role) {
        if (!isOn() || role == Role.DEVELOPER) return true;
        return role == Role.SUPER_ADMIN && settings.getBooleanSetting(ALLOW_ADMINS, true);
    }

    public String message() { return message(settings.getSettings(List.of(MESSAGE))); }

    /** Call before starting a brand-new quiz attempt (resuming one is always allowed). */
    public void assertNewAttemptsAllowed() {
        if (isOn()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "New attempts can't start while the portal is under maintenance. " + message());
    }

    private static String message(Map<String, String> s) {
        String m = s.get(MESSAGE);
        return m == null || m.isBlank() ? DEFAULT_MESSAGE : m;
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }
}
