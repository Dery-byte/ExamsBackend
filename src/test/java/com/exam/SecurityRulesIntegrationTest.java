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
