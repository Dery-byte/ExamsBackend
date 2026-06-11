package com.exam.controller;

import com.exam.model.exam.LlmProvider;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import com.exam.service.llm.LLMEvaluationStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for managing LLM provider selection per quiz.
 *
 * <pre>
 * GET  /api/v1/auth/llm/providers              → list all available providers with metadata
 * GET  /api/v1/auth/llm/quiz/{quizId}/provider → get current provider for a quiz
 * PUT  /api/v1/auth/llm/quiz/{quizId}/provider → update provider for a quiz
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth/llm")
@CrossOrigin(origins = "*", maxAge = 3600)
public class LlmProviderController {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderController.class);

    @Autowired private LLMEvaluationStrategyFactory factory;
    @Autowired private QuizRepository               quizRepository;

    // ── Provider catalogue ────────────────────────────────────────────────────

    /**
     * Returns all supported LLM providers with display metadata.
     * The frontend uses this to build the provider selection dropdown.
     */
    @GetMapping("/providers")
    public ResponseEntity<?> getAvailableProviders() {
        List<Map<String, Object>> providers = factory.getAvailableProviders().stream()
                .map(this::providerMeta)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("success",   true);
        response.put("providers", providers);
        response.put("count",     providers.size());
        return ResponseEntity.ok(response);
    }

    // ── Per-quiz provider management ─────────────────────────────────────────

    /**
     * Get the currently configured LLM provider for a specific quiz.
     */
    @GetMapping("/quiz/{quizId}/provider")
    public ResponseEntity<?> getQuizProvider(@PathVariable Long quizId) {
        Quiz quiz = quizRepository.findById(quizId).orElse(null);
        if (quiz == null) {
            return ResponseEntity.notFound().build();
        }

        LlmProvider current = quiz.getLlmProvider() != null ? quiz.getLlmProvider() : LlmProvider.GPT;

        Map<String, Object> response = new HashMap<>();
        response.put("success",  true);
        response.put("quizId",   quizId);
        response.put("quizTitle", quiz.getTitle());
        response.put("provider", providerMeta(current));
        return ResponseEntity.ok(response);
    }

    /**
     * Set (or change) the LLM provider for a specific quiz.
     * Body: {@code { "provider": "GEMINI" }}
     */
    @PutMapping("/quiz/{quizId}/provider")
    public ResponseEntity<?> setQuizProvider(@PathVariable Long quizId,
                                              @RequestBody Map<String, String> body) {
        Quiz quiz = quizRepository.findById(quizId).orElse(null);
        if (quiz == null) {
            return ResponseEntity.notFound().build();
        }

        String providerStr = body.get("provider");
        if (providerStr == null || providerStr.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(error("Missing 'provider' field in request body"));
        }

        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(error("Invalid provider '" + providerStr + "'. Valid values: "
                                + List.of(LlmProvider.values())));
        }

        LlmProvider previous = quiz.getLlmProvider();
        quiz.setLlmProvider(provider);
        quizRepository.save(quiz);
        log.info("Quiz {} provider changed: {} → {}", quizId, previous, provider);

        Map<String, Object> response = new HashMap<>();
        response.put("success",          true);
        response.put("quizId",           quizId);
        response.put("quizTitle",        quiz.getTitle());
        response.put("previousProvider", previous != null ? previous.name() : "GPT");
        response.put("currentProvider",  providerMeta(provider));
        response.put("message",          "LLM provider updated successfully");
        return ResponseEntity.ok(response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> providerMeta(LlmProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.name());
        m.put("displayName", displayName(p));
        m.put("description", description(p));
        m.put("available",   isAvailable(p));
        return m;
    }

    private String displayName(LlmProvider p) {
        return switch (p) {
            case GPT      -> "OpenAI GPT";
            case GEMINI   -> "Google Gemini";
            case DEEPSEEK -> "DeepSeek";
            case CLAUDE   -> "Anthropic Claude";
        };
    }

    private String description(LlmProvider p) {
        return switch (p) {
            case GPT      -> "OpenAI's GPT models (gpt-3.5-turbo, gpt-4, etc.)";
            case GEMINI   -> "Google's Gemini models (gemini-1.5-flash, gemini-1.5-pro, etc.)";
            case DEEPSEEK -> "DeepSeek's chat models — cost-effective with strong reasoning";
            case CLAUDE   -> "Anthropic's Claude models — requires separate API key";
        };
    }

    private boolean isAvailable(LlmProvider p) {
        // Claude is optional; all others are expected to be configured
        return p != LlmProvider.CLAUDE;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("error",   message);
        return err;
    }
}
