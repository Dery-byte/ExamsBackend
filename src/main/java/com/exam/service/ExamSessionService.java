package com.exam.service;

import com.exam.DTO.QuestionResponseDTO;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.repository.UserRepository;
import com.exam.service.comms.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything the exam page loads when it opens, in one request instead of nine: the quiz, its objective
 * and theory questions, the theory settings, the clock, saved answers and the violation state.
 * Each part is built by the same service calls as its own endpoint (which all remain), so the content
 * is identical. As on the page before, a part that fails comes back null instead of failing the rest;
 * only the access check fails the whole request.
 */
@Service
public class ExamSessionService {

    private static final Logger log = LoggerFactory.getLogger(ExamSessionService.class);

    @Autowired private QuizService quizService;
    @Autowired private AttemptService attemptService;
    @Autowired private QuestionsService questionsService;
    @Autowired private TheoryService theoryService;
    @Autowired private NumberOfTheoryToAnswerService numberOfTheoryToAnswerService;
    @Autowired private QuizTimerService quizTimerService;
    @Autowired private QuizProgressService quizProgressService;
    @Autowired private TheoryProgressService theoryProgressService;
    @Autowired private UserRepository users;

    public Map<String, Object> open(Long quizId, Principal principal) {
        User me = principal == null ? null : CurrentUserService.loadedFor(principal)
                .or(() -> users.findByUsername(principal.getName())).orElse(null);
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");

        quizService.assertStudentMayAccess(quizId, principal);
        Quiz quiz = quizService.getQuiz(quizId);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("quiz", quiz);
        // Begins/resumes the attempt once for both sections (the separate endpoints each did it)
        List<QuestionResponseDTO> questions = part("questions", () -> objectiveQuestions(quizId, principal));
        s.put("questions", questions);
        s.put("theoryQuestions", questions == null ? null
                : part("theory questions", () -> theoryService.getTheoryQuestionsForStudent(quizId)));
        s.put("theorySettings", part("theory settings", () -> numberOfTheoryToAnswerService.TheoryTOByQuiz_Id(quiz)));
        s.put("timer", part("timer", () -> quizTimerService.getQuizTimer(me.getId(), quizId)));
        s.put("answers", part("answers", () -> quizProgressService.getAnswersByQuiz(me.getId(), quizId)));
        s.put("theoryAnswers", part("theory answers", () -> theoryProgressService.getAnswers(me, quiz)));
        s.put("violationDelay", part("violation delay", () -> quizTimerService.getViolationDelayTime(quizId, me.getId())));
        s.put("violationCount", part("violation count", () -> quizTimerService.getViolationCount(quizId, me.getId())));
        return s;
    }

    /**
     * The objective questions a caller gets (after the access check): a student begins or resumes their
     * attempt and gets its questions (a question-pool quiz: its draw), with the attempt's option order;
     * staff get a preview. 409 when the student has no attempts left.
     */
    public List<QuestionResponseDTO> objectiveQuestions(Long quizId, Principal principal) {
        AttemptService.StudentQuestions mine = attemptService.beginAndDrawIfStudent(principal, quizId);
        List<Long> drawn = mine == null ? null : mine.drawn();
        if (mine == null) {
            Quiz quiz = quizService.getQuiz(quizId);
            if (quiz.drawsQuestions()) drawn = questionsService.previewDraw(quiz);
        }
        return questionsService.getQuestionsForStudent(quizId, drawn, mine == null ? null : mine.attemptId());
    }

    private static <T> T part(String what, Supplier<T> load) {
        try {
            return load.get();
        } catch (RuntimeException e) {
            log.debug("[EXAM] Could not load the {} for the exam page: {}", what, e.getMessage());
            return null;
        }
    }
}
