package com.exam.service.academic;

import com.exam.model.academic.DegreeClass;
import com.exam.model.academic.GradingPreset;
import com.exam.model.academic.GradeBand;
import com.exam.model.exam.StudentCourseMark;
import com.exam.repository.DegreeClassRepository;
import com.exam.repository.GradeBandRepository;
import com.exam.repository.StudentCourseMarkRepository;
import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * The grading scale (score → letter → grade point), classes of degree, default credit units
 * and promotion rules. All are set by the Super Admin.
 */
@Service
public class GradingService {

    public static final String DEFAULT_CREDIT_UNITS   = "DEFAULT_CREDIT_UNITS";
    /** Max outstanding carry-overs a student may have and still be promoted; -1 = no limit. */
    public static final String PROMOTION_MAX_CARRYOVERS = "PROMOTION_MAX_CARRYOVERS";
    /** Minimum CGPA for promotion; 0 = no minimum. */
    public static final String PROMOTION_MIN_CGPA       = "PROMOTION_MIN_CGPA";
    private static final String SEEDED_KEY = "GRADING_SCALE_SEEDED";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired private GradeBandRepository bandRepository;
    @Autowired private DegreeClassRepository classRepository;
    @Autowired private StudentCourseMarkRepository studentCourseMarkRepository;
    @Autowired private SystemSettingService systemSettingService;
    @Autowired private com.exam.repository.GradingPresetRepository presetRepository;

    /** A band / class row as sent by the settings page. */
    public record BandRow(String letter, BigDecimal minScore, BigDecimal gradePoint, String remark, Boolean passing) {}
    public record ClassRow(String name, BigDecimal minCgpa) {}

    // ── Presets ──────────────────────────────────────────────────────────────

    /** LEGACY reproduces the grades the system used before the scale was configurable. */
    public static final Map<String, List<BandRow>> BAND_PRESETS = Map.of(
            "LEGACY", List.of(
                    band("A+", 90, 4.0, "Distinction", true), band("A", 80, 4.0, "Excellent", true),
                    band("B", 70, 3.0, "Very Good", true), band("C", 60, 2.0, "Good", true),
                    band("D", 50, 1.0, "Pass", true), band("F", 0, 0.0, "Fail", false)),
            "FOUR_POINT", List.of(
                    band("A", 80, 4.0, "Excellent", true), band("B+", 75, 3.5, "Very Good", true),
                    band("B", 70, 3.0, "Good", true), band("C+", 65, 2.5, "Average", true),
                    band("C", 60, 2.0, "Fair", true), band("D+", 55, 1.5, "Barely Satisfactory", true),
                    band("D", 50, 1.0, "Weak Pass", true), band("E", 0, 0.0, "Fail", false)),
            "FIVE_POINT", List.of(
                    band("A", 70, 5.0, "Excellent", true), band("B", 60, 4.0, "Very Good", true),
                    band("C", 50, 3.0, "Good", true), band("D", 45, 2.0, "Fair", true),
                    band("E", 40, 1.0, "Pass", true), band("F", 0, 0.0, "Fail", false)),
            // Ghana, Primary (KG–B6): GES standards-based proficiency levels
            "GH_PRIMARY", List.of(
                    band("A", 80, 4.0, "Advanced", true), band("P", 68, 3.0, "Proficient", true),
                    band("AP", 54, 2.0, "Approaching Proficiency", true), band("D", 40, 1.0, "Developing", true),
                    band("B", 0, 0.0, "Beginning", false)),
            // Ghana, JHS: BECE-style grades 1–9 (1 is best). Points count up so GPA works: Grade 1 = 8 … Grade 9 = 0
            "GH_JHS", List.of(
                    band("1", 80, 8.0, "Highest", true), band("2", 70, 7.0, "Higher", true),
                    band("3", 60, 6.0, "High", true), band("4", 55, 5.0, "High Average", true),
                    band("5", 50, 4.0, "Average", true), band("6", 45, 3.0, "Low Average", true),
                    band("7", 40, 2.0, "Low", true), band("8", 35, 1.0, "Lower", true),
                    band("9", 0, 0.0, "Lowest", false)),
            // Ghana, SHS: WASSCE-style A1–F9. Points count up so GPA works: A1 = 8 … F9 = 0
            "GH_SHS", List.of(
                    band("A1", 75, 8.0, "Excellent", true), band("B2", 70, 7.0, "Very Good", true),
                    band("B3", 65, 6.0, "Good", true), band("C4", 60, 5.0, "Credit", true),
                    band("C5", 55, 4.0, "Credit", true), band("C6", 50, 3.0, "Credit", true),
                    band("D7", 45, 2.0, "Pass", true), band("E8", 40, 1.0, "Pass", true),
                    band("F9", 0, 0.0, "Fail", false)));

    public static final Map<String, List<ClassRow>> CLASS_PRESETS = Map.of(
            "GH_PRIMARY", List.of(),     // schools have no classes of degree
            "GH_JHS", List.of(),
            "GH_SHS", List.of(),
            "LEGACY", fourPointClasses(),
            "FOUR_POINT", fourPointClasses(),
            "FIVE_POINT", List.of(
                    cls("First Class", 4.5), cls("Second Class (Upper Division)", 3.5),
                    cls("Second Class (Lower Division)", 2.4), cls("Third Class", 1.5), cls("Pass", 1.0)));

    /** Built-in presets in the order the settings page lists them. */
    public record PresetInfo(String key, String label, String description) {}

    public static final List<PresetInfo> BUILT_IN_PRESETS = List.of(
            new PresetInfo("LEGACY", "Current bands (A+ from 90 … F below 50), 4.0 points",
                    "The grades the system used before the scale was configurable."),
            new PresetInfo("FOUR_POINT", "University — 4.0 scale (A 80+, B+ 75, B 70 … E below 50)",
                    "Common Ghanaian university scale with First Class from 3.60."),
            new PresetInfo("FIVE_POINT", "University — 5.0 scale (A 70+, B 60, C 50, D 45, E 40, F below 40)",
                    "5-point scale with First Class from 4.50."),
            new PresetInfo("GH_PRIMARY", "Ghana Primary (KG–B6) — A, P, AP, D, B",
                    "GES standards-based levels: Advanced 80+, Proficient 68–79, Approaching Proficiency 54–67, Developing 40–53, Beginning below 40. No classes."),
            new PresetInfo("GH_JHS", "Ghana JHS — BECE-style grades 1–9",
                    "Grade 1 (80+) to Grade 9 (below 35), using common school cut-offs. Grade 1 = 8 points … Grade 9 = 0 so GPA counts up. No classes."),
            new PresetInfo("GH_SHS", "Ghana SHS — WASSCE-style A1–F9",
                    "A1 75+, B2 70, B3 65, C4 60, C5 55, C6 50, D7 45, E8 40, F9 below 40. A1 = 8 points … F9 = 0 so GPA counts up. No classes."));

    private static List<ClassRow> fourPointClasses() {
        return List.of(cls("First Class", 3.6), cls("Second Class (Upper Division)", 3.0),
                cls("Second Class (Lower Division)", 2.5), cls("Third Class", 2.0), cls("Pass", 1.0));
    }

    private static BandRow band(String l, double min, double gp, String remark, boolean pass) {
        return new BandRow(l, BigDecimal.valueOf(min), BigDecimal.valueOf(gp), remark, pass);
    }

    private static ClassRow cls(String name, double min) { return new ClassRow(name, BigDecimal.valueOf(min)); }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** Cached scale; cleared whenever it is saved. */
    private volatile List<GradeBand> cachedBands;
    private volatile List<DegreeClass> cachedClasses;

    /** Seeds the legacy scale and classes on first start (called at application start-up). */
    @Transactional
    public void seedDefaults() {
        // Seed once. Afterwards an empty class list is a deliberate choice (e.g. a school preset), not "unset".
        if ("true".equals(systemSettingService.getSetting(SEEDED_KEY))) return;
        if (bandRepository.count() == 0) saveBands(BAND_PRESETS.get("LEGACY"));
        if (classRepository.count() == 0) saveClasses(CLASS_PRESETS.get("LEGACY"));
        systemSettingService.updateSetting(SEEDED_KEY, "true");
    }

    /** Bands, highest first. Falls back to the legacy scale if none are stored. */
    public List<GradeBand> bands() {
        List<GradeBand> b = cachedBands;
        if (b == null) {
            b = bandRepository.findAllByOrderByMinScoreDesc();
            if (b.isEmpty()) b = BAND_PRESETS.get("LEGACY").stream().map(GradingService::toBand).toList();
            cachedBands = b;
        }
        return b;
    }

    public List<DegreeClass> classes() {
        List<DegreeClass> c = cachedClasses;
        if (c == null) {
            c = classRepository.findAllByOrderByMinCgpaDesc();
            cachedClasses = c;
        }
        return c;
    }

    /** The band a total score falls into (null score → lowest band). */
    public GradeBand gradeFor(BigDecimal score) {
        List<GradeBand> bands = bands();
        BigDecimal s = score == null ? BigDecimal.ZERO : score;
        for (GradeBand b : bands) if (s.compareTo(b.getMinScore()) >= 0) return b;
        return bands.get(bands.size() - 1);
    }

    public Optional<GradeBand> bandForLetter(String letter) {
        if (letter == null) return Optional.empty();
        return bands().stream().filter(b -> b.getLetter().equalsIgnoreCase(letter.trim())).findFirst();
    }

    /** Degree class for a CGPA, or null below every class. */
    public String classFor(BigDecimal cgpa) {
        if (cgpa == null) return null;
        for (DegreeClass c : classes()) if (cgpa.compareTo(c.getMinCgpa()) >= 0) return c.getName();
        return null;
    }

    public BigDecimal maxGradePoint() {
        return bands().stream().map(GradeBand::getGradePoint).max(Comparator.naturalOrder()).orElse(BigDecimal.valueOf(4));
    }

    public int defaultCreditUnits() {
        try { return Math.max(0, Integer.parseInt(Objects.toString(systemSettingService.getSetting(DEFAULT_CREDIT_UNITS), "3"))); }
        catch (NumberFormatException e) { return 3; }
    }

    public int maxCarryoversForPromotion() {
        try { return Integer.parseInt(Objects.toString(systemSettingService.getSetting(PROMOTION_MAX_CARRYOVERS), "-1")); }
        catch (NumberFormatException e) { return -1; }
    }

    public BigDecimal minCgpaForPromotion() {
        try { return new BigDecimal(Objects.toString(systemSettingService.getSetting(PROMOTION_MIN_CGPA), "0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Everything the settings page shows. */
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bands", bands().stream().map(b -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("letter", b.getLetter());
            r.put("minScore", b.getMinScore());
            r.put("gradePoint", b.getGradePoint());
            r.put("remark", b.getRemark());
            r.put("passing", b.isPassing());
            return r;
        }).toList());
        m.put("classes", classes().stream().map(c -> Map.of("name", c.getName(), "minCgpa", c.getMinCgpa())).toList());
        m.put("defaultCreditUnits", defaultCreditUnits());
        m.put("maxCarryoversForPromotion", maxCarryoversForPromotion());
        m.put("minCgpaForPromotion", minCgpaForPromotion());
        m.put("presets", presets());
        return m;
    }

    /** Built-in presets first, then the Super Admin's saved ones (key "CUSTOM_<id>"). */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PresetInfo p : BUILT_IN_PRESETS)
            out.add(Map.of("key", p.key(), "label", p.label(), "description", p.description(), "builtIn", true));
        for (GradingPreset g : presetRepository.findAllByOrderByNameAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", "CUSTOM_" + g.getId());
            m.put("id", g.getId());
            m.put("label", g.getName());
            m.put("description", g.getDescription() == null ? "" : g.getDescription());
            m.put("builtIn", false);
            m.put("createdBy", g.getCreatedBy());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> preset(String key) {
        if (key != null && key.startsWith("CUSTOM_")) {
            GradingPreset g = presetRepository.findById(parseId(key.substring(7)))
                    .orElseThrow(() -> new IllegalArgumentException("Preset not found."));
            try {
                return Map.of(
                        "bands", JSON.readValue(g.getBandsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<BandRow>>() {}),
                        "classes", JSON.readValue(g.getClassesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<ClassRow>>() {}));
            } catch (Exception e) {
                throw new IllegalArgumentException("That preset could not be read.");
            }
        }
        if (!BAND_PRESETS.containsKey(key)) throw new IllegalArgumentException("Unknown preset.");
        return Map.of("bands", BAND_PRESETS.get(key), "classes", CLASS_PRESETS.get(key));
    }

    /** Saves a grading scale as a named preset the Super Admin can load later. */
    @Transactional
    public Map<String, Object> savePreset(String name, String description, List<BandRow> bands, List<ClassRow> classes, String createdBy) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 80) throw new IllegalArgumentException("Give the preset a name (up to 80 characters).");
        if (presetRepository.existsByNameIgnoreCase(clean)) throw new IllegalArgumentException("A preset called \"" + clean + "\" already exists.");
        boolean clashesWithBuiltIn = BUILT_IN_PRESETS.stream().anyMatch(p -> p.label().equalsIgnoreCase(clean));
        if (clashesWithBuiltIn) throw new IllegalArgumentException("That name is used by a built-in preset.");
        validateBands(bands == null ? List.of() : bands);
        validateClasses(classes == null ? List.of() : classes);
        GradingPreset g = new GradingPreset();
        g.setName(clean);
        g.setDescription(description == null || description.isBlank() ? null : description.trim());
        try {
            g.setBandsJson(JSON.writeValueAsString(bands));
            g.setClassesJson(JSON.writeValueAsString(classes == null ? List.of() : classes));
        } catch (Exception e) {
            throw new IllegalArgumentException("The scale could not be saved.");
        }
        g.setCreatedBy(createdBy);
        presetRepository.save(g);
        return settings();
    }

    @Transactional
    public Map<String, Object> deletePreset(Long id) {
        GradingPreset g = presetRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Preset not found."));
        presetRepository.delete(g);
        return settings();
    }

    private static Long parseId(String s) {
        try { return Long.valueOf(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("Preset not found."); }
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /** Replaces the scale, classes and rules. Grades already stored are not changed (see {@link #recalculate()}). */
    @Transactional
    public Map<String, Object> update(List<BandRow> bands, List<ClassRow> classes, Integer defaultCredits,
                                      Integer maxCarryovers, BigDecimal minCgpa) {
        if (bands != null) saveBands(validateBands(bands));
        if (classes != null) saveClasses(validateClasses(classes));
        if (defaultCredits != null) {
            if (defaultCredits < 0 || defaultCredits > 30) throw new IllegalArgumentException("Default credit units must be between 0 and 30.");
            systemSettingService.updateSetting(DEFAULT_CREDIT_UNITS, defaultCredits.toString());
        }
        if (maxCarryovers != null) {
            if (maxCarryovers < -1) throw new IllegalArgumentException("Carry-over limit must be -1 (no limit) or more.");
            systemSettingService.updateSetting(PROMOTION_MAX_CARRYOVERS, maxCarryovers.toString());
        }
        if (minCgpa != null) {
            if (minCgpa.signum() < 0 || minCgpa.compareTo(maxGradePoint()) > 0)
                throw new IllegalArgumentException("Minimum CGPA must be between 0 and " + maxGradePoint() + ".");
            systemSettingService.updateSetting(PROMOTION_MIN_CGPA, minCgpa.stripTrailingZeros().toPlainString());
        }
        return settings();
    }

    /**
     * Re-grades every graded mark on sheets that are not yet PUBLISHED using the current scale,
     * and fills in missing grade points on published marks (by letter). Published grades are
     * never re-lettered. Returns how many marks changed.
     */
    @Transactional
    public int recalculate() {
        int changed = 0;
        for (StudentCourseMark m : studentCourseMarkRepository.findAll()) {
            if (m.getGrade() == null || "N/A".equals(m.getGrade()) || m.getSemesterSheet() == null) continue;
            boolean published = "PUBLISHED".equals(m.getSemesterSheet().getStatus());
            if (published) {
                if (m.getGradePoint() == null) {
                    Optional<GradeBand> b = bandForLetter(m.getGrade());
                    if (b.isPresent()) { m.setGradePoint(b.get().getGradePoint()); studentCourseMarkRepository.save(m); changed++; }
                }
                continue;
            }
            GradeBand b = gradeFor(m.getTotalScore());
            if (!b.getLetter().equals(m.getGrade()) || m.getGradePoint() == null || m.getGradePoint().compareTo(b.getGradePoint()) != 0) {
                m.setGrade(b.getLetter());
                m.setGradePoint(b.getGradePoint());
                studentCourseMarkRepository.save(m);
                changed++;
            }
        }
        return changed;
    }

    /** Sets letter and grade point on a mark from its total, using the current scale. */
    public void applyGrade(StudentCourseMark m, BigDecimal total) {
        GradeBand b = gradeFor(total);
        m.setGrade(b.getLetter());
        m.setGradePoint(b.getGradePoint());
    }

    private List<BandRow> validateBands(List<BandRow> rows) {
        if (rows.size() < 2) throw new IllegalArgumentException("The scale needs at least two grades.");
        Set<String> letters = new HashSet<>();
        Set<BigDecimal> mins = new HashSet<>();
        boolean hasZero = false, hasFail = false;
        for (BandRow r : rows) {
            if (r.letter() == null || r.letter().isBlank() || r.letter().trim().length() > 5)
                throw new IllegalArgumentException("Every grade needs a letter of up to 5 characters.");
            if (!letters.add(r.letter().trim().toUpperCase())) throw new IllegalArgumentException("Grade " + r.letter() + " appears twice.");
            if (r.minScore() == null || r.minScore().signum() < 0 || r.minScore().compareTo(BigDecimal.valueOf(100)) > 0)
                throw new IllegalArgumentException("Minimum scores must be between 0 and 100.");
            if (!mins.add(r.minScore().stripTrailingZeros())) throw new IllegalArgumentException("Two grades start at the same score.");
            if (r.gradePoint() == null || r.gradePoint().signum() < 0 || r.gradePoint().compareTo(BigDecimal.TEN) > 0)
                throw new IllegalArgumentException("Grade points must be between 0 and 10.");
            if (r.minScore().signum() == 0) hasZero = true;
            if (Boolean.FALSE.equals(r.passing())) hasFail = true;
        }
        if (!hasZero) throw new IllegalArgumentException("One grade must start at 0 so every score gets a grade.");
        if (!hasFail) throw new IllegalArgumentException("Mark at least one grade as failing.");
        return rows;
    }

    private List<ClassRow> validateClasses(List<ClassRow> rows) {
        Set<String> names = new HashSet<>();
        for (ClassRow r : rows) {
            if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Every degree class needs a name.");
            if (!names.add(r.name().trim().toLowerCase())) throw new IllegalArgumentException(r.name() + " appears twice.");
            if (r.minCgpa() == null || r.minCgpa().signum() < 0) throw new IllegalArgumentException("Minimum CGPA can't be negative.");
        }
        return rows;
    }

    private void saveBands(List<BandRow> rows) {
        bandRepository.deleteAllInBatch();
        bandRepository.saveAll(rows.stream().map(GradingService::toBand).toList());
        cachedBands = null;
    }

    private void saveClasses(List<ClassRow> rows) {
        classRepository.deleteAllInBatch();
        classRepository.saveAll(rows.stream().map(GradingService::toClass).toList());
        cachedClasses = null;
    }

    private static GradeBand toBand(BandRow r) {
        GradeBand b = new GradeBand();
        b.setLetter(r.letter().trim().toUpperCase());
        b.setMinScore(r.minScore());
        b.setGradePoint(r.gradePoint());
        b.setRemark(r.remark() == null ? null : r.remark().trim());
        b.setPassing(!Boolean.FALSE.equals(r.passing()));
        return b;
    }

    private static DegreeClass toClass(ClassRow r) {
        DegreeClass c = new DegreeClass();
        c.setName(r.name().trim());
        c.setMinCgpa(r.minCgpa());
        return c;
    }
}
