package com.exam.helper;

import com.exam.model.exam.Quiz;

/**
 * A quiz's Number of Questions is the size of each student's Section A.
 *
 * Without a question pool, students are given every objective question in the quiz, so a bulk upload, a
 * single add or a bank draw may not take it past that number. With a question pool on, the quiz may hold
 * more (up to {@link QuestionDraw#MAX_POOL}) and each attempt draws Number of Questions of them, so the pool
 * must have at least that many before the quiz goes live, and may not drop below it while live.
 * Applied in QuestionsController (add, upload, delete), QuestionBankService.drawIntoQuiz and QuizService
 * (create, update); the Add Questions page shows the same limits before anything is sent.
 */
public final class SectionALimit {

    private SectionALimit() {}

    /** Throws (400, with the message shown to the lecturer) when adding would pass the quiz's limit. */
    public static void require(Quiz quiz, long inQuiz, long adding) {
        if (adding <= 0) return;
        if (quiz.drawsQuestions()) {
            if (inQuiz + adding <= QuestionDraw.MAX_POOL) return;
            long room = Math.max(0, QuestionDraw.MAX_POOL - inQuiz);
            throw new IllegalArgumentException("A question pool can hold at most " + QuestionDraw.MAX_POOL
                    + " questions and this quiz already has " + inQuiz + ". "
                    + (room == 0 ? "It is full." : "You can add " + room + " more, but tried to add " + adding + "."));
        }
        Integer allowed = quiz.getNumberOfQuestions();
        if (allowed == null || inQuiz + adding <= allowed) return;
        long room = Math.max(0, allowed - inQuiz);
        throw new IllegalArgumentException("This quiz allows " + allowed + " objective question" + (allowed == 1 ? "" : "s")
                + " and already has " + inQuiz + ". "
                + (room == 0
                    ? "It is full: delete some questions, raise the quiz's Number of Questions, or turn on the question pool."
                    : "You can add " + room + " more, but tried to add " + adding + "."));
    }

    /** A question-pool quiz must say how many questions each student gets. */
    public static void requireDrawSize(Quiz quiz) {
        if (!quiz.drawsQuestions()) return;
        Integer n = quiz.getNumberOfQuestions();
        if (n == null || n < 1)
            throw new IllegalArgumentException("With the question pool on, set Number of Questions to how many questions each student gets.");
        if (n > QuestionDraw.MAX_POOL)
            throw new IllegalArgumentException("Number of Questions can be at most " + QuestionDraw.MAX_POOL + ".");
    }

    /** Before a question-pool quiz goes live: the pool must hold at least one full draw. */
    public static void requirePoolReady(Quiz quiz, long inQuiz) {
        if (!quiz.drawsQuestions()) return;
        requireDrawSize(quiz);
        int n = quiz.getNumberOfQuestions();
        if (inQuiz >= n) return;
        throw new IllegalArgumentException("Each student gets " + n + " question" + (n == 1 ? "" : "s")
                + " but the question pool has " + (inQuiz == 0 ? "none yet" : "only " + inQuiz) + ". Add "
                + (n - inQuiz) + " more, or lower Number of Questions, before the quiz goes live.");
    }

    /** Before a question is deleted: a live question-pool quiz may not drop below one full draw. */
    public static void requireRoomToDelete(Quiz quiz, long inQuiz) {
        if (!quiz.drawsQuestions() || !quiz.isActive()) return;
        int n = QuestionDraw.size(quiz);
        if (inQuiz - 1 >= n) return;
        throw new IllegalArgumentException("This quiz is live and each student gets " + n + " questions, so its pool cannot go below "
                + n + ". Add a question first, or take the quiz back to draft.");
    }

    /** Turning the pool off: students would get every question, so the quiz may not hold more than Number of Questions. */
    public static void requireFitsWithoutPool(Quiz quiz, long inQuiz) {
        Integer allowed = quiz.getNumberOfQuestions();
        if (allowed == null || inQuiz <= allowed) return;
        throw new IllegalArgumentException("This quiz has " + inQuiz + " objective questions but Number of Questions is " + allowed
                + ". Without the question pool every student would get all " + inQuiz + ". Delete " + (inQuiz - allowed)
                + ", or raise Number of Questions, before turning the pool off.");
    }
}
