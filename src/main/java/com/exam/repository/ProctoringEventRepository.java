package com.exam.repository;

import com.exam.model.examops.ProctoringEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProctoringEventRepository extends JpaRepository<ProctoringEvent, Long> {
    List<ProctoringEvent> findByQuiz_qIdOrderByOccurredAtAsc(Long quizId);
    List<ProctoringEvent> findByQuiz_qIdAndUser_IdOrderByOccurredAtAsc(Long quizId, Long userId);
    long countByQuiz_qIdAndUser_Id(Long quizId, Long userId);
}
