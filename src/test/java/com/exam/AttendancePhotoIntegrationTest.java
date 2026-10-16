package com.exam;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.attendance.AttendanceQr;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The whole application on H2: a student uploads a profile photo, the lecturer starts a scanned class,
 * the student shows their code, the lecturer (and a helper with no location) scans it, and the photo
 * comes back with the scan and loads without signing in (as an &lt;img&gt; tag does).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:attphoto;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class AttendancePhotoIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired CategoryRepository categories;
    @Autowired Registered_coursesRepository registrations;
    @Autowired SystemSettingService settings;
    @Autowired AcademicSessionService academicSessions;

    private final ObjectMapper json = new ObjectMapper();
    static final double LAT = 5.6037, LNG = -0.1870;
    private User student, lecturer;
    private Category course;

    @BeforeEach
    void setUp() {
        settings.updateSetting("FEATURE_ATTENDANCE", "true");
        String tag = String.valueOf(System.nanoTime());
        Department d = new Department();
        d.setName("Computing " + tag);
        d.setCode("C" + tag.substring(tag.length() - 6));
        d = departments.save(d);
        Program p = new Program();
        p.setName("Computer Science");
        p.setCode("CS");
        p.setDurationYears(4);
        p.setDepartment(d);
        p.setEnabled(true);
        p = programs.save(p);

        lecturer = save("lec" + tag, Role.LECTURER, null, d);
        student = save("stu" + tag, Role.NORMAL, p, null);

        course = new Category();
        course.setTitle("Data Structures");
        course.setCourseCode("CSC 201");
        course.setPrograms(new HashSet<>(Set.of(p)));
        course.setUser(lecturer);
        course = categories.save(course);
        Registered_courses r = new Registered_courses(null, new Date(), course, student);
        r.setSession(academicSessions.current());
        registrations.save(r);
    }

    private User save(String name, Role role, Program p, Department d) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setFirstname(name);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setProgram(p);
        u.setDepartment(d);
        if (p != null) u.setCurrentLevel(200);
        return users.save(u);
    }

    private static RequestPostProcessor as(User u) {
        return user(u.getUsername()).authorities(new SimpleGrantedAuthority(u.getRole().name()));
    }

    private JsonNode post(String url, User who, String body) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(as(who))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " -> " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) b.setRGB(x, y, (x * 5 + y * 3) & 0xFFFFFF);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(b, "jpg", o);
        return o.toByteArray();
    }

    @Test
    void theStudentsPhotoComesBackWithTheScanAndLoadsWithoutSignIn() throws Exception {
        // The student uploads a photo
        MvcResult up = mvc.perform(multipart("/api/v1/auth/profile/photo")
                .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", jpeg(900, 600))).with(as(student))).andReturn();
        assertThat(up.getResponse().getStatus()).as(up.getResponse().getContentAsString()).isEqualTo(200);
        String photo = json.readTree(up.getResponse().getContentAsString()).get("profilePicture").asText();
        assertThat(photo).startsWith("question-images/").endsWith(".webp");
        JsonNode me = json.readTree(mvc.perform(get("/api/v1/auth/current-user").with(as(student))).andReturn().getResponse().getContentAsString());
        assertThat(me.get("profilePicture").asText()).isEqualTo(photo);

        // A scanned class; the student's phone gets its key from inside the room
        JsonNode session = post("/api/attendance/manage/sessions", lecturer, "{\"scope\":\"COURSE\",\"courseId\":" + course.getCid()
                + ",\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"accuracy\":8,\"durationMinutes\":60,\"method\":\"STAFF_SCAN\"}");
        long sessionId = session.get("id").asLong();
        JsonNode key = post("/api/attendance/student/qr-key", student, "{\"sessionId\":" + sessionId
                + ",\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"accuracy\":8,\"deviceId\":\"student-phone-000000001\"}");
        assertThat(key.get("ok").asBoolean()).as(key.toString()).isTrue();
        String code = AttendanceQr.token(Base64.getUrlDecoder().decode(key.get("key").asText()), sessionId, student.getId(),
                AttendanceQr.step(System.currentTimeMillis()));

        // The lecturer scans: the photo comes back
        JsonNode scan = post("/api/attendance/manage/sessions/" + sessionId + "/scan", lecturer, "{\"code\":\"" + code + "\"}");
        assertThat(scan.get("outcome").asText()).isEqualTo("RECORDED");
        assertThat(scan.get("photo").asText()).isEqualTo(photo);

        // ... and loads with no sign-in, as an <img> does
        MvcResult img = mvc.perform(get("/api/v1/auth/" + photo)).andReturn();
        assertThat(img.getResponse().getStatus()).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo("image/webp");
        assertThat(img.getResponse().getContentAsByteArray().length).isGreaterThan(100);

        // A helper scans with no location at all (the classroom is the lecturer's)
        JsonNode helper = post("/api/attendance/manage/sessions/" + sessionId + "/helpers", lecturer, "{\"label\":\"TA\"}");
        MvcResult h = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/attendance/scan/" + helper.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")).andReturn();
        JsonNode hs = json.readTree(h.getResponse().getContentAsString());
        assertThat(hs.get("outcome").asText()).as(hs.toString()).isEqualTo("ALREADY");
        assertThat(hs.get("photo").asText()).isEqualTo(photo);
    }
}
