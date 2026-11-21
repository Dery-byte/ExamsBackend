package com.exam.repository;

import com.exam.model.examops.QuizCodeLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuizCodeLockRepository extends JpaRepository<QuizCodeLock, Long> {

    Optional<QuizCodeLock> findByQuiz_qIdAndStudent_Id(Long quizId, Long studentId);

    /** The row locked for update, so two wrong codes sent together both count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM QuizCodeLock c WHERE c.quiz.qId = :quizId AND c.student.id = :studentId")
    Optional<QuizCodeLock> findForUpdate(@Param("quizId") Long quizId, @Param("studentId") Long studentId);

    /** Students locked out of the quiz's access code right now, with the students loaded. */
    @Query("SELECT c FROM QuizCodeLock c JOIN FETCH c.student WHERE c.quiz.qId = :quizId AND c.lockedAt IS NOT NULL ORDER BY c.lockedAt")
    List<QuizCodeLock> findLockedForQuiz(@Param("quizId") Long quizId);
}
