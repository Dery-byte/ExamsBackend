package com.exam.controller;
import com.exam.model.User;
import com.exam.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth/quiz-results")
public class QuizResultsController {

    @Autowired
    private ReportService reportService;

    /**
     * Get all reports and answers for a quiz
     * GET /api/quiz-results/quiz/{quizId}
     */
    @GetMapping("/quiz/{quizId}")
    public ResponseEntity<?> getQuizResults(@PathVariable Long quizId) {
        try {
            Map<String, Object> results = reportService.getQuizReportsWithAnswers(quizId);
            return ResponseEntity.ok(Map.of("success", true, "data", results));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get specific report with answers
     * GET /api/quiz-results/report/{reportId}
     */
    @GetMapping("/report/{reportId}")
    public ResponseEntity<?> getReport(@PathVariable Long reportId) {
        try {
            Map<String, Object> result = reportService.getReportWithAnswers(reportId);
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get current user's reports for a quiz
     * GET /api/quiz-results/my-quiz/{quizId}
     */
    @GetMapping("/my-quiz/{quizId}")
    public ResponseEntity<?> getMyQuizReports(
            @PathVariable Long quizId,
            @AuthenticationPrincipal User currentUser) {
        try {
            List<Map<String, Object>> reports = reportService
                    .getUserReportsForQuiz(currentUser.getId(), quizId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "userId", currentUser.getId(),
                    "quizId", quizId,
                    "count", reports.size(),
                    "data", reports
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get current user's latest report for a quiz
     * GET /api/quiz-results/my-quiz/{quizId}/latest
     */
    @GetMapping("/my-quiz/{quizId}/latest")
    public ResponseEntity<?> getMyLatestQuizReport(
            @PathVariable Long quizId,
            @AuthenticationPrincipal User currentUser) {
        try {
            Map<String, Object> result = reportService
                    .getLatestReportForQuiz(currentUser.getId(), quizId);
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

}