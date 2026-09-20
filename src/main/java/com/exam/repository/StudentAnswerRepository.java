package com.exam.repository;

import com.exam.model.exam.Report;
import com.exam.model.exam.StudentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StudentAnswerRepository extends JpaRepository<StudentAnswer, Long> {


    @Query("""
SELECT sa
FROM StudentAnswer sa
JOIN FETCH sa.question q
JOIN FETCH q.quiz quiz
WHERE sa.user.id = :studentId
AND quiz.qId = :quizId
""")
    List<StudentAnswer> findByStudentAndQuiz(
            @Param("studentId") Long studentId,
            @Param("quizId") Long quizId
    );

    /** Removes a student's earlier objective answers for a quiz so a new attempt replaces them. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM StudentAnswer sa WHERE sa.user.id = :studentId AND sa.question.quesId IN " +
           "(SELECT q.quesId FROM Questions q WHERE q.quiz.qId = :quizId)")
    void deleteByStudentAndQuiz(@Param("studentId") Long studentId, @Param("quizId") Long quizId);

}
