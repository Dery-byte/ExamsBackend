package com.exam.service.llm;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.LlmProvider;

/**
 * Strategy interface for LLM-based subjective answer evaluation.
 * Each concrete implementation wraps one AI provider (GPT, Gemini, DeepSeek, Claude, …).
 */
public interface LLMEvaluationStrategy {

    /**
     * Evaluate all subjective submissions in {@code request} for the given {@code user}.
     *
     * @param request  the structured list of question submissions
     * @param user     the authenticated student
     * @return         a fully populated evaluation response including scores, feedback, and report
     */
    QuizEvaluationResponse evaluate(GeminiRequest request, User user);

    /**
     * Returns the provider this strategy handles.
     * Used by the factory for registration and the report's {@code evaluationMethod} field.
     */
    LlmProvider getProvider();
}
