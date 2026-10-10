package com.exam.service.admin;

import com.exam.model.academic.SystemMode;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.academic.ThemeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Exports the system's configuration — institution details, mode, colours, every on/off switch and
 * the logo — as one JSON file, and loads such a file back (preview first, then apply). Used to restore
 * a setup or copy it to another deployment. People, courses and results are not part of it.
 */
@Service
public class ConfigTransferService {

    public static final String FORMAT = "exam-portal-config";
    public static final int FORMAT_VERSION = 1;

    /** Runtime state that belongs to one installation, never copied. */
    private static final List<String> STATE_PREFIXES = List.of("MAINTENANCE_", "SETUP_");
    private static final Set<String> STATE_KEYS = Set.of(ThemeService.VERSION, "GRADING_SCALE_SEEDED", "ACCOUNT_STATUS_ENFORCED");
    private static final Pattern SECRET = Pattern.compile("(?i).*(SECRET|PASSWORD|TOKEN|API_?KEY|PRIVATE).*");
    private static final Pattern KEY = Pattern.compile("^[A-Z0-9_]{1,100}$");

    @Autowired private SystemSettingService settings;
    @Autowired private InstitutionService institution;

    public static boolean transferable(String key) {
        return key != null && KEY.matcher(key).matches() && !STATE_KEYS.contains(key)
                && STATE_PREFIXES.stream().noneMatch(key::startsWith) && !SECRET.matcher(key).matches();
    }

    public Map<String, Object> export(boolean includeLogo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("formatVersion", FORMAT_VERSION);
        m.put("exportedAt", LocalDateTime.now().withNano(0).toString());
        m.put("institution", Map.of("name", institution.name(), "shortName", institution.shortName(), "mode", institution.mode().name()));
        Map<String, String> s = new TreeMap<>();
        settings.getAllSettings().forEach((k, v) -> { if (transferable(k) && v != null) s.put(k, v); });
        m.put("settings", s);
        if (includeLogo) {
            institution.logo().ifPresent(l -> m.put("logo", Map.of("contentType", l.getContentType(),
                    "data", Base64.getEncoder().encodeToString(l.getData()))));
        }
        return m;
    }

    /** Compares a file with the current setup; writes it only when {@code apply} is true. */
    @Transactional
    public Map<String, Object> importConfig(Map<String, Object> file, boolean apply) {
        if (file == null || !FORMAT.equals(file.get("format")))
            throw new IllegalArgumentException("This is not an exam portal settings file.");
        Object ver = file.get("formatVersion");
        if (!(ver instanceof Number n) || n.intValue() > FORMAT_VERSION)
            throw new IllegalArgumentException("This settings file was made by a newer version of the portal.");
        if (!(file.get("settings") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("The file has no settings.");

        Map<String, String> current = settings.getAllSettings();
        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int unchanged = 0;
        boolean themeChanged = false;
        Map<String, String> toWrite = new LinkedHashMap<>();

        for (Map.Entry<?, ?> e : new TreeMap<>(raw).entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = e.getValue() == null ? null : String.valueOf(e.getValue());
            if (!transferable(key) || value == null || value.length() > 2000 || !valid(key, value)) { skipped.add(key); continue; }
            String before = current.get(key);
            if (value.equals(before)) { unchanged++; continue; }
            changes.add(change(key, before, value));
            toWrite.put(key, value);
            if (key.startsWith("THEME_")) themeChanged = true;
        }

        byte[] logo = null;
        String logoType = null;
        if (file.get("logo") instanceof Map<?, ?> l) {
            try {
                logo = Base64.getDecoder().decode(String.valueOf(l.get("data")));
                logoType = String.valueOf(l.get("contentType"));
            } catch (IllegalArgumentException e) {
                skipped.add("logo");
            }
        }

        if (apply) {
            toWrite.forEach(settings::updateSetting);
            if (themeChanged) settings.updateSetting(ThemeService.VERSION, String.valueOf(System.currentTimeMillis()));
            if (logo != null) institution.saveLogo(logo, logoType);   // checks size and type; rolls everything back if refused
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applied", apply);
        m.put("changes", changes);
        m.put("unchanged", unchanged);
        m.put("skipped", skipped);
        m.put("logo", logo != null);
        m.put("source", file.get("institution"));
        m.put("exportedAt", file.get("exportedAt"));
        return m;
    }

    /** Values the rest of the system would choke on are left out rather than written. */
    private static boolean valid(String key, String value) {
        if (key.equals(InstitutionService.MODE)) return SystemMode.parse(value) != null;
        if (key.equals(ThemeService.ACCENT)) return value.isEmpty() || ThemeService.isHex(value);
        if (key.equals(ThemeService.BRAND) || key.equals(ThemeService.SIDEBAR)) return ThemeService.isHex(value);
        return true;
    }

    private static Map<String, Object> change(String key, String from, String to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("from", from);
        m.put("to", to);
        return m;
    }
}
