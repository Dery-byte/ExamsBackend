package com.exam.service.Impl;

import com.exam.DTO.QuizEvaluationResult;
import com.exam.model.exam.Questions;

import java.util.List;

public interface QuizEvaluationService {
    QuizEvaluationResult evaluateQuiz(
            List<Questions> questions,
            String username,
            Long quizId
    );
}
