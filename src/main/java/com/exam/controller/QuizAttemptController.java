package com.exam.controller;

import com.exam.DTO.AttemptDTO;
import com.exam.DTO.AttemptStatusDTO;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import com.exam.repository.UserRepository;
import com.exam.service.AttemptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Attempt endpoints. Note: /api/v1/auth/** is permitAll at the URL level, so every endpoint here
 * resolves the caller itself and rejects anonymous / wrong-role callers.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth/quiz-attempts")
public class QuizAttemptController {

    @Autowired private AttemptService  attemptService;
    @Autowired private UserRepository  userRepository;
    @Autowired private QuizRepository  quizRepository;

    // ── Student ──────────────────────────────────────────────────────────────

    /** Where the calling student stands on this quiz (limit, used, remaining). Own data only. */
    @GetMapping("/{quizId}/my-status")
    public AttemptStatusDTO myStatus(@PathVariable Long quizId, Principal principal) {
        return attemptService.myStatus(caller(principal), quiz(quizId));
    }

    /** Starts a new attempt or resumes the one in progress. 409 when no attempt is available. */
    @PostMapping("/{quizId}/begin")
    public AttemptStatusDTO begin(@PathVariable Long quizId, Principal principal) {
        return attemptService.begin(student(principal), quiz(quizId));
    }

    /** Marks the attempt in progress as finished. */
    @PostMapping("/{quizId}/finish")
    public ResponseEntity<Void> finish(@PathVariable Long quizId, Principal principal) {
        attemptService.finish(student(principal), quiz(quizId));
        return ResponseEntity.noContent().build();
    }

    // ── Staff (super admin, HOD of the quiz's department, lecturer who owns the quiz) ──

    /** Every attempt of every student for a quiz, each with its own marks. */
    @GetMapping("/quiz/{quizId}")
    public List<AttemptDTO> attemptsForQuiz(@PathVariable Long quizId, Principal principal) {
        return attemptService.attemptsForQuiz(quizId, caller(principal));
    }

    /** Allow one student to take the quiz again. Body: { "reason": "optional note" }. */
    @PostMapping("/quiz/{quizId}/student/{studentId}/retake")
    public AttemptStatusDTO allowRetake(@PathVariable Long quizId,
                                        @PathVariable Long studentId,
                                        @RequestBody(required = false) Map<String, String> body,
                                        Principal principal) {
        String reason = body == null ? null : body.get("reason");
        return attemptService.grantRetake(quizId, studentId, caller(principal), reason);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User caller(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }

    private User student(Principal principal) {
        User u = caller(principal);
        if (u.getRole() != com.exam.model.Role.NORMAL) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students take quizzes");
        }
        return u;
    }

    private Quiz quiz(Long quizId) {
        return quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found"));
    }
}
