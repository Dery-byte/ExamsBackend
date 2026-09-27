package com.exam.config;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.repository.*;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import com.exam.service.examops.ExamAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ownership checks driven by path variables, applied after the role rules in {@link EndpointRules}:
 * <ul>
 *   <li>Students may only name themselves in a user-id path variable (own results, own answers).</li>
 *   <li>Lecturers may only act on quizzes they created or whose course they teach.</li>
 *   <li>HODs may only act on quizzes, students, lecturers and programs of their own department.</li>
 * </ul>
 * The Super Admin passes every check. Endpoints whose quiz id is in the request body call
 * {@link #requireQuiz} themselves.
 */
@Configuration
public class QuizOwnershipInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Set<String> QUIZ_VARS = Set.of("quizId", "qid", "qId", "quiz_Id");
    private static final Set<String> USER_VARS = Set.of("userId", "user_Id", "uid");

    @Autowired private CurrentUserService currentUserService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionsRepository questionsRepository;
    @Autowired private TheoryQuestionsRepository theoryQuestionsRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProgramRepository programRepository;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/auth/**")
                .excludePathPatterns("/api/v1/auth/quiz/*/public-summary", "/api/v1/auth/programs/**", "/api/v1/auth/question-images/**");
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || vars.isEmpty()) return true;
        Optional<User> caller = currentUserService.current();
        if (caller.isEmpty()) return true;                       // role rules already require sign-in where needed
        User u = caller.get();
        if (u.getRole() == Role.SUPER_ADMIN) return true;
        String path = request.getRequestURI();

        // Students: user-id path variables must be their own
        if (u.getRole() == Role.NORMAL) {
            for (String v : USER_VARS) {
                Long id = asLong(vars.get(v));
                if (id != null && !id.equals(u.getId())) return deny(response, "You can only view your own results.");
            }
            return true;
        }

        // Staff: quiz ownership
        for (String v : QUIZ_VARS) {
            Long qid = asLong(vars.get(v));
            if (qid == null) continue;
            Quiz quiz = quizRepository.findById(qid).orElse(null);
            if (quiz != null && !ExamAccess.canManageQuiz(u, quiz)) return deny(response, "You don't manage this quiz.");
        }
        Long quesId = asLong(vars.get("quesId"));
        if (quesId != null) {
            Quiz quiz = path.contains("/theoryquestion/")
                    ? theoryQuestionsRepository.findById(quesId).map(t -> t.getQuiz()).orElse(null)
                    : questionsRepository.findById(quesId).map(q -> q.getQuiz()).orElse(null);
            if (quiz != null && !ExamAccess.canManageQuiz(u, quiz)) return deny(response, "You don't manage this quiz.");
        }

        // HODs: people and programs must be in their department
        if (u.getRole() == Role.ADMIN) {
            for (String v : new String[]{"id", "studentId", "lecturerId"}) {
                Long id = asLong(vars.get(v));
                if (id == null || !isPersonPath(path)) continue;
                User target = userRepository.findById(id).orElse(null);
                if (target != null && target.getRole() != Role.SUPER_ADMIN && target.getRole() != Role.ADMIN
                        && !NotificationService.inDepartment(target, u.getDepartment()))
                    return deny(response, "That person is not in your department.");
            }
            Long programId = asLong(vars.get("programId"));
            if (programId != null) {
                Program p = programRepository.findById(programId).orElse(null);
                if (p != null && (p.getDepartment() == null || u.getDepartment() == null
                        || !p.getDepartment().getId().equals(u.getDepartment().getId())))
                    return deny(response, "That program is not in your department.");
            }
        }
        return true;
    }

    /** For endpoints that carry the quiz id in the request body. Throws 403 when the caller may not manage it. */
    public void requireQuiz(Long quizId) {
        if (quizId == null) return;
        User u = currentUserService.current().orElse(null);
        if (u == null || u.getRole() == Role.SUPER_ADMIN) return;
        Quiz quiz = quizRepository.findById(quizId).orElse(null);
        if (quiz != null && !ExamAccess.canManageQuiz(u, quiz))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "You don't manage this quiz.");
    }

    /** Body-carried question id → its quiz must be manageable by the caller. */
    public void requireQuestion(Long quesId) {
        if (quesId != null) questionsRepository.findById(quesId).ifPresent(q -> requireQuiz(q.getQuiz() != null ? q.getQuiz().getqId() : null));
    }

    public void requireTheoryQuestion(Long tqId) {
        if (tqId != null) theoryQuestionsRepository.findById(tqId).ifPresent(t -> requireQuiz(t.getQuiz() != null ? t.getQuiz().getqId() : null));
    }

    /** Path variables named id/studentId/lecturerId refer to users only on these endpoints. */
    private static boolean isPersonPath(String path) {
        return path.matches(".*/(update/(student|lecturer)|student|lecturer|studentbyId|lecturerbyId|admin/student|admin/unenroll-student|courses/\\d+/assign)/.*");
    }

    private static Long asLong(String v) {
        if (v == null) return null;
        try { return Long.valueOf(v); } catch (NumberFormatException e) { return null; }
    }

    private static boolean deny(HttpServletResponse response, String message) throws java.io.IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
