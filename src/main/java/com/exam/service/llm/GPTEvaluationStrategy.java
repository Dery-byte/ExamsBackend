package com.exam.service.llm;

import com.exam.DTO.GPTMessage;
import com.exam.DTO.GPTRequest;
import com.exam.DTO.GPTResponse;
import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.LlmProvider;
import com.exam.model.exam.QuestionEvaluationResult;
import com.exam.model.exam.QuestionSubmission;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LLM strategy backed by the OpenAI Chat Completions API.
 * Model is configurable via {@code openai.model} (default: gpt-3.5-turbo).
 */
@Service
public class GPTEvaluationStrategy implements LLMEvaluationStrategy {

    private static final Logger log = LoggerFactory.getLogger(GPTEvaluationStrategy.class);
    private static final int    MAX_RETRIES    = 3;
    private static final long   RETRY_DELAY_MS = 2000L;

    @Value("${openai.api.url}")
    private String apiUrl;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model:gpt-3.5-turbo}")
    private String model;

    @Autowired private RestTemplate              restTemplate;
    @Autowired private EvaluationPersistenceHelper helper;

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GPT;
    }

    @Override
    public QuizEvaluationResponse evaluate(GeminiRequest request, User user) {
        List<QuestionSubmission> submissions = helper.parseSubmissions(request);
        log.info("[GPT] Evaluating {} questions for user {} with model {}",
                 submissions.size(), user.getUsername(), model);

        List<QuestionEvaluationResult> results = submissions.stream()
                .map(this::evaluateOne)
                .collect(Collectors.toList());

        return helper.buildResponse(results, submissions, user, "GPT");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private QuestionEvaluationResult evaluateOne(QuestionSubmission s) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                if (apiKey == null || apiKey.isBlank() || apiKey.contains("${")) {
                    throw new IllegalStateException("OpenAI API key is not configured");
                }

                GPTRequest req = buildRequest(s);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(apiKey);

                GPTResponse resp = restTemplate.postForObject(
                        apiUrl, new HttpEntity<>(req, headers), GPTResponse.class);

                return parseResponse(resp, s);

            } catch (HttpClientErrorException e) {
                attempt++;
                log.error("[GPT] HTTP error attempt {}/{}: {} — {}", attempt, MAX_RETRIES,
                          e.getStatusCode(), e.getResponseBodyAsString());
                if (attempt >= MAX_RETRIES) return helper.createFailedResult(s, MAX_RETRIES, e);
            } catch (Exception e) {
                attempt++;
                log.error("[GPT] Error attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage(), e);
                if (attempt >= MAX_RETRIES) return helper.createFailedResult(s, MAX_RETRIES, e);
                try { TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return helper.createFailedResult(s, MAX_RETRIES, ie); }
            }
        }
        return helper.createFailedResult(s, MAX_RETRIES, new Exception("Max retries exceeded"));
    }

    private GPTRequest buildRequest(QuestionSubmission s) {
        GPTMessage msg = new GPTMessage();
        msg.setRole("user");
        msg.setContent(helper.buildEvaluationPrompt(s));

        GPTRequest req = new GPTRequest();
        req.setModel(model);
        req.setTemperature(0.3);
        req.setMaxTokens(500);
        req.setMessages(Collections.singletonList(msg));
        return req;
    }

    private QuestionEvaluationResult parseResponse(GPTResponse resp, QuestionSubmission s) {
        if (resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()) {
            throw new IllegalArgumentException("Empty GPT response");
        }
        String text = resp.getChoices().get(0).getMessage().getContent()
                          .replaceAll("^```json\\s*|\\s*```$", "").trim();

        double score = Math.max(0, Math.min(helper.extractDoubleValue(text, "score"), s.getMaxMarks()));
        return new QuestionEvaluationResult(
                s.getQuizId(), s.getTqid(), s.getQuestionNumber(),
                s.getQuestion(), s.getStudentAnswer(),
                score, s.getMaxMarks(),
                helper.extractStringValue(text, "feedback"),
                helper.extractStringArray(text, "keyMissed"));
    }
}
