package com.exam.controller;

import com.exam.DTO.AnswerDTO;
import com.exam.model.User;
import com.exam.model.exam.Answer;
import com.exam.service.AnswerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth")
public class AnswerController {
    private final AnswerService answerService;
    @Autowired
    private final UserDetailsService userDetailsService;
    @Autowired
    private com.exam.service.examops.ResultWithholdService resultWithholdService;

    public AnswerController(AnswerService answerService, UserDetailsService userDetailsService) {
        this.answerService = answerService;
        this.userDetailsService = userDetailsService;
    }

    @GetMapping("/answers/quiz/{quizId}")
//    public ResponseEntity<List<AnswerDTO>> getAnswersByQuiz(
//            @PathVariable Long quizId,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        return ResponseEntity.ok(answerService.getAnswersByUserAndQuiz(user.getId(), quizId));
//    }

//    public ResponseEntity<List<Answer>> getAnswersByQuiz(
//            @PathVariable Long quizId,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        List<Answer> answers = answerService.getAnswersByUserAndQuiz(user.getId(), quizId);
//        return ResponseEntity.ok(answers);
//    }


    public ResponseEntity<List<AnswerDTO>> getAnswersByQuiz(
            @PathVariable Long quizId,
            Principal principal) {
        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
        resultWithholdService.requireVisible(user, user.getId(), quizId);
        return ResponseEntity.ok(answerService.getAnswersByUserAndQuiz(user.getId(), quizId));
    }

    @GetMapping("/answers/by-user-quiz/{userId}/{quizId}")
    public ResponseEntity<?> getStudentAnswers(
            @PathVariable Long userId,
            @PathVariable Long quizId,
            Principal principal) {
        User caller = principal == null ? null : (User) this.userDetailsService.loadUserByUsername(principal.getName());
        if (caller == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).body(java.util.Map.of("message", "Please sign in."));
        // A student may only read their own answers; staff may read any student's
        if (caller.getRole() == com.exam.model.Role.NORMAL && !caller.getId().equals(userId)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "You can only view your own results."));
        }
        resultWithholdService.requireVisible(caller, userId, quizId);
        return ResponseEntity.ok(answerService.getAnswersByUserAndQuiz(userId, quizId));
    }

}
