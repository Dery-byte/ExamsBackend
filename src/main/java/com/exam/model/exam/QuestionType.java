package com.exam.model.exam;

public enum QuestionType {
    MCQ,         // Single or multi-select (existing behaviour)
    TRUE_FALSE,  // option1="True", option2="False", correct_answer=["True"] or ["False"]
    MATCHING     // Flexible pairs stored in MatchingPair entity
}