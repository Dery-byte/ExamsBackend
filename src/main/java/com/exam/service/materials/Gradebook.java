package com.exam.service.materials;

import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.MarkSheetSection;
import com.exam.model.exam.SemesterSheet;
import com.exam.model.materials.AssignmentSubmission;
import com.exam.model.materials.CourseMaterial;
import com.exam.model.materials.CourseSpace;
import com.exam.repository.CategoryRepository;
import com.exam.repository.materials.AssignmentSubmissionRepository;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.repository.materials.CourseSpaceRepository;
import com.exam.service.MarksEntryService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A course's running assignment total: every student's mark on each assignment and the sum.
 * <p>
 * Rules (agreed with the user, 2026-11-17): the total is the plain sum of marks. An assignment adds to
 * a student's total once it is marked for them, or once its submissions have closed (work not handed
 * in then counts 0). Handed in but not yet marked is pending and left out. Assignments switched to
 * "doesn't count" (practice) and drafts stay out of the total. Students see only released marks.
 * Scaling happens only when the total is sent to a Marks Sheet column.
 */
@Service
public class Gradebook {

    public enum State { MARKED, MISSING, PENDING, OPEN }

    @Autowired private CategoryRepository categories;
    @Autowired private CourseSpaceRepository spaces;
    @Autowired private CourseMaterialRepository materials;
    @Autowired private AssignmentSubmissionRepository submissions;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private MaterialService materialService;
    @Autowired private MaterialAccess access;
    @Autowired private AssignmentService assignments;
    @Autowired private MarksEntryService marksEntry;

    public record SheetRequest(Long sheetId, Long sectionId) {}

    /** What one student has on one assignment: the mark (or 0 for missing work) and whether it counts now. */
    record Cell(State state, BigDecimal score, boolean late) {
        boolean inTotal() { return state == State.MARKED || state == State.MISSING; }
    }

    static Cell cell(CourseMaterial a, AssignmentSubmission s, LocalDateTime now) {
        boolean handedIn = s != null && s.getSubmittedAt() != null;
        if (s != null && s.getScore() != null) return new Cell(State.MARKED, s.getScore(), handedIn && s.isLate());
        if (handedIn) return new Cell(State.PENDING, null, s.isLate());
        return MaterialService.isOpen(a, now) ? new Cell(State.OPEN, null, false) : new Cell(State.MISSING, BigDecimal.ZERO, false);
    }

    /** Assignments that can count: not drafts, switched on for the total. */
    static boolean counts(CourseMaterial a) {
        return a.isCountsTowardTotal() && a.getVisibility() != CourseMaterial.Visibility.DRAFT;
    }

    // ══ Staff ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> staff(User u, Long courseId) {
        Category c = categories.findById(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found."));
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        CourseSpace s = spaces.findByCourse_CidAndAcademicSession_Id(c.getCid(), academicSessions.current().getId()).orElse(null);
        Map<String, Object> out = MaterialService.map("course", MaterialService.courseInfo(c));
        if (s == null) {
            out.put("assignments", List.of());
            out.put("students", List.of());
            return out;
        }
        Book b = build(s);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> cols = new ArrayList<>();
        for (CourseMaterial a : b.assignments()) {
            List<BigDecimal> marks = b.people().keySet().stream()
                    .map(id -> b.cells().get(id).get(a.getId())).filter(x -> x.state() == State.MARKED).map(Cell::score).toList();
            long handed = b.people().keySet().stream().map(id -> b.cells().get(id).get(a.getId()))
                    .filter(x -> x.state() == State.MARKED || x.state() == State.PENDING).count();
            cols.add(MaterialService.map("id", a.getId(), "title", a.getTitle(), "weekNumber", a.getWeekNumber(),
                    "maxMarks", a.getMaxMarks(), "dueAt", a.getDueAt(), "closesAt", a.getClosesAt(), "closed", !MaterialService.isOpen(a, now),
                    "released", a.isMarksReleased(), "counts", a.isCountsTowardTotal(), "draft", a.getVisibility() == CourseMaterial.Visibility.DRAFT,
                    "marked", marks.size(), "handedIn", handed,
                    "average", marks.isEmpty() ? null : marks.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(marks.size()), 2, RoundingMode.HALF_UP),
                    "highest", marks.stream().max(Comparator.naturalOrder()).orElse(null),
                    "lowest", marks.stream().min(Comparator.naturalOrder()).orElse(null)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, User> e : b.people().entrySet()) {
            Map<String, Object> cellsOut = new LinkedHashMap<>();
            for (CourseMaterial a : b.assignments()) {
                Cell x = b.cells().get(e.getKey()).get(a.getId());
                cellsOut.put(String.valueOf(a.getId()), MaterialService.map("state", x.state().name(), "score", x.score(), "late", x.late()));
            }
            Totals t = totals(b.assignments(), b.cells().get(e.getKey()), a -> true);
            rows.add(MaterialService.map("studentId", e.getKey(), "name", CurrentUserService.displayName(e.getValue()),
                    "username", e.getValue().getUsername(), "cells", cellsOut,
                    "total", t.total(), "outOf", t.outOf(), "percent", t.percent(), "onList", b.onList().contains(e.getKey())));
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("name")), String.CASE_INSENSITIVE_ORDER));
        List<BigDecimal> percents = rows.stream().map(r -> (BigDecimal) r.get("percent")).filter(Objects::nonNull).toList();
        out.put("assignments", cols);
        out.put("students", rows);
        out.put("classAverage", percents.isEmpty() ? null
                : percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(percents.size()), 1, RoundingMode.HALF_UP));
        return out;
    }

    @Transactional
    public Map<String, Object> setCounts(User u, Long assignmentId, boolean counts) {
        CourseMaterial a = materials.findById(assignmentId).filter(x -> x.getDeletedAt() == null && x.isAssignment())
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        access.requireOnFor(u, a.getSpace().getCourse());
        MaterialAccess.requireManage(u, a.getSpace().getCourse());
        a.setCountsTowardTotal(counts);
        materials.save(a);
        return staff(u, a.getSpace().getCourse().getCid());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sheets(User u, Long courseId) {
        Category c = categories.findById(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found."));
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        return assignments.sheetTargets(c);
    }

    /** Each student's total, scaled to the column's maximum (total ÷ out of × column), into a Marks Sheet. */
    @Transactional
    public Map<String, Object> sendToSheet(User u, Long courseId, SheetRequest r) {
        Category c = categories.findById(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found."));
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        if (r == null || r.sheetId() == null || r.sectionId() == null) throw new IllegalArgumentException("Choose the marks sheet and its column.");
        SemesterSheet sheet = spaces.sheetsWithCourse(c.getCid(), academicSessions.current().getId()).stream()
                .filter(x -> x.getId().equals(r.sheetId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That marks sheet doesn't include this course."));
        MarkSheetSection section = sheet.getSections().stream().filter(x -> x.getId().equals(r.sectionId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That column isn't on this marks sheet."));
        CourseSpace s = spaces.findByCourse_CidAndAcademicSession_Id(c.getCid(), academicSessions.current().getId())
                .orElseThrow(() -> new IllegalArgumentException("No assignments yet."));
        Book b = build(s);
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (Long sid : b.people().keySet()) {
            Totals t = totals(b.assignments(), b.cells().get(sid), a -> true);
            if (t.outOf().signum() == 0) continue;
            scores.put(sid, t.total().multiply(section.getMaxScore()).divide(t.outOf(), 2, RoundingMode.HALF_UP));
        }
        if (scores.isEmpty()) throw new IllegalArgumentException("No assignment marks count towards the total yet.");
        int n = marksEntry.applyExternalMarks(sheet.getId(), section.getId(), c.getCid(), scores);
        return MaterialService.map("updated", n, "column", section.getSectionName(),
                "message", n == 0 ? "None of these students are on that marks sheet yet."
                        : n + " student" + (n == 1 ? "'s" : "s'") + " assignment totals went into \"" + section.getSectionName() + "\" (scaled to "
                        + section.getMaxScore().stripTrailingZeros().toPlainString() + ").");
    }

    // ══ Students ═════════════════════════════════════════════════════════════

    /**
     * A student's own marks for the assignments they can see, released ones only, and their total.
     * notReleased: assignments that would count but whose marks the lecturer hasn't released yet.
     */
    static Map<String, Object> forStudent(List<CourseMaterial> visibleAssignments, Map<Long, AssignmentSubmission> mine, LocalDateTime now) {
        List<CourseMaterial> list = visibleAssignments.stream().filter(CourseMaterial::isAssignment)
                .sorted(Comparator.comparingInt(CourseMaterial::getWeekNumber).thenComparing(CourseMaterial::getId)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, Cell> cells = new HashMap<>();
        int notReleased = 0;
        for (CourseMaterial a : list) {
            Cell x = cell(a, mine.get(a.getId()), now);
            boolean released = a.isMarksReleased();
            if (!released && counts(a) && x.inTotal()) notReleased++;
            if (released) cells.put(a.getId(), x);
            rows.add(MaterialService.map("id", a.getId(), "title", a.getTitle(), "weekNumber", a.getWeekNumber(), "maxMarks", a.getMaxMarks(),
                    "counts", counts(a), "released", released,
                    "state", released ? x.state().name() : (x.state() == State.OPEN ? "OPEN" : "NOT_RELEASED"),
                    "score", released ? x.score() : null));
        }
        Totals t = totals(list.stream().filter(a -> cells.containsKey(a.getId())).toList(), cells, a -> true);
        return MaterialService.map("assignments", rows, "total", t.total(), "outOf", t.outOf(), "percent", t.percent(), "notReleased", notReleased);
    }

    // ══ Working ══════════════════════════════════════════════════════════════

    record Totals(BigDecimal total, BigDecimal outOf, BigDecimal percent) {}

    static Totals totals(List<CourseMaterial> list, Map<Long, Cell> cells, java.util.function.Predicate<CourseMaterial> extra) {
        BigDecimal total = BigDecimal.ZERO, outOf = BigDecimal.ZERO;
        for (CourseMaterial a : list) {
            Cell x = cells.get(a.getId());
            if (x == null || !counts(a) || !extra.test(a) || !x.inTotal()) continue;
            total = total.add(x.score());
            outOf = outOf.add(a.getMaxMarks());
        }
        BigDecimal percent = outOf.signum() == 0 ? null : total.multiply(BigDecimal.valueOf(100)).divide(outOf, 1, RoundingMode.HALF_UP);
        return new Totals(total.stripTrailingZeros().scale() < 0 ? total.setScale(0) : total.stripTrailingZeros(),
                outOf.stripTrailingZeros().scale() < 0 ? outOf.setScale(0) : outOf.stripTrailingZeros(), percent);
    }

    /** onList: on the course's class list now (others handed something in earlier and still show). */
    record Book(List<CourseMaterial> assignments, Map<Long, User> people, Set<Long> onList, Map<Long, Map<Long, Cell>> cells) {}

    Book build(CourseSpace s) {
        LocalDateTime now = LocalDateTime.now();
        List<CourseMaterial> list = materials.live(s.getId()).stream().filter(CourseMaterial::isAssignment)
                .sorted(Comparator.comparingInt(CourseMaterial::getWeekNumber)
                        .thenComparing(a -> a.getDueAt() == null ? LocalDateTime.MAX : a.getDueAt()).thenComparing(CourseMaterial::getId))
                .toList();
        Map<Long, User> people = new LinkedHashMap<>();
        Set<Long> onList = new HashSet<>();
        for (User st : materialService.roster(s)) { people.put(st.getId(), st); onList.add(st.getId()); }
        Map<Long, Map<Long, AssignmentSubmission>> subs = new HashMap<>();
        if (!list.isEmpty())
            for (AssignmentSubmission x : submissions.findByAssignment_IdIn(list.stream().map(CourseMaterial::getId).toList())) {
                people.putIfAbsent(x.getStudent().getId(), x.getStudent());
                subs.computeIfAbsent(x.getStudent().getId(), k -> new HashMap<>()).put(x.getAssignment().getId(), x);
            }
        Map<Long, Map<Long, Cell>> cells = new HashMap<>();
        for (Long sid : people.keySet()) {
            Map<Long, Cell> row = new HashMap<>();
            for (CourseMaterial a : list) row.put(a.getId(), cell(a, subs.getOrDefault(sid, Map.of()).get(a.getId()), now));
            cells.put(sid, row);
        }
        return new Book(list, people, onList, cells);
    }
}
