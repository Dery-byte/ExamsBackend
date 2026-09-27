package com.exam.service.academic;

import com.exam.model.academic.DegreeClass;
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

    @Autowired private GradeBandRepository bandRepository;
    @Autowired private DegreeClassRepository classRepository;
    @Autowired private StudentCourseMarkRepository studentCourseMarkRepository;
    @Autowired private SystemSettingService systemSettingService;

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
                    band("E", 40, 1.0, "Pass", true), band("F", 0, 0.0, "Fail", false)));

    public static final Map<String, List<ClassRow>> CLASS_PRESETS = Map.of(
            "LEGACY", fourPointClasses(),
            "FOUR_POINT", fourPointClasses(),
            "FIVE_POINT", List.of(
                    cls("First Class", 4.5), cls("Second Class (Upper Division)", 3.5),
                    cls("Second Class (Lower Division)", 2.4), cls("Third Class", 1.5), cls("Pass", 1.0)));

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
        if (bandRepository.count() == 0) saveBands(BAND_PRESETS.get("LEGACY"));
        if (classRepository.count() == 0) saveClasses(CLASS_PRESETS.get("LEGACY"));
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
            if (c.isEmpty()) c = CLASS_PRESETS.get("LEGACY").stream().map(GradingService::toClass).toList();
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
        m.put("presets", List.of(
                Map.of("key", "LEGACY", "label", "Current bands (A+ from 90 … F below 50), 4.0 points"),
                Map.of("key", "FOUR_POINT", "label", "4.0 scale (A 80+, B+ 75, B 70 … E below 50)"),
                Map.of("key", "FIVE_POINT", "label", "5.0 scale (A 70+, B 60, C 50, D 45, E 40, F below 40)")));
        return m;
    }

    public Map<String, Object> preset(String key) {
        if (!BAND_PRESETS.containsKey(key)) throw new IllegalArgumentException("Unknown preset.");
        return Map.of("bands", BAND_PRESETS.get(key), "classes", CLASS_PRESETS.get(key));
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
