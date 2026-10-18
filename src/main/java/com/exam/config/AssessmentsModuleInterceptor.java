package com.exam.config;

import com.exam.controller.*;
import com.exam.service.SystemSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * The developer's Assessments switch, enforced on the server: while it is off, every request to
 * the quiz module (quizzes, questions, theory, attempts, timers, progress, answers, marking,
 * results, question bank, exam timetable, proctoring, re-marks, quiz analytics) is refused with
 * 403 ASSESSMENTS_OFF, for every role. The pages are hidden too; this stops typed-in addresses.
 * Question images stay served: profile photos use the same store.
 */
@Configuration
public class AssessmentsModuleInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** Controllers that belong entirely to the quiz module. */
    static final Set<Class<?>> ASSESSMENT_CONTROLLERS = Set.of(
            QuizController.class, QuestionsController.class, TheoryController.class, NumberOfTheoryToAnswerController.class,
            QuizAttemptController.class, QuizTimerController.class, QuizProgressController.class, TheoryProgressController.class,
            AnswerController.class, GradeController.class, GeminiController.class, QuizGPTController.class, LlmProviderController.class,
            QuizResultsController.class, ReportController.class, PdfReportController.class, ExamOpsController.class);

    @Autowired private SystemSettingService settings;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        if (!belongsToAssessments(request, handler) || settings.assessmentsOn()) return true;
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"ASSESSMENTS_OFF\",\"message\":\"Assessments are switched off for this institution.\"}");
        return false;
    }

    static boolean belongsToAssessments(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod hm && ASSESSMENT_CONTROLLERS.contains(hm.getBeanType())) return true;
        // Quiz analytics live with other dashboards' endpoints
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/analytics/");
    }
}
