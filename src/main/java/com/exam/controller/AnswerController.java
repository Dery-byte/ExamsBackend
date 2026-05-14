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
        return ResponseEntity.ok(answerService.getAnswersByUserAndQuiz(user.getId(), quizId));
    }


}
