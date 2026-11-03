package com.exam;

import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The exam page's optimised path, end to end with a real sign-in token: one request opens the exam,
 * answer changes go in batches (matching answers kept by pair), marking counts the quiz's own
 * questions, and a token stops working the moment its owner signs out (despite the validity cache).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:examsession;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class ExamSessionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionsRepository questions;
    @Autowired Registered_coursesRepository registrations;
    @Autowired ReportRepository reports;
    @Autowired StudentAnswerRepository studentAnswers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    private final ObjectMapper json = new ObjectMapper();
    private String token;

    private MvcResult call(MockHttpServletRequestBuilder req, String body) throws Exception {
        req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 204);
        String body = r.getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private Questions mcq(Quiz quiz, String content) {
        Questions q = new Questions();
        q.setContent(content);
        q.setOption1("right");
        q.setOption2("wrong");
        q.setcorrect_answer(new String[]{"right"});
        q.setQuestionType(QuestionType.MCQ);
        q.setQuiz(quiz);
        return questions.save(q);
    }

    @Test
    void theExamPageOpensSavesAndSubmitsThroughTheOptimisedEndpoints() throws Exception {
        User student = new User();
        student.setUsername("exs1");
        student.setEmail("exs1@example.com");
        student.setFirstname("Ada");
        student.setLastname("Test");
        student.setPassword(encoder.encode("student-pw"));
        student.setRole(Role.NORMAL);
        student.setEnabled(true);
        student = users.save(student);

        Category course = new Category();
        course.setTitle("Networks");
        course.setCourseCode("CS310");
        course = categories.save(course);
        Registered_courses reg = new Registered_courses();
        reg.setUser(student);
        reg.setCategory(course);
        registrations.save(reg);

        Quiz quiz = new Quiz();
        quiz.setTitle("Quiz 1");
        quiz.setQuizTime("30");
        quiz.setQuizpassword("");
        quiz.setQuizType(QuizType.OBJ);
        quiz.setMaxMarks(9.0);
        quiz.setNumberOfQuestions(3);
        quiz.setActive(true);
        quiz.setCategory(course);
        quiz = quizzes.save(quiz);
        long qid = quiz.getqId();

        Questions q1 = mcq(quiz, "One");
        Questions q2 = mcq(quiz, "Two");
        Questions match = new Questions();
        match.setContent("Match");
        match.setQuestionType(QuestionType.MATCHING);
        match.setQuiz(quiz);
        match.setMatchingPairs(new java.util.ArrayList<>(List.of(
                new MatchingPair("404", "Not Found", 0, match), new MatchingPair("500", "Server Error", 1, match))));
        match = questions.save(match);

        String login = mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"exs1\",\"password\":\"student-pw\"}")).andReturn().getResponse().getContentAsString();
        token = json.readTree(login).get("token").asText();

        // Begin, then one request for everything the page shows
        ok(call(post("/api/v1/auth/quiz-attempts/" + qid + "/begin"), null));
        JsonNode s = ok(call(get("/api/v1/auth/quiz-attempts/" + qid + "/session"), null));
        assertThat(s.get("quiz").get("title").asText()).isEqualTo("Quiz 1");
        assertThat(s.get("questions")).hasSize(3);
        assertThat(s.get("questions").toString()).doesNotContain("correct_answer\":[");   // students never get the key
        assertThat(s.get("theoryQuestions").isArray()).isTrue();
        assertThat(s.get("timer").get("status").asText()).isEqualTo("not_found");
        assertThat(s.get("violationCount").get("totalViolationCount").asInt()).isZero();

        // Answer changes in one batch, applied in order; a matching answer is kept by its pair
        String changes = "[" +
                "{\"questionId\":" + q1.getQuesId() + ",\"option\":\"wrong\",\"checked\":true,\"quizId\":" + qid + "}," +
                "{\"questionId\":" + q1.getQuesId() + ",\"option\":\"wrong\",\"checked\":false,\"quizId\":" + qid + "}," +
                "{\"questionId\":" + q1.getQuesId() + ",\"option\":\"right\",\"checked\":true,\"quizId\":" + qid + "}," +
                "{\"questionId\":" + match.getQuesId() + ",\"option\":\"Server Error\",\"checked\":true,\"pairIndex\":1,\"quizId\":" + qid + "}," +
                "{\"questionId\":" + match.getQuesId() + ",\"option\":\"x\",\"checked\":true,\"pairIndex\":999,\"quizId\":" + qid + "}" +
                "]";
        assertThat(ok(call(post("/api/v1/auth/quiz-progress/update-batch"), changes)).get("saved").asInt()).isEqualTo(5);

        // Reloading the page restores them
        s = ok(call(get("/api/v1/auth/quiz-attempts/" + qid + "/session"), null));
        assertThat(s.get("answers").get("answers").get(String.valueOf(q1.getQuesId())).toString()).isEqualTo("[\"right\"]");
        assertThat(s.get("answers").get("matchingAnswers").get(String.valueOf(match.getQuesId())).asText()).isEqualTo("|Server Error");
        assertThat(s.get("answers").get("answers").has(String.valueOf(match.getQuesId()))).isFalse();

        // Sending only the one right answer is still marked out of the quiz's three questions
        JsonNode result = ok(call(post("/api/v1/auth/eval-quiz/" + qid),
                "[{\"quesId\":" + q1.getQuesId() + ",\"givenAnswer\":[\"right\"]}]"));
        assertThat(result.get("marksGot").asDouble()).isEqualTo(3.0);
        assertThat(studentAnswers.findAll()).hasSize(3);
        ok(call(post("/api/v1/auth/quiz-attempts/" + qid + "/finish"), null));

        // Signing out stops the token at once, though it was just confirmed (and cached) as valid
        ok(call(get("/api/v1/auth/quiz-attempts/" + qid + "/my-status"), null));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(call(get("/api/v1/auth/quiz-attempts/" + qid + "/my-status"), null).getResponse().getStatus()).isNotEqualTo(200);
    }
}
