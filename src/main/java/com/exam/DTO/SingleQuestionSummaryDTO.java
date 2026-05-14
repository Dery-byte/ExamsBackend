//package com.exam.DTO;
//
//import com.exam.model.exam.QuestionType;
//import lombok.Data;
//
//import java.util.List;
//
//@Data
//public class SingleQuestionSummaryDTO  {
//    private Long quesId;
//    private String content;
//    private String image;
//    private String option1;
//    private String option2;
//    private String option3;
//    private String option4;
//    private List<String> correctAnswer;
//    private List<String> givenAnswer;
//    private QuestionType questionType;
//}
//
//



package com.exam.DTO;

import com.exam.model.exam.QuestionType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SingleQuestionSummaryDTO {
    private Long quesId;
    private String content;
    private String image;

    // MCQ / TRUE_FALSE options (null for MATCHING)
    private String option1;
    private String option2;
    private String option3;
    private String option4;

    private List<String> correctAnswer;
    private List<String> givenAnswer;
    private QuestionType questionType;

    // MATCHING pairs — ordered by pairOrder, empty list for non-MATCHING types
    private List<MatchingPairDTO> matchingPairs = new ArrayList<>();

    @Data
    public static class MatchingPairDTO {
        private Long id;
        private String prompt;
        private String answer;
        private Integer pairOrder;
    }
}