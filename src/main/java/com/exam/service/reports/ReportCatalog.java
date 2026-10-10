package com.exam.service.reports;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.examops.ExamAccess;
import com.exam.service.fees.ResultsHoldService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * The reports, who may run each one, which filters it takes, and running one.
 * <ul>
 *   <li>Super Admin: every report, across the institution.</li>
 *   <li>HOD: department reports, locked to their department ({@link ReportSupport#scopeToDepartment}).</li>
 *   <li>Lecturer: teaching reports, locked to the courses they teach and quizzes they manage
 *       ({@link ReportSupport#scopeToLecturer}).</li>
 * </ul>
 * Locked filters (department; programme for lecturers) are not offered, and ids outside the lock are refused.
 */
@Service
public class ReportCatalog {

    public record Option(String value, String label) {}

    /** key: session | department | program | level | semester | from | to | course | quiz | status. Options for course, quiz and status. */
    public record Filter(String key, String label, List<Option> options, boolean required) {}

    public record Definition(String key, String title, String group, String description, String icon, List<Filter> filters) {}

    private record Entry(Definition definition, Set<Role> roles, BiFunction<ReportFilters, User, ReportResult> run) {}

    private static final Set<Role> ALL = EnumSet.of(Role.SUPER_ADMIN, Role.ADMIN, Role.LECTURER);
    private static final Set<Role> ADMINS = EnumSet.of(Role.SUPER_ADMIN, Role.ADMIN);
    private static final Set<Role> SUPER = EnumSet.of(Role.SUPER_ADMIN);
    /** The question paper with its answers: opening and printing it is recorded in the audit log. */
    public static final String ANSWER_KEY = "question-paper-answers";

    @Autowired private ReportSupport support;
    @Autowired private AcademicReports academic;
    @Autowired private ResultsReports results;
    @Autowired private TeachingReports teaching;
    @Autowired private QuestionPapers papers;
    @Autowired private ExamReports exams;
    @Autowired private FinanceReports finance;
    @Autowired private OversightReports oversight;
    @Autowired private ResultsHoldService resultsHoldService;

    /** The reports this person may run, as the hub and filter bar show them. */
    public List<Definition> definitions(User viewer) {
        Role role = viewer.getRole();
        Lookups l = support.lookups();
        List<Option> courses = courseOptions(viewer, l), quizzes = quizOptions(viewer, l);
        return entries().stream().filter(e -> e.roles().contains(role))
                .map(e -> forViewer(e.definition(), role, courses, quizzes)).toList();
    }

    public ReportResult run(String key, ReportFilters f, User actor) {
        Entry entry = entries().stream().filter(e -> e.definition().key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown report: " + key));
        if (!entry.roles().contains(actor.getRole())) throw new AccessDeniedException("That report is not available to you.");
        ReportFilters scoped = switch (actor.getRole()) {
            case ADMIN -> support.scopeToDepartment(f, actor);
            case LECTURER -> support.scopeToLecturer(f, actor);
            case SUPER_ADMIN -> f;
            default -> throw new AccessDeniedException("Reports are for staff only.");
        };
        ReportResult r = entry.run().apply(scoped, actor);
        r.setGeneratedBy(CurrentUserService.displayName(actor));
        return r;
    }

    private List<Entry> entries() {
        Map<String, String> t = support.terms();
        boolean school = support.isSchool();
        String students = t.get("student") + "s", course = t.get("course"), lecturer = t.get("lecturer");
        String program = t.get("program").toLowerCase(), level = t.get("level").toLowerCase(), semester = t.get("semester").toLowerCase();
        Filter session = f("session", "Session"), dept = f("department", "Department"), prog = f("program", t.get("program")),
                lvl = f("level", t.get("level")), sem = f("semester", t.get("semester")), from = f("from", "From"), to = f("to", "To");
        Filter sheets = status("Marks sheets", ResultsReports.SHEET_CHOICES);
        Filter quiz = req(f("quiz", "Quiz")), courseReq = req(f("course", course)), courseAny = f("course", course);

        List<Entry> out = new ArrayList<>();
        // Teaching: one quiz or one course
        out.add(e(ALL, new Definition("quiz-results", "Quiz result sheet", "Teaching",
                "Every " + t.get("student").toLowerCase() + "'s marks on one quiz (sat, unfinished or absent) with score statistics and grades.",
                "table", List.of(quiz)), (x, u) -> teaching.quizResults(x)));
        out.add(e(ALL, new Definition("question-analysis", "Question analysis", "Teaching",
                "How each question performed: correct rate, difficulty, discrimination, options chosen, and answer keys to check.",
                "search", List.of(quiz)), (x, u) -> teaching.questionAnalysis(x)));
        out.add(e(ALL, new Definition("question-paper", "Question paper", "Teaching",
                "One quiz's questions, ready to print: quiz details (date, duration, course), instructions, and every objective and theory question.",
                "paper", List.of(quiz)), (x, u) -> papers.questionPaper(x, false)));
        out.add(e(ALL, new Definition(ANSWER_KEY, "Question paper with answers", "Teaching",
                "The same paper with the Section A answer key: correct options, accepted answers and matching pairs; theory questions as set.",
                "key", List.of(quiz)), (x, u) -> papers.questionPaper(x, true)));
        out.add(e(ALL, new Definition("course-assessment", "Continuous assessment summary", "Teaching",
                "Every " + t.get("student").toLowerCase() + "'s score on every quiz in a " + course.toLowerCase()
                        + ", with averages, trends and who needs support.",
                "trend", List.of(courseReq, session, from, to)), (x, u) -> teaching.courseAssessment(x)));
        out.add(e(ALL, new Definition("remark-requests", "Re-mark requests", "Teaching",
                "Every re-mark request: who asked and why, how long it waited, and how marks changed.",
                "message", List.of(session, from, to, dept, courseAny, status("Status", List.of(o("PENDING", "Waiting"),
                        o("RESOLVED", "Re-marked"), o("REJECTED", "Turned down"))))), (x, u) -> teaching.remarkRequests(x)));

        // Institution
        out.add(e(SUPER, new Definition("department-comparison", "Department comparison", "Institution",
                "Every department side by side: " + students.toLowerCase() + ", " + lecturer.toLowerCase() + "s, quiz and official pass rates, marks sheets.",
                "building", List.of(session, from, to)), (x, u) -> academic.departmentComparison(x)));

        // Students
        out.add(e(ADMINS, new Definition("student-headcount", students + " by programme and level", students,
                "Headcount by " + program + ", " + level + " and " + semester + ", plus records missing details.",
                "users", List.of(dept, prog, lvl)), (x, u) -> academic.studentHeadcount(x)));
        out.add(e(ALL, new Definition("course-registration", course + " registration", students,
                "Registered " + students.toLowerCase() + " per " + course.toLowerCase() + " and " + semester
                        + ", who has not registered, and each " + course.toLowerCase() + "'s list.",
                "clipboard", List.of(session, dept, prog, lvl, sem)), (x, u) -> academic.courseRegistration(x)));
        if (!school) out.add(e(ADMINS, new Definition("academic-standing", "Academic standing", students,
                "CGPA, credits, carry-overs and class for every " + t.get("student").toLowerCase() + ", and who falls short of promotion rules.",
                "graduation", List.of(dept, prog, lvl, status("Standing", List.of(
                        o("GOOD", AcademicReports.standingName("GOOD")), o("CARRYOVER", AcademicReports.standingName("CARRYOVER")),
                        o("AT_RISK", AcademicReports.standingName("AT_RISK")), o("NO_RESULTS", AcademicReports.standingName("NO_RESULTS")))))),
                (x, u) -> academic.academicStanding(x)));
        if (!school) out.add(e(ADMINS, new Definition("top-performers", "Top performers and Dean's list", students,
                "Highest CGPAs per " + program + " and " + level + ", best " + semester + " GPAs, and the Dean's list.",
                "trophy", List.of(session, dept, prog, lvl, sem)), (x, u) -> results.topPerformers(x)));

        // Results
        out.add(e(ADMINS, new Definition("broadsheet", "Broadsheet", "Results",
                "Every " + t.get("student").toLowerCase() + " against every " + course.toLowerCase() + " for one " + program + ", " + level
                        + " and " + semester + (school ? ", with totals, averages and positions." : ", with GPA, CGPA, carry-overs and remarks."),
                "grid", List.of(session, dept, req(prog), req(lvl), req(sem), sheets)), (x, u) -> results.broadsheet(x)));
        out.add(e(ALL, new Definition("course-results", course + " result summary", "Results",
                "Registered, graded, passed and failed per " + course.toLowerCase() + ", with the spread of marks and grades.",
                "list", List.of(session, dept, prog, lvl, sem, sheets)), (x, u) -> results.courseResults(x)));
        out.add(e(ALL, new Definition("results-publication", "Results publication tracker", "Results",
                "Every marks sheet from draft to published, and the ones waiting for approval or release.",
                "send", List.of(session, dept, prog, lvl, sem, status("Status", AcademicReports.SHEET_STATUSES.stream()
                        .map(s -> o(s, AcademicReports.statusName(s))).toList()))), (x, u) -> academic.resultsPublication(x)));
        out.add(e(ALL, new Definition("grade-moderation", "Grade moderation", "Results",
                "Score spread and grades per " + course.toLowerCase() + ", with unusual results flagged for the exam board.",
                "scale", List.of(session, dept, prog, lvl, sem)), (x, u) -> academic.gradeModeration(x)));

        // Exams
        out.add(e(ALL, new Definition("exam-absentees", "Exam absentees", "Exams",
                "Per quiz: expected, sat, never submitted and absent, with each quiz's list of absentees.",
                "userx", List.of(session, from, to, dept, prog, lvl)), (x, u) -> exams.examAbsentees(x)));
        out.add(e(ALL, new Definition("exam-integrity", "Exam integrity", "Exams",
                "Proctoring violations, auto-submissions, voided attempts and re-mark requests per quiz.",
                "shield", List.of(session, from, to, dept)), (x, u) -> exams.examIntegrity(x)));
        out.add(e(ALL, new Definition("ai-marking", "AI marking", "Exams",
                "Theory answers marked by AI: marked, failed, still waiting, and scripts not yet reviewed.",
                "bot", List.of(session, from, to, dept)), (x, u) -> exams.aiMarking(x)));
        out.add(e(ALL, new Definition("exam-load", "Exam schedule load", "Exams",
                "How many candidates are expected to write at the same time, day by day.",
                "activity", List.of(from, to, dept, prog, lvl)), (x, u) -> exams.examLoad(x, u)));

        // Staff
        out.add(e(ADMINS, new Definition("staff-workload", lecturer + " workload", "Staff",
                "Per " + lecturer.toLowerCase() + ": " + t.get("courses").toLowerCase() + ", quizzes, scripts to review, re-marks and open marks sheets.",
                "briefcase", List.of(session, from, to, dept)), (x, u) -> oversight.staffWorkload(x)));

        // Finance
        out.add(e(SUPER, new Definition("fee-collections", "Fee collections", "Finance",
                "Billed, collected and outstanding per " + program + " and " + level + ", payment methods, and staff-entered or voided payments.",
                "wallet", List.of(session, dept, prog, lvl)), (x, u) -> finance.feeCollections(x)));
        out.add(e(SUPER, new Definition("fee-debtors", "Fee debtors", "Finance",
                students + " who still owe this session's fee, with contact details and whether their results are on hold.",
                "receipt", List.of(dept, prog, lvl)), (x, u) -> finance.feeDebtors(x)));
        if (!resultsHoldService.programsWithRule().isEmpty())
            out.add(e(ADMINS, new Definition("results-on-hold", "Results on hold for fees", "Finance",
                    students + " whose " + t.get("reportCard").toLowerCase() + "s or transcript are held until they pay more of this session's fee.",
                    "lock", List.of(dept, prog, lvl)), (x, u) -> finance.resultsOnHold(x)));

        // Oversight
        if (oversight.auditVisible()) out.add(e(SUPER, new Definition("sensitive-actions", "Sensitive actions", "Oversight",
                "Changes to results, accounts, fees and settings from the audit log, by action and by person.",
                "alert", List.of(from, to, status("Group", new LinkedHashSet<>(OversightReports.SENSITIVE.values()).stream().map(g -> o(g, g)).toList()))),
                (x, u) -> oversight.sensitiveActions(x)));
        out.add(e(SUPER, new Definition("documents-issued", "Documents issued", "Oversight",
                "Transcripts and report cards printed with a verification code, by month and " + program + ".",
                "file", List.of(session, from, to, status("Document", List.of(o("TRANSCRIPT", "Transcript"),
                        o("REPORT_CARD", t.get("reportCard")), o("CUMULATIVE_REPORT", "Cumulative report"))))),
                (x, u) -> oversight.documentsIssued(x)));
        return out;
    }

    /** The definition as this role sees it: locked filters removed, course and quiz pickers filled in. */
    private static Definition forViewer(Definition d, Role role, List<Option> courses, List<Option> quizzes) {
        List<Filter> filters = new ArrayList<>();
        for (Filter x : d.filters()) {
            if (x.key().equals("department") && role != Role.SUPER_ADMIN) continue;
            if (x.key().equals("program") && role == Role.LECTURER) continue;
            if (x.key().equals("course")) x = new Filter(x.key(), x.label(), courses, x.required());
            else if (x.key().equals("quiz")) x = new Filter(x.key(), x.label(), quizzes, x.required());
            filters.add(x);
        }
        return new Definition(d.key(), d.title(), d.group(), d.description(), d.icon(), filters);
    }

    private static List<Option> courseOptions(User viewer, Lookups l) {
        Predicate<Category> mine = switch (viewer.getRole()) {
            case LECTURER -> c -> c.getUser() != null && viewer.getId().equals(c.getUser().getId());
            case ADMIN -> c -> viewer.getDepartment() != null && l.courseDepts(c).contains(viewer.getDepartment().getId());
            default -> c -> true;
        };
        return l.courses.values().stream().filter(mine)
                .sorted(Comparator.comparing((Category c) -> Objects.toString(c.getCourseCode(), "")).thenComparing(c -> Objects.toString(c.getTitle(), "")))
                .map(c -> o(String.valueOf(c.getCid()), TeachingReports.courseLabel(c))).toList();
    }

    private static List<Option> quizOptions(User viewer, Lookups l) {
        Predicate<Quiz> mine = switch (viewer.getRole()) {
            case LECTURER -> q -> ExamAccess.canManageQuiz(viewer, q);
            case ADMIN -> q -> viewer.getDepartment() != null && l.courseDepts(q.getCategory()).contains(viewer.getDepartment().getId());
            default -> q -> true;
        };
        return l.quizzes.values().stream().filter(mine)
                .sorted(Comparator.comparing((Quiz q) -> q.getQuizDate() == null ? LocalDate.MIN : q.getQuizDate()).reversed()
                        .thenComparing(Quiz::getqId, Comparator.reverseOrder()))
                .map(q -> o(String.valueOf(q.getqId()), TeachingReports.quizLabel(q))).toList();
    }

    private static Entry e(Set<Role> roles, Definition d, BiFunction<ReportFilters, User, ReportResult> run) { return new Entry(d, roles, run); }

    private static Filter f(String key, String label) { return new Filter(key, label, null, false); }

    private static Filter req(Filter x) { return new Filter(x.key(), x.label(), x.options(), true); }

    private static Filter status(String label, List<Option> options) { return new Filter("status", label, options, false); }

    private static Option o(String value, String label) { return new Option(value, label); }
}
