package com.exam.service.llm;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.User;
import com.exam.model.exam.Answer;
import com.exam.model.exam.QuestionEvaluationResult;
import com.exam.model.exam.QuestionSubmission;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.exam.TheoryQuestions;
import com.exam.repository.AnswerRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.repository.TheoryQuestionsRepository;
import com.exam.repository.UserRepository;
import com.exam.service.AttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Saving AI marking results: an answer the AI could not mark must never be dropped (the review page
 * then showed the student as having answered nothing). While tries remain it is retried instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationPersistenceHelperTest {

    @Mock AnswerRepository answers;
    @Mock TheoryQuestionsRepository theoryQuestions;
    @Mock QuizRepository quizzes;
    @Mock ReportRepository reports;
    @Mock UserRepository users;
    @Mock AttemptService attemptService;

    @InjectMocks EvaluationPersistenceHelper helper;

    User student;
    Quiz quiz;
    final List<QuestionSubmission> submissions = List.of(
            new QuestionSubmission("1", "3", "Q1a", "Define X", "X is a thing", 10, "-"),
            new QuestionSubmission("1", "4", "Q1b", "Define Y", "Y is another thing", 10, "-"));

    @BeforeEach
    void setUp() {
        student = new User();
        student.setId(7L);
        student.setUsername("ada");
        quiz = new Quiz();
        quiz.setqId(1L);
        when(users.findById(7L)).thenReturn(Optional.of(student));
        when(quizzes.findById(1L)).thenReturn(Optional.of(quiz));
        when(reports.findByUserAndQuiz(any(User.class), any(Quiz.class))).thenReturn(Optional.empty());
        when(reports.saveAndFlush(any(Report.class))).thenAnswer(i -> i.getArgument(0));
        when(theoryQuestions.findById(anyLong())).thenAnswer(i -> {
            TheoryQuestions tq = new TheoryQuestions();
            tq.setTqId(i.getArgument(0));
            return Optional.of(tq);
        });
    }

    private static QuestionEvaluationResult marked(String tqid, String no, String answer, double score) {
        return new QuestionEvaluationResult("1", tqid, no, "q", answer, score, 10, "Good points made", List.of());
    }

    private static QuestionEvaluationResult aiFailed(String tqid, String no, String answer) {
        return new QuestionEvaluationResult("1", tqid, no, "q", answer, 0, 10,
                "Evaluation failed after 3 attempts: 404 model not found", List.of());
    }

    @Test
    void anAnswerTheAiCouldNotMarkIsStillSavedForHandMarking() {
        List<QuestionEvaluationResult> results = List.of(
                marked("3", "Q1a", "X is a thing", 6),
                aiFailed("4", "Q1b", "Y is another thing"));

        QuizEvaluationResponse res = helper.buildResponse(results, submissions, student, "GPT");

        ArgumentCaptor<Answer> saved = ArgumentCaptor.forClass(Answer.class);
        verify(answers, times(2)).saveAndFlush(saved.capture());
        Answer unmarked = saved.getAllValues().get(1);
        assertThat(unmarked.getQuesNo()).isEqualTo("Q1b");
        assertThat(unmarked.getStudentAnswer()).isEqualTo("Y is another thing");
        assertThat(unmarked.getScore()).isZero();
        assertThat(unmarked.getFeedback()).isEqualTo(EvaluationPersistenceHelper.NOT_MARKED_NOTE);
        assertThat(unmarked.getFeedback().length()).isLessThanOrEqualTo(255);

        // The unmarked question still counts towards the maximum
        assertThat(res.getSummary().getTotalScore()).isEqualTo(6);
        assertThat(res.getSummary().getTotalMaxMarks()).isEqualTo(20);
        assertThat(res.getSummary().getAnswersSaved()).isEqualTo(2);
        verify(attemptService).recordTheory(any(), eq(quiz), eq(BigDecimal.valueOf(6.0)));
    }

    @Test
    void whileTriesRemainAnAiFailureIsRetriedAndNothingIsSaved() {
        List<QuestionEvaluationResult> results = List.of(
                marked("3", "Q1a", "X is a thing", 6),
                aiFailed("4", "Q1b", "Y is another thing"));

        assertThatThrownBy(() -> EvaluationPersistenceHelper.retryingIfAiFails(true,
                () -> helper.buildResponse(results, submissions, student, "GPT")))
                .isInstanceOf(EvaluationPersistenceHelper.AiMarkingUnavailableException.class)
                .hasMessageContaining("1 of 2");

        verifyNoInteractions(answers, attemptService);
        verify(reports, never()).saveAndFlush(any());
    }

    @Test
    void whenTheAiMarksEverythingTheRetryFlagChangesNothing() {
        List<QuestionEvaluationResult> results = List.of(
                marked("3", "Q1a", "X is a thing", 6),
                marked("4", "Q1b", "Y is another thing", 8));

        QuizEvaluationResponse res = EvaluationPersistenceHelper.retryingIfAiFails(true,
                () -> helper.buildResponse(results, submissions, student, "GPT"));

        verify(answers, times(2)).saveAndFlush(any(Answer.class));
        assertThat(res.getSummary().getTotalScore()).isEqualTo(14);
        assertThat(res.getSummary().getTotalMaxMarks()).isEqualTo(20);
    }
}
