//package com.exam.controller;
//
//import com.exam.DTO.QuizTimerRequestDTO;
//import com.exam.DTO.QuizTimerResponseDTO;
//import com.exam.DTO.ViolationTimerResponseDTO;
//import com.exam.DTO.VoilationTimerRequestDTO;
//import com.exam.model.User;
//import com.exam.service.QuizTimerService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.web.bind.annotation.*;
//
//import java.security.Principal;
//
//@RestController
//@RequestMapping("/api/v1/auth/quiz-timer")
//public class QuizTimerController {
//
//    @Autowired
//    private final QuizTimerService quizTimerService;
//
//    @Autowired
//    private final UserDetailsService userDetailsService;
//
//    public QuizTimerController(QuizTimerService quizTimerService, UserDetailsService userDetailsService) {
//        this.quizTimerService = quizTimerService;
//        this.userDetailsService = userDetailsService;
//    }
//
//    @GetMapping("/getRemainingTime/{quizId}")
//    public ResponseEntity<QuizTimerResponseDTO> getQuizTimer(
//            @PathVariable Long quizId,
//             Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        QuizTimerResponseDTO timer = quizTimerService.getQuizTimer(user.getId(), quizId);
//        if (timer == null) {
//            return ResponseEntity.notFound().build();
//        }
//        return ResponseEntity.ok(timer);
//    }
//
//    @PostMapping("/saveRemainingTime/{quizId}")
//    public ResponseEntity<QuizTimerResponseDTO> saveQuizTimer(
//            @PathVariable Long quizId,
//            @RequestBody QuizTimerRequestDTO request,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        QuizTimerResponseDTO timer = quizTimerService.saveQuizTimer(user.getId(), quizId, request);
//
//        return ResponseEntity.ok(timer);
//    }
//
//
//
//
//
//
//
//
//
//    @DeleteMapping("/deleteRemainingTime/{quizId}")
//    public ResponseEntity<Void> deleteQuizTimer(
//            @PathVariable Long quizId,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        quizTimerService.deleteQuizTimer(user.getId(), quizId);
//        return ResponseEntity.noContent().build();
//    }
//
//
//
//
//
//    @GetMapping("/getViolation-delay/{quizId}")
//    public ResponseEntity<ViolationTimerResponseDTO> getViolationDelayTime(
//            @PathVariable Long quizId,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        ViolationTimerResponseDTO response = quizTimerService.getViolationDelayTime(quizId, user.getId());
//        return ResponseEntity.ok(response);
//    }
//
//    @PostMapping("/saveViolation-delay/{quizId}")
//    public ResponseEntity<ViolationTimerResponseDTO> saveViolationDelayTime(
//            @PathVariable Long quizId,
//            @RequestBody VoilationTimerRequestDTO request,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        ViolationTimerResponseDTO response = quizTimerService.saveViolationDelayTime(quizId, user.getId(), request);
//        return ResponseEntity.ok(response);
//    }
//
//
//
//
//
//
//
//    @GetMapping("/getViolationCount/{quizId}")
//    public ResponseEntity<ViolationTimerResponseDTO> getViolationCount(
//            @PathVariable Long quizId,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        ViolationTimerResponseDTO response = quizTimerService.getViolationCount(quizId, user.getId());
//        return ResponseEntity.ok(response);
//    }
//
//    @PostMapping("/saveViolationCount/{quizId}")
//    public ResponseEntity<ViolationTimerResponseDTO> saveViolationCount(
//            @PathVariable Long quizId,
//            @RequestBody VoilationTimerRequestDTO request,
//            Principal principal) {
//        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//        ViolationTimerResponseDTO response = quizTimerService.saveViolationCount(quizId, user.getId(), request);
//        return ResponseEntity.ok(response);
//    }
//}









package com.exam.controller;

import com.exam.DTO.QuizTimerRequestDTO;
import com.exam.DTO.QuizTimerResponseDTO;
import com.exam.DTO.ViolationTimerResponseDTO;
import com.exam.DTO.VoilationTimerRequestDTO;
import com.exam.model.User;
import com.exam.service.QuizTimerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/auth/quiz-timer")
public class QuizTimerController {

    @Autowired
    private final QuizTimerService quizTimerService;

    @Autowired
    private final UserDetailsService userDetailsService;

    public QuizTimerController(QuizTimerService quizTimerService,
                               UserDetailsService userDetailsService) {
        this.quizTimerService = quizTimerService;
        this.userDetailsService = userDetailsService;
    }

    // ─────────────────────────────────────────────
    //  TIMER
    // ─────────────────────────────────────────────

    /**
     * Called ONCE when the student loads or reloads the quiz on any device.
     *
     * Response status field tells the client what to do:
     *   "saved"     → resume countdown from remainingTime
     *   "not_found" → no checkpoint yet, start from full quiz duration
     */
    @GetMapping("/getRemainingTime/{quizId}")
    public ResponseEntity<QuizTimerResponseDTO> getQuizTimer(
            @PathVariable Long quizId,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        QuizTimerResponseDTO timer = quizTimerService.getQuizTimer(user.getId(), quizId);
        return ResponseEntity.ok(timer); // never 404 — status field handles "not_found"
    }

    /**
     * Changed from POST to PATCH — semantically correct for a partial update.
     *
     * Called by the client on:
     *   - Every 60 seconds (not 5 seconds)
     *   - Tab/window blur (student switches app or minimises)
     *   - beforeunload (student closes tab or navigates away)
     */
    @PatchMapping("/saveRemainingTime/{quizId}")
    public ResponseEntity<QuizTimerResponseDTO> saveQuizTimer(
            @PathVariable Long quizId,
            @RequestBody QuizTimerRequestDTO request,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        QuizTimerResponseDTO timer = quizTimerService.saveQuizTimer(user.getId(), quizId, request);
        return ResponseEntity.ok(timer);
    }

    /**
     * Called on quiz submit to clean up the timer row.
     * Unchanged from your original.
     */
    @DeleteMapping("/deleteRemainingTime/{quizId}")
    public ResponseEntity<Void> deleteQuizTimer(
            @PathVariable Long quizId,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        quizTimerService.deleteQuizTimer(user.getId(), quizId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    //  VIOLATION DELAY — unchanged
    // ─────────────────────────────────────────────

    @GetMapping("/getViolation-delay/{quizId}")
    public ResponseEntity<ViolationTimerResponseDTO> getViolationDelayTime(
            @PathVariable Long quizId,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        return ResponseEntity.ok(
                quizTimerService.getViolationDelayTime(quizId, user.getId()));
    }

    @PostMapping("/saveViolation-delay/{quizId}")
    public ResponseEntity<ViolationTimerResponseDTO> saveViolationDelayTime(
            @PathVariable Long quizId,
            @RequestBody VoilationTimerRequestDTO request,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        return ResponseEntity.ok(
                quizTimerService.saveViolationDelayTime(quizId, user.getId(), request));
    }










































    // ─────────────────────────────────────────────
    //  VIOLATION COUNT — unchanged
    // ─────────────────────────────────────────────

    @GetMapping("/getViolationCount/{quizId}")
    public ResponseEntity<ViolationTimerResponseDTO> getViolationCount(
            @PathVariable Long quizId,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        return ResponseEntity.ok(
                quizTimerService.getViolationCount(quizId, user.getId()));
    }


    @PostMapping("/saveViolationCount/{quizId}")
    public ResponseEntity<ViolationTimerResponseDTO> saveViolationCount(
            @PathVariable Long quizId,
            @RequestBody VoilationTimerRequestDTO request,
            Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        return ResponseEntity.ok(
                quizTimerService.saveViolationCount(quizId, user.getId(), request));
    }


}