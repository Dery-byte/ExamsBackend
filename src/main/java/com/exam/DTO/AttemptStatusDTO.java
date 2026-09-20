package com.exam.DTO;

/** A student's attempt position for one quiz. */
public record AttemptStatusDTO(
        Long quizId,
        int maxAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        /** Number of the attempt currently in progress (resumable), or null. */
        Integer activeAttemptNumber,
        /** True if the student may start or resume an attempt right now. */
        boolean canStart,
        boolean quizClosed,
        /** True when staff allowed this student a retake that they have not started yet. */
        boolean retakeGranted,
        /** True when the result has been marked as reviewed, which closes it to further attempts. */
        boolean resultReviewed
) {}
