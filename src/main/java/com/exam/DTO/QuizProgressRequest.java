package com.exam.DTO;


import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class QuizProgressRequest{
    private Long questionId;
    private String option;
    private boolean checked;
    private Long quizId; // Optional

    /**
     * For MATCHING questions: the 0-based index of the pair being answered.
     * null means MCQ / TRUE_FALSE — use existing option/checked logic.
     */
    private Integer pairIndex;

    // Getters and Setters
}





