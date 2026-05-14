package com.exam.service;
import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.repository.QuestionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Drop-in replacement for the saveAllQuestions() method in QuestionsService.
 *
 * Handles all 3 question types:
 *   MCQ        → validates option1–4 and correct_answer present
 *   TRUE_FALSE → forces option1="True", option2="False", correct_answer=["True"|"False"]
 *   MATCHING   → links each MatchingPair FK back to its parent question before saving
 */
@Service
public class BulkQuestionUploadService {

    @Autowired
    private QuestionsRepository questionsRepository;

    @Transactional
    public List<Questions> saveAllQuestions(List<Questions> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("Question list must not be empty");
        }

        List<Questions> prepared = new ArrayList<>();

        for (int i = 0; i < incoming.size(); i++) {
            Questions q = incoming.get(i);

            // Default to MCQ if questionType is missing (backwards compatible)
            if (q.getQuestionType() == null) {
                q.setQuestionType(QuestionType.MCQ);
            }

            switch (q.getQuestionType()) {

                case MCQ -> {
                    validateMCQ(q, i);
                    // Clear any accidental matchingPairs on an MCQ
                    q.setMatchingPairs(new ArrayList<>());
                }

                case TRUE_FALSE -> {
                    validateTrueFalse(q, i);
                    // Enforce canonical option values regardless of what was sent
                    q.setOption1("True");
                    q.setOption2("False");
                    q.setOption3(null);
                    q.setOption4(null);
                    q.setMatchingPairs(new ArrayList<>());
                }

                case MATCHING -> {
                    validateMatching(q, i);
                    // Clear MCQ fields — not used for MATCHING
                    q.setOption1(null);
                    q.setOption2(null);
                    q.setOption3(null);
                    q.setOption4(null);
                    q.setcorrect_answer(null);

                    // Link each pair FK to the parent question
                    // (must be done before save so cascade persists them)
                    if (q.getMatchingPairs() != null) {
                        for (MatchingPair pair : q.getMatchingPairs()) {
                            pair.setQuestion(q);
                        }
                    }
                }
            }

            prepared.add(q);
        }

        return questionsRepository.saveAll(prepared);
    }

    // ── Validators ────────────────────────────────────────────────────────────

    private void validateMCQ(Questions q, int index) {
        if (isBlank(q.getContent())) {
            throw new IllegalArgumentException("Question [" + index + "] MCQ: content is required");
        }
        if (isBlank(q.getOption1()) || isBlank(q.getOption2())) {
            throw new IllegalArgumentException("Question [" + index + "] MCQ: at least option1 and option2 are required");
        }
        if (q.getcorrect_answer() == null || q.getcorrect_answer().length == 0) {
            throw new IllegalArgumentException("Question [" + index + "] MCQ: correct_answer is required");
        }
    }

    private void validateTrueFalse(Questions q, int index) {
        if (isBlank(q.getContent())) {
            throw new IllegalArgumentException("Question [" + index + "] TRUE_FALSE: content is required");
        }
        String[] ans = q.getcorrect_answer();
        if (ans == null || ans.length != 1 ||
                (!ans[0].equals("True") && !ans[0].equals("False"))) {
            throw new IllegalArgumentException(
                    "Question [" + index + "] TRUE_FALSE: correct_answer must be exactly [\"True\"] or [\"False\"]"
            );
        }
    }

    private void validateMatching(Questions q, int index) {
        if (isBlank(q.getContent())) {
            throw new IllegalArgumentException("Question [" + index + "] MATCHING: content is required");
        }
        if (q.getMatchingPairs() == null || q.getMatchingPairs().size() < 2) {
            throw new IllegalArgumentException(
                    "Question [" + index + "] MATCHING: at least 2 matchingPairs are required"
            );
        }
        for (int p = 0; p < q.getMatchingPairs().size(); p++) {
            MatchingPair pair = q.getMatchingPairs().get(p);
            if (isBlank(pair.getPrompt()) || isBlank(pair.getAnswer())) {
                throw new IllegalArgumentException(
                        "Question [" + index + "] MATCHING pair [" + p + "]: prompt and answer are both required"
                );
            }
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}