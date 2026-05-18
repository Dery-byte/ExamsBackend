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
            User user = (User) userDetailsService.loadUserByUsername(principal.getName());
            byte[] pdf = pdfReportService.generateReportPdf(quizId, user.getId());


            Quiz quiz = quizService.getQuiz(quizId);
            String safeCourseName = quiz.getCategory() != null ? quiz.getCategory().getTitle() : "Course";
            String safeQuizTitle = quiz.getTitle() != null ? quiz.getTitle() : "Quiz";

            System.out.println(safeCourseName);
            System.out.println(safeQuizTitle);


            String filename = "ResultsSlips_" + safeCourseName + "_" + safeQuizTitle + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate report: " + e.getMessage());
        }
    }
}
