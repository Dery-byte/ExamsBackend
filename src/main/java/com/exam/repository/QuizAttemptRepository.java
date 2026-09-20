package com.exam.repository;

import com.exam.model.exam.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    List<QuizAttempt> findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(Long userId, Long quizId);

    /** All attempts of a quiz with the student loaded — one query for the staff attempts view. */
    @Query("SELECT a FROM QuizAttempt a JOIN FETCH a.user WHERE a.quiz.qId = :quizId ORDER BY a.user.id, a.attemptNumber")
    List<QuizAttempt> findAllForQuiz(@Param("quizId") Long quizId);

    @Modifying
    @Query("DELETE FROM QuizAttempt a WHERE a.quiz.qId = :quizId")
    void deleteByQuizId(@Param("quizId") Long quizId);
}
