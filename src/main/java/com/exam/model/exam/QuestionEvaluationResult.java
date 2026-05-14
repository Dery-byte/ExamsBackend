package com.exam.model.exam;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionEvaluationResult {
    private String quizId;
    private String tqid;
    private String questionNumber;
    private String question;  // Add this field
    private String studentAnswer;  // Add this field
    private double score;
    private double maxMarks;
    private String feedback;
    private List<String> keyMissed;  // Add this field if needed

}