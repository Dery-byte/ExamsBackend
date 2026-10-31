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
    private static final Set<Role> AUDITED_ROLES = Set.of(Role.SUPER_ADMIN, Role.ADMIN, Role.LECTURER, Role.DEVELOPER, Role.ACCOUNTANT);

    /** Noisy or non-business endpoints that are never recorded. */
    private static final List<Pattern> IGNORED = List.of(
            Pattern.compile("/api/notifications.*"),
            Pattern.compile("/api/proctoring/events"),
            // One per student scanned; the record itself says who scanned whom
            Pattern.compile("/api/attendance/manage/sessions/\\d+/scan"),
            // Boarding checks and previews change nothing; the real run is recorded
            Pattern.compile("/api/boarding/manage/(import/[a-z]+/check|auto-allocate/preview|clear/preview)"),
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
            r("PUT",    ".*/developer/audit-log/access$",           "Changed Super Admin audit log access"),
            r("PUT",    ".*/developer/modules$",                    "Changed the modules (Assessments / day and boarding / wallet payments)"),
            r("PUT",    ".*/developer/theme$",                       "Changed the colour theme"),
            r("DELETE", ".*/developer/theme$",                       "Reset the colour theme"),
            r("PUT",    ".*/developer/maintenance$",                 "Changed maintenance mode"),
            r("POST",   ".*/developer/setup/super-admin$",           "Created a Super Admin account"),
            r("POST",   ".*/developer/setup/test-email$",            "Sent a test email"),
            r("POST",   ".*/developer/config/import$",               "Imported system settings"),
            r("POST",   ".*/developer/subscription/key$",            "Installed a subscription key"),
            r("POST",   ".*/developer/subscription/suspend$",        "Suspended the portal (subscription)"),
            r("POST",   ".*/developer/subscription/resume$",         "Lifted the portal suspension"),
            r("PUT",    ".*/developer/subscription/extension$",      "Extended the subscription grace period"),
            r("DELETE", ".*/developer/subscription/extension$",      "Removed the subscription extension"),
            r("PUT",    ".*/developer/subscription/settings$",       "Changed subscription notice settings"),
            r("PUT",    ".*/developer/subscription/vendor$",         "Linked the portal to the vendor portal"),
            r("DELETE", ".*/developer/subscription/vendor$",         "Unlinked the portal from the vendor portal"),
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
            r("PUT",    ".*/super-admin/departments/order$",        "Reordered departments"),
            r("PUT",    ".*/super-admin/programs/order$",           "Reordered programs"),
            r("POST",   ".*/super-admin/register/hod$",             "Created HOD account"),
            r("PUT",    ".*/super-admin/admin/\\d+$",               "Updated HOD account"),
            r("DELETE", ".*/super-admin/admin/\\d+$",               "Deleted HOD account"),
            r("PUT",    ".*/super-admin/settings$",                 "Changed system settings"),
            // Fees
            r("PUT",    ".*/(super-admin|finance)/fees/schedules$",           "Set programme fee"),
            r("DELETE", ".*/(super-admin|finance)/fees/schedules/\\d+$",      "Removed programme fee"),
            r("POST",   ".*/(super-admin|finance)/fees/schedules/copy$",      "Copied fees from another session"),
            r("POST",   ".*/(super-admin|finance)/fees/payments/manual$",     "Recorded fee payment"),
            r("POST",   ".*/(super-admin|finance)/fees/payments/\\d+/void$",  "Cancelled fee payment"),
            r("POST",   ".*/(super-admin|finance)/fees/payments/\\d+/recheck$", "Re-checked payment with Paystack"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/students/\\d+/deposits$", "Recorded a wallet deposit"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/students/\\d+/spend$",    "Paid from a student wallet"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/withdrawals/\\d+/review$",  "Reviewed a wallet withdrawal"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/withdrawals/\\d+/approve$", "Approved a wallet withdrawal"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/withdrawals/\\d+/pay$",     "Paid out a wallet withdrawal"),
            r("POST",   ".*/(super-admin|finance)/fees/wallets/withdrawals/\\d+/reject$",  "Rejected a wallet withdrawal"),
            r("PUT",    ".*/(super-admin|finance)/fees/wallets/settings$",                  "Changed the wallet limit"),
            r("PUT",    ".*/(super-admin|finance)/fees/settings$",      "Changed fee settings"),
            r("PUT",    ".*/(super-admin|finance)/fees/results-holds$", "Changed a results hold rule"),
            r("DELETE", ".*/(super-admin|finance)/fees/results-holds/\\d+$", "Lifted a results hold rule"),
            r("POST",   ".*/(super-admin|finance)/fees/price-items$",         "Added a price-list item"),
            r("PUT",    ".*/(super-admin|finance)/fees/price-items/\\d+$",   "Changed a price-list item"),
            r("POST",   ".*/(super-admin|finance)/fees/price-items/\\d+/active$", "Retired or restored a price-list item"),
            r("POST",   ".*/(super-admin|finance)/fees/bills$",               "Issued a bill"),
            r("POST",   ".*/(super-admin|finance)/fees/bills/\\d+/cancel$",  "Cancelled a bill for a group"),
            r("POST",   ".*/(super-admin|finance)/fees/student-bills/\\d+/cancel$", "Cancelled a student's bill"),
            r("POST",   ".*/(super-admin|finance)/fees/concessions$",          "Granted a fee concession"),
            r("POST",   ".*/(super-admin|finance)/fees/concessions/\\d+/revoke$", "Revoked a fee concession"),
            r("POST",   ".*/(super-admin|finance)/fees/year-end/close$",       "Closed a session's fees"),
            r("POST",   ".*/(super-admin|finance)/fees/year-end/reopen$",      "Reopened a session's fees"),
            r("POST",   ".*/(super-admin|finance)/fees/opening-balances$",     "Imported opening balances"),
            r("PUT",    ".*/(super-admin|finance)/fees/students/\\d+/opening-balance$", "Set an opening balance"),
            r("POST",   ".*/(super-admin|finance)/fees/arrears/\\d+/write-off$", "Wrote off arrears"),
            r("POST",   ".*/(super-admin|finance)/fees/arrears/\\d+/restore$", "Restored written-off arrears"),
            r("POST",   ".*/(super-admin|finance)/fees/refunds$",              "Refunded a student"),
            r("POST",   ".*/(super-admin|finance)/fees/refunds/\\d+/void$",    "Cancelled a refund"),
            r("POST",   ".*/(super-admin|finance)/fees/cash-ups$",             "Handed in a cash-up"),
            r("POST",   ".*/(super-admin|finance)/fees/cash-ups/\\d+/confirm$", "Confirmed a cash-up"),
            r("PUT",    ".*/(super-admin|finance)/fees/stores/items/\\d+$",   "Changed stock keeping of an item"),
            r("POST",   ".*/(super-admin|finance)/fees/stores/items/\\d+/receive$", "Received stock"),
            r("POST",   ".*/(super-admin|finance)/fees/stores/items/\\d+/adjust$", "Corrected a stock count"),
            r("POST",   ".*/(super-admin|finance)/fees/stores/lines/\\d+/hand-over$", "Handed over store items"),
            r("POST",   ".*/(super-admin|finance)/fees/stores/lines/\\d+/take-back$", "Took back store items"),
            r("POST",   ".*/(super-admin|finance)/fees/reminders$",            "Sent fee reminders"),
            r("PUT",    ".*/(super-admin|finance)/fees/reminders/automatic$",  "Changed automatic fee reminders"),
            r("PUT",    ".*/super-admin/finance-staff/settings$",             "Changed accounts office settings"),
            r("POST",   ".*/super-admin/finance-staff$",                "Added an accountant"),
            r("PUT",    ".*/super-admin/finance-staff/\\d+$",          "Changed an accountant"),
            r("POST",   ".*/super-admin/finance-staff/\\d+/reset-password$", "Reset an accountant's password"),
            // Question bank, re-marks
            r("POST",   ".*/api/question-bank/course/\\d+$",        "Added question to bank"),
            r("PUT",    ".*/api/question-bank/\\d+$",               "Edited bank question"),
            r("DELETE", ".*/api/question-bank/\\d+$",               "Deleted bank question"),
            r("POST",   ".*/api/question-bank/import/quiz/\\d+$",   "Imported quiz questions into bank"),
            r("POST",   ".*/api/question-bank/draw/quiz/\\d+$",     "Drew bank questions into quiz"),
            r("POST",   ".*/api/remarks/\\d+/respond$",             "Answered re-mark request"),
            r("POST",   ".*/profile/photo$",                       "Changed their profile photo"),
            r("DELETE", ".*/profile/photo$",                       "Removed their profile photo"),
            // Class attendance
            r("POST",   ".*/api/attendance/manage/sessions$",                          "Started class attendance"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/end$",                 "Ended class attendance"),
            r("DELETE", ".*/api/attendance/manage/sessions/\\d+$",                     "Deleted an attendance session"),
            r("PUT",    ".*/api/attendance/manage/sessions/\\d+/students/\\d+$",       "Marked a student's attendance"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/approve-pending$",     "Approved waiting check-ins"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/students/\\d+/unlock$", "Unlocked a student's check-in"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/students/\\d+/reset-phone$", "Reset a student's attendance phone"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/close-check-in$",      "Closed attendance check-in"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/extend$",              "Reopened attendance check-in"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/second-check$",        "Started the attendance second check"),
            r("DELETE", ".*/api/attendance/manage/sessions/\\d+/second-check$",        "Cancelled the attendance second check"),
            r("PUT",    ".*/api/attendance/manage/sessions/\\d+/headcount$",           "Entered the class headcount"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/recentre$",            "Moved a class's attendance room centre"),
            r("POST",   ".*/api/attendance/manage/sessions/\\d+/helpers$",             "Gave a helper an attendance scanner link"),
            r("DELETE", ".*/api/attendance/manage/sessions/\\d+/helpers/\\d+$",        "Cancelled a helper's scanner link"),
            r("POST",   ".*/api/attendance/manage/classrooms$",                        "Saved a classroom"),
            r("PUT",    ".*/api/attendance/manage/classrooms/\\d+$",                   "Changed a classroom"),
            r("DELETE", ".*/api/attendance/manage/classrooms/\\d+$",                   "Deleted a classroom"),
            r("POST",   ".*/api/attendance/manage/schedules$",                         "Added a class time"),
            r("PUT",    ".*/api/attendance/manage/schedules/\\d+$",                    "Changed a class time"),
            r("DELETE", ".*/api/attendance/manage/schedules/\\d+$",                    "Deleted a class time"),
            r("PUT",    ".*/api/attendance/settings/system$",                          "Changed the default attendance rules"),
            r("PUT",    ".*/api/attendance/settings/departments/\\d+$",                "Changed a department's attendance rules"),
            r("DELETE", ".*/api/attendance/settings/departments/\\d+$",                "Reset a department's attendance rules"),
            // Boarding
            r("POST",   ".*/api/boarding/manage/hostels$",                     "Added a hostel"),
            r("PUT",    ".*/api/boarding/manage/hostels/\\d+$",                "Changed a hostel"),
            r("DELETE", ".*/api/boarding/manage/hostels/\\d+$",                "Deleted or archived a hostel"),
            r("POST",   ".*/api/boarding/manage/hostels/\\d+/restore$",        "Restored a hostel"),
            r("POST",   ".*/api/boarding/manage/hostels/\\d+/blocks$",         "Added a hostel block"),
            r("PUT",    ".*/api/boarding/manage/blocks/\\d+$",                 "Changed a hostel block"),
            r("DELETE", ".*/api/boarding/manage/blocks/\\d+$",                 "Deleted a hostel block"),
            r("POST",   ".*/api/boarding/manage/hostels/\\d+/rooms$",          "Added a hostel room"),
            r("POST",   ".*/api/boarding/manage/hostels/\\d+/rooms/generate$", "Added hostel rooms in bulk"),
            r("PUT",    ".*/api/boarding/manage/rooms/\\d+$",                  "Changed a hostel room"),
            r("DELETE", ".*/api/boarding/manage/rooms/\\d+$",                  "Deleted or archived a hostel room"),
            r("POST",   ".*/api/boarding/manage/allocations$",                 "Gave a student a room"),
            r("POST",   ".*/api/boarding/manage/allocations/\\d+/move$",       "Moved a student to another room"),
            r("POST",   ".*/api/boarding/manage/allocations/swap$",            "Swapped two students' rooms"),
            r("POST",   ".*/api/boarding/manage/allocations/\\d+/vacate$",     "Took a student out of their room"),
            r("PUT",    ".*/api/boarding/manage/students/\\d+$",               "Changed a student's gender / residency"),
            r("POST",   ".*/api/boarding/manage/staff$",                       "Gave boarding duties"),
            r("DELETE", ".*/api/boarding/manage/staff/\\d+$",                  "Removed boarding duties"),
            r("PUT",    ".*/api/boarding/settings$",                           "Changed boarding settings"),
            r("POST",   ".*/api/boarding/manage/import/allocations$",          "Imported a room list"),
            r("POST",   ".*/api/boarding/manage/import/rooms$",                "Imported hostel rooms"),
            r("POST",   ".*/api/boarding/manage/auto-allocate$",               "Auto-allocated rooms"),
            r("POST",   ".*/api/boarding/manage/clear$",                       "Cleared rooms for a new year"),
            // Academic core
            r("POST",   ".*/api/academic/sessions$",                "Created academic session"),
            r("PUT",    ".*/api/academic/sessions/\\d+$",           "Updated academic session"),
            r("POST",   ".*/api/academic/sessions/\\d+/current$",   "Changed current academic session"),
            r("DELETE", ".*/api/academic/sessions/\\d+$",           "Deleted academic session"),
            r("PUT",    ".*/api/academic/grading$",                 "Changed grading scale / promotion rules"),
            r("POST",   ".*/api/academic/classes/\\d+/promote$",    "Promoted a class to its next class"),
            r("POST",   ".*/api/academic/students/\\d+/move$",      "Moved a student to another class"),
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
