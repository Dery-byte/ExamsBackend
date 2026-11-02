package com.exam.helper;

import com.exam.DTO.QuestionResponseDTO;
import com.exam.model.exam.QuestionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Per-student answer order on the exam page. Multiple choice options are shuffled here; marking compares the
 * chosen option's text with the answer key (AnswerMatcher), never its position, so marks cannot change.
 * True/False keeps True, False. Matching pairs stay in pairOrder (their answers are marked by pair position);
 * only the list of answers to choose from is shuffled, in the browser, with {@code optionSeed}.
 *
 * The order comes from the attempt's seed and the question id, so it is the same on every reload and device
 * for one attempt, and differs between students and between attempts.
 */
public final class OptionOrder {

    private OptionOrder() {}

    public static void apply(QuestionResponseDTO dto, long attemptSeed) {
        if (dto == null || dto.getQuesId() == null) return;
        long seed = mix(attemptSeed, dto.getQuesId());
        dto.setOptionSeed((int) (seed >>> 33));   // 31 bits: a non-negative int for the browser's shuffle

        QuestionType type = dto.getQuestionType() == null ? QuestionType.MCQ : dto.getQuestionType();
        if (type != QuestionType.MCQ) return;
        List<String> options = new ArrayList<>();
        for (String o : new String[]{dto.getOption1(), dto.getOption2(), dto.getOption3(), dto.getOption4()})
            if (o != null && !o.isBlank()) options.add(o);
        if (options.size() < 2) return;
        Collections.shuffle(options, new Random(seed));
        dto.setOption1(options.get(0));
        dto.setOption2(options.get(1));
        dto.setOption3(options.size() > 2 ? options.get(2) : null);
        dto.setOption4(options.size() > 3 ? options.get(3) : null);
    }

    /** SplitMix64 over the two values, so nearby attempt and question ids still give unrelated orders. */
    static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
