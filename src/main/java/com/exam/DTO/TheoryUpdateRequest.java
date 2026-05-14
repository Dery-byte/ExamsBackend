package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TheoryUpdateRequest {
    private Long tqId;       // the ID of the question to update
    private String quesNo;   // optional
    private String question; // optional
    private String marks;    // optional
    private Long quizId;     // optional, if you allow changing the quiz
    private String evaluationCriteria;
}
