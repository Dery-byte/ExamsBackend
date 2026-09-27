package com.exam.repository;

import com.exam.model.examops.QuizUnlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuizUnlockRepository extends JpaRepository<QuizUnlock, Long> {
    Optional<QuizUnlock> findTopByUser_IdAndQuiz_qIdOrderByUnlockedAtDesc(Long userId, Long quizId);
}
