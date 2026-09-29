package com.exam.model.exam;

public enum QuestionType {
    MCQ,         // Single or multi-select (existing behaviour)
    TRUE_FALSE,  // option1="True", option2="False", correct_answer=["True"] or ["False"]
    MATCHING,    // Flexible pairs stored in MatchingPair entity
    FILL_BLANK,  // Typed answer; correct_answer = every accepted answer (case/space-insensitive)
    NUMERIC      // Typed number; correct_answer[0] = the value, tolerance = allowed difference
}