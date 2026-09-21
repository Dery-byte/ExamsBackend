package com.exam.service;

import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.exam.AutoCloseFraction;
import com.exam.model.exam.NumberOfTheoryToAnswer;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Auto-close: a quiz with autoClose=true should shut itself once half (or a quarter) of its total
 * duration has elapsed since it was published. Duration = quizTime (objective) + timeAllowed
 * (theory) — the same two numbers the student's own exam timer sums — read live every check, since
 * timeAllowed is normally configured after the quiz already exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizServiceAutoCloseTest {

    @Mock QuizRepository quizRepository;
    @Mock NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
    @InjectMocks QuizService service;

    @BeforeEach
    void setUp() {
        when(quizRepository.save(any(Quiz.class))).thenAnswer(i -> i.getArgument(0));
        when(numberOfTheoryToAnswerRepository.findByQuiz_qId(anyLong())).thenReturn(List.of());
    }

    /** Published `minutesAgo` minutes ago, OBJ quiz, quarter-fraction auto-close (the 30 → 7.5 min example). */
    private Quiz openQuiz(int quizTimeMinutes, double minutesAgo) {
        Quiz q = new Quiz();
        q.setqId(1L);
        q.setQuizType(QuizType.OBJ);
        q.setQuizTime(String.valueOf(quizTimeMinutes));
        q.setActive(true);
        q.setStatus(QuizStatus.OPEN);
        q.setAutoClose(true);
        q.setAutoCloseFraction(AutoCloseFraction.QUARTER);
        q.setPublishedAt(LocalDateTime.now().minusSeconds(Math.round(minutesAgo * 60)));
        return q;
    }

    private void theoryTimeAllowed(int minutes) {
        NumberOfTheoryToAnswer n = new NumberOfTheoryToAnswer();
        n.setTimeAllowed(minutes);
        when(numberOfTheoryToAnswerRepository.findByQuiz_qId(1L)).thenReturn(List.of(n));
    }

    @Test
    void closesAtAQuarterOfDurationExactlyLikeTheWorkedExample() {
        // 18:00 start, 30 min duration, quarter → closes 7.5 minutes in
        Quiz q = openQuiz(30, 7.6);   // just past 7.5 minutes

        service.ensureAutoClosed(q);

        assertThat(q.getStatus()).isEqualTo(QuizStatus.CLOSED);
        verify(quizRepository).save(q);
    }

    @Test
    void doesNotCloseBeforeTheFractionElapses() {
        Quiz q = openQuiz(30, 7.4);   // just short of 7.5 minutes

        service.ensureAutoClosed(q);

        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);
        verify(quizRepository, never()).save(any());
    }

    @Test
    void halfFractionClosesAtHalfTheDuration() {
        Quiz q = openQuiz(30, 14.9);
        q.setAutoCloseFraction(AutoCloseFraction.HALF);
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);   // 14.9 < 15

        q.setPublishedAt(LocalDateTime.now().minusSeconds(Math.round(15.1 * 60)));
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.CLOSED);   // 15.1 >= 15
    }

    @Test
    void missingFractionDefaultsToHalfRatherThanFailing() {
        Quiz q = openQuiz(30, 14.9);
        q.setAutoCloseFraction(null);
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);

        q.setPublishedAt(LocalDateTime.now().minusSeconds(Math.round(15.1 * 60)));
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.CLOSED);
    }

    @Test
    void autoCloseOffIsANoOp() {
        Quiz q = openQuiz(30, 1000);
        q.setAutoClose(false);
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);
        verifyNoInteractions(quizRepository);
    }

    @Test
    void draftOrAlreadyClosedQuizzesAreLeftAlone() {
        Quiz draft = openQuiz(30, 1000);
        draft.setActive(false);
        service.ensureAutoClosed(draft);
        assertThat(draft.getStatus()).isEqualTo(QuizStatus.OPEN);   // not flipped despite being "due"

        Quiz alreadyClosed = openQuiz(30, 1000);
        alreadyClosed.setStatus(QuizStatus.CLOSED);
        service.ensureAutoClosed(alreadyClosed);
        verify(quizRepository, never()).save(alreadyClosed);
    }

    @Test
    void neverClosesBeforeItWasEverPublished() {
        Quiz q = openQuiz(30, 1000);
        q.setPublishedAt(null);
        service.ensureAutoClosed(q);
        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);
        verifyNoInteractions(quizRepository);
    }

    @Test
    void theoryQuizWaitsForItsTimeAllowedToBeConfiguredBeforeCountingDown() {
        Quiz q = openQuiz(0, 1000);   // OBJ minutes irrelevant/blank for a THEORY quiz
        q.setQuizType(QuizType.THEORY);
        // no NumberOfTheoryToAnswer row yet — duration is unknown

        service.ensureAutoClosed(q);

        assertThat(q.getStatus()).isEqualTo(QuizStatus.OPEN);   // must not close against a 0-minute duration
        verifyNoInteractions(quizRepository);

        // theory duration gets configured after the fact
        theoryTimeAllowed(20);
        service.ensureAutoClosed(q);   // quarter of 20 = 5 min, and it's been ~1000 min

        assertThat(q.getStatus()).isEqualTo(QuizStatus.CLOSED);
    }

    @Test
    void combinedQuizDurationIsObjectivePlusTheoryTimeExactlyLikeTheStudentTimer() {
        Quiz q = openQuiz(20, 1000);   // 20 min objective
        q.setQuizType(QuizType.BOTH);
        theoryTimeAllowed(10);         // + 10 min theory = 30 total, quarter → 7.5 min

        Quiz justUnder = openQuiz(20, 7.4);
        justUnder.setQuizType(QuizType.BOTH);
        justUnder.setqId(1L);
        service.ensureAutoClosed(justUnder);
        assertThat(justUnder.getStatus()).isEqualTo(QuizStatus.OPEN);

        service.ensureAutoClosed(q);   // published 1000 minutes ago — well past 7.5
        assertThat(q.getStatus()).isEqualTo(QuizStatus.CLOSED);
    }

    @Test
    void sweepClosesEveryDueOpenQuizAndSkipsOnesNotYetDue() {
        Quiz due = openQuiz(30, 100);
        Quiz notYetDue = openQuiz(30, 1);
        when(quizRepository.findByAutoCloseTrueAndActiveTrueAndStatus(QuizStatus.OPEN))
                .thenReturn(List.of(due, notYetDue));

        service.closeDueQuizzes();

        assertThat(due.getStatus()).isEqualTo(QuizStatus.CLOSED);
        assertThat(notYetDue.getStatus()).isEqualTo(QuizStatus.OPEN);
        verify(quizRepository, times(1)).save(any(Quiz.class));
    }
}
