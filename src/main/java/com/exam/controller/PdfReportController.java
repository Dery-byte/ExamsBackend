package com.exam.controller;

import com.exam.model.User;
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

            String coursetle = String.valueOf(quizService.getQuiz(quizId).getCategory());
            String quizTitle = String.valueOf(quizService.getQuiz(quizId).getTitle());

//            String filename = "ResultSlip_" + user.getUsername().toUpperCase() + "_Q" + quizId +".pdf";


            String filename = "ResultSlip_" + coursetle + "_" + quizTitle  +".pdf";

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
