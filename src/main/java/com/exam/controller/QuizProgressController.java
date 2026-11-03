package com.exam.controller;

import com.exam.DTO.*;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.QuizProgressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/quiz-progress")
public class QuizProgressController {

    @Autowired
    private QuizProgressService service;

    @Autowired
    private final UserDetailsService userDetailsService;

    public QuizProgressController(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @PostMapping("/update")
    public ResponseEntity<QuizProgressResponse> updateAnswer(
            Principal principal,
            @RequestBody QuizProgressRequest request) {
        User user = signedIn(principal);
        QuizProgressResponse response = service.updateAnswer(user.getId(), request);

        return ResponseEntity.ok(response);
    }

    /** Several answer changes in one request (the exam page's save queue), applied in order. */
    @PostMapping("/update-batch")
    public ResponseEntity<Map<String, Integer>> updateAnswers(
            Principal principal,
            @RequestBody List<QuizProgressRequest> changes) {
        User user = signedIn(principal);
        return ResponseEntity.ok(Map.of("saved", service.updateAnswers(user.getId(), changes)));
    }

    @GetMapping("/all")
    public ResponseEntity<UserQuizProgressResponse> getAllAnswers(Principal principal) {
        User user = signedIn(principal);
        UserQuizProgressResponse response = service.getAllAnswers(user.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/quiz/{quizId}")
    public ResponseEntity<UserQuizProgressResponse> getAnswersByQuiz(
            Principal principal,
            @PathVariable Long quizId) {
        User user = signedIn(principal);
        UserQuizProgressResponse response = service.getAnswersByQuiz(user.getId(), quizId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/quiz/{quizId}")
    public ResponseEntity<Void> clearQuizAnswers(
            Principal principal,
            @PathVariable Long quizId) {
        User user = signedIn(principal);
        service.clearAnswers(user.getId(), quizId);
        return ResponseEntity.noContent().build();
    }



//    @GetMapping("/getViolation-delay/{quizId}")
//    public ResponseEntity<Integer> getViolationDelayTime(
//            @PathVariable Long quizId,
//            @RequestParam Long userId) {
//        Integer delayTime = service.getViolationDelayTime(quizId, userId);
//        return ResponseEntity.ok(delayTime);
//    }
//
//
//
//    @PostMapping("/saveViolation-delay")
//    public ResponseEntity<ViolationTimerResponseDTO> saveViolationDelayTime(
//            @RequestParam Long quizId,
//           @RequestBody VoilationTimerRequestDTO request,  Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        ViolationTimerResponseDTO timer = service.saveViolationDelayTime(user.getId(), quizId, request);
//        return ResponseEntity.ok(timer);
//    }












    /** The signed-in user the sign-in filter already loaded (no second lookup per exam save). */
    private User signedIn(Principal principal) {
        return CurrentUserService.loadedFor(principal)
                .orElseGet(() -> (User) userDetailsService.loadUserByUsername(principal.getName()));
    }
}