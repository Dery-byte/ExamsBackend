package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.QuizType;
import com.exam.model.examops.ResultWithhold;
import com.exam.repository.CategoryRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ResultWithholdRepository;
import com.exam.repository.UserRepository;
import com.exam.service.QuizDeletionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The withheld-result queries on H2, and the quiz purge removing withheld rows. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:withhold;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import(QuizDeletionService.class)
class ResultWithholdJpaTest {

    @Autowired ResultWithholdRepository withholds;
    @Autowired UserRepository users;
    @Autowired QuizRepository quizzes;
    @Autowired CategoryRepository categories;
    @Autowired QuizDeletionService deletion;
    @Autowired EntityManager em;

    User ama;
    User kofi;
    Quiz midterm;
    Quiz quiz2;

    private static User user(String name) {
        User u = new User();
        u.setFirstname(name);
        u.setLastname("Test");
        u.setEmail(name + "@example.com");
        u.setUsername(name);
        u.setRole(Role.NORMAL);
        return u;
    }

    private Quiz quiz(String title, Category c) {
        Quiz q = new Quiz();
        q.setTitle(title);
        q.setQuizTime("45");
        q.setQuizpassword("");
        q.setQuizType(QuizType.OBJ);
        q.setActive(true);
        q.setCategory(c);
        return quizzes.save(q);
    }

    private ResultWithhold withhold(User s, Quiz q, boolean active) {
        ResultWithhold w = new ResultWithhold();
        w.setStudent(s);
        w.setQuiz(q);
        w.setReason("Took 60 minutes");
        w.setWithheldByName("Lect");
        w.setWithheldAt(LocalDateTime.now());
        w.setActive(active);
        return withholds.save(w);
    }

    @BeforeEach
    void setUp() {
        ama = users.save(user("ama"));
        kofi = users.save(user("kofi"));
        Category c = new Category();
        c.setTitle("Data Structures");
        c.setCourseCode("CS201");
        c = categories.save(c);
        midterm = quiz("Midterm", c);
        quiz2 = quiz("Quiz 2", c);
    }

    @Test
    void onlyActiveRowsCount() {
        withhold(ama, midterm, true);
        withhold(ama, quiz2, false);      // released earlier

        assertThat(withholds.existsByQuiz_qIdAndStudent_IdAndActiveTrue(midterm.getqId(), ama.getId())).isTrue();
        assertThat(withholds.existsByQuiz_qIdAndStudent_IdAndActiveTrue(quiz2.getqId(), ama.getId())).isFalse();
        assertThat(withholds.existsByQuiz_qIdAndStudent_IdAndActiveTrue(midterm.getqId(), kofi.getId())).isFalse();
        assertThat(withholds.findActiveQuizIdsForStudent(ama.getId())).containsExactly(midterm.getqId());
        assertThat(withholds.findActiveForQuiz(midterm.getqId())).extracting(w -> w.getStudent().getId()).containsExactly(ama.getId());
        assertThat(withholds.findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(midterm.getqId(), ama.getId())).isPresent();
    }

    @Test
    void deletingTheQuizRemovesItsWithheldRows() {
        withhold(ama, midterm, true);
        withhold(kofi, midterm, false);
        withhold(ama, quiz2, true);
        em.flush();

        deletion.purge(midterm.getqId());
        em.flush();
        em.clear();

        assertThat(quizzes.findById(midterm.getqId())).isEmpty();
        assertThat(withholds.findAll()).extracting(w -> w.getQuiz().getqId()).containsExactly(quiz2.getqId());
    }
}
