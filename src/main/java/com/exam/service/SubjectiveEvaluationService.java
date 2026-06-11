package com.exam.service;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.LlmProvider;
import com.exam.model.exam.QuestionSubmission;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import com.exam.service.llm.EvaluationPersistenceHelper;
import com.exam.service.llm.LLMEvaluationStrategy;
import com.exam.service.llm.LLMEvaluationStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Central orchestrator for subjective (theory) answer evaluation.
 * <p>
 * Resolves the quiz from the incoming request, looks up its {@link LlmProvider},
 * selects the correct {@link LLMEvaluationStrategy} from the factory, and delegates.
 * <p>
 * All controllers should call <em>this</em> service instead of the individual
 * provider-specific services.
 */
@Service
public class SubjectiveEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(SubjectiveEvaluationService.class);

    @Autowired private LLMEvaluationStrategyFactory factory;
    @Autowired private EvaluationPersistenceHelper  helper;
    @Autowired private QuizRepository               quizRepository;

    /**
     * Evaluate subjective answers using whatever LLM provider is configured on the quiz.
     *
     * @param request the structured list of question submissions (same format used by all providers)
     * @param user    the authenticated student
     * @return        a fully populated evaluation response including scores, feedback, and report
     */
    public QuizEvaluationResponse evaluate(GeminiRequest request, User user) {
        // Resolve the quiz to get its configured LLM provider
        List<QuestionSubmission> submissions = helper.parseSubmissions(request);
        if (submissions.isEmpty()) {
            throw new IllegalArgumentException("No question submissions found in request");
        }

        Long quizId = Long.valueOf(submissions.get(0).getQuizId());
        Quiz quiz   = quizRepository.findById(quizId)
                      .orElseThrow(() -> new RuntimeException("Quiz not found: " + quizId));

        LlmProvider provider = quiz.getLlmProvider() != null ? quiz.getLlmProvider() : LlmProvider.GPT;
        log.info("Subjective evaluation — quizId={}, provider={}, user={}",
                 quizId, provider, user.getUsername());
                 
        System.out.println("\n=======================================================");
        System.out.println("🚀 STARTING SUBJECTIVE QUIZ EVALUATION");
        System.out.println("=======================================================");
        System.out.println("👉 Quiz ID      : " + quizId);
        System.out.println("👉 Student      : " + user.getUsername());
        System.out.println("👉 LLM Provider : " + provider.name());
        System.out.println("=======================================================\n");

        LLMEvaluationStrategy strategy = factory.getStrategy(provider);
        return strategy.evaluate(request, user);
    }

    /**
     * Evaluate using an explicitly specified provider (overrides quiz setting).
     * Useful for admin testing of a specific provider.
     */
    public QuizEvaluationResponse evaluateWithProvider(GeminiRequest request, User user,
                                                        LlmProvider provider) {
        log.info("Subjective evaluation (explicit provider) — provider={}, user={}",
                 provider, user.getUsername());
        return factory.getStrategy(provider).evaluate(request, user);
    }
}
