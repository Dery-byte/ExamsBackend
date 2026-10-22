package com.exam.repository;

import com.exam.model.exam.AttemptStatus;
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

    boolean existsByUser_IdAndQuiz_qIdAndStatus(Long userId, Long quizId, AttemptStatus status);

    /** Whether the student is sitting any exam (subscription limits never interrupt one). */
    boolean existsByUser_IdAndStatus(Long userId, AttemptStatus status);

    boolean existsByUser_IdAndStatusAndSubmittedAtAfter(Long userId, AttemptStatus status, java.time.LocalDateTime after);

    /** All attempts of a quiz with the student loaded — one query for the staff attempts view. */
    @Query("SELECT a FROM QuizAttempt a JOIN FETCH a.user WHERE a.quiz.qId = :quizId ORDER BY a.user.id, a.attemptNumber")
    List<QuizAttempt> findAllForQuiz(@Param("quizId") Long quizId);

    /** Ids of quizzes with at least one attempt in the given status. */
    @Query("SELECT DISTINCT a.quiz.qId FROM QuizAttempt a WHERE a.status = :status")
    List<Long> findQuizIdsWithStatus(@Param("status") AttemptStatus status);

    @Modifying
    @Query("DELETE FROM QuizAttempt a WHERE a.quiz.qId = :quizId")
    void deleteByQuizId(@Param("quizId") Long quizId);
}
