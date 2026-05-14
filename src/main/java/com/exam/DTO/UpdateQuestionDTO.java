//package com.exam.DTO;
//
//import lombok.Data;
//import lombok.Getter;
//import lombok.Setter;
//
//@Data
//@Setter
//@Getter
//public class UpdateQuestionDTO {
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
//
//    // getters & setters
//}





package com.exam.DTO;

import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import lombok.Data;

import java.util.List;

@Data
public class UpdateQuestionDTO {

    private Long quesId;
    private String content;
    private String image;

    // MCQ / TRUE_FALSE only — ignored for MATCHING
    private String option1;
    private String option2;
    private String option3;
    private String option4;
    private String[] correct_answer;

    // ── New fields ─────────────────────────────────────────────────────────────
    private QuestionType questionType;

    /**
     * For MATCHING questions: full list of pairs to replace existing ones.
     * Each MatchingPair should have prompt, answer, and pairOrder set.
     * The question FK will be set server-side in QuestionsService.
     */
    private List<MatchingPair> matchingPairs;
}