package com.exam.service;

import com.exam.DTO.QuizEvaluationResult;
import com.exam.model.QuizType;
import com.exam.model.User;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.StudentAnswer;
import com.exam.repository.ReportRepository;
import com.exam.repository.StudentAnswerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Marking a question-pool quiz: exactly the attempt's drawn questions count, whatever the browser sends. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizEvaluationPoolTest {

    @Mock UserDetailsService      userDetailsService;
    @Mock QuizService             quizService;
    @Mock QuestionsService        questionsService;
    @Mock ReportService           reportService;
    @Mock ReportRepository        reportRepository;
    @Mock StudentAnswerRepository studentAnswerRepository;
    @Mock AttemptService          attemptService;

    @InjectMocks QuizEvaluationServiceImpl service;

    final Map<Long, Questions> db = new HashMap<>();
    User student;
    Quiz quiz;

    @BeforeEach
    void setUp() {
        student = new User();
        student.setId(1L);
        student.setUsername("ada");

        quiz = new Quiz();
        quiz.setqId(10L);
        quiz.setQuizType(QuizType.OBJ);
        quiz.setMaxMarks(10.0);
        quiz.setNumberOfQuestions(4);
        quiz.setQuestionPool(true);

        Quiz other = new Quiz();
        other.setqId(99L);
        for (long id = 1; id <= 8; id++) db.put(id, question(id, quiz));
        db.put(50L, question(50L, other));

        when(userDetailsService.loadUserByUsername("ada")).thenReturn(student);
        when(quizService.getQuiz(10L)).thenReturn(quiz);
        when(questionsService.get(anyLong())).thenAnswer(i -> db.get((Long) i.getArgument(0)));
        when(questionsService.findInOrder(anyList())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0);
            return ids.stream().map(db::get).filter(Objects::nonNull).toList();
        });
        when(reportRepository.findByUserAndQuiz(any(User.class), any(Quiz.class))).thenReturn(Optional.empty());
    }

    private static Questions question(long id, Quiz quiz) {
        Questions q = new Questions();
        q.setQuesId(id);
        q.setQuiz(quiz);
        q.setOption1("right");
        q.setOption2("wrong");
        q.setcorrect_answer(new String[]{"right"});
        return q;
    }

    private static Questions answer(long id, String given) {
        Questions q = new Questions(given == null ? null : new String[]{given});
        q.setQuesId(id);
        return q;
    }

    @Test
    void onlyTheDrawnQuestionsAreMarkedEachWorthMaxMarksOverTheDraw() {
        when(attemptService.drawnForActiveAttempt(student, quiz)).thenReturn(List.of(3L, 1L, 7L, 5L));

        QuizEvaluationResult r = service.evaluateQuiz(List.of(
                answer(3, "right"),
                answer(1, "wrong"),
                answer(2, "right"),     // not drawn for this student: ignored
                answer(50, "right"),    // another quiz: ignored
                answer(3, "wrong")      // sent twice: the first one counts
                // 7 and 5 never sent: blank
        ), "ada", 10L);

        assertThat(r.getMarksGot()).isEqualTo(2.5);   // 1 right of 4 drawn, 10 marks
        assertThat(r.getCorrectAnswers()).isEqualTo(1);
        assertThat(r.getAttempted()).isEqualTo(2);
        verify(attemptService).recordObjective(eq(student), eq(quiz), argThat(m -> m.compareTo(new BigDecimal("2.5")) == 0));

        // An answer row for every drawn question (blank ones too), and none for anything else
        ArgumentCaptor<StudentAnswer> saved = ArgumentCaptor.forClass(StudentAnswer.class);
        verify(studentAnswerRepository, times(4)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(a -> a.getQuestion().getQuesId()).containsExactly(3L, 1L, 7L, 5L);
    }

    @Test
    void aTamperedShortSubmissionCannotRaiseTheMarksPerQuestion() {
        when(attemptService.drawnForActiveAttempt(student, quiz)).thenReturn(List.of(1L, 2L, 3L, 4L));
        QuizEvaluationResult r = service.evaluateQuiz(List.of(answer(1, "right")), "ada", 10L);
        assertThat(r.getMarksGot()).isEqualTo(2.5);
    }

    @Test
    void anEmptySubmissionStillRecordsZeroForTheDraw() {
        when(attemptService.drawnForActiveAttempt(student, quiz)).thenReturn(List.of(1L, 2L, 3L, 4L));
        QuizEvaluationResult r = service.evaluateQuiz(List.of(), "ada", 10L);
        assertThat(r.getMarksGot()).isZero();
        verify(attemptService).recordObjective(eq(student), eq(quiz), argThat(m -> m.signum() == 0));
    }

    @Test
    void aDrawnQuestionDeletedSinceIsLeftOut() {
        when(attemptService.drawnForActiveAttempt(student, quiz)).thenReturn(List.of(1L, 2L, 404L, 4L));
        QuizEvaluationResult r = service.evaluateQuiz(List.of(answer(1, "right"), answer(2, "right")), "ada", 10L);
        assertThat(r.getMarksGot()).isCloseTo(20.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void withoutARecordedDrawOnlyThisQuizsQuestionsCountOutOfNumberOfQuestions() {
        when(attemptService.drawnForActiveAttempt(student, quiz)).thenReturn(null);
        QuizEvaluationResult r = service.evaluateQuiz(List.of(
                answer(1, "right"), answer(2, "right"), answer(50, "right")), "ada", 10L);
        assertThat(r.getMarksGot()).isEqualTo(5.0);   // 2 right, marked out of 4
    }

    @Test
    void aQuizWithoutAPoolIsMarkedAsBefore() {
        quiz.setQuestionPool(false);
        QuizEvaluationResult r = service.evaluateQuiz(List.of(answer(1, "right"), answer(2, "wrong")), "ada", 10L);
        assertThat(r.getMarksGot()).isEqualTo(5.0);
        verify(attemptService, never()).drawnForActiveAttempt(any(), any());
    }
}
