package com.exam.config;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Who may call what. Evaluated top to bottom; the first matching rule wins.
 * <p>
 * Only sign-in, sign-up, password reset and the few pages shown before sign-in are public.
 * Everything else needs a signed-in user, and staff / admin endpoints need the right role.
 * Ownership (e.g. "a lecturer only manages their own quizzes") is enforced in
 * {@link QuizOwnershipInterceptor} and in the services.
 */
public final class EndpointRules {

    private EndpointRules() {}

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String[] ADMINS = {"SUPER_ADMIN", "ADMIN"};
    public static final String[] STAFF = {"SUPER_ADMIN", "ADMIN", "LECTURER"};
    public static final String DEVELOPER = "DEVELOPER";
    /** Every role that uses the school system itself; the developer only has their own area. */
    public static final String[] SYSTEM_USERS = {"SUPER_ADMIN", "ADMIN", "LECTURER", "NORMAL"};

    private static final String A = "/api/v1/auth";

    private static String[] p(String... paths) {
        String[] out = new String[paths.length];
        for (int i = 0; i < paths.length; i++) out[i] = A + paths[i];
        return out;
    }

    public static void apply(AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
            // ── Infrastructure ───────────────────────────────────────────────
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers("/error", "/token-info").permitAll()

            // ── Public: sign-in, sign-up, password reset, pre-login pages ────
            .requestMatchers(HttpMethod.POST, p("/authenticate", "/register", "/logout",
                    "/forgotten-password", "/forgotten-password/**", "/reset-password", "/reset-password-with-token")).permitAll()
            .requestMatchers(HttpMethod.GET, p("/validate-reset-token")).permitAll()
            // Developer sign-in by emailed code, and the status check uptime monitors call (both rate-limited)
            .requestMatchers(HttpMethod.POST, p("/developer/request-code", "/developer/verify")).permitAll()
            .requestMatchers(HttpMethod.GET, p("/status")).permitAll()
            // First Super Admin only; the controller refuses once one exists (unless a Super Admin calls it)
            .requestMatchers(HttpMethod.POST, p("/register/super-admin")).permitAll()
            .requestMatchers(HttpMethod.GET, p("/programs/my-department")).authenticated()
            .requestMatchers(HttpMethod.GET, p("/programs", "/programs/*", "/programs/department/*", "/departments")).permitAll()
            .requestMatchers(HttpMethod.GET, p("/quiz/*/public-summary", "/public-settings", "/institution", "/institution/logo")).permitAll()
            // Anyone holding a printed transcript / report card can check its code (rate-limited)
            .requestMatchers(HttpMethod.GET, p("/verify/*")).permitAll()
            // Question images are loaded by <img> tags, which can't send a token; ids are random UUIDs
            .requestMatchers(HttpMethod.GET, p("/question-images/**")).permitAll()
            // Paystack calls this server-to-server; FeePaymentService rejects it unless the signature matches
            .requestMatchers(HttpMethod.POST, "/api/payments/paystack/webhook").permitAll()

            // ── Developer: system mode, health, errors ───────────────────────
            .requestMatchers("/api/v1/developer/**").hasAuthority(DEVELOPER)
            // Things every signed-in person (the developer included) needs
            .requestMatchers(HttpMethod.GET, p("/current-user", "/feature-flags")).authenticated()
            .requestMatchers(HttpMethod.POST, p("/client-errors")).authenticated()

            // ── Super Admin ──────────────────────────────────────────────────
            .requestMatchers("/api/v1/super-admin/**").hasAuthority(SUPER_ADMIN)
            .requestMatchers(HttpMethod.POST, p("/register/admin")).hasAuthority(SUPER_ADMIN)
            .requestMatchers(p("/quizGPT/debug/**", "/quizGPT/info")).hasAuthority(SUPER_ADMIN)
            .requestMatchers(HttpMethod.PUT, p("/changePassword")).hasAuthority(SUPER_ADMIN)   // sets another user's password by username

            // ── Super Admin + HODs ───────────────────────────────────────────
            .requestMatchers(HttpMethod.POST, p("/register/lecturer", "/add", "/sendMail", "/sendMail2", "/sendMailf", "/sms/**")).hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.POST, A).hasAnyAuthority(ADMINS)                                   // save/update lecturer
            .requestMatchers(p("/admin/**")).hasAnyAuthority(ADMINS)
            .requestMatchers(p("/users", "/all/lecturers", "/all/students", "/lecturerbyId/*", "/studentbyId/*",
                    "/students/counts", "/lecturers/counts", "/admins/counts", "/lecturers/by-department", "/getCategories")).hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.PUT, p("/update/lecturer/*", "/update/student/*", "/category/admin/updateCategory/*",
                    "/category/updateCategory", "/courses/*/assign/*", "/*/unassign", "/report-email-setting")).hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.DELETE, p("/lecturer/*", "/student/*", "/category/*")).hasAnyAuthority(ADMINS)
            .requestMatchers("/api/analytics/**", "/api/admin-tools/**", "/api/accounts/**", "/api/features/**").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.POST, "/api/announcements").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.DELETE, "/api/announcements/*").hasAnyAuthority(ADMINS)
            .requestMatchers("/api/announcements/manage").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.GET, "/api/academic/students/**", "/api/academic/promotion-preview").hasAnyAuthority(ADMINS)

            // ── Staff (Super Admin, HODs, lecturers): authoring, marking, review ─
            .requestMatchers(HttpMethod.POST, p("/question/add", "/upload/*", "/theoryquestion/add", "/theoryupload/*",
                    "/numberOfTheoryQuestion/add", "/addQuiz", "/lecturer/addQuiz", "/user/addQuiz",
                    "/lecturer/addCategory", "/user/addCategory", "/question-images/upload",
                    "/quizGPT/evaluate-single", "/quizEval", "/gradeSubjective", "/gemini-data")).hasAnyAuthority(STAFF)
            .requestMatchers(HttpMethod.PUT, p("/question/updateQuestions", "/theoryquestion/updateQuestions",
                    "/numberOfTheoryQuestion/update", "/update-compulsory/**", "/update", "/quiz/status/*",
                    "/quiz/*/email-report", "/save-review", "/addtheoryMark")).hasAnyAuthority(STAFF)
            .requestMatchers(HttpMethod.DELETE, p("/question/*", "/theoryquestion/*", "/delete/quiz/*")).hasAnyAuthority(STAFF)
            .requestMatchers(HttpMethod.GET, p("/question/*", "/questionAdmin/**", "/questions/quiz/all/**", "/questionSSS/**",
                    "/random-records", "/theoryquestion/*", "/theoryquestions/**", "/user/getQuiz", "/quiz/category/**",
                    "/category/taken/**", "/getReport", "/getReport/*", "/getReports/**", "/my-students-reports",
                    "/my/quiz-titles/reports", "/quiz-results/quiz/**", "/quiz-results/report/**", "/categoriesForUser",
                    "/category/my-courses-with-quizzes", "/category/lecturer/**", "/quizGPT/health")).hasAnyAuthority(STAFF)
            .requestMatchers(p("/quiz-attempts/quiz/**", "/llm/**")).hasAnyAuthority(STAFF)
            .requestMatchers("/api/question-bank/**", "/api/remarks/manage", "/api/remarks/*/respond").hasAnyAuthority(STAFF)
            .requestMatchers(HttpMethod.GET, "/api/proctoring/quiz/**").hasAnyAuthority(STAFF)

            // ── Marks sheets: admins run the workflow, staff enter marks, students read their own ─
            .requestMatchers(HttpMethod.POST, "/api/marks/sheet/activate", "/api/marks/sheet/*/publish", "/api/marks/sheet/*/revert",
                    "/api/marks/sheet/*/schedule-publish",
                    "/api/marks/sheet/*/approve", "/api/marks/sheet/*/enroll-students").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.PUT, "/api/marks/sheet/*").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.DELETE, "/api/marks/sheet/*", "/api/marks/sheet/*/schedule-publish").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.GET, "/api/marks/sheet/all").hasAnyAuthority(ADMINS)
            .requestMatchers(HttpMethod.POST, "/api/marks/sheet/*/save", "/api/marks/sheet/*/submit",
                    "/api/marks/sheet/*/sync-marks/**", "/api/marks/sheet/*/sections").hasAnyAuthority(STAFF)
            .requestMatchers(HttpMethod.DELETE, "/api/marks/sheet/*/sections/*").hasAnyAuthority(STAFF)
            .requestMatchers("/api/marks/sheet/*/term-remarks").hasAnyAuthority(STAFF)   // class teacher / HOD checked in the service
            .requestMatchers(HttpMethod.GET, "/api/marks/sheet/my-sheets", "/api/marks/sheet/*").hasAnyAuthority(STAFF)

            // ── Students' own fees and online payment (Super Admin's side is under /super-admin) ─
            .requestMatchers("/api/fees/**").hasAuthority("NORMAL")

            // ── Everything else (students' exam flow, own results, profile …): any signed-in user.
            //    Services check that students only reach their own data and quizzes they may take.
            .requestMatchers(A + "/**").hasAnyAuthority(SYSTEM_USERS)
            .anyRequest().hasAnyAuthority(SYSTEM_USERS);
    }
}
