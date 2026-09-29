package com.exam.service.academic;

import com.exam.model.academic.InstitutionLogo;
import com.exam.model.academic.SystemMode;
import com.exam.repository.InstitutionLogoRepository;
import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.util.*;

/**
 * Who the portal belongs to and what kind of institution it is.
 * <p>
 * The system mode ({@link SystemMode}) is chosen by the developer. UNIVERSITY uses levels, semesters,
 * courses, GPA and classes of degree; the school modes (SHS, Basic, all three) use forms/classes,
 * three terms and subjects, print the position in class and the class teacher's and head's remarks.
 */
@Service
public class InstitutionService {

    public static final String NAME = "INSTITUTION_NAME";
    public static final String SHORT_NAME = "INSTITUTION_SHORT_NAME";
    public static final String SUBTITLE = "INSTITUTION_SUBTITLE";
    /** Legacy UNIVERSITY/SCHOOL switch, read once to seed {@link #MODE}. */
    public static final String TYPE = "INSTITUTION_TYPE";
    public static final String MODE = "SYSTEM_MODE";
    public static final String PORTAL_URL = "PORTAL_URL";
    public static final String SHOW_POSITION = "REPORT_SHOW_POSITION";

    public static final String UNIVERSITY = "UNIVERSITY";
    public static final String SCHOOL = "SCHOOL";

    private static final String DEFAULT_NAME = "University of Cape Coast";
    private static final String DEFAULT_SHORT = "UCC";

    @Autowired private SystemSettingService settings;
    @Autowired private InstitutionLogoRepository logoRepository;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String defaultPortalUrl;

    public String name() { return orDefault(NAME, DEFAULT_NAME); }
    public String shortName() { return orDefault(SHORT_NAME, DEFAULT_SHORT); }
    public String subtitle() { return orDefault(SUBTITLE, ""); }
    /** UNIVERSITY or SCHOOL (kept for older callers); see {@link #mode()} for the exact mode. */
    public String type() { return isSchool() ? SCHOOL : UNIVERSITY; }
    public boolean isSchool() { return mode().isSchool(); }

    public SystemMode mode() {
        SystemMode m = SystemMode.parse(settings.getSetting(MODE));
        if (m == null) m = SCHOOL.equals(settings.getSetting(TYPE)) ? SystemMode.ALL_SCHOOLS : SystemMode.UNIVERSITY;
        SystemMode.setCurrent(m);   // keep entities (Program levels / terms) in step
        return m;
    }

    @jakarta.annotation.PostConstruct
    void loadMode() {
        try { mode(); } catch (Exception e) { SystemMode.setCurrent(SystemMode.UNIVERSITY); }
    }

    /** Developer only. */
    public SystemMode setMode(SystemMode mode) {
        if (mode == null) throw new IllegalArgumentException("Choose a system mode.");
        settings.updateSetting(MODE, mode.name());
        SystemMode.setCurrent(mode);
        return mode;
    }

    /** Position in class on report cards: on by default for schools, off for universities. */
    public boolean showPosition() { return settings.getBooleanSetting(SHOW_POSITION, isSchool()); }

    public String portalUrl() {
        String url = orDefault(PORTAL_URL, defaultPortalUrl);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The words the UI and the printed documents use for this kind of institution. */
    public Map<String, String> terms() {
        return mode().terms();
    }

    public String semesterName(Object semester) {
        String n = Objects.toString(semester, "");
        if (isSchool()) return switch (n) { case "1" -> "First Term"; case "2" -> "Second Term"; case "3" -> "Third Term"; default -> "Term " + n; };
        return switch (n) { case "1" -> "First Semester"; case "2" -> "Second Semester"; default -> "Semester " + n; };
    }

    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("shortName", shortName());
        m.put("subtitle", subtitle());
        SystemMode mode = mode();
        m.put("type", type());
        m.put("mode", mode.name());
        m.put("modeLabel", mode.label());
        m.put("periodsPerLevel", mode.defaultPeriodsPerLevel());
        m.put("portalUrl", portalUrl());
        m.put("showPosition", showPosition());
        m.put("hasLogo", logoRepository.existsById(InstitutionLogo.ID));
        m.put("terms", terms());
        return m;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        text(body, "name", NAME, 120);
        text(body, "shortName", SHORT_NAME, 20);
        text(body, "subtitle", SUBTITLE, 200);
        if (body.containsKey("portalUrl")) {
            String url = Objects.toString(body.get("portalUrl"), "").trim();
            if (!url.isEmpty() && !url.matches("(?i)https?://[^\\s\"<>]+"))
                throw new IllegalArgumentException("The portal address must start with http:// or https://");
            settings.updateSetting(PORTAL_URL, url);
        }
        if (body.containsKey("showPosition") && body.get("showPosition") != null)
            settings.updateSetting(SHOW_POSITION, String.valueOf(Boolean.TRUE.equals(body.get("showPosition"))));
        return info();
    }

    /** PNG/JPEG logo as a data URL for the PDFs; the bundled UCC crest is used until one is uploaded. */
    public String logoDataUrl() {
        Optional<InstitutionLogo> logo = logoRepository.findById(InstitutionLogo.ID);
        if (logo.isPresent())
            return "data:" + logo.get().getContentType() + ";base64," + Base64.getEncoder().encodeToString(logo.get().getData());
        if (!name().equals(DEFAULT_NAME)) return "";   // another institution: don't print UCC's crest
        try {
            byte[] bytes = StreamUtils.copyToByteArray(new ClassPathResource("static/images/ucc-logo.png").getInputStream());
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    public Optional<InstitutionLogo> logo() { return logoRepository.findById(InstitutionLogo.ID); }

    public void saveLogo(byte[] data, String contentType) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Choose an image file.");
        if (data.length > 512 * 1024) throw new IllegalArgumentException("The logo must be smaller than 512 KB.");
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.equals("image/png") && !type.equals("image/jpeg"))
            throw new IllegalArgumentException("Upload a PNG or JPEG image.");
        InstitutionLogo logo = logoRepository.findById(InstitutionLogo.ID).orElseGet(InstitutionLogo::new);
        logo.setId(InstitutionLogo.ID);
        logo.setData(data);
        logo.setContentType(type);
        logo.setUpdatedAt(java.time.LocalDateTime.now());
        logoRepository.save(logo);
    }

    public void deleteLogo() {
        if (logoRepository.existsById(InstitutionLogo.ID)) logoRepository.deleteById(InstitutionLogo.ID);
    }

    private void text(Map<String, Object> body, String field, String key, int max) {
        if (!body.containsKey(field)) return;
        String v = Objects.toString(body.get(field), "").trim();
        if (v.length() > max) throw new IllegalArgumentException(field + " is too long (max " + max + " characters).");
        settings.updateSetting(key, v);
    }

    private String orDefault(String key, String def) {
        String v = settings.getSetting(key);
        return v == null || v.isBlank() ? def : v;
    }
}
