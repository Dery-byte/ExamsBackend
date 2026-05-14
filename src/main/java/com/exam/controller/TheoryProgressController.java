package com.exam.controller;


import com.exam.DTO.TheoryProgressDTO;
import com.exam.model.exam.Quiz;
import com.exam.model.User;
import com.exam.service.QuizService;
import com.exam.service.TheoryProgressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth/theory-progress")
public class TheoryProgressController {
    @Autowired
    private TheoryProgressService theoryProgressService;
    @Autowired
    private QuizService quizService;

//    @PostMapping("/save/{quizId}")
//    public ResponseEntity<?> saveAnswers(
//            @PathVariable Long quizId,
//            @RequestBody List<TheoryProgressDTO> answers,
//            @AuthenticationPrincipal User user) {
//        try {
//            Quiz quiz = quizService.getQuiz(quizId);
//            theoryProgressService.saveAnswers(answers, user, quiz);
//            return ResponseEntity.ok("Answers saved successfully");
//        } catch (Exception e) {
//            return ResponseEntity.badRequest().body("Error saving answers: " + e.getMessage());
//        }
//    }



    @PostMapping("/save/{quizId}")
    public ResponseEntity<?> saveAnswers(
            @PathVariable Long quizId,
            @RequestBody List<TheoryProgressDTO> answers,
            @AuthenticationPrincipal User user) {
        try {
            Quiz quiz = quizService.getQuiz(quizId);
            theoryProgressService.saveAnswers(answers, user, quiz);
            // Return a Map that will be automatically converted to JSON
            return ResponseEntity.ok(Map.of("message", "Answers saved successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Error saving answers: " + e.getMessage()));
        }
    }






    @GetMapping("/load/{quizId}")
    public ResponseEntity<List<TheoryProgressDTO>> loadAnswers(
            @PathVariable Long quizId,
            @AuthenticationPrincipal User user) {

        try {
            Quiz quiz = quizService.getQuiz(quizId);
            List<TheoryProgressDTO> answers = theoryProgressService.getAnswers(user, quiz);
            return ResponseEntity.ok(answers);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    @DeleteMapping("/clear/{quizId}")
    public ResponseEntity<?> clearAnswers(
            @PathVariable Long quizId,
            @AuthenticationPrincipal User user) {
        try {
            Quiz quiz = quizService.getQuiz(quizId);
            theoryProgressService.clearAnswers(user, quiz);
            return ResponseEntity.ok("Answers cleared successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error clearing answers: " + e.getMessage());
        }
    }




//    @DeleteMapping("/clear-all")
//    public ResponseEntity<?> clearAllUserAnswers(@AuthenticationPrincipal User user) {
//        try {
//            theoryProgressService.clearAllUserAnswers(user);
//            return ResponseEntity.ok(Map.of(
//                    "message", "All answers cleared successfully"
//            ));
//        } catch (Exception e) {
//            return ResponseEntity.badRequest().body(Map.of(
//                    "error", "Error clearing all answers: " + e.getMessage()
//            ));
//        }
//    }
}