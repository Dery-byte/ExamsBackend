package com.exam.repository;

import com.exam.model.QuizTimer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuizTimerRepository extends JpaRepository<QuizTimer, Long> {

    Optional<QuizTimer> findByUserIdAndQuiz_qId(Long userId, Long quizId);

    void deleteByUserIdAndQuiz_qId(Long userId, Long quizId);
}