package com.exam.controller;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.service.PdfReportService;
import com.exam.service.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class PdfReportController {

    @Autowired private PdfReportService pdfReportService;
    @Autowired private UserDetailsService userDetailsService;

    @Autowired private QuizService quizService;

    /**
     * GET /api/v1/auth/report/pdf/{quizId}
     * Downloads a standardised academic PDF result slip for the authenticated student.
     */
    @GetMapping("/report/pdf/{quizId}")
    public ResponseEntity<?> downloadReportPdf(
            @PathVariable Long quizId,
            Principal principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated.");
        }

        try {
            System.out.println("\n[PDF] ▶ Step 1: Resolving user from principal: " + principal.getName());
            User user = (User) userDetailsService.loadUserByUsername(principal.getName());
            System.out.println("[PDF] ✔ Step 1 Done — userId=" + user.getId());

            System.out.println("[PDF] ▶ Step 2: Calling pdfReportService.generateReportPdf(quizId=" + quizId + ", userId=" + user.getId() + ")");
            byte[] pdf = pdfReportService.generateReportPdf(quizId, user.getId());
            System.out.println("[PDF] ✔ Step 2 Done — PDF byte size=" + (pdf != null ? pdf.length : "null"));

            System.out.println("[PDF] ▶ Step 3: Resolving quiz metadata");
            Quiz quiz = quizService.getQuiz(quizId);
            String safeCourseName = quiz.getCategory() != null ? quiz.getCategory().getTitle() : "Course";
            String safeQuizTitle  = quiz.getTitle()    != null ? quiz.getTitle()                : "Quiz";
            System.out.println("[PDF] ✔ Step 3 Done — course='" + safeCourseName + "', quiz='" + safeQuizTitle + "'");

            String filename = "ResultsSlips_" + safeCourseName + "_" + safeQuizTitle + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(pdf.length);
            System.out.println("[PDF] ✅ SUCCESS — sending PDF as: " + filename);
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);

        } catch (Exception e) {
            System.err.println("\n[PDF] ❌ FAILED at some step — quizId=" + quizId);
            System.err.println("[PDF] Error type   : " + e.getClass().getName());
            System.err.println("[PDF] Error message: " + e.getMessage());
            System.err.println("[PDF] Full stack trace:");
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate report: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }
}
