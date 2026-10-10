package com.exam.service.reports;

import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.GradingService;
import com.exam.service.reports.ReportQueries.MarkRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * CGPA, credits and carry-overs for every student at once, by the transcript's rules
 * ({@link AcademicRecordService}): approved and published results, every attempt counts towards
 * the CGPA, and a course is outstanding when its latest attempt was a fail.
 */
@Component
public class Standings {

    @Autowired private ReportQueries queries;
    @Autowired private AcademicRecordService records;
    @Autowired private GradingService gradingService;

    /** A result with its credits, grade point and pass/fail worked out. */
    public record Graded(MarkRow mark, int credits, BigDecimal gradePoint, boolean passed) {}

    public record Standing(BigDecimal cgpa, int results, int creditsAttempted, int creditsEarned, List<Graded> outstanding) {}

    /** Session start, then level, then semester, then sheet: the order results were earned in. */
    public static final Comparator<MarkRow> CHRONOLOGICAL = Comparator
            .comparing((MarkRow m) -> m.sessionStart() == null ? LocalDate.MIN : m.sessionStart())
            .thenComparingInt(m -> ReportSupport.levelNumber(m.level()))
            .thenComparingInt(m -> m.semester() == null ? 0 : m.semester())
            .thenComparing(MarkRow::sheetId);

    private static final Comparator<Graded> OLDEST_FIRST = Comparator.comparing(Graded::mark, CHRONOLOGICAL);

    public Graded graded(MarkRow m, int defaultCredits) {
        int credits = m.creditUnits() != null ? m.creditUnits() : defaultCredits;
        return new Graded(m, credits, records.gradePoint(m.grade(), m.gradePoint(), m.score()), records.passed(m.grade()));
    }

    public static boolean isGraded(MarkRow m) {
        return m.grade() != null && !m.grade().isBlank() && !"N/A".equalsIgnoreCase(m.grade());
    }

    /** Credit-weighted grade point average, 2 decimals; null with no credits. */
    public static BigDecimal gpa(Collection<Graded> results) {
        int credits = results.stream().mapToInt(Graded::credits).sum();
        if (credits == 0) return null;
        BigDecimal points = results.stream().map(g -> g.gradePoint().multiply(BigDecimal.valueOf(g.credits())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return points.divide(BigDecimal.valueOf(credits), 2, RoundingMode.HALF_UP);
    }

    /** Courses whose latest attempt was a fail. */
    public static List<Graded> outstanding(Collection<Graded> results) {
        List<Graded> sorted = new ArrayList<>(results);
        sorted.sort(OLDEST_FIRST);
        Map<Long, Graded> latest = new LinkedHashMap<>();
        for (Graded g : sorted) latest.put(g.mark().courseId(), g);
        return latest.values().stream().filter(g -> !g.passed()).toList();
    }

    public static Standing standing(Collection<Graded> results) {
        int credits = results.stream().mapToInt(Graded::credits).sum();
        int earned = results.stream().filter(Graded::passed).mapToInt(Graded::credits).sum();
        return new Standing(gpa(results), results.size(), credits, earned, outstanding(results));
    }

    /** Every student with at least one approved or published result → their standing. */
    public Map<Long, Standing> all() {
        Map<Long, List<Graded>> byStudent = official();
        Map<Long, Standing> out = new HashMap<>();
        byStudent.forEach((id, list) -> out.put(id, standing(list)));
        return out;
    }

    /** Every approved or published result, by student. */
    public Map<Long, List<Graded>> official() {
        int defaultCredits = gradingService.defaultCreditUnits();
        Map<Long, List<Graded>> byStudent = new HashMap<>();
        for (MarkRow m : queries.gradedMarks(AcademicRecordService.STAFF_VISIBLE))
            byStudent.computeIfAbsent(m.studentId(), k -> new ArrayList<>()).add(graded(m, defaultCredits));
        return byStudent;
    }
}
