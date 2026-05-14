package com.exam.DTO;

import lombok.*;

import java.util.List;

// Response DTO
@Data
@Setter
@Getter
public class QuizProgressResponse {
    private Long questionId;
    private List<String> selectedOptions;
    private String matchingAnswers; // pipe-delimited positional answers for MATCHING


//    public QuizProgressResponse(Long questionId, List<String> selectedOptions) {
//        this.questionId = questionId;
//        this.selectedOptions = selectedOptions;
//    }

    public QuizProgressResponse(Long questionId, List<String> selectedOptions, String matchingAnswers) {
        this.questionId = questionId;
        this.selectedOptions = selectedOptions;
        this.matchingAnswers = matchingAnswers;
    }
}