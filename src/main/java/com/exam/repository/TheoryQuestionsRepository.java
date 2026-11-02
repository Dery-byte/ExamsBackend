package com.exam.repository;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryQuestions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;


@Repository
public interface TheoryQuestionsRepository extends JpaRepository<TheoryQuestions, Long> {
    Set<TheoryQuestions> findByQuiz(Quiz quiz);

    /**
     * A quiz's theory questions in the order they were added, so a question's parts (Q1a, Q1ai, Q1b)
     * appear in the order they were typed or uploaded; sorting the labels can't do that (Roman numerals).
     */
    default List<TheoryQuestions> findByQuizInOrder(Quiz quiz) {
        return findByQuiz(quiz).stream()
                .sorted(Comparator.comparing(TheoryQuestions::getTqId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }


    @Modifying
    @Transactional
    @Query("DELETE FROM TheoryQuestions t WHERE t.quiz.qId = :quizId")
    void deleteByQuizId(@Param("quizId") Long quizId);


    void deleteByQuiz_qId(Long quizId);

    List<TheoryQuestions> findByQuiz_qId(Long quizId);




    // Find all compulsory questions for a quiz
//    List<TheoryQuestions> findByQuiz_QIdAndIsCompulsoryTrue(Long quizId);
//    List<TheoryQuestions> findByQuiz_QId(Long quizId);
}
