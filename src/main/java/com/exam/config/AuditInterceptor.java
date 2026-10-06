package com.exam.config;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.service.comms.AuditService;
import com.exam.service.comms.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records every change (POST / PUT / PATCH / DELETE) made by staff — Super Admin, HODs and
 * lecturers — into the audit log, with a readable action name. Students' exam traffic
 * (answers, timers, progress) is not recorded.
 */
@Configuration
public class AuditInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** Request attribute a controller can set to add details to its audit entry. */
    public static final String AUDIT_DETAILS = "auditDetails";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<Role> AUDITED_ROLES = Set.of(Role.SUPER_ADMIN, Role.ADMIN, Role.LECTURER, Role.DEVELOPER);

    /** Noisy or non-business endpoints that are never recorded. */
    private static final List<Pattern> IGNORED = List.of(
            Pattern.compile("/api/notifications.*"),
            Pattern.compile("/api/proctoring/events"),
            Pattern.compile(".*/(quiz-progress|quiz-timer|theory-progress)(/.*)?"),
            Pattern.compile(".*/(logout|authenticate|refresh-token)$")
    );

    private record Rule(String method, Pattern path, String action) {}

    private static Rule r(String method, String regex, String action) {
        return new Rule(method, Pattern.compile(regex), action);
    }

    /** First match wins. Paths are matched against the full request URI. */
    private static final List<Rule> RULES = List.of(
            // Marks sheets
            r("POST",   ".*/marks/sheet/activate$",                 "Created marks sheet"),
            r("POST",   ".*/marks/sheet/\\d+/save$",                "Saved marks"),
            r("POST",   ".*/marks/sheet/\\d+/submit$",              "Submitted marks sheet"),
            r("POST",   ".*/marks/sheet/\\d+/approve$",             "Approved marks sheet"),
            r("POST",   ".*/marks/sheet/\\d+/publish$",             "Published marks sheet"),
            r("POST",   ".*/marks/sheet/\\d+/revert$",              "Returned marks sheet for corrections"),
            r("POST",   ".*/marks/sheet/\\d+/schedule-publish$",    "Scheduled results release"),
            r("PUT",    ".*/marks/sheet/\\d+/term-remarks$",        "Saved report-card remarks"),
            r("PUT",    ".*/super-admin/institution$",               "Updated institution profile"),
            r("PUT",    ".*/developer/mode$",                        "Changed the system mode"),
            r("POST",   ".*/developer/errors/\\d+/resolve$",        "Changed an error's status"),
            r("DELETE", ".*/developer/errors/resolved$",             "Cleared resolved errors"),
            r("POST",   ".*/super-admin/institution/logo$",          "Uploaded institution logo"),
            r("DELETE", ".*/super-admin/institution/logo$",          "Removed institution logo"),
            r("POST",   ".*/super-admin/documents/\\d+/revoke$",    "Changed a document's verification status"),
            r("DELETE", ".*/marks/sheet/\\d+/schedule-publish$",    "Cancelled scheduled results release"),
            r("POST",   ".*/marks/sheet/\\d+/enroll-students$",     "Enrolled students into marks sheet"),
            r("POST",   ".*/marks/sheet/\\d+/sync-marks/.*",        "Synced system marks"),
            r("POST",   ".*/marks/sheet/\\d+/sections$",            "Added marks sheet section"),
            r("DELETE", ".*/marks/sheet/\\d+/sections/\\d+$",       "Deleted marks sheet section"),
            r("PUT",    ".*/marks/sheet/\\d+$",                     "Updated marks sheet"),
            r("DELETE", ".*/marks/sheet/\\d+$",                     "Deleted marks sheet"),
            // Courses
            r("POST",   ".*/v1/auth/add$",                          "Created course"),
            r("POST",   ".*/(lecturer|user)/addCategory$",          "Created course"),
            r("PUT",    ".*/category/(admin/)?updateCategory.*",    "Updated course"),
            r("DELETE", ".*/category/\\d+$",                        "Deleted course"),
            r("PUT",    ".*/courses/\\d+/assign/\\d+$",             "Assigned lecturer to course"),
            r("PUT",    ".*/\\d+/unassign$",                        "Unassigned lecturer from course"),
            // Enrolment & promotion
            r("POST",   ".*/enroll-student$",                       "Enrolled student in course"),
            r("DELETE", ".*/unenroll-student/\\d+/\\d+$",           "Unenrolled student from course"),
            r("PUT",    ".*/promote-all/.*",                        "Promoted students (level)"),
            r("PUT",    ".*/promote-semester-all/.*",               "Promoted students (semester)"),
            r("PUT",    ".*/demote-semester-all/.*",                "Demoted students (semester)"),
            r("PUT",    ".*/student/\\d+/promote$",                 "Promoted student"),
            r("PUT",    ".*/student/\\d+/level-semester$",          "Changed student level/semester"),
            // Quizzes & review
            r("POST",   ".*/(lecturer|user)?/?addQuiz$",            "Created quiz"),
            r("PUT",    ".*/v1/auth/update$",                       "Updated quiz"),
            r("DELETE", ".*/delete/quiz/\\d+$",                     "Deleted quiz"),
            r("PUT",    ".*/quiz/status/\\d+$",                     "Changed quiz status"),
            r("PUT",    ".*/quiz/\\d+/email-report$",               "Changed quiz result-email option"),
            r("PUT",    ".*/save-review$",                          "Reviewed student script"),
            r("PUT",    ".*/report-email-setting$",                 "Changed result-email setting"),
            // University structure & accounts (Super Admin)
            r("POST",   ".*/super-admin/departments$",              "Created department"),
            r("PUT",    ".*/super-admin/departments/\\d+$",         "Updated department"),
            r("DELETE", ".*/super-admin/departments/\\d+$",         "Deleted department"),
            r("POST",   ".*/super-admin/programs$",                 "Created program"),
            r("PUT",    ".*/super-admin/programs/\\d+$",            "Updated program"),
            r("DELETE", ".*/super-admin/programs/\\d+$",            "Deleted program"),
            r("PATCH",  ".*/super-admin/programs/\\d+/toggle$",     "Enabled/disabled program"),
            r("POST",   ".*/super-admin/register/hod$",             "Created HOD account"),
            r("PUT",    ".*/super-admin/admin/\\d+$",               "Updated HOD account"),
            r("DELETE", ".*/super-admin/admin/\\d+$",               "Deleted HOD account"),
            r("PUT",    ".*/super-admin/settings$",                 "Changed system settings"),
            // Fees
            r("PUT",    ".*/super-admin/fees/schedules$",           "Set programme fee"),
            r("DELETE", ".*/super-admin/fees/schedules/\\d+$",      "Removed programme fee"),
            r("POST",   ".*/super-admin/fees/schedules/copy$",      "Copied fees from another session"),
            r("POST",   ".*/super-admin/fees/payments/manual$",     "Recorded fee payment"),
            r("POST",   ".*/super-admin/fees/payments/\\d+/void$",  "Cancelled fee payment"),
            r("POST",   ".*/super-admin/fees/payments/\\d+/recheck$", "Re-checked payment with Paystack"),
            // Question bank, re-marks
            r("POST",   ".*/api/question-bank/course/\\d+$",        "Added question to bank"),
            r("PUT",    ".*/api/question-bank/\\d+$",               "Edited bank question"),
            r("DELETE", ".*/api/question-bank/\\d+$",               "Deleted bank question"),
            r("POST",   ".*/api/question-bank/import/quiz/\\d+$",   "Imported quiz questions into bank"),
            r("POST",   ".*/api/question-bank/draw/quiz/\\d+$",     "Drew bank questions into quiz"),
            r("POST",   ".*/api/remarks/\\d+/respond$",             "Answered re-mark request"),
            // Academic core
            r("POST",   ".*/api/academic/sessions$",                "Created academic session"),
            r("PUT",    ".*/api/academic/sessions/\\d+$",           "Updated academic session"),
            r("POST",   ".*/api/academic/sessions/\\d+/current$",   "Changed current academic session"),
            r("DELETE", ".*/api/academic/sessions/\\d+$",           "Deleted academic session"),
            r("PUT",    ".*/api/academic/grading$",                 "Changed grading scale / promotion rules"),
            r("POST",   ".*/api/academic/grading/recalculate$",     "Recalculated grades"),
            r("POST",   ".*/api/academic/grading/presets$",         "Saved grading preset"),
            r("DELETE", ".*/api/academic/grading/presets/[0-9]+$",  "Deleted grading preset"),
            // Feature controls
            r("PUT",    ".*/api/features/[A-Z_]+/departments/\\d+$", "Changed department feature setting"),
            r("PUT",    ".*/api/features/[A-Z_]+$",               "Changed system-wide feature switch"),
            // Admin productivity
            r("POST",   ".*/api/admin-tools/import/students$",      "Imported students"),
            r("POST",   ".*/api/admin-tools/import/lecturers$",     "Imported lecturers"),
            r("POST",   ".*/api/admin-tools/import/courses$",       "Imported courses"),
            r("POST",   ".*/api/admin-tools/bulk-enroll$",          "Bulk-enrolled students"),
            r("POST",   ".*/api/accounts/\\d+/deactivate$",         "Deactivated account"),
            r("POST",   ".*/api/accounts/\\d+/reactivate$",         "Reactivated account"),
            r("DELETE", ".*/v1/auth/student/\\d+$",                 "Deleted student"),
            r("DELETE", ".*/v1/auth/lecturer/\\d+$",                "Deleted lecturer"),
            // Announcements
            r("POST",   ".*/api/announcements$",                    "Posted announcement"),
            r("DELETE", ".*/api/announcements/\\d+$",               "Deleted announcement")
    );

    private static final Pattern FIRST_ID = Pattern.compile("/(\\d+)(/|$)");

    @Autowired private AuditService auditService;
    @Autowired private CurrentUserService currentUserService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        if (!MUTATING.contains(method)) return;
        String path = request.getRequestURI();
        if (IGNORED.stream().anyMatch(p -> p.matcher(path).matches())) return;

        Optional<User> actor = currentUserService.current();
        if (actor.isEmpty() || !AUDITED_ROLES.contains(actor.get().getRole())) return;

        String action = RULES.stream()
                .filter(rule -> rule.method().equals(method) && rule.path().matcher(path).matches())
                .map(Rule::action).findFirst()
                .orElse(method + " " + path);

        Matcher m = FIRST_ID.matcher(path);
        String entityId = m.find() ? m.group(1) : null;

        int status = ex != null ? 500 : response.getStatus();
        // Controllers can attach extra context with request.setAttribute(AUDIT_DETAILS, "...")
        Object extra = request.getAttribute(AUDIT_DETAILS);
        String details = extra != null ? extra.toString() : request.getQueryString();
        auditService.record(actor.get(), action, method, path, entityId, details, status, clientIp(request));
    }

    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
