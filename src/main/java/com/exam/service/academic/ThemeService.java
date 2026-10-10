package com.exam.service.academic;

import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The institution's colour theme, chosen by the developer: brand, sidebar and an optional accent
 * (blank = worked out from the brand). The browser derives every other shade (utils/theme.ts);
 * {@link #palette()} does the same maths for the PDFs and emails, which can't use CSS variables.
 */
@Service
public class ThemeService {

    public static final String BRAND = "THEME_BRAND";
    public static final String SIDEBAR = "THEME_SIDEBAR";
    /** "" = auto (derived from the brand); no row = the default accent. */
    public static final String ACCENT = "THEME_ACCENT";
    public static final String PRESET = "THEME_PRESET";
    /** Set on every save; also tells the setup checklist the theme has been chosen. */
    public static final String VERSION = "THEME_VERSION";
    public static final List<String> KEYS = List.of(BRAND, SIDEBAR, ACCENT, PRESET, VERSION);

    public static final String DEFAULT_BRAND = "#5156be";
    public static final String DEFAULT_SIDEBAR = "#2a3142";
    public static final String DEFAULT_ACCENT = "#7c3aed";

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PRESET_NAME = Pattern.compile("^[a-z0-9-]{1,40}$");

    @Autowired private SystemSettingService settings;

    public Map<String, Object> theme() {
        Map<String, String> s = settings.getSettings(KEYS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brand", hexOr(s.get(BRAND), DEFAULT_BRAND));
        m.put("sidebar", hexOr(s.get(SIDEBAR), DEFAULT_SIDEBAR));
        String accent = s.get(ACCENT);
        m.put("accent", accent == null ? DEFAULT_ACCENT : isHex(accent) ? accent.toLowerCase(Locale.ROOT) : null);
        m.put("preset", s.getOrDefault(PRESET, s.containsKey(VERSION) ? null : "default"));
        m.put("version", parseLong(s.get(VERSION)));
        m.put("customised", s.containsKey(VERSION));
        return m;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        String brand = requireHex(body.get("brand"), "brand");
        String sidebar = requireHex(body.get("sidebar"), "sidebar");
        Object accent = body.get("accent");
        String accentValue = accent == null || accent.toString().isBlank() ? "" : requireHex(accent, "accent");
        String preset = Objects.toString(body.get("preset"), "").trim();
        if (!preset.isEmpty() && !PRESET_NAME.matcher(preset).matches())
            throw new IllegalArgumentException("Unknown preset name.");

        settings.updateSetting(BRAND, brand);
        settings.updateSetting(SIDEBAR, sidebar);
        settings.updateSetting(ACCENT, accentValue);
        if (preset.isEmpty()) settings.deleteSetting(PRESET); else settings.updateSetting(PRESET, preset);
        settings.updateSetting(VERSION, String.valueOf(System.currentTimeMillis()));
        return theme();
    }

    /** Back to the built-in colours. */
    public Map<String, Object> reset() {
        KEYS.forEach(settings::deleteSetting);
        return theme();
    }

    /** The fixed print colours the PDF and email templates are written in, and what each becomes. */
    private static final Map<String, String> DOCUMENT_COLOURS = Map.ofEntries(
            Map.entry("#1a2744", "deep"), Map.entry("#182848", "deep"),
            Map.entry("#4a4a9c", "text"), Map.entry("#4b6cb7", "text"),
            Map.entry("#c5c9e8", "border"), Map.entry("#d0d4ee", "border"), Map.entry("#d7dbef", "border"),
            Map.entry("#edf0fb", "soft"), Map.entry("#e8ecf8", "soft"), Map.entry("#f6f7fd", "soft"),
            Map.entry("#f7f8fd", "soft"), Map.entry("#f0f7ff", "soft"));
    private static final Pattern DOCUMENT_COLOUR = Pattern.compile("(?i)#(1a2744|182848|4a4a9c|4b6cb7|c5c9e8|d0d4ee|d7dbef|edf0fb|e8ecf8|f6f7fd|f7f8fd|f0f7ff)\\b");

    /**
     * Rewrites a rendered PDF or email (the renderers can't use CSS variables) in the institution's
     * colours. Headers and text get the brand shade that reads on white, and white reads on it, so
     * a pale brand never leaves a heading unreadable. Untouched until the developer picks a theme.
     */
    public String recolor(String html) {
        Map<String, Object> t = theme();
        if (html == null || !Boolean.TRUE.equals(t.get("customised"))) return html;
        String brand = (String) t.get("brand");
        Map<String, String> to = Map.of(
                "deep", mix(brand, "#000000", 0.55),
                "text", ensureContrast(brand, WHITE, "#000000"),
                "border", mix(brand, WHITE, 0.75),
                "soft", mix(brand, WHITE, 0.92));
        return DOCUMENT_COLOUR.matcher(html).replaceAll(m -> to.get(DOCUMENT_COLOURS.get(m.group().toLowerCase(Locale.ROOT))));
    }

    // ── Colour maths, kept in step with examfront_React/src/utils/theme.ts ──

    private static final String WHITE = "#ffffff";
    private static final String INK = "#111827";
    private static final double AA = 4.5;

    public static boolean isHex(String v) { return v != null && HEX.matcher(v).matches(); }

    static int[] rgb(String hex) {
        return new int[]{ Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16) };
    }

    static String mix(String a, String b, double t) {
        int[] x = rgb(a), y = rgb(b);
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < 3; i++) {
            long v = Math.round(x[i] + (y[i] - x[i]) * t);
            sb.append(String.format("%02x", Math.max(0, Math.min(255, v))));
        }
        return sb.toString();
    }

    static double luminance(String hex) {
        int[] c = rgb(hex);
        double[] l = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            l[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    }

    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static String readableOn(String bg) {
        double w = contrast(WHITE, bg);
        return w >= AA || w >= contrast(INK, bg) ? WHITE : INK;
    }

    static String ensureContrast(String c, String bg, String target) {
        for (int i = 0; i <= 20; i++) {
            String v = mix(c, target, i * 0.05);
            if (contrast(v, bg) >= AA) return v;
        }
        return target;
    }

    private static String hexOr(String v, String def) { return isHex(v) ? v.toLowerCase(Locale.ROOT) : def; }

    private static String requireHex(Object v, String field) {
        String s = v == null ? "" : v.toString().trim();
        if (!isHex(s)) throw new IllegalArgumentException("The " + field + " colour must look like #1a2b3c.");
        return s.toLowerCase(Locale.ROOT);
    }

    private static long parseLong(String v) {
        try { return v == null ? 0L : Long.parseLong(v); } catch (NumberFormatException e) { return 0L; }
    }
}
