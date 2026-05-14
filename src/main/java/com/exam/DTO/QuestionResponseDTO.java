////package com.exam.DTO;
////
////import lombok.Data;
////import java.util.List;
////
/////**
//// * Student-facing DTO for objective questions.
//// *
//// * IMPORTANT: correct_answer is intentionally EXCLUDED here.
//// * Sending correct answers to the client is a security risk —
//// * students can inspect network responses and cheat.
//// *
//// * correct_answer should only be used server-side during grading.
//// */
////@Data
////public class QuestionResponseDTO {
////
////    private Long quesId;
////
////    /** Assigned after shuffle — reflects actual display order */
////    private int count;
////
////    /** Question text (may contain HTML) */
////    private String content;
////
////    /** Optional image URL */
////    private String image;
////
////    // ── Answer options ──
////    private String option1;
////    private String option2;
////    private String option3;
////    private String option4;
////
////    /**
////     * Student's current selected answers.
////     * Empty list = unanswered.
////     * List because multi-select questions use .includes() in the frontend.
////     */
////    private List<String> givenAnswer;
////
////    // ── NOTE: correct_answer is NOT included ──
////    // It is only used server-side in the grading/submission logic.
////}
//
//
//
//
//
//package com.exam.DTO;
//
//import com.exam.model.exam.MatchingPair;
//import com.exam.model.exam.QuestionType;
//import lombok.Data;
//
//import java.util.ArrayList;
//import java.util.List;
//
//@Data
//public class QuestionResponseDTO {
//
//    private Long quesId;
//    private int count;
//    private String content;
//    private String image;
//
//    // MCQ / TRUE_FALSE options (null for MATCHING)
//    private String option1;
//    private String option2;
//    private String option3;
//    private String option4;
//
//    private List<String> givenAnswer = new ArrayList<>();
//
//    // ── New fields ─────────────────────────────────────────────────────────────
//    private QuestionType questionType;
//
//    /**
//     * For MATCHING questions: ordered list of pairs (prompt + answer).
//     * NOTE: answers are included here because the frontend needs them to
//     * populate the answer pool. The correct mapping is verified server-side
//     * during evaluation — never trust client scoring.
//     *
//     * If you want to hide correct answers from students until after submission,
//     * strip the `answer` field here and resolve it only in evaluateQuiz().
//     */
//    private List<MatchingPair> matchingPairs = new ArrayList<>();
//
//    public QuestionResponseDTO() {}
//}




package com.exam.DTO;

import com.exam.model.exam.QuestionType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QuestionResponseDTO {

    private Long quesId;
    private int count;
    private String content;
    private String image;

    // MCQ / TRUE_FALSE options (null for MATCHING)
    private String option1;
    private String option2;
    private String option3;
    private String option4;

    private List<String> givenAnswer = new ArrayList<>();

    private QuestionType questionType;

    /**
     * For MATCHING questions: ordered list of pairs (prompt + answer).
     * NOTE: answers are included here because the frontend needs them to
     * populate the answer pool. The correct mapping is verified server-side
     * during evaluation — never trust client scoring.
     *
     * If you want to hide correct answers from students until after submission,
     * strip the `answer` field here and resolve it only in evaluateQuiz().
     */
    private List<MatchingPairDTO> matchingPairs = new ArrayList<>();

    // ── NOTE: correctAnswer is intentionally EXCLUDED ──
    // Sending correct answers to the client is a security risk —
    // students can inspect network responses and cheat.
    // correctAnswer is only used server-side during grading.

    @Data
    public static class MatchingPairDTO {
        private Long id;
        private String prompt;
        private String answer;
        private Integer pairOrder;
    }

    public QuestionResponseDTO() {}
}