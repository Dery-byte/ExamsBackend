package com.exam;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.examops.RemarkRequest;
import com.exam.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A lecturer answers a student's re-mark request through the real endpoint, end to end. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:remarks;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class RemarkFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired QuizRepository quizzes;
    @Autowired ReportRepository reports;
    @Autowired RemarkRequestRepository remarks;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

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

    @Test
    void lecturerCanSendAResponse() throws Exception {
        // Real accounts have department/program links (eagerly loaded collections): reproduce that
        com.exam.model.exam.Department dept = new com.exam.model.exam.Department();
        dept.setName("Computer Science"); dept.setCode("CS");
        dept = departments.save(dept);
        com.exam.model.exam.Program prog = new com.exam.model.exam.Program();
        prog.setName("BSc CS"); prog.setCode("BCS"); prog.setDepartment(dept);
        prog = programs.save(prog);

        User student = save("rstu", Role.NORMAL);
        student.setProgram(prog);
        student.setDepartment(dept);
        student.getSecondaryDepartments().add(dept);
        student = users.save(student);
        User lecturer = save("rlec", Role.LECTURER);
        lecturer.setPassword(encoder.encode("lecturer-pw"));
        lecturer.setDepartment(dept);
        lecturer.getSecondaryDepartments().add(dept);
        lecturer = users.save(lecturer);

        Category c = new Category();
        c.setTitle("Algorithms");
        c.setCourseCode("CS301");
        c.setUser(lecturer);
        c.getPrograms().add(prog);
        c = categories.save(c);

        Quiz q = new Quiz();
        q.setTitle("Mid-sem");
        q.setQuizTime("30");
        q.setQuizpassword("");
        q.setCategory(c);
        q.setUser(lecturer);
        q = quizzes.save(q);

        // Seed in its own transaction (Report cascades to its quiz); the request below runs outside it, like production
        final Long quizId = q.getqId(), studentId = student.getId();
        Long remarkId = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(tx -> {
            Report rep = new Report();
            rep.setUser(em.find(User.class, studentId));
            rep.setQuiz(em.find(Quiz.class, quizId));
            rep.setMarks(new BigDecimal("12"));
            rep.setMarksB(new BigDecimal("8"));
            rep.setIsReviewed(true);
            em.persist(rep);
            RemarkRequest r = new RemarkRequest();
            r.setReport(rep);
            r.setStudent(rep.getUser());
            r.setReason("Question 2 deserves more marks.");
            r.setScoreBefore(new BigDecimal("20"));
            em.persist(r);
            return r.getId();
        });
        RemarkRequest rr = remarks.findById(remarkId).orElseThrow();

        // Sign in for real and use the Bearer token, exactly like the browser
        String login = mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rlec\",\"password\":\"lecturer-pw\"}")).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login).get("token").asText();

        MvcResult res = mvc.perform(post("/api/remarks/" + rr.getId() + "/respond")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RESOLVED\",\"response\":\"Re-checked, marks unchanged.\"}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(remarks.findById(rr.getId()).orElseThrow().getStatus()).isEqualTo(RemarkRequest.Status.RESOLVED);
    }
}
