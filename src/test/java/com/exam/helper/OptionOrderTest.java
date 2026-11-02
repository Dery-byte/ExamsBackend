package com.exam.helper;

import com.exam.DTO.QuestionResponseDTO;
import com.exam.model.exam.QuestionType;
import com.exam.service.AnswerMatcher;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OptionOrderTest {

    private static QuestionResponseDTO mcq(long id, String... options) {
        QuestionResponseDTO d = new QuestionResponseDTO();
        d.setQuesId(id);
        d.setQuestionType(QuestionType.MCQ);
        d.setOption1(options.length > 0 ? options[0] : null);
        d.setOption2(options.length > 1 ? options[1] : null);
        d.setOption3(options.length > 2 ? options[2] : null);
        d.setOption4(options.length > 3 ? options[3] : null);
        return d;
    }

    private static List<String> shown(QuestionResponseDTO d) {
        List<String> out = new ArrayList<>();
        for (String o : new String[]{d.getOption1(), d.getOption2(), d.getOption3(), d.getOption4()}) if (o != null) out.add(o);
        return out;
    }

    @Test
    void sameAttemptSameOrderOnEveryLoad() {
        QuestionResponseDTO a = mcq(5, "Stack", "Queue", "Heap", "Tree");
        QuestionResponseDTO b = mcq(5, "Stack", "Queue", "Heap", "Tree");
        OptionOrder.apply(a, 4242);
        OptionOrder.apply(b, 4242);
        assertEquals(shown(a), shown(b));
        assertEquals(a.getOptionSeed(), b.getOptionSeed());
    }

    @Test
    void everyOptionIsKeptAndStudentsGetDifferentOrders() {
        Set<List<String>> orders = new HashSet<>();
        for (long attempt = 1; attempt <= 200; attempt++) {
            QuestionResponseDTO d = mcq(5, "Stack", "Queue", "Heap", "Tree");
            OptionOrder.apply(d, attempt);
            assertEquals(Set.of("Stack", "Queue", "Heap", "Tree"), new HashSet<>(shown(d)));
            assertEquals(4, shown(d).size());
            assertTrue(d.getOptionSeed() >= 0);
            orders.add(shown(d));
        }
        assertEquals(24, orders.size(), "all 24 orders of four options turn up across students");
    }

    @Test
    void twoOrThreeOptionsCloseUpWithoutGaps() {
        QuestionResponseDTO d = mcq(9, "Yes", "No", "", null);
        OptionOrder.apply(d, 77);
        assertEquals(Set.of("Yes", "No"), new HashSet<>(shown(d)));
        assertNull(d.getOption3());
        assertNull(d.getOption4());
    }

    @Test
    void trueFalseAndMatchingKeepTheirOrder() {
        QuestionResponseDTO tf = mcq(3, "True", "False");
        tf.setQuestionType(QuestionType.TRUE_FALSE);
        OptionOrder.apply(tf, 12345);
        assertEquals(List.of("True", "False"), shown(tf));

        QuestionResponseDTO match = new QuestionResponseDTO();
        match.setQuesId(4L);
        match.setQuestionType(QuestionType.MATCHING);
        QuestionResponseDTO.MatchingPairDTO p = new QuestionResponseDTO.MatchingPairDTO();
        p.setPrompt("200");
        p.setAnswer("OK");
        p.setPairOrder(0);
        match.setMatchingPairs(new ArrayList<>(List.of(p)));
        OptionOrder.apply(match, 12345);
        assertEquals(0, match.getMatchingPairs().get(0).getPairOrder());   // pairs are marked by position: untouched
        assertNotNull(match.getOptionSeed());                              // the browser shuffles the answer list with it
    }

    @Test
    void choosingTheRightTextFromAShuffledListStillMarksCorrect() {
        String[] key = {"Queue"};
        String[] multiKey = {"Stack", "Heap"};
        for (long attempt = 1; attempt <= 50; attempt++) {
            QuestionResponseDTO d = mcq(5, "Stack", "Queue", "Heap", "Tree");
            OptionOrder.apply(d, attempt);
            // The exam page stores the text of the option the student clicks, wherever it was shown
            String clicked = shown(d).stream().filter("Queue"::equals).findFirst().orElseThrow();
            assertTrue(AnswerMatcher.isCorrect(QuestionType.MCQ, key, null, List.of(clicked)));
            List<String> both = shown(d).stream().filter(o -> o.equals("Heap") || o.equals("Stack")).toList();
            assertTrue(AnswerMatcher.isCorrect(QuestionType.MCQ, multiKey, null, both));
            assertFalse(AnswerMatcher.isCorrect(QuestionType.MCQ, key, null, List.of(shown(d).get(shown(d).indexOf("Tree")))));
        }
    }
}
