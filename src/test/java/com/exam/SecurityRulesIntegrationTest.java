package com.exam;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Boots the whole application (in-memory H2) and checks the access rules end to end:
 * public endpoints stay public, everything else needs sign-in, and each role is kept to its own area.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:security;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Dummy values for the external services' required settings (nothing is called in these tests)
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class SecurityRulesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private User student, other, lecturer, hod, superAdmin, developer;
    @Autowired com.exam.service.monitoring.ErrorMonitorService errorMonitor;
    @Autowired com.exam.repository.ErrorEventRepository errorEvents;
    @Autowired com.exam.repository.DeveloperEmailRepository developerEmails;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        student = save("stu", Role.NORMAL);
        other = save("stu2", Role.NORMAL);
        lecturer = save("lec", Role.LECTURER);
        hod = save("hod", Role.ADMIN);
        superAdmin = save("sa", Role.SUPER_ADMIN);
        developer = save("dev", Role.DEVELOPER);
        developerEmails.deleteAll();
        var row = new com.exam.model.monitoring.DeveloperEmail();
        row.setEmail("DEV@example.com");   // matched case-insensitively
        developerEmails.save(row);
    }

    private User save(String name, Role role) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setFirstname(name);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    private static RequestPostProcessor as(User u) {
        return user(u.getUsername()).authorities(new SimpleGrantedAuthority(u.getRole().name()));
    }

    private int status(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
    }

    /** Allowed = passed security (the controller may still reject the empty test body). */
    private static boolean allowed(int s) { return s != 401 && s != 403; }

    private int json(MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private String body(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse().getContentAsString();
    }

    @Test
    void publicEndpointsNeedNoSignIn() throws Exception {
        assertThat(status(get("/api/v1/auth/programs"))).isEqualTo(200);
        assertThat(status(get("/api/v1/auth/departments"))).isEqualTo(200);
        assertThat(allowed(status(post("/api/v1/auth/forgotten-password")))).isTrue();
    }

    @Test
    void everythingElseRequiresSignIn() throws Exception {
        for (var req : new MockHttpServletRequestBuilder[]{
                get("/api/v1/auth/users"), get("/api/v1/auth/getQuizzes"), post("/api/v1/auth/add"),
                delete("/api/v1/auth/student/1"), get("/api/v1/auth/admin/students"), put("/api/v1/auth/save-review"),
                post("/api/v1/auth/register/admin"), get("/api/marks/sheet/all"), get("/api/notifications")}) {
            assertThat(status(req)).as(req.toString()).isEqualTo(401);
        }
    }

    @Test
    void studentsAreKeptOutOfStaffAndAdminAreas() throws Exception {
        assertThat(status(get("/api/v1/auth/users").with(as(student)))).isEqualTo(403);
        assertThat(status(put("/api/v1/auth/save-review").with(as(student)))).isEqualTo(403);
        assertThat(status(post("/api/v1/auth/question/add").with(as(student)))).isEqualTo(403);
        assertThat(status(get("/api/v1/auth/questionAdmin/quiz/all/1").with(as(student)))).isEqualTo(403);
        assertThat(status(delete("/api/v1/auth/delete/quiz/1").with(as(student)))).isEqualTo(403);
        assertThat(status(get("/api/marks/sheet/1").with(as(student)))).isEqualTo(403);
        assertThat(status(post("/api/v1/auth/register/lecturer").with(as(student)))).isEqualTo(403);
        // their own exam flow is open
        assertThat(allowed(status(get("/api/v1/auth/getRegCourses").with(as(student))))).isTrue();
        assertThat(allowed(status(get("/api/v1/auth/current-user").with(as(student))))).isTrue();
    }

    @Test
    void studentsOnlySeeTheirOwnResults() throws Exception {
        assertThat(allowed(status(get("/api/v1/auth/getReportsByUser/" + student.getId()).with(as(student))))).isTrue();
        assertThat(status(get("/api/v1/auth/getReportsByUser/" + other.getId()).with(as(student)))).isEqualTo(403);
        assertThat(status(get("/api/v1/auth/answers/by-user-quiz/" + other.getId() + "/1").with(as(student)))).isEqualTo(403);
    }

    @Test
    void lecturersAuthorButDontAdminister() throws Exception {
        assertThat(allowed(status(get("/api/v1/auth/user/getQuiz").with(as(lecturer))))).isTrue();
        assertThat(status(get("/api/v1/auth/admin/students").with(as(lecturer)))).isEqualTo(403);
        assertThat(status(delete("/api/v1/auth/student/" + student.getId()).with(as(lecturer)))).isEqualTo(403);
        assertThat(status(get("/api/marks/sheet/all").with(as(lecturer)))).isEqualTo(403);
        assertThat(status(get("/api/v1/super-admin/settings").with(as(lecturer)))).isEqualTo(403);
    }

    @Test
    void hodsAdministerButArentSuperAdmins() throws Exception {
        assertThat(allowed(status(get("/api/marks/sheet/all").with(as(hod))))).isTrue();
        assertThat(allowed(status(get("/api/v1/auth/users").with(as(hod))))).isTrue();
        assertThat(status(get("/api/v1/super-admin/settings").with(as(hod)))).isEqualTo(403);
        assertThat(status(post("/api/v1/auth/register/admin").with(as(hod)))).isEqualTo(403);
    }

    @Autowired com.exam.service.SystemSettingService settings;

    @Test
    void feesAreSuperAdminManagedAndStudentPaid() throws Exception {
        // Fee set-up and the payments register: Super Admin only
        for (User u : new User[]{student, lecturer, hod}) {
            assertThat(status(get("/api/v1/super-admin/fees/overview").with(as(u)))).as(u.getUsername()).isEqualTo(403);
            assertThat(status(put("/api/v1/super-admin/fees/schedules").with(as(u)))).as(u.getUsername()).isEqualTo(403);
            assertThat(status(post("/api/v1/super-admin/fees/payments/manual").with(as(u)))).as(u.getUsername()).isEqualTo(403);
        }
        assertThat(allowed(status(get("/api/v1/super-admin/fees/overview").with(as(superAdmin))))).isTrue();
        // Paying: students only
        assertThat(status(get("/api/fees/me"))).isEqualTo(401);
        for (User u : new User[]{lecturer, hod, superAdmin}) {
            assertThat(status(post("/api/fees/pay").with(as(u)))).as(u.getUsername()).isEqualTo(403);
        }
        // Hidden until the Super Admin switches fees on for students
        assertThat(status(get("/api/fees/me").with(as(student)))).isEqualTo(403);
        settings.updateSetting(com.exam.service.SystemSettingService.FEES_VISIBLE_STUDENT, "true");
        try {
            assertThat(status(get("/api/fees/me").with(as(student)))).isEqualTo(200);
        } finally {
            settings.updateSetting(com.exam.service.SystemSettingService.FEES_VISIBLE_STUDENT, "false");
        }
        // The webhook passes security but is refused without Paystack's signature
        int webhook = mvc.perform(post("/api/payments/paystack/webhook").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"charge.success\",\"data\":{\"reference\":\"FEE-1\"}}")).andReturn().getResponse().getStatus();
        assertThat(webhook).isEqualTo(401);
    }

    @Test
    void superAdminSignUpIsClosedOnceOneExists() throws Exception {
        String body = "{\"username\":\"intruder\",\"email\":\"i@example.com\",\"password\":\"secret123\",\"firstname\":\"I\",\"lastname\":\"X\"}";
        int anon = mvc.perform(post("/api/v1/auth/register/super-admin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(anon).isEqualTo(403);
        assertThat(users.findByUsername("intruder")).isEmpty();
    }

    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String signIn(String username, String password) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("token").asText();
    }

    @Test
    void signingInAgainEndsTheEarlierSession() throws Exception {
        User u = save("twodevices", Role.NORMAL);
        u.setPassword(passwordEncoder.encode("correct-horse"));
        users.save(u);

        String first = signIn("twodevices", "correct-horse");
        assertThat(mvc.perform(get("/api/v1/auth/current-user").header("Authorization", "Bearer " + first))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        Thread.sleep(1100);   // JWTs issued in the same second are identical; a real second sign-in is never that fast
        String second = signIn("twodevices", "correct-horse");

        assertThat(mvc.perform(get("/api/v1/auth/current-user").header("Authorization", "Bearer " + first))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/auth/current-user").header("Authorization", "Bearer " + second))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Autowired com.exam.repository.DepartmentRepository departments;

    @Test
    void departmentCanHideReportCardsFromItsStudents() throws Exception {
        com.exam.model.exam.Department d = new com.exam.model.exam.Department();
        d.setName("Physics"); d.setCode("PHY");
        d = departments.save(d);
        hod.setDepartment(d);
        users.save(hod);
        student.setDepartment(d);
        users.save(student);
        String url = "/api/features/STUDENT_REPORT_CARD/departments/" + d.getId();
        try {
            assertThat(allowed(status(get("/api/marks/sheet/my-marks/all").with(as(student))))).isTrue();

            assertThat(json(put(url).with(as(hod)), "{\"enabled\":false}")).isEqualTo(200);   // status() would send "{}"
            var refused = mvc.perform(get("/api/marks/sheet/my-marks/all").with(as(student))).andReturn().getResponse();
            assertThat(refused.getStatus()).isEqualTo(403);
            assertThat(refused.getContentAsString()).contains("Student report cards have been turned off by your department.");
            assertThat(status(get("/api/marks/sheet/report-card/all/pdf").with(as(student)))).isEqualTo(403);
            assertThat(mvc.perform(get("/api/v1/auth/feature-flags").with(as(student))).andReturn().getResponse().getContentAsString())
                    .contains("\"STUDENT_REPORT_CARD\":false")
                    .contains("\"STUDENT_TRANSCRIPT\":true");                                 // the transcript switch is separate

            // A student in another department, and the department's staff, are unaffected
            assertThat(allowed(status(get("/api/marks/sheet/my-marks/all").with(as(other))))).isTrue();
            assertThat(allowed(status(get("/api/marks/sheet/all").with(as(hod))))).isTrue();
        } finally {
            json(put(url).with(as(superAdmin)), "{\"enabled\":true}");
        }
        assertThat(allowed(status(get("/api/marks/sheet/my-marks/all").with(as(student))))).isTrue();
    }

    @Test
    void replacedLogoGetsANewUrlSoBrowsersDontShowTheCachedOne() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        try {
            String first = mvc.perform(multipart("/api/v1/super-admin/institution/logo")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", png)).with(as(superAdmin)))
                    .andReturn().getResponse().getContentAsString();
            Thread.sleep(20);
            String second = mvc.perform(multipart("/api/v1/super-admin/institution/logo")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "b.png", "image/png", png)).with(as(superAdmin)))
                    .andReturn().getResponse().getContentAsString();
            assertThat(first).contains("\"hasLogo\":true").containsPattern("\"logoVersion\":[1-9]");
            String v1 = first.replaceAll(".*\"logoVersion\":(\\d+).*", "$1");
            String v2 = second.replaceAll(".*\"logoVersion\":(\\d+).*", "$1");
            assertThat(v2).isNotEqualTo(v1);
            assertThat(mvc.perform(get("/api/v1/auth/institution")).andReturn().getResponse().getContentAsString())
                    .contains("\"logoVersion\":" + v2);

            // Versioned URL may be cached; the bare URL must be revalidated
            assertThat(mvc.perform(get("/api/v1/auth/institution/logo").param("v", v2)).andReturn().getResponse().getHeader("Cache-Control"))
                    .contains("max-age");
            assertThat(mvc.perform(get("/api/v1/auth/institution/logo")).andReturn().getResponse().getHeader("Cache-Control"))
                    .contains("no-cache");
        } finally {
            mvc.perform(delete("/api/v1/super-admin/institution/logo").with(as(superAdmin)));
        }
        assertThat(mvc.perform(get("/api/v1/auth/institution")).andReturn().getResponse().getContentAsString())
                .contains("\"hasLogo\":false").contains("\"logoVersion\":0");
    }

    @Test
    void loginVerifyLinkFollowsTheSuperAdminSwitch() throws Exception {
        String key = com.exam.service.SystemSettingService.LOGIN_VERIFY_LINK_VISIBLE;
        try {
            assertThat(mvc.perform(get("/api/v1/auth/public-settings")).andReturn().getResponse().getContentAsString())
                    .contains("\"showVerifyLink\":true");
            assertThat(json(put("/api/v1/super-admin/settings").with(as(hod)), "{\"" + key + "\":\"false\"}")).isEqualTo(403);
            assertThat(json(put("/api/v1/super-admin/settings").with(as(superAdmin)), "{\"" + key + "\":\"false\"}")).isEqualTo(200);
            assertThat(mvc.perform(get("/api/v1/auth/public-settings")).andReturn().getResponse().getContentAsString())
                    .contains("\"showVerifyLink\":false");
        } finally {
            settings.updateSetting(key, "true");
        }
    }

    @Test
    void featureSwitchesAreEnforcedEndToEnd() throws Exception {
        String signup = "{\"username\":\"newbie\",\"email\":\"n@example.com\",\"password\":\"secret123\",\"firstname\":\"N\",\"lastname\":\"B\"}";
        try {
            // Super Admin closes self sign-up: anonymous sign-up is refused, the public setting reflects it
            assertThat(status(put("/api/features/STUDENT_SELF_SIGNUP").with(as(superAdmin)).content("{\"enabled\":false}"))).isEqualTo(200);
            assertThat(mvc.perform(get("/api/v1/auth/public-settings")).andReturn().getResponse().getContentAsString())
                    .contains("\"studentSelfSignup\":false");
            int anon = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(signup))
                    .andReturn().getResponse().getStatus();
            assertThat(anon).isEqualTo(403);
            assertThat(users.findByUsername("newbie")).isEmpty();

            // HODs can't touch system-wide switches
            assertThat(status(put("/api/features/STUDENT_SELF_SIGNUP").with(as(hod)).content("{\"enabled\":true}"))).isEqualTo(403);
        } finally {
            status(put("/api/features/STUDENT_SELF_SIGNUP").with(as(superAdmin)).content("{\"enabled\":true}"));
        }

        // An HOD switches a department-level feature off for their own department only
        com.exam.model.exam.Department d = new com.exam.model.exam.Department();
        d.setName("Maths"); d.setCode("MTH");
        d = departments.save(d);
        hod.setDepartment(d);
        users.save(hod);
        assertThat(status(put("/api/features/STUDENT_TIMETABLE/departments/" + d.getId()).with(as(hod)).content("{\"enabled\":false}"))).isEqualTo(200);
        assertThat(status(get("/api/features").with(as(hod)))).isEqualTo(200);
        assertThat(status(get("/api/features").with(as(student)))).isEqualTo(403);
    }

    @Test
    void repeatedWrongPasswordsLockTheAccount() throws Exception {
        String body = "{\"username\":\"stu\",\"password\":\"wrong\"}";
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
    }

    @Test
    void theDeveloperOnlyReachesTheirOwnArea() throws Exception {
        // sign-in by code and the status check are public
        assertThat(allowed(status(post("/api/v1/auth/developer/request-code")))).isTrue();
        assertThat(status(get("/api/v1/auth/status"))).isEqualTo(200);
        assertThat(status(get("/api/v1/developer/health"))).isEqualTo(401);

        assertThat(status(get("/api/v1/developer/health").with(as(developer)))).isEqualTo(200);
        assertThat(status(get("/api/v1/developer/mode").with(as(developer)))).isEqualTo(200);
        assertThat(status(get("/api/v1/developer/errors").with(as(developer)))).isEqualTo(200);
        assertThat(allowed(status(get("/api/v1/auth/current-user").with(as(developer))))).isTrue();
        // …but nothing of the school system itself
        assertThat(status(get("/api/v1/auth/getRegCourses").with(as(developer)))).isEqualTo(403);
        assertThat(status(get("/api/v1/auth/users").with(as(developer)))).isEqualTo(403);
        assertThat(status(get("/api/marks/sheet/all").with(as(developer)))).isEqualTo(403);
        assertThat(status(get("/api/notifications").with(as(developer)))).isEqualTo(403);
        assertThat(status(get("/api/v1/super-admin/settings").with(as(developer)))).isEqualTo(403);

        assertThat(status(get("/api/v1/developer/developers").with(as(developer)))).isEqualTo(200);
        // Developers are only ever added in the database: there is no endpoint for it, for anyone
        assertThat(status(post("/api/v1/developer/developers").with(as(developer)))).isIn(404, 405);
        assertThat(status(post("/api/v1/super-admin/developers/first").with(as(superAdmin)))).isEqualTo(404);

        // and nobody else reaches the developer's area, not even the Super Admin
        assertThat(status(get("/api/v1/developer/health").with(as(superAdmin)))).isEqualTo(403);
        assertThat(status(put("/api/v1/developer/mode").with(as(superAdmin)).content("{\"mode\":\"SHS\"}"))).isEqualTo(403);
    }

    @Test
    void theDeveloperDecidesWhetherTheSuperAdminSeesTheAuditLog() throws Exception {
        String key = com.exam.service.SystemSettingService.AUDIT_LOG_VISIBLE_SUPER_ADMIN;
        try {
            // On by default; the developer always reads it
            assertThat(status(get("/api/v1/super-admin/audit-logs").with(as(superAdmin)))).isEqualTo(200);
            assertThat(status(get("/api/v1/developer/audit-logs").with(as(developer)))).isEqualTo(200);
            assertThat(status(get("/api/v1/developer/audit-logs/actions").with(as(developer)))).isEqualTo(200);
            assertThat(status(get("/api/v1/developer/audit-logs").with(as(superAdmin)))).isEqualTo(403);

            // Only the developer flips the switch: not via its endpoint, nor the Super Admin's settings
            assertThat(json(put("/api/v1/developer/audit-log/access").with(as(superAdmin)), "{\"superAdminVisible\":true}")).isEqualTo(403);
            assertThat(json(put("/api/v1/developer/audit-log/access").with(as(developer)), "{\"superAdminVisible\":false}")).isEqualTo(200);
            assertThat(mvc.perform(get("/api/v1/auth/feature-flags").with(as(superAdmin))).andReturn().getResponse().getContentAsString())
                    .contains("\"auditLogSuperAdmin\":false");
            assertThat(status(get("/api/v1/super-admin/audit-logs").with(as(superAdmin)))).isEqualTo(403);
            assertThat(status(get("/api/v1/super-admin/audit-logs/actions").with(as(superAdmin)))).isEqualTo(403);
            assertThat(json(put("/api/v1/super-admin/settings").with(as(superAdmin)), "{\"" + key + "\":\"true\"}")).isEqualTo(403);
            assertThat(settings.getBooleanSetting(key, true)).isFalse();
            assertThat(status(get("/api/v1/developer/audit-logs").with(as(developer)))).isEqualTo(200);

            assertThat(json(put("/api/v1/developer/audit-log/access").with(as(developer)), "{\"superAdminVisible\":true}")).isEqualTo(200);
            assertThat(status(get("/api/v1/super-admin/audit-logs").with(as(superAdmin)))).isEqualTo(200);
            assertThat(json(put("/api/v1/developer/audit-log/access").with(as(developer)), "{}")).isEqualTo(400);

            // The developer's own changes are logged, but only the developer sees them
            String devAction = "Changed Super Admin audit log access";
            assertThat(body(get("/api/v1/developer/audit-logs").with(as(developer)))).contains(devAction);
            assertThat(body(get("/api/v1/developer/audit-logs/actions").with(as(developer)))).contains(devAction);
            assertThat(body(get("/api/v1/super-admin/audit-logs").with(as(superAdmin)))).doesNotContain(devAction).doesNotContain("DEVELOPER");
            assertThat(body(get("/api/v1/super-admin/audit-logs").param("role", "DEVELOPER").with(as(superAdmin)))).contains("\"totalItems\":0");
            assertThat(body(get("/api/v1/super-admin/audit-logs/actions").with(as(superAdmin)))).doesNotContain(devAction);
        } finally {
            settings.updateSetting(key, "true");
        }
    }

    @Test
    void theDeveloperSwitchesTheSystemMode() throws Exception {
        try {
            assertThat(json(put("/api/v1/developer/mode").with(as(developer)), "{\"mode\":\"SHS\"}")).isEqualTo(200);
            String info = mvc.perform(get("/api/v1/auth/institution")).andReturn().getResponse().getContentAsString();
            assertThat(info).contains("\"mode\":\"SHS\"").contains("\"course\":\"Subject\"").contains("\"level\":\"Form\"");
            assertThat(com.exam.model.academic.SystemMode.current().defaultPeriodsPerLevel()).isEqualTo(3);
            assertThat(json(put("/api/v1/developer/mode").with(as(developer)), "{\"mode\":\"NOPE\"}")).isEqualTo(400);
        } finally {
            json(put("/api/v1/developer/mode").with(as(developer)), "{\"mode\":\"UNIVERSITY\"}");
        }
    }

    @Test
    void repeatedFailuresAreGroupedIntoOneError() {
        var req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/remarks/5/respond");
        var req2 = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/remarks/9/respond");
        errorMonitor.recordRequest(req, 500, new IllegalStateException("force initializing collection loading"));
        errorMonitor.recordRequest(req, 500, new IllegalStateException("counted once per request"));
        errorMonitor.recordRequest(req2, 500, new IllegalStateException("force initializing collection loading"));
        var events = errorEvents.findAll().stream().filter(e -> "POST /api/remarks/{id}/respond".equals(e.getLocation())).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOccurrences()).isEqualTo(2);
        assertThat(events.get(0).isResolved()).isFalse();
    }

    @Test
    void deletingTheDeveloperRowEndsTheirAccess() throws Exception {
        assertThat(status(get("/api/v1/developer/health").with(as(developer)))).isEqualTo(200);
        developerEmails.deleteAll();
        assertThat(status(get("/api/v1/developer/health").with(as(developer)))).isEqualTo(403);
    }
}
