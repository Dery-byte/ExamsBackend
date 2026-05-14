package com.exam.service;

import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.Questions;

import java.util.Arrays;
import java.util.List;

import static com.exam.model.exam.QuestionType.*;

/**
 * Stateless scoring utility for all question types.
 *
 * Scoring rules:
 *  - MCQ / TRUE_FALSE  → 1 mark for fully correct answer, 0 otherwise
 *  - MATCHING          → 1 mark per correct pair  (partial credit allowed)
 *                        e.g. 3 out of 4 pairs correct = 3 marks earned out of 4
 */
public class QuestionScoringService {

    public record ScoreResult(double earned, double total) {
        /** Convenience: fully correct? */
        public boolean isPerfect() { return earned == total; }
        /** 0.0 – 1.0 ratio */
        public double ratio()      { return total == 0 ? 0 : earned / total; }
    }

    /**
     * Score a single question given the student's submitted answers.
     *
     * @param question   the persisted question entity
     * @param givenAnswer the student's answer array (transient field or from report)
     * @return ScoreResult with earned marks and total possible marks for this question
     */
    public static ScoreResult score(Questions question, String[] givenAnswer) {
        if (givenAnswer == null) givenAnswer = new String[0];

        return switch (question.getQuestionType()) {
            case MATCHING   -> scoreMatching(question, givenAnswer);
            case MCQ,
                 TRUE_FALSE -> scoreSingleOrMulti(question, givenAnswer);
        };
    }

    // ── MATCHING: 1 mark per correctly placed pair ────────────────────────────
    private static ScoreResult scoreMatching(Questions question, String[] givenAnswer) {
        List<MatchingPair> pairs = question.getMatchingPairs();
        if (pairs == null || pairs.isEmpty()) return new ScoreResult(0, 0);

        double earned = 0;
        for (int i = 0; i < pairs.size(); i++) {
            String expected = pairs.get(i).getAnswer();
            String given    = i < givenAnswer.length ? givenAnswer[i] : null;
            if (expected != null && expected.equals(given)) earned++;
        }
        return new ScoreResult(earned, pairs.size());
    }

    // ── MCQ / TRUE_FALSE: fully correct = 1 mark, anything else = 0 ──────────
    private static ScoreResult scoreSingleOrMulti(Questions question, String[] givenAnswer) {
        String[] correct = question.getcorrect_answer();
        if (correct == null || correct.length == 0) return new ScoreResult(0, 1);

        String[] sortedCorrect = Arrays.stream(correct).sorted().toArray(String[]::new);
        String[] sortedGiven   = Arrays.stream(givenAnswer).sorted().toArray(String[]::new);

        boolean fullMark = Arrays.equals(sortedCorrect, sortedGiven);
        return new ScoreResult(fullMark ? 1 : 0, 1);
    }

    /**
     * Score an entire quiz attempt.
     *
     * @param questions list of questions (each should have givenAnswer populated)
     * @return total earned marks / total possible marks
     */
    public static ScoreResult scoreAll(List<Questions> questions) {
        double earned = 0, total = 0;
        for (Questions q : questions) {
            ScoreResult r = score(q, q.getGivenAnswer());
            earned += r.earned();
            total  += r.total();
        }
        return new ScoreResult(earned, total);
    }
}