package com.exam.helper;

import com.exam.model.exam.Quiz;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionALimitTest {

    private static Quiz quiz(Integer numberOfQuestions) {
        Quiz q = new Quiz();
        q.setNumberOfQuestions(numberOfQuestions);
        return q;
    }

    @Test
    void allowsUpToTheQuizTotal() {
        assertDoesNotThrow(() -> SectionALimit.require(quiz(30), 0, 30));
        assertDoesNotThrow(() -> SectionALimit.require(quiz(30), 25, 5));
        assertDoesNotThrow(() -> SectionALimit.require(quiz(30), 30, 0));
    }

    @Test
    void countsQuestionsAlreadyInTheQuiz() {
        // Two uploads of 30 into a 30-question quiz: the second is refused
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SectionALimit.require(quiz(30), 30, 30));
        assertTrue(e.getMessage().contains("allows 30 objective questions and already has 30"));
        assertTrue(e.getMessage().contains("It is full"));

        e = assertThrows(IllegalArgumentException.class, () -> SectionALimit.require(quiz(30), 25, 6));
        assertTrue(e.getMessage().contains("You can add 5 more, but tried to add 6."));
    }

    @Test
    void noLimitWhenTheQuizHasNoNumberOfQuestions() {
        assertDoesNotThrow(() -> SectionALimit.require(quiz(null), 100, 100));
    }

    // ── Question pool ────────────────────────────────────────────────────────

    private static Quiz pool(Integer numberOfQuestions, boolean live) {
        Quiz q = quiz(numberOfQuestions);
        q.setQuizType(com.exam.model.QuizType.OBJ);
        q.setQuestionPool(true);
        q.setActive(live);
        return q;
    }

    @Test
    void aPoolMayHoldMoreThanEachStudentGetsUpToTheMaximum() {
        assertDoesNotThrow(() -> SectionALimit.require(pool(30, false), 0, 200));
        assertDoesNotThrow(() -> SectionALimit.require(pool(30, false), 1800, 200));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SectionALimit.require(pool(30, false), 1900, 200));
        assertTrue(e.getMessage().contains("at most " + QuestionDraw.MAX_POOL));
    }

    @Test
    void theoryOnlyQuizIgnoresThePoolSwitch() {
        Quiz q = pool(30, false);
        q.setQuizType(com.exam.model.QuizType.THEORY);
        assertFalse(q.drawsQuestions());
        assertThrows(IllegalArgumentException.class, () -> SectionALimit.require(q, 30, 1));
    }

    @Test
    void aPoolNeedsNumberOfQuestions() {
        assertThrows(IllegalArgumentException.class, () -> SectionALimit.requireDrawSize(pool(null, false)));
        assertThrows(IllegalArgumentException.class, () -> SectionALimit.requireDrawSize(pool(0, false)));
        assertDoesNotThrow(() -> SectionALimit.requireDrawSize(pool(1, false)));
    }

    @Test
    void goingLiveNeedsOneFullDraw() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SectionALimit.requirePoolReady(pool(30, true), 25));
        assertTrue(e.getMessage().contains("Each student gets 30 questions but the question pool has only 25. Add 5 more"));
        e = assertThrows(IllegalArgumentException.class, () -> SectionALimit.requirePoolReady(pool(30, true), 0));
        assertTrue(e.getMessage().contains("has none yet"));
        assertDoesNotThrow(() -> SectionALimit.requirePoolReady(pool(30, true), 30));
        assertDoesNotThrow(() -> SectionALimit.requirePoolReady(quiz(30), 0));   // no pool: nothing to check
    }

    @Test
    void aLivePoolCannotDropBelowOneDraw() {
        assertThrows(IllegalArgumentException.class, () -> SectionALimit.requireRoomToDelete(pool(30, true), 30));
        assertDoesNotThrow(() -> SectionALimit.requireRoomToDelete(pool(30, true), 31));
        assertDoesNotThrow(() -> SectionALimit.requireRoomToDelete(pool(30, false), 30));   // draft: free to edit
        assertDoesNotThrow(() -> SectionALimit.requireRoomToDelete(quiz(30), 1));
    }

    @Test
    void turningThePoolOffNeedsTheQuizToFit() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SectionALimit.requireFitsWithoutPool(quiz(30), 200));
        assertTrue(e.getMessage().contains("Delete 170"));
        assertDoesNotThrow(() -> SectionALimit.requireFitsWithoutPool(quiz(30), 30));
        assertDoesNotThrow(() -> SectionALimit.requireFitsWithoutPool(quiz(null), 200));
    }
}
