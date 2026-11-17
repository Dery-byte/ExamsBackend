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
 * The developer's Course Materials switch on the whole application: off by default (every
 * /api/materials request refused, even signed download links), only the developer switches it,
 * and once on, staff and students reach only their own side.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:materialsmodule;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.materials.dir=${java.io.tmpdir}/exam-materials-it",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class MaterialsModuleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SystemSettingService settings;
    @Autowired com.exam.repository.DeveloperEmailRepository developerEmails;

    private User student, lecturer, superAdmin, developer;

    @BeforeEach
    void setUp() {
        String tag = String.valueOf(System.nanoTime());
        student = save("s" + tag, Role.NORMAL);
        lecturer = save("l" + tag, Role.LECTURER);
        superAdmin = save("a" + tag, Role.SUPER_ADMIN);
        developer = save("d" + tag, Role.DEVELOPER);
        var row = new com.exam.model.monitoring.DeveloperEmail();
        row.setEmail(developer.getEmail());
        developerEmails.save(row);
    }

    @AfterEach
    void switchOff() {
        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "false");
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

    private MvcResult call(MockHttpServletRequestBuilder req, User who, String body) throws Exception {
        if (who != null) req = req.with(as(who));
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static boolean off(MvcResult r) throws Exception {
        return r.getResponse().getStatus() == 403 && r.getResponse().getContentAsString().contains("MATERIALS_OFF");
    }

    @Test
    void offByDefaultAndOnlyTheDeveloperSwitchesItOn() throws Exception {
        assertThat(off(call(get("/api/materials/student/courses"), student, "{}"))).isTrue();
        assertThat(off(call(get("/api/materials/manage/courses"), lecturer, "{}"))).isTrue();
        assertThat(off(call(get("/api/materials/download/abc?u=1&e=1&i=0&s=x"), null, "{}"))).isTrue();
        assertThat(call(get("/api/v1/auth/feature-flags"), student, "{}").getResponse().getContentAsString()).contains("\"materials\":false");

        assertThat(call(put("/api/v1/developer/modules"), superAdmin, "{\"materials\":true}").getResponse().getStatus()).isEqualTo(403);
        MvcResult on = call(put("/api/v1/developer/modules"), developer, "{\"materials\":true,\"materialsMaxFileMb\":30}");
        assertThat(on.getResponse().getStatus()).isEqualTo(200);
        assertThat(on.getResponse().getContentAsString()).contains("\"materials\":true").contains("\"materialsMaxFileMb\":30");

        // On: each side reaches its own endpoints only
        assertThat(call(get("/api/materials/student/courses"), student, "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/materials/manage/courses"), lecturer, "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/materials/manage/courses"), student, "{}").getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/materials/student/courses"), lecturer, "{}").getResponse().getStatus()).isEqualTo(403);
        // A made-up download link is refused without signing in
        assertThat(call(get("/api/materials/download/abc?u=1&e=1&i=0&s=x"), null, "{}").getResponse().getStatus()).isEqualTo(403);
        // Term dates: staff read, only the Super Admin sets
        assertThat(call(get("/api/academic/terms"), lecturer, "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(call(put("/api/academic/terms/1"), lecturer, "{\"startDate\":\"2026-09-07\",\"weeks\":13}").getResponse().getStatus()).isEqualTo(403);
        MvcResult set = call(put("/api/academic/terms/1"), superAdmin, "{\"startDate\":\"2026-09-07\",\"weeks\":13}");
        assertThat(set.getResponse().getStatus()).isEqualTo(200);
        assertThat(set.getResponse().getContentAsString()).contains("2026-09-07");
    }

    @Autowired com.exam.repository.DepartmentRepository departments;
    @Autowired com.exam.repository.ProgramRepository programs;
    @Autowired com.exam.repository.CategoryRepository categories;
    @Autowired com.exam.repository.Registered_coursesRepository registrations;
    @Autowired com.exam.service.academic.AcademicSessionService academicSessions;
    @Autowired com.exam.service.materials.MaterialService materialService;

    /** A week's ZIP and a single file through the real HTTP layer (signed links, no sign-in header). */
    @Test
    void weekZipAndFileDownloadsWork() throws Exception {
        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "true");
        String tag = String.valueOf(System.nanoTime());
        var dept = new com.exam.model.exam.Department();
        dept.setName("Dept " + tag);
        dept.setCode("D" + tag.substring(tag.length() - 6));
        dept = departments.save(dept);
        var prog = new com.exam.model.exam.Program();
        prog.setName("Prog " + tag);
        prog.setCode("P" + tag.substring(tag.length() - 6));
        prog.setDurationYears(4);
        prog.setDepartment(dept);
        prog.setEnabled(true);
        prog = programs.save(prog);
        var course = new com.exam.model.exam.Category();
        course.setTitle("Zip course");
        course.setCourseCode("ZIP " + tag.substring(tag.length() - 4));
        course.setLevel("100");
        course.setSemester(1);
        course.setPrograms(new java.util.HashSet<>(java.util.Set.of(prog)));
        course.setUser(lecturer);
        course = categories.save(course);
        student.setProgram(prog);
        student = users.save(student);
        var reg = new com.exam.model.exam.Registered_courses(null, new java.util.Date(), course, student);
        reg.setSession(academicSessions.current());
        registrations.save(reg);

        byte[] pdf = "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        materialService.create(lecturer, course.getCid(), new com.exam.service.materials.MaterialService.MaterialRequest(1, "NOTE", "Week one notes",
                null, null, "VISIBLE", null, null, null, null, null, null, null, null),
                java.util.List.of(new org.springframework.mock.web.MockMultipartFile("files", "notes.pdf", "application/pdf", pdf)));

        // ZIP, for the student and for the lecturer: the body is streamed, so finish the async dispatch
        for (User who : java.util.List.of(student, lecturer)) {
            String url = (String) materialService.zipLink(who, course.getCid(), 1).get("url");
            MvcResult started = mvc.perform(get("/api" + url)).andReturn();
            MvcResult done = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started)).andReturn();
            assertThat(done.getResponse().getStatus()).isEqualTo(200);
            assertThat(done.getResponse().getContentType()).isEqualTo("application/zip");
            byte[] zip = done.getResponse().getContentAsByteArray();
            assertThat(new String(zip, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
            try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                var entry = in.getNextEntry();
                assertThat(entry.getName()).isEqualTo("Week one notes/notes.pdf");
                assertThat(in.readAllBytes()).isEqualTo(pdf);
            }
        }
        // A tampered ZIP link is refused with a readable message, not a 500
        String url = (String) materialService.zipLink(student, course.getCid(), 1).get("url");
        MvcResult bad = mvc.perform(get("/api" + url.replaceAll("s=[^&]+", "s=tampered"))).andReturn();
        if (bad.getRequest().isAsyncStarted())
            bad = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(bad)).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(403);
        assertThat(bad.getResponse().getContentAsString()).contains("expired");

        // A single file
        var week1 = (java.util.Map<?, ?>) ((java.util.List<?>) materialService.manageSpace(lecturer, course.getCid()).get("weeks")).get(1);
        var item = (java.util.Map<?, ?>) ((java.util.List<?>) week1.get("items")).get(0);
        String fileId = (String) ((java.util.Map<?, ?>) ((java.util.List<?>) item.get("files")).get(0)).get("id");
        String fileUrl = (String) materialService.studentFileLink(student, fileId, false).get("url");
        MvcResult file = mvc.perform(get("/api" + fileUrl)).andReturn();
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        assertThat(file.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
    }
}
