package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizEvaluationResult {
    private double marksGot;
    private int correctAnswers;
    private int attempted;
    private double maxMarks;
}
