package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuestionEvalRequest {
    private Long quesId;
    private String[] givenAnswer; // student answers
}
