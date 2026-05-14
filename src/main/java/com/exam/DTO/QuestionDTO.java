//package com.exam.DTO;
//
//import lombok.Data;
//
//@Data
//public class QuestionDTO {
//
//    private Long quesId;
//    private String content;
//    private String image;
//
//    private String option1;
//    private String option2;
//    private String option3;
//    private String option4;
//
//    private String[] correct_answer;
//    private String[] givenAnswer;
//
//    public QuestionDTO() {}
//
//    public QuestionDTO(Long quesId, String content, String image,
//                       String option1, String option2,
//                       String option3, String option4,
//                       String[] correct_answer, String[] givenAnswer) {
//        this.quesId = quesId;
//        this.content = content;
//        this.image = image;
//        this.option1 = option1;
//        this.option2 = option2;
//        this.option3 = option3;
//        this.option4 = option4;
//        this.correct_answer = correct_answer;
//        this.givenAnswer = givenAnswer;
//    }
//
//    // getters & setters
//}



package com.exam.DTO;

import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import lombok.Data;

import java.util.List;

@Data
public class QuestionDTO {

    private Long quesId;
    private String content;
    private String image;

    // MCQ / TRUE_FALSE options (null for MATCHING)
    private String option1;
    private String option2;
    private String option3;
    private String option4;

    private String[] correct_answer;
    private String[] givenAnswer;

    // ── New fields ─────────────────────────────────────────────────────────────
    private QuestionType questionType;
    private List<MatchingPair> matchingPairs;   // populated only for MATCHING

    public QuestionDTO() {}

    public QuestionDTO(Long quesId, String content, String image,
                       String option1, String option2,
                       String option3, String option4,
                       String[] correct_answer, String[] givenAnswer,
                       QuestionType questionType,
                       List<MatchingPair> matchingPairs) {
        this.quesId          = quesId;
        this.content         = content;
        this.image           = image;
        this.option1         = option1;
        this.option2         = option2;
        this.option3         = option3;
        this.option4         = option4;
        this.correct_answer  = correct_answer;
        this.givenAnswer     = givenAnswer;
        this.questionType    = questionType;
        this.matchingPairs   = matchingPairs;
    }
}