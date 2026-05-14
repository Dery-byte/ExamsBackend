package com.exam.DTO;

import lombok.Data;

import java.util.List;
import java.util.Map;

// Bulk response DTO

@Data
public class UserQuizProgressResponse {
    private Map<Long, List<String>> answers;
    private Map<Long, String> matchingAnswers;


//    public UserQuizProgressResponse(Map<Long, List<String>> answers) {
//        this.answers = answers;
//    }



    public UserQuizProgressResponse(Map<Long, List<String>> answers,
                                    Map<Long, String> matchingAnswers) {
        this.answers = answers;
        this.matchingAnswers = matchingAnswers;
    }


}