package com.exam.DTO;
import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QuestionResponseAdminDTO {

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
     */
    private List<MatchingPair> matchingPairs = new ArrayList<>();

    /**
     * Correct answer(s) for MCQ / TRUE_FALSE / SHORT_ANSWER / MATCHING questions.
     * Mirrors the String[] correct_answer field on the Questions entity.
     *
     * - MCQ (single):   ["option2"]
     * - MCQ (multi):    ["option1", "option3"]
     * - TRUE_FALSE:     ["True"]
     * - MATCHING:       ["Not Found", "OK", "Forbidden"]  (in pairOrder)
     *
     * ⚠️ WARNING: Only populate this in contexts where exposing the correct
     * answer is intentional (e.g. post-submission review, teacher dashboard).
     * Leave null for student-facing quiz attempts.
     */
    private List<String> correctAnswer;

}