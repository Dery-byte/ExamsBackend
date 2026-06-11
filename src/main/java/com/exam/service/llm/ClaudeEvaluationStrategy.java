package com.exam.service.llm;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.LlmProvider;
import com.exam.model.exam.QuestionEvaluationResult;
import com.exam.model.exam.QuestionSubmission;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LLM strategy backed by the Anthropic Claude Messages API.
 * <p>
 * <b>Status: OPTIONAL STUB</b> — fully wired but returns a friendly "not configured" error
 * when {@code claude.api.key} is not set or left as the placeholder value.
 * <p>
 * To activate: set {@code CLAUDE_API_KEY} in your environment / properties file.
 * Model is configurable via {@code claude.model} (default: claude-3-haiku-20240307).
 */
@Service
public class ClaudeEvaluationStrategy implements LLMEvaluationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ClaudeEvaluationStrategy.class);
    private static final int    MAX_RETRIES    = 3;
    private static final long   RETRY_DELAY_MS = 2000L;

    @Value("${claude.api.url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${claude.api.key:NOT_CONFIGURED}")
    private String apiKey;

    @Value("${claude.api.version:2023-06-01}")
    private String apiVersion;

    @Value("${claude.model:claude-3-haiku-20240307}")
    private String model;

    @Autowired private RestTemplate              restTemplate;
    @Autowired private EvaluationPersistenceHelper helper;

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.CLAUDE;
    }

    @Override
    public QuizEvaluationResponse evaluate(GeminiRequest request, User user) {
        List<QuestionSubmission> submissions = helper.parseSubmissions(request);
        log.info("[Claude] Evaluating {} questions for user {} with model {}",
                 submissions.size(), user.getUsername(), model);

        List<QuestionEvaluationResult> results = submissions.stream()
                .map(this::evaluateOne)
                .collect(Collectors.toList());

        return helper.buildResponse(results, submissions, user, "CLAUDE");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private QuestionEvaluationResult evaluateOne(QuestionSubmission s) {
        // Guard: key not yet set
        if ("NOT_CONFIGURED".equals(apiKey) || apiKey == null || apiKey.isBlank()
                || apiKey.contains("${")) {
            log.warn("[Claude] API key not configured — returning stub failure for question {}",
                     s.getQuestionNumber());
            return helper.createFailedResult(s, 0,
                    new IllegalStateException(
                            "Claude API key is not configured. "
                            + "Set CLAUDE_API_KEY in your environment to enable this provider."));
        }

        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                Map<String, Object> body = buildRequestBody(s);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-api-key", apiKey);
                headers.set("anthropic-version", apiVersion);

                ClaudeResponse resp = restTemplate.postForObject(
                        apiUrl, new HttpEntity<>(body, headers), ClaudeResponse.class);

                return parseResponse(resp, s);

            } catch (HttpClientErrorException e) {
                attempt++;
                log.error("[Claude] HTTP error attempt {}/{}: {} — {}",
                          attempt, MAX_RETRIES, e.getStatusCode(), e.getResponseBodyAsString());
                if (attempt >= MAX_RETRIES) return helper.createFailedResult(s, MAX_RETRIES, e);
            } catch (Exception e) {
                attempt++;
                log.error("[Claude] Error attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage(), e);
                if (attempt >= MAX_RETRIES) return helper.createFailedResult(s, MAX_RETRIES, e);
                try { TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return helper.createFailedResult(s, MAX_RETRIES, ie); }
            }
        }
        return helper.createFailedResult(s, MAX_RETRIES, new Exception("Max retries exceeded"));
    }

    private Map<String, Object> buildRequestBody(QuestionSubmission s) {
        return Map.of(
            "model",      model,
            "max_tokens", 512,
            "messages",   List.of(Map.of("role", "user", "content", helper.buildEvaluationPrompt(s)))
        );
    }

    private QuestionEvaluationResult parseResponse(ClaudeResponse resp, QuestionSubmission s) {
        if (resp == null || resp.content == null || resp.content.isEmpty()) {
            throw new IllegalArgumentException("Empty Claude response");
        }
        String text = resp.content.get(0).text
                          .replaceAll("^```json\\s*|\\s*```$", "").trim();

        double score = Math.max(0, Math.min(helper.extractDoubleValue(text, "score"), s.getMaxMarks()));
        return new QuestionEvaluationResult(
                s.getQuizId(), s.getTqid(), s.getQuestionNumber(),
                s.getQuestion(), s.getStudentAnswer(),
                score, s.getMaxMarks(),
                helper.extractStringValue(text, "feedback"),
                helper.extractStringArray(text, "keyMissed"));
    }

    // ── Inner response DTOs for Claude's Messages API ────────────────────────

    static class ClaudeResponse {
        public List<ContentBlock> content;
    }

    static class ContentBlock {
        public String type;
        public String text;
    }
}
