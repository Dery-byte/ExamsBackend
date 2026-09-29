package com.exam.service;

import com.exam.model.exam.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerMatcherTest {

    private static boolean ok(QuestionType t, String[] key, Double tol, String... given) {
        return AnswerMatcher.isCorrect(t, key, tol, List.of(given));
    }

    @Test
    void fillBlankIgnoresCaseSpacesAndTrailingFullStop() {
        String[] key = {"Photosynthesis", "photo-synthesis"};
        assertThat(ok(QuestionType.FILL_BLANK, key, null, "  photosynthesis. ")).isTrue();
        assertThat(ok(QuestionType.FILL_BLANK, key, null, "PHOTO-SYNTHESIS")).isTrue();
        assertThat(ok(QuestionType.FILL_BLANK, key, null, "photo synthesis")).isFalse();
        assertThat(ok(QuestionType.FILL_BLANK, key, null, "")).isFalse();
    }

    @Test
    void numericUsesTolerance() {
        String[] key = {"3.14"};
        assertThat(ok(QuestionType.NUMERIC, key, 0.01, "3.142")).isTrue();
        assertThat(ok(QuestionType.NUMERIC, key, 0.01, "3,14")).isTrue();          // comma decimal
        assertThat(ok(QuestionType.NUMERIC, key, 0.01, "3.2")).isFalse();
        assertThat(ok(QuestionType.NUMERIC, key, null, "3.14")).isTrue();          // exact when no tolerance
        assertThat(ok(QuestionType.NUMERIC, key, null, "pi")).isFalse();
        assertThat(ok(QuestionType.NUMERIC, new String[]{"1000"}, 0.0, "1,000")).isTrue();
    }

    @Test
    void mcqStillNeedsTheExactSetOfOptions() {
        String[] key = {"B", "A"};
        assertThat(ok(QuestionType.MCQ, key, null, "A", "B")).isTrue();
        assertThat(ok(QuestionType.MCQ, key, null, "A")).isFalse();
        assertThat(ok(null, new String[]{"True"}, null, "True")).isTrue();          // legacy rows without a type
    }

    @Test
    void answerKeysAreValidatedAndCleaned() {
        assertThatThrownBy(() -> AnswerMatcher.validateTyped(QuestionType.NUMERIC, new String[]{"abc"}, null))
                .hasMessageContaining("number");
        assertThatThrownBy(() -> AnswerMatcher.validateTyped(QuestionType.FILL_BLANK, new String[]{" ", null}, null))
                .hasMessageContaining("accepted answer");
        assertThat(AnswerMatcher.cleanAccepted(new String[]{" Accra ", "accra", "", "Kumasi"})).containsExactly("Accra", "Kumasi");
    }
}
