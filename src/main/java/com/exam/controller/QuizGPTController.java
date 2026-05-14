package com.exam.controller;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.QuestionEvaluationResult;
import com.exam.model.exam.TheoryQuestions;
import com.exam.repository.TheoryQuestionsRepository;
import com.exam.service.QuizGPTService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth/quizGPT")
@CrossOrigin(origins = "*", maxAge = 3600)
public class QuizGPTController {

    private static final Logger logger = LoggerFactory.getLogger(QuizGPTController.class);

    @Autowired
    private QuizGPTService quizGPTService;

    @Autowired
    private TheoryQuestionsRepository theoryQuestionsRepository;

    /**
     * Evaluate quiz answers using GPT
     * POST /api/quiz/gpt/evaluate
     */



//
//    @PostMapping("/evaluate")
//    public ResponseEntity<?> evaluateQuiz(
//            @Valid @RequestBody GeminiRequest request,
//            @AuthenticationPrincipal User currentUser) {
//
//        try {
//            logger.info("User {} is evaluating quiz with GPT", currentUser.getUsername());
//
//            // Validate request
//            if (request.getContents() == null || request.getContents().isEmpty()) {
//                return ResponseEntity.badRequest()
//                        .body(createErrorResponse("Request contents cannot be empty"));
//            }
//
//            // Evaluate quiz and get the response DTO
//            QuizEvaluationResponse response = quizGPTService.evaluateQuiz(request, currentUser);
//
//            // Log the results
//            logger.info("Quiz evaluation completed for user {}. Score: {}/{} ({}%)",
//                    currentUser.getUsername(), response.getSummary().getTotalScore(),
//                    response.getSummary().getTotalMaxMarks(), response.getSummary().getPercentage());
//
//            // Return the response
//            return ResponseEntity.ok(response);
//
//        } catch (IllegalArgumentException e) {
//            logger.error("Invalid request for user {}: {}", currentUser.getUsername(), e.getMessage());
//            return ResponseEntity.badRequest()
//                    .body(createErrorResponse("Invalid request: " + e.getMessage()));
//
//        } catch (Exception e) {
//            logger.error("Error evaluating quiz for user {}: {}", currentUser.getUsername(), e.getMessage(), e);
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(createErrorResponse("Failed to evaluate quiz: " + e.getMessage()));
//        }
//    }
//


    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateQuiz(
            @Valid @RequestBody GeminiRequest request,
            @AuthenticationPrincipal User currentUser) {

        try {
//            logger.info("=== CONTROLLER HIT ===");
//            logger.info("User: {}", currentUser != null ? currentUser.getUsername() : "NULL USER");
//            logger.info("Request contents null? {}", request.getContents() == null);
//            logger.info("Request contents size: {}", request.getContents() != null ? request.getContents().size() : "N/A");

            if (request.getContents() == null || request.getContents().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Request contents cannot be empty"));
            }

//            logger.info("=== CALLING SERVICE ===");
            QuizEvaluationResponse response = quizGPTService.evaluateQuiz(request, currentUser);
//            logger.info("=== SERVICE RETURNED ===");
//            logger.info("Report ID: {}", response.getReportId());
//            logger.info("Answers saved: {}", response.getSummary().getAnswersSaved());
//            logger.info("Total score: {}/{}", response.getSummary().getTotalScore(), response.getSummary().getTotalMaxMarks());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
//            logger.error("=== ILLEGAL ARGUMENT EXCEPTION ===");
//            logger.error("User: {}", currentUser.getUsername());
//            logger.error("Message: {}", e.getMessage(), e); // full stack trace
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Invalid request: " + e.getMessage()));
        } catch (Exception e) {
//            logger.error("=== GENERAL EXCEPTION ===");
//            logger.error("Exception type: {}", e.getClass().getName());
//            logger.error("User: {}", currentUser.getUsername());
//            logger.error("Message: {}", e.getMessage(), e); // full stack trace

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to evaluate quiz: " + e.getMessage()));
        }
    }

    /**
     * Evaluate a single question using GPT
     * POST /api/quiz/gpt/evaluate-single
     */
    @PostMapping("/evaluate-single")
    public ResponseEntity<?> evaluateSingleQuestion(
            @Valid @RequestBody GeminiRequest request,
            @AuthenticationPrincipal User currentUser) {

        try {
            logger.info("User {} is evaluating a single question with GPT", currentUser.getUsername());

            if (request.getContents() == null || request.getContents().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Request contents cannot be empty"));
            }

            if (request.getContents().size() > 1 ||
                    request.getContents().get(0).getParts().size() > 1) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Only one question allowed for single evaluation"));
            }

            List<QuestionEvaluationResult> results = (List<QuestionEvaluationResult>) quizGPTService.evaluateQuiz(request, currentUser);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Question evaluated successfully");
            response.put("result", results.get(0));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
//            logger.error("Error evaluating single question for user {}: {}",
//                    currentUser.getUsername(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to evaluate question: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint to verify GPT service is available
     * GET /api/quiz/gpt/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "UP");
            response.put("service", "QuizGPTService");
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Health check failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorResponse("Service unavailable: " + e.getMessage()));
        }
    }

    /**
     * Get service information
     * GET /api/quiz/gpt/info
     */
    @GetMapping("/info")
    public ResponseEntity<?> getServiceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("service", "GPT-powered Quiz Evaluation");
        info.put("model", "GPT-4 Turbo / GPT-3.5 Turbo");
        info.put("version", "1.0.0");
        info.put("features", List.of(
                "Automated answer evaluation",
                "Detailed feedback generation",
                "Key point identification",
                "Score calculation",
                "Retry mechanism for reliability"
        ));
        info.put("endpoints", Map.of(
                "evaluate", "POST /api/quiz/gpt/evaluate - Evaluate complete quiz",
                "evaluate-single", "POST /api/quiz/gpt/evaluate-single - Evaluate single question",
                "health", "GET /api/quiz/gpt/health - Service health check",
                "info", "GET /api/quiz/gpt/info - Service information"
        ));

        return ResponseEntity.ok(info);
    }

    // Helper methods

    private Map<String, Object> createSummary(double totalScore, double totalMaxMarks,
                                              double percentage, int questionCount) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalScore", totalScore);
        summary.put("totalMaxMarks", totalMaxMarks);
        summary.put("percentage", Math.round(percentage * 100.0) / 100.0);
        summary.put("questionsEvaluated", questionCount);
        summary.put("grade", calculateGrade(percentage));

        return summary;
    }


    /**
     * Debug: Check if a specific TQID exists in database
     * GET /api/quiz/gpt/debug/check-tqid/{tqid}
     */
    @GetMapping("/debug/check-tqid/{tqid}")
    public ResponseEntity<?> checkTqid(@PathVariable Long tqid) {
        try {
            Optional<TheoryQuestions> question = theoryQuestionsRepository.findById(tqid);

            if (question.isPresent()) {
                TheoryQuestions tq = question.get();
                Map<String, Object> response = new HashMap<>();
                response.put("exists", true);
                response.put("tqid", tqid);
                response.put("questionNumber", tq.getQuesNo());
                response.put("question", tq.getQuestion());
                response.put("marks", tq.getMarks());
                response.put("quizId", tq.getQuiz().getqId());
                response.put("quizTitle", tq.getQuiz().getTitle());

                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.ok(Map.of(
                        "exists", false,
                        "tqid", tqid,
                        "message", "Question not found in database"
                ));
            }
        } catch (Exception e) {
            logger.error("Error checking tqid {}: {}", tqid, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error checking tqid: " + e.getMessage()));
        }
    }


    private String calculateGrade(double percentage) {
        if (percentage >= 90) return "A+";
        if (percentage >= 80) return "A";
        if (percentage >= 70) return "B";
        if (percentage >= 60) return "C";
        if (percentage >= 50) return "D";
        return "F";
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }
}