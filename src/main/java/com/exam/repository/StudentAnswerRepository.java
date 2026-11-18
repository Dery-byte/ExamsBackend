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
    // Not a "DELETE ... WHERE user = ?": when the student has no rows yet, MySQL locks the empty index gap
    // their rows would go in, and two students submitting at once then deadlock on each other's inserts
    // (HTTP 500 on eval-quiz under load, 2026-11-18). A plain read takes no locks; deleting by id locks
    // only the rows that exist, and nothing at all on a first attempt.
    default void deleteByStudentAndQuiz(Long studentId, Long quizId) {
        List<Long> ids = findIdsByStudentAndQuiz(studentId, quizId);
        if (!ids.isEmpty()) deleteAllByIdInBatch(ids);
    }

    @Query("SELECT sa.id FROM StudentAnswer sa WHERE sa.user.id = :studentId AND sa.question.quiz.qId = :quizId")
    List<Long> findIdsByStudentAndQuiz(@Param("studentId") Long studentId, @Param("quizId") Long quizId);

}
