package com.exam.service.reports;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.DocumentVerification;
import com.exam.model.comms.AuditLog;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.examops.RemarkRequest;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.NotificationService;
import com.exam.service.reports.ReportQueries.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/** Staff workload, sensitive actions from the audit log, and official documents issued. */
@Service
@Transactional(readOnly = true)
public class OversightReports {

    /** Audit actions worth a second look, by group (names as written by AuditInterceptor). */
    static final Map<String, String> SENSITIVE = new LinkedHashMap<>();
    static {
        for (String a : List.of("Approved marks sheet", "Published marks sheet", "Returned marks sheet for corrections",
                "Scheduled results release", "Cancelled scheduled results release", "Reviewed student script",
                "Answered re-mark request", "Recalculated grades", "Changed grading scale / promotion rules",
                "Synced system marks", "Deleted marks sheet"))
            SENSITIVE.put(a, "Results & grading");
        for (String a : List.of("Promoted student", "Promoted students (level)", "Promoted students (semester)",
                "Demoted students (semester)", "Changed student level/semester"))
            SENSITIVE.put(a, "Student progression");
        for (String a : List.of("Created HOD account", "Updated HOD account", "Deleted HOD account", "Deleted lecturer",
                "Deleted student", "Deactivated account", "Reactivated account", "Imported students", "Imported lecturers"))
            SENSITIVE.put(a, "Accounts");
        for (String a : List.of("Recorded fee payment", "Cancelled fee payment", "Set programme fee", "Removed programme fee",
                "Copied fees from another session"))
            SENSITIVE.put(a, "Finance");
        for (String a : List.of("Changed a document's verification status"))
            SENSITIVE.put(a, "Documents");
        for (String a : List.of("Deleted quiz", "Deleted course", "Deleted program", "Deleted department",
                "Deleted academic session", "Changed current academic session"))
            SENSITIVE.put(a, "Structure");
        for (String a : List.of("Changed system settings", "Changed system-wide feature switch", "Changed department feature setting",
                "Updated institution profile"))
            SENSITIVE.put(a, "Settings");
    }

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private UserRepository userRepository;
    @Autowired private SystemSettingService systemSettingService;

    // ── Staff workload ───────────────────────────────────────────────────────

    public ReportResult staffWorkload(ReportFilters f) {
        Map<String, String> t = support.terms();
        String lecturer = t.get("lecturer");
        ReportResult r = new ReportResult("staff-workload", lecturer + " workload",
                "What each " + lecturer.toLowerCase() + " teaches and what is waiting on them: scripts to review, failed marking, "
                        + "re-marks and marks sheets still open.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.sessionScope(r, f);
        support.periodScope(r, p);
        support.placeScope(r, f, l);

        Department dept = f.departmentId() == null ? null : l.departments.get(f.departmentId());
        List<User> lecturers = userRepository.findByRole(Role.LECTURER).stream()
                .filter(u -> dept == null || NotificationService.inDepartment(u, dept))
                .sorted(Comparator.comparing(u -> Objects.toString(name(u), ""))).toList();

        List<ResultRow> results = queries.quizResults(p);
        List<JobRow> jobs = queries.gradingJobs(Period.allTime());
        List<RemarkRow> remarks = queries.remarks(Period.allTime());
        Set<Long> aiQuizzes = jobs.stream().map(JobRow::quizId).collect(Collectors.toSet());
        results.stream().filter(x -> x.evaluationMethod() != null && !x.evaluationMethod().isBlank()).forEach(x -> aiQuizzes.add(x.quizId()));
        Long sessionId = f.sessionId();
        Map<Long, Set<Long>> sheetCourses = queries.sheetCourses();
        List<SheetRow> openSheets = queries.sheets().stream()
                .filter(s -> sessionId == null || sessionId.equals(s.sessionId()))
                .filter(s -> "DRAFT".equals(s.status()) || "ACTIVE".equals(s.status())).toList();
        Map<Long, Object[]> activity = queries.activityByActor(Role.LECTURER.name());
        LocalDateTime monthAgo = LocalDateTime.now().minusDays(30);

        Map<Long, Long> quizOwner = new HashMap<>();
        l.quizzes.values().forEach(q -> { User u = Lookups.lecturerOf(q); if (u != null) quizOwner.put(q.getqId(), u.getId()); });

        Table tb = r.table("lecturers", lecturer + "s")
                .text("name", "Name").text("email", "Email").text("department", "Department")
                .col("courses", t.get("courses"), INT).col("quizzes", "Quizzes", INT).col("quizzesTaken", "Quizzes taken in period", INT)
                .col("results", "Quiz results", INT).col("average", "Average score", PERCENT)
                .col("toReview", "Scripts to review", INT).col("failed", "Failed AI marking", INT)
                .col("remarks", "Re-marks waiting", INT).col("openSheets", "Open marks sheets", INT)
                .col("lastAction", "Last recorded action", DATETIME).text("account", "Account");
        int noCourses = 0, withBacklog = 0, quiet = 0;
        for (User u : lecturers) {
            Long id = u.getId();
            List<Category> courses = l.courses.values().stream().filter(c -> c.getUser() != null && id.equals(c.getUser().getId())).toList();
            Set<Long> courseIds = courses.stream().map(Category::getCid).collect(Collectors.toSet());
            Set<Long> quizzes = quizOwner.entrySet().stream().filter(e -> id.equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
            List<ResultRow> rs = results.stream().filter(x -> quizzes.contains(x.quizId())).toList();
            long toReview = rs.stream().filter(x -> !x.reviewed() && aiQuizzes.contains(x.quizId())).count();
            long failed = jobs.stream().filter(j -> j.status() == TheoryGradingJob.Status.FAILED && quizzes.contains(j.quizId())).count();
            long pending = remarks.stream().filter(x -> x.status() == RemarkRequest.Status.PENDING && quizzes.contains(x.quizId())).count();
            long sheets = openSheets.stream().filter(s -> !Collections.disjoint(sheetCourses.getOrDefault(s.id(), Set.of()), courseIds)).count();
            Object[] act = activity.get(id);
            LocalDateTime last = act == null ? null : (LocalDateTime) act[0];

            tb.add(name(u), u.getEmail(), u.getDepartment() == null ? null : u.getDepartment().getName(), courses.size(), quizzes.size(),
                    rs.stream().map(ResultRow::quizId).distinct().count(), rs.size(),
                    round1OrNull(rs.stream().filter(x -> x.percentage() != null).mapToDouble(ResultRow::percentage).average()),
                    toReview, failed, pending, sheets, minute(last), u.isEnabled() ? "Active" : "Deactivated");
            if (courses.isEmpty()) noCourses++;
            if (toReview + failed + pending > 0) withBacklog++;
            if (u.isEnabled() && (last == null || last.isBefore(monthAgo))) quiet++;
        }

        r.stat(lecturer + "s", lecturers.size())
         .stat("Without " + t.get("courses").toLowerCase(), noCourses, null, noCourses > 0 ? "warn" : null)
         .stat("With work waiting", withBacklog, "Scripts, failed marking or re-marks", withBacklog > 0 ? "warn" : null)
         .stat("No recorded action in 30 days", quiet);
        r.note("Quizzes and results belong to the " + t.get("course").toLowerCase() + "'s " + lecturer.toLowerCase()
                + " (else whoever set the quiz). Scripts to review are AI-marked scripts in the period not yet marked as reviewed.");
        r.note("Open marks sheets are draft or open sheets" + (sessionId == null ? "" : " in the session")
                + " that include one of the " + lecturer.toLowerCase() + "'s " + t.get("courses").toLowerCase()
                + ". Last recorded action counts changes (setting quizzes, saving marks …); signing in and viewing are not recorded.");
        return r;
    }

    // ── Sensitive actions ────────────────────────────────────────────────────

    public boolean auditVisible() {
        return systemSettingService.getBooleanSetting(SystemSettingService.AUDIT_LOG_VISIBLE_SUPER_ADMIN, true);
    }

    public ReportResult sensitiveActions(ReportFilters f) {
        if (!auditVisible()) throw new AccessDeniedException("The audit log has been turned off by the developer.");
        ReportResult r = new ReportResult("sensitive-actions", "Sensitive actions",
                "Changes to results, accounts, fees and settings from the audit log, grouped so unusual activity stands out.");
        Period p = f.from() == null && f.to() == null ? Period.of(LocalDate.now().minusDays(29), LocalDate.now(), null) : Period.of(f.from(), f.to(), null);
        support.periodScope(r, p);
        if (f.hasStatus()) r.scope("Group: " + f.status());

        List<String> actions = SENSITIVE.entrySet().stream().filter(e -> !f.hasStatus() || e.getValue().equalsIgnoreCase(f.status()))
                .map(Map.Entry::getKey).toList();
        String hidden = Role.DEVELOPER.name();

        Table byAction = r.table("actions", "By action").text("group", "Group").text("action", "Action")
                .col("times", "Times", INT).col("last", "Last", DATETIME);
        Map<String, Long> perGroup = new LinkedHashMap<>();
        long total = 0;
        List<Object[]> counts = new ArrayList<>(queries.auditCountsByAction(actions, hidden, p));
        counts.sort(Comparator.comparing((Object[] o) -> SENSITIVE.get((String) o[0])).thenComparing(o -> -((Number) o[1]).longValue()));
        for (Object[] o : counts) {
            long n = ((Number) o[1]).longValue();
            byAction.add(SENSITIVE.get((String) o[0]), o[0], n, minute((LocalDateTime) o[2]));
            perGroup.merge(SENSITIVE.get((String) o[0]), n, Long::sum);
            total += n;
        }

        Table groups = r.table("groups", "By group").text("group", "Group").col("times", "Times", INT).chart("group", "times");
        perGroup.forEach(groups::add);

        Table people = r.table("people", "By person").text("name", "Name").text("role", "Role")
                .col("times", "Actions", INT).col("last", "Last", DATETIME);
        queries.auditCountsByActor(actions, hidden, p).stream()
                .sorted(Comparator.comparing((Object[] o) -> -((Number) o[2]).longValue()))
                .forEach(o -> people.add(o[0], roleName((String) o[1]), ((Number) o[2]).longValue(), minute((LocalDateTime) o[3])));

        int limit = 2000;
        List<AuditLog> entries = queries.auditEntries(actions, hidden, p, limit);
        Table log = r.table("log", "Entries").subtitle(entries.size() >= limit ? "The latest " + limit + " entries." : null)
                .col("when", "When", DATETIME).text("who", "Who").text("role", "Role").text("group", "Group").text("action", "Action")
                .text("item", "Item").text("details", "Details").text("result", "Result").text("ip", "IP address");
        for (AuditLog a : entries)
            log.add(minute(a.getCreatedAt()), a.getActorName(), roleName(a.getActorRole()), SENSITIVE.get(a.getAction()), a.getAction(),
                    a.getEntityId(), a.getDetails(), a.getStatusCode() == null || a.getStatusCode() < 400 ? "Done" : "Failed (" + a.getStatusCode() + ")",
                    a.getIpAddress());

        r.stat("Sensitive actions", total);
        for (String g : new LinkedHashSet<>(SENSITIVE.values()))
            if (!f.hasStatus() || g.equalsIgnoreCase(f.status())) r.stat(g, perGroup.getOrDefault(g, 0L));
        r.note("The audit log records changes made by staff; viewing and signing in are not recorded.");
        return r;
    }

    static String roleName(String role) {
        if (role == null) return null;
        return switch (role) {
            case "SUPER_ADMIN" -> "Super Admin";
            case "ADMIN" -> "HOD";
            case "LECTURER" -> "Lecturer";
            case "NORMAL" -> "Student";
            default -> role;
        };
    }

    // ── Documents issued ─────────────────────────────────────────────────────

    public ReportResult documentsIssued(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("documents-issued", "Documents issued",
                "Transcripts and report cards printed with a verification code, and any revoked.");
        Period p = support.period(f);
        support.periodScope(r, p);
        if (f.hasStatus()) r.scope("Document: " + docName(f.status(), t));

        ZoneId zone = ZoneId.systemDefault();
        List<DocumentVerification> docs = queries.documents(p).stream()
                .filter(d -> !f.hasStatus() || d.getDocType().name().equalsIgnoreCase(f.status())).toList();

        Table types = r.table("types", "By document").text("document", "Document").col("issued", "Issued", INT).col("revoked", "Revoked", INT);
        for (DocumentVerification.Type ty : DocumentVerification.Type.values()) {
            List<DocumentVerification> g = docs.stream().filter(d -> d.getDocType() == ty).toList();
            if (!g.isEmpty() || !f.hasStatus()) types.add(docName(ty.name(), t), g.size(), g.stream().filter(DocumentVerification::isRevoked).count());
        }

        Table months = r.table("months", "By month").text("month", "Month").col("issued", "Issued", INT).chart("month", "issued");
        DateTimeFormatter mf = DateTimeFormatter.ofPattern("MMM yyyy");
        docs.stream().collect(Collectors.groupingBy(d -> YearMonth.from(d.getIssuedAt().atZone(zone)), TreeMap::new, Collectors.counting()))
                .forEach((m, n) -> months.add(mf.format(m), n));

        Table progs = r.table("programmes", "By " + t.get("program").toLowerCase()).text("programme", t.get("program"));
        for (DocumentVerification.Type ty : DocumentVerification.Type.values()) progs.col(ty.name(), docName(ty.name(), t), INT);
        progs.col("total", "Total", INT);
        docs.stream().collect(Collectors.groupingBy(d -> Objects.toString(d.getProgramName(), "Not recorded"), TreeMap::new, Collectors.toList()))
                .forEach((name, g) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("programme", name);
                    for (DocumentVerification.Type ty : DocumentVerification.Type.values())
                        row.put(ty.name(), g.stream().filter(d -> d.getDocType() == ty).count());
                    row.put("total", g.size());
                    progs.add(row);
                });

        int limit = 2000;
        Table list = r.table("documents", "Documents").subtitle(docs.size() > limit ? "The latest " + limit + "." : null)
                .col("issued", "Issued", DATETIME).text("code", "Code").text("document", "Document").text("studentId", t.get("studentId"))
                .text("name", "Name").text("programme", t.get("program")).text("issuedBy", "Issued by").text("revoked", "Revoked")
                .text("reason", "Reason");
        docs.stream().limit(limit).forEach(d -> list.add(minute(LocalDateTime.ofInstant(d.getIssuedAt(), zone)), d.getCode(),
                docName(d.getDocType().name(), t), d.getStudentUsername(), d.getStudentName(), d.getProgramName(), d.getIssuedBy(),
                yesNo(d.isRevoked()), d.getRevokedReason()));

        r.stat("Issued", docs.size());
        for (DocumentVerification.Type ty : DocumentVerification.Type.values())
            if (!f.hasStatus() || ty.name().equalsIgnoreCase(f.status()))
                r.stat(docName(ty.name(), t) + "s", docs.stream().filter(d -> d.getDocType() == ty).count());
        long revoked = docs.stream().filter(DocumentVerification::isRevoked).count();
        r.stat("Revoked", revoked, null, revoked > 0 ? "warn" : null);
        r.note("Only documents printed with a verification code are counted. How often codes were checked is not recorded.");
        return r;
    }

    static String docName(String type, Map<String, String> t) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "TRANSCRIPT" -> "Transcript";
            case "REPORT_CARD" -> t.get("reportCard");
            case "CUMULATIVE_REPORT" -> "Cumulative report";
            default -> type;
        };
    }
}
