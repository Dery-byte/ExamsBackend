package com.exam.repository;

import com.exam.model.examops.ResultWithhold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResultWithholdRepository extends JpaRepository<ResultWithhold, Long> {

    boolean existsByQuiz_qIdAndStudent_IdAndActiveTrue(Long quizId, Long studentId);

    Optional<ResultWithhold> findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(Long quizId, Long studentId);

    /** The quiz's results held back right now, with the students loaded. */
    @Query("SELECT w FROM ResultWithhold w JOIN FETCH w.student WHERE w.quiz.qId = :quizId AND w.active = true")
    List<ResultWithhold> findActiveForQuiz(@Param("quizId") Long quizId);

    /** Ids of the quizzes whose result is held back for this student right now. */
    @Query("SELECT w.quiz.qId FROM ResultWithhold w WHERE w.student.id = :studentId AND w.active = true")
    List<Long> findActiveQuizIdsForStudent(@Param("studentId") Long studentId);
}
