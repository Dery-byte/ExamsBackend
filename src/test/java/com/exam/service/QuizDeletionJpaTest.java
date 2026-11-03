package com.exam.service;

import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs QuizDeletionService's JPQL on in-memory H2: the "has results" checks and the full purge,
 * which must clear every row that points at the quiz before the quiz itself goes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:quizdelete;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import(QuizDeletionService.class)
class QuizDeletionJpaTest {

    @Autowired QuizDeletionService service;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired QuizRepository quizzes;
    @Autowired CategoryRepository categories;
    @Autowired QuestionsRepository questions;
    @Autowired TheoryQuestionsRepository theoryQuestions;
    @Autowired StudentAnswerRepository studentAnswers;
    @Autowired AnswerRepository answers;
    @Autowired QuizAttemptRepository attempts;
    @Autowired ReportRepository reports;

    User student;
    Category course;
    Quiz taken;
    Quiz untouched;
    Questions takenQuestion;

    @BeforeEach
    void setUp() {
        student = users.save(user("ada"));
        Category c = new Category();
        c.setTitle("Data Structures");
        c.setCourseCode("CS201");
        course = categories.save(c);

        taken = quizzes.save(quiz("Mid-sem"));
        untouched = quizzes.save(quiz("Draft"));

        takenQuestion = objective(taken);
        objective(untouched);
        TheoryQuestions tq = theory(taken);

        // The student sat `taken`: an attempt, a report, an objective answer and a theory answer
        QuizAttempt a = new QuizAttempt();
        a.setUser(student);
        a.setQuiz(taken);
        a.setAttemptNumber(1);
        a.setStartedAt(LocalDateTime.now());
        attempts.save(a);

        Report r = new Report();
        r.setUser(student);
        r.setQuiz(taken);
        reports.save(r);

        StudentAnswer sa = new StudentAnswer();
        sa.setUser(student);
        sa.setQuestion(takenQuestion);
        studentAnswers.save(sa);

        Answer ans = new Answer();
        ans.setUser(student);
        ans.setQuiz(taken);
        ans.setTheoryQuestion(tq);
        ans.setQuesNo("1a");
        ans.setStudentAnswer("Because");
        ans.setReport(r);
        answers.save(ans);

        em.flush();
        em.clear();
    }

    @Test
    void findsResultsOnlyOnTheTakenQuiz() {
        assertThat(service.studentsWithResults(taken.getqId())).isEqualTo(1);
        assertThat(service.hasResults(untouched.getqId())).isFalse();
        assertThat(service.quizIdsWithResults(List.of(taken.getqId(), untouched.getqId())))
                .containsExactly(taken.getqId());
        assertThat(service.courseIdsWithResults(List.of(course.getCid()))).containsExactly(course.getCid());
    }

    @Test
    void guardsRefuseWithConflict() {
        assertThatThrownBy(() -> service.requireQuestionDeletable(taken))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.requireQuizDeletable(taken.getqId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.requireCourseDeletable(course.getCid(), List.of(taken, untouched)))
                .hasMessageContaining("1 quiz");

        service.requireQuestionDeletable(untouched);
        service.requireQuizDeletable(untouched.getqId());
    }

    @Test
    void purgeRemovesTheQuizAndEverythingPointingAtIt() {
        service.purge(taken.getqId());
        em.flush();
        em.clear();

        assertThat(quizzes.findById(taken.getqId())).isEmpty();
        assertThat(questions.findById(takenQuestion.getQuesId())).isEmpty();
        assertThat(theoryQuestions.findByQuiz_qId(taken.getqId())).isEmpty();
        assertThat(studentAnswers.count()).isZero();
        assertThat(answers.count()).isZero();
        assertThat(attempts.count()).isZero();
        assertThat(reports.count()).isZero();

        // The other quiz is left alone
        assertThat(quizzes.findById(untouched.getqId())).isPresent();
        assertThat(questions.count()).isEqualTo(1);
    }

    private Quiz quiz(String title) {
        Quiz q = new Quiz();
        q.setTitle(title);
        q.setQuizTime("30");
        q.setQuizpassword("");
        q.setQuizType(QuizType.BOTH);
        q.setCategory(course);
        return q;
    }

    private Questions objective(Quiz quiz) {
        Questions q = new Questions();
        q.setContent("2 + 2?");
        q.setAnswer("4");
        q.setQuiz(quiz);
        q.getMatchingPairs().add(new MatchingPair("left", "right", 0, q));
        return questions.save(q);
    }

    private TheoryQuestions theory(Quiz quiz) {
        TheoryQuestions t = new TheoryQuestions();
        t.setQuesNo("1a");
        t.setQuestion("Explain.");
        t.setMarks("5");
        t.setQuiz(quiz);
        return theoryQuestions.save(t);
    }

    private static User user(String name) {
        User u = new User();
        u.setFirstname(name);
        u.setLastname("Test");
        u.setEmail(name + "@example.com");
        u.setUsername(name);
        u.setRole(Role.NORMAL);
        return u;
    }
}
