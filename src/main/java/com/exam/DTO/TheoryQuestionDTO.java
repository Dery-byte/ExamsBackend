package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TheoryQuestionDTO {
    private Long tqId;
    private String quesNo;
    private String question;
    private String marks;
    private String evaluationCriteria;
    private Long quizId; // optional, just to know which quiz it belongs to
}