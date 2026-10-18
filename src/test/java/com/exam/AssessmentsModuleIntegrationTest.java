package com.exam;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The developer's Assessments switch on the whole application: on by default (nothing changes);
 * off, every quiz endpoint is refused for every role, the flag tells the pages to hide it, quiz
 * reports disappear, and only the developer can flip it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:modules;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class AssessmentsModuleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SystemSettingService settings;

    private User student, lecturer, superAdmin, developer;

    @BeforeEach
    void setUp() {
        String tag = String.valueOf(System.nanoTime());
        student = save("s" + tag, Role.NORMAL);
        lecturer = save("l" + tag, Role.LECTURER);
        superAdmin = save("a" + tag, Role.SUPER_ADMIN);
        developer = save("d" + tag, Role.DEVELOPER);
        var row = new com.exam.model.monitoring.DeveloperEmail();   // developers also need their email on the list
        row.setEmail(developer.getEmail());
        developerEmails.save(row);
    }

    @Autowired com.exam.repository.DeveloperEmailRepository developerEmails;

    @AfterEach
    void switchBackOn() {
        settings.updateSetting(SystemSettingService.MODULE_ASSESSMENTS, "true");
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

    private MvcResult call(MockHttpServletRequestBuilder req, User who) throws Exception {
        if (who != null) req = req.with(as(who));
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    }

    private boolean refusedAsOff(MvcResult r) throws Exception {
        return r.getResponse().getStatus() == 403 && r.getResponse().getContentAsString().contains("ASSESSMENTS_OFF");
    }

    @Test
    void offRefusesTheQuizModuleForEveryRoleAndOnlyTheDeveloperSwitchesIt() throws Exception {
        // On (the default): quiz endpoints work as before
        assertThat(refusedAsOff(call(get("/api/v1/auth/active/quizzes"), student))).isFalse();
        assertThat(call(get("/api/v1/auth/feature-flags"), student).getResponse().getContentAsString()).contains("\"assessments\":true");

        // Only the developer can switch it
        assertThat(call(put("/api/v1/developer/modules").content("{\"assessments\":false}"), superAdmin).getResponse().getStatus()).isEqualTo(403);
        MvcResult off = mvc.perform(put("/api/v1/developer/modules").with(as(developer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"assessments\":false}")).andReturn();
        assertThat(off.getResponse().getStatus()).isEqualTo(200);
        assertThat(off.getResponse().getContentAsString()).contains("\"assessments\":false");

        // Off: every role is refused, whatever the quiz endpoint
        assertThat(refusedAsOff(call(get("/api/v1/auth/active/quizzes"), student))).isTrue();
        assertThat(refusedAsOff(call(post("/api/v1/auth/quiz-attempts/1/begin"), student))).isTrue();
        assertThat(refusedAsOff(call(get("/api/v1/auth/getReportsByUser/" + student.getId()), student))).isTrue();
        assertThat(refusedAsOff(call(post("/api/v1/auth/lecturer/addQuiz"), lecturer))).isTrue();
        assertThat(refusedAsOff(call(get("/api/v1/auth/user/getQuiz"), lecturer))).isTrue();
        assertThat(refusedAsOff(call(get("/api/timetable"), lecturer))).isTrue();
        assertThat(refusedAsOff(call(get("/api/question-bank/courses"), lecturer))).isTrue();
        assertThat(refusedAsOff(call(get("/api/v1/auth/getQuizzes"), superAdmin))).isTrue();
        assertThat(refusedAsOff(call(get("/api/analytics/overview"), superAdmin))).isTrue();
        assertThat(call(get("/api/v1/auth/feature-flags"), student).getResponse().getContentAsString()).contains("\"assessments\":false");

        // Everything else carries on: profile, courses, photos
        assertThat(refusedAsOff(call(get("/api/v1/auth/current-user"), student))).isFalse();
        assertThat(refusedAsOff(call(get("/api/v1/auth/getRegCourses"), student))).isFalse();
        assertThat(refusedAsOff(call(get("/api/v1/auth/question-images/missing.webp"), null))).isFalse();

        // Quiz reports leave the catalogue
        String catalog = call(get("/api/v1/super-admin/reports"), superAdmin).getResponse().getContentAsString();
        assertThat(catalog).doesNotContain("\"quiz-results\"").doesNotContain("\"exam-absentees\"").contains("\"broadsheet\"");

        // The Super Admin's own settings endpoint can't switch it back on either
        assertThat(SystemSettingService.isDeveloperOnly(SystemSettingService.MODULE_ASSESSMENTS)).isTrue();

        // Back on: as before
        mvc.perform(put("/api/v1/developer/modules").with(as(developer)).contentType(MediaType.APPLICATION_JSON).content("{\"assessments\":true}"));
        assertThat(refusedAsOff(call(get("/api/v1/auth/active/quizzes"), student))).isFalse();
    }
}
