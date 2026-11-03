package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import com.exam.service.QuizDeletionService;
import com.exam.service.comms.CurrentUserService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Super Admin only (the /super-admin path is locked to that role): deletes a quiz that students
 * have already taken, with all their results. A reason is required and goes into the audit log
 * with the Super Admin's username, the quiz and how many students lost results.
 */
@RestController
@RequestMapping("/api/v1/super-admin/quizzes")
public class SuperAdminQuizController {

    static final int MIN_REASON = 10;
    static final int MAX_REASON = 500;

    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizDeletionService quizDeletionService;
    @Autowired private CurrentUserService currentUserService;

    @PostMapping("/{qid}/delete-with-results")
    public Map<String, Object> deleteWithResults(@PathVariable("qid") Long qid,
                                                 @RequestBody Map<String, String> body,
                                                 HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : body.get("reason").trim();
        if (reason.length() < MIN_REASON) {
            throw new IllegalArgumentException("Please give a reason of at least " + MIN_REASON + " characters.");
        }
        if (reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("Please keep the reason under " + MAX_REASON + " characters.");
        }

        Quiz quiz = quizRepository.findById(qid)
                .orElseThrow(() -> new EntityNotFoundException("Quiz not found with id: " + qid));
        long students = quizDeletionService.studentsWithResults(qid);
        String course = quiz.getCategory() == null ? "no course"
                : quiz.getCategory().getTitle() + (quiz.getCategory().getCourseCode() != null
                        ? " (" + quiz.getCategory().getCourseCode() + ")" : "");
        String username = currentUserService.current().map(User::getUsername).orElse("unknown");

        // The audit entry is written after the response, with the time; it records failures too
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS,
                "Quiz \"" + quiz.getTitle() + "\" (id " + qid + ") in " + course
                        + " · results of " + students + (students == 1 ? " student" : " students") + " deleted"
                        + " · by username " + username
                        + " · Reason: " + reason);

        quizDeletionService.purge(qid);
        return Map.of("deleted", true, "students", students);
    }
}
