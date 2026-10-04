package com.exam.repository;

import com.exam.model.QuizTimer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface QuizTimerRepository extends JpaRepository<QuizTimer, Long> {

    Optional<QuizTimer> findByUserIdAndQuiz_qId(Long userId, Long quizId);

    /**
     * Creates the (user, quiz) row if it does not exist yet, atomically, so concurrent first saves
     * (blur + visibilitychange + violation count all fire on one tab switch) cannot collide on the
     * unique key. Returns 1 when a row was inserted, 0 when it already existed.
     */
    @Modifying
    @Query(value = "INSERT INTO quiz_timers (user_id, quiz_id, remaining_time, updated_at, total_violation_count) "
            + "VALUES (:userId, :quizId, :remainingTime, :now, 0) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("quizId") Long quizId,
                       @Param("remainingTime") Integer remainingTime, @Param("now") LocalDateTime now);

    /** Same lookup as findByUserIdAndQuiz_qId, but holds a row lock until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM QuizTimer t WHERE t.user.id = :userId AND t.quiz.qId = :quizId")
    Optional<QuizTimer> findForUpdate(@Param("userId") Long userId, @Param("quizId") Long quizId);

    void deleteByUserIdAndQuiz_qId(Long userId, Long quizId);
}