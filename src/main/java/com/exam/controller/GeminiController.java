package com.exam.controller;

import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.QuestionEvaluationResult;
import com.exam.service.QuizGeminiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class GeminiController {

    @Autowired
    private QuizGeminiService quizGeminiService;

    @Autowired
    private final UserDetailsService userDetailsService;

    public GeminiController(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @PostMapping("/quizEval")
    public List<QuestionEvaluationResult> chat(@RequestBody GeminiRequest geminiRequest, Principal principal) throws InterruptedException {
        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
        return quizGeminiService.evaluateQuiz(geminiRequest, user);
    }
}
