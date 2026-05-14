package com.exam.repository;

import com.exam.model.exam.Answer;
import com.exam.model.exam.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
    List<Answer> findByUser_IdAndQuiz_qId(Long userId, Long quizId);

    // ✅ Find by Quiz ID
    List<Answer> findByQuiz_qId(Long quizId);

    // ✅ Find by User ID
    List<Answer> findByUser_Id(Long userId);

    // ✅ Find by Report ID
    List<Answer> findByReport_Id(Long reportId);


    // ✅ Find by Report (all answers for a report)
    List<Answer> findByReport(Report report);

    @Modifying
    @Transactional
    @Query("DELETE FROM Answer a WHERE a.theoryQuestion.TqId = :questionId")
    void deleteByTheoryQuestionId(@Param("questionId") Long questionId);
}
