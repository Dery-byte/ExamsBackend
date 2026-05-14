package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AnswerDTO {
    private Long answerId;
    // extra fields from TheoryQuestions
    private String quesO;
    private String question;
    private String studentAnswer;
    private double maxMarks;
    private double score;
    private List<String> keyMissed;
    private Long theoryQuestionId;
    private Long quizId;
    private String feedback;





}
