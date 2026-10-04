package com.exam.service;

import com.exam.model.QuizType;
import com.exam.model.exam.*;
import com.exam.repository.QuestionsRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A matching question in a quiz open to several programs must load each pair once. Loading the
 * question, its quiz and the quiz's programs in one joined query used to repeat every pair once per
 * program, which broke editing ("Multiple representations of the same entity") and showed students
 * duplicate pairs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:matchingpairs;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
class MatchingPairsLoadJpaTest {

    @Autowired EntityManager em;
    @Autowired QuestionsRepository questions;

    private Program program(Department d, String code) {
        Program p = new Program();
        p.setName("Program " + code);
        p.setCode(code);
        p.setDurationYears(4);
        p.setDepartment(d);
        em.persist(p);
        return p;
    }

    private int made;

    private Questions matchingQuestion() {
        String n = String.valueOf(++made);   // departments and programs have unique names and codes
        Department d = new Department();
        d.setName("Computing " + n);
        d.setCode("CMP" + n);
        em.persist(d);

        Quiz quiz = new Quiz();
        quiz.setTitle("Networks");
        quiz.setQuizTime("30");
        quiz.setQuizpassword("");
        quiz.setQuizType(QuizType.OBJ);
        quiz.getPrograms().add(program(d, "CS" + n));
        quiz.getPrograms().add(program(d, "IT" + n));
        quiz.getPrograms().add(program(d, "SE" + n));
        em.persist(quiz);

        Questions q = new Questions();
        q.setContent("Match the status codes");
        q.setQuestionType(QuestionType.MATCHING);
        q.setQuiz(quiz);
        q.getMatchingPairs().add(new MatchingPair("404", "Not Found", 0, q));
        q.getMatchingPairs().add(new MatchingPair("500", "Server Error", 1, q));
        em.persist(q);
        em.flush();
        em.clear();
        return q;
    }

    private static MatchingPair edited(Long id, String prompt, String answer) {
        MatchingPair p = new MatchingPair(prompt, answer, null, null);
        p.setId(id);
        return p;
    }

    @Test
    void eachPairLoadsOnceWhenTheQuizIsOpenToSeveralPrograms() {
        Questions q = matchingQuestion();
        Quiz quiz = q.getQuiz();

        List<MatchingPair> pairs = questions.findById(q.getQuesId()).orElseThrow().getMatchingPairs();
        assertThat(pairs).extracting(MatchingPair::getPrompt).containsExactly("404", "500");

        em.clear();
        List<MatchingPair> viaQuiz = questions.findByQuiz(em.find(Quiz.class, quiz.getqId())).iterator().next().getMatchingPairs();
        assertThat(viaQuiz).extracting(MatchingPair::getPrompt).containsExactly("404", "500");
    }

    @Test
    void editingSavesTheEditedPairsEvenWhenThePageSentAPairTwice() {
        Questions saved = matchingQuestion();
        Questions q = questions.findById(saved.getQuesId()).orElseThrow();
        Long id404 = q.getMatchingPairs().get(0).getId();

        // What an edit page loaded before the fix could send: pair 404 twice, 500 dropped, one new pair
        QuestionsService.applyMatchingPairs(q, List.of(
                edited(id404, "404", "Not Found (edited)"),
                edited(id404, "404", "Not Found (edited)"),
                edited(null, "301", "Moved")));
        questions.save(q);
        em.flush();
        em.clear();

        List<MatchingPair> after = questions.findById(saved.getQuesId()).orElseThrow().getMatchingPairs();
        assertThat(after).extracting(MatchingPair::getPrompt).containsExactly("404", "404", "301");
        assertThat(after.get(0).getId()).isEqualTo(id404);                 // existing pair updated in place
        assertThat(after.get(0).getAnswer()).isEqualTo("Not Found (edited)");
        assertThat(after).extracting(MatchingPair::getPairOrder).containsExactly(0, 1, 2);
        assertThat(after).extracting(MatchingPair::getAnswer).doesNotContain("Server Error");   // dropped pair deleted
    }

    @Test
    void anotherQuestionsPairIdIsNotTakenOver() {
        Questions a = matchingQuestion();
        Questions b = questions.findById(matchingQuestion().getQuesId()).orElseThrow();
        Long foreign = questions.findById(a.getQuesId()).orElseThrow().getMatchingPairs().get(0).getId();
        em.clear();
        b = questions.findById(b.getQuesId()).orElseThrow();

        QuestionsService.applyMatchingPairs(b, List.of(edited(foreign, "X", "Y"), edited(null, "Z", "W")));
        questions.save(b);
        em.flush();
        em.clear();

        assertThat(questions.findById(a.getQuesId()).orElseThrow().getMatchingPairs())
                .extracting(MatchingPair::getPrompt).containsExactly("404", "500");   // untouched
        List<MatchingPair> bPairs = questions.findById(b.getQuesId()).orElseThrow().getMatchingPairs();
        assertThat(bPairs).extracting(MatchingPair::getPrompt).containsExactly("X", "Z");
        assertThat(bPairs.get(0).getId()).isNotEqualTo(foreign);
    }
}
