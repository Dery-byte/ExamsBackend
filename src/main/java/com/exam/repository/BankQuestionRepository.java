package com.exam.repository;

import com.exam.model.examops.BankQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BankQuestionRepository extends JpaRepository<BankQuestion, Long> {
    List<BankQuestion> findByCourse_CidOrderByCreatedAtDesc(Long courseId);

    @Query("SELECT DISTINCT b.topic FROM BankQuestion b WHERE b.course.cid = :courseId AND b.topic IS NOT NULL ORDER BY b.topic")
    List<String> findTopics(@Param("courseId") Long courseId);

    long countByCourse_Cid(Long courseId);
}
