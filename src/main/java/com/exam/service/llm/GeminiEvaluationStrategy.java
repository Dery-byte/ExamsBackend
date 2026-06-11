package com.exam.service.llm;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LLM strategy backed by the Google Gemini REST API.
 * Model is configurable via {@code google.gemini.model} (default: gemini-1.5-flash).
 */
@Service
public class GeminiEvaluationStrategy implements LLMEvaluationStrategy {

    private static final Logger log = LoggerFactory.getLogger(GeminiEvaluationStrategy.class);
    private static final int    MAX_RETRIES    = 3;
    private static final long   RETRY_DELAY_MS = 2000L;

    @Value("${google.gemini.api.url}")
    private String apiUrl;

    @Value("${google.gemini.api.key}")
    private String apiKey;

    @Value("${google.gemini.model:gemini-1.5-flash}")
    private String model;

    @Autowired private RestTemplate              restTemplate;
    @Autowired private EvaluationPersistenceHelper helper;

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GEMINI;
    }

    @Override
    public QuizEvaluationResponse evaluate(GeminiRequest request, User user) {
        List<QuestionSubmission> submissions = helper.parseSubmissions(request);
        log.info("[Gemini] Evaluating {} questions for user {} with model {}",
                 submissions.size(), user.getUsername(), model);

        List<QuestionEvaluationResult> results = submissions.stream()
                .map(this::evaluateOne)
                .collect(Collectors.toList());

        return helper.buildResponse(results, submissions, user, "GEMINI");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private QuestionEvaluationResult evaluateOne(QuestionSubmission s) {
        // Gemini URL encodes the model name and appends key as a query param
        String fullUrl = apiUrl + "?key=" + apiKey;
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try {
                GeminiRequest evalReq = buildRequest(s);
                GeminiResponse resp   = restTemplate.postForObject(fullUrl, evalReq, GeminiResponse.class);
                return parseResponse(resp, s);

            } catch (Exception e) {
                attempt++;
                log.error("[Gemini] Error attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt >= MAX_RETRIES) return helper.createFailedResult(s, MAX_RETRIES, e);
                try { TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return helper.createFailedResult(s, MAX_RETRIES, ie); }
            }
        }
        return helper.createFailedResult(s, MAX_RETRIES, new Exception("Max retries exceeded"));
    }

    private GeminiRequest buildRequest(QuestionSubmission s) {
        return new GeminiRequest(helper.buildEvaluationPrompt(s));
    }

    private QuestionEvaluationResult parseResponse(GeminiResponse resp, QuestionSubmission s) {
        if (resp == null || resp.getCandidates() == null || resp.getCandidates().isEmpty()) {
            throw new IllegalArgumentException("Empty Gemini response");
        }
        String text = resp.getCandidates().get(0).getContent().getParts().get(0).getText()
                          .replaceAll("^```json|```$", "").trim();

        double score = Math.max(0, Math.min(helper.extractDoubleValue(text, "score"), s.getMaxMarks()));
        return new QuestionEvaluationResult(
                s.getQuizId(), s.getTqid(), s.getQuestionNumber(),
                s.getQuestion(), s.getStudentAnswer(),
                score, s.getMaxMarks(),
                helper.extractStringValue(text, "feedback"),
                helper.extractStringArray(text, "keyMissed"));
    }
}
