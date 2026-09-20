package com.exam.service;

import com.exam.DTO.AttemptStatusDTO;
import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.QuizAttempt;
import com.exam.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real JPA layer on an in-memory H2 database (never the real DB). This proves what the
 * fake-repository tests cannot: the new entity maps, every new JPQL query is valid, the row lock
 * query runs, and the DB itself rejects a duplicate attempt number.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Explicit in-memory H2. @TestPropertySource outranks application.yml, so the real (MySQL/RDS) datasource is never used.
        // NON_KEYWORDS=USER because `user` is reserved in H2 (not in MySQL).
        "spring.datasource.url=jdbc:h2:mem:attempts;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import(AttemptService.class)
class AttemptServiceJpaTest {

    @Autowired AttemptService        service;
    @Autowired QuizAttemptRepository attempts;
    @Autowired UserRepository        users;
    @Autowired QuizRepository        quizzes;
    @Autowired StudentAnswerRepository studentAnswers;
    @Autowired AnswerRepository      answers;

    User student;
    User admin;
    Quiz quiz;

    @BeforeEach
    void setUp() {
        student = users.save(user("ada", Role.NORMAL));
        admin   = users.save(user("root", Role.SUPER_ADMIN));

        Quiz q = new Quiz();
        q.setTitle("Mid-sem");
        q.setQuizTime("30");
        q.setQuizpassword("pw");
        q.setQuizType(QuizType.OBJ);
        q.setMaxAttempts(2);
        quiz = quizzes.save(q);
    }

    @Test
    void fullLifecycleAgainstTheRealJpaLayer() {
        // attempt 1
        AttemptStatusDTO s = service.begin(student, quiz);
        assertThat(s.activeAttemptNumber()).isEqualTo(1);
        service.recordObjective(student, quiz, new BigDecimal("6.5"));

        // attempt 2 (fresh-session resets run real delete queries)
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
        service.recordObjective(student, quiz, new BigDecimal("8.0"));

        // limit reached
        assertThatThrownBy(() -> service.begin(student, quiz)).isInstanceOf(ResponseStatusException.class);

        // both attempts stored separately
        List<QuizAttempt> rows = attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(student.getId(), quiz.getqId());
        assertThat(rows).extracting(QuizAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(rows.get(0).getMarksA()).isEqualByComparingTo("6.5");
        assertThat(rows.get(1).getMarksA()).isEqualByComparingTo("8.0");

        // retake voids the latest and frees exactly one slot
        service.grantRetake(quiz.getqId(), student.getId(), admin, "power cut");
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(3);

        List<QuizAttempt> all = attempts.findAllForQuiz(quiz.getqId());   // staff view: student fetched in the same query
        assertThat(all).hasSize(3);
        assertThat(all).extracting(QuizAttempt::getStatus)
                .containsExactly(AttemptStatus.SUBMITTED, AttemptStatus.VOIDED, AttemptStatus.IN_PROGRESS);
        assertThat(all.get(1).getVoidedByName()).isEqualTo(admin.getFullName());
        assertThat(all.get(1).getMarksA()).isEqualByComparingTo("8.0");   // voided attempt's marks are kept
    }

    @Test
    void databaseRejectsADuplicateAttemptNumber() {
        attempts.saveAndFlush(attempt(1));
        assertThatThrownBy(() -> attempts.saveAndFlush(attempt(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newQueriesAreValidAndRunnable() {
        // Hibernate parses every @Query at startup; running them proves the JPQL semantics too.
        assertThat(users.lockById(student.getId())).isPresent();
        assertThat(users.lockById(-1L)).isEmpty();
        studentAnswers.deleteByStudentAndQuiz(student.getId(), quiz.getqId());
        attempts.deleteByQuizId(quiz.getqId());
        assertThat(attempts.findAllForQuiz(quiz.getqId())).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private QuizAttempt attempt(int number) {
        QuizAttempt a = new QuizAttempt();
        a.setUser(student);
        a.setQuiz(quiz);
        a.setAttemptNumber(number);
        a.setStartedAt(LocalDateTime.now());
        return a;
    }

    private static User user(String name, Role role) {
        User u = new User();
        u.setFirstname(name);
        u.setLastname("Test");
        u.setEmail(name + "@example.com");
        u.setUsername(name);
        u.setRole(role);
        return u;
    }
}
