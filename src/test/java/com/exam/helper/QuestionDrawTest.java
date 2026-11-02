package com.exam.helper;

import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QuestionDrawTest {

    private long nextId = 1;

    private Questions q(String difficulty, QuestionType type, String topic) {
        Questions q = new Questions();
        q.setQuesId(nextId++);
        q.setDifficulty(difficulty);
        q.setQuestionType(type);
        q.setTopic(topic);
        return q;
    }

    private List<Questions> many(int count, String difficulty, QuestionType type, String topic) {
        List<Questions> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(q(difficulty, type, topic));
        return out;
    }

    private static Map<String, Long> countBy(List<Long> ids, Map<Long, Questions> byId, Function<Questions, String> key) {
        return ids.stream().map(byId::get).collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    @Test
    void drawsExactlyTheNumberAskedForWithoutRepeatsAndOnlyFromThePool() {
        List<Questions> pool = many(200, null, QuestionType.MCQ, null);
        Set<Long> poolIds = pool.stream().map(Questions::getQuesId).collect(Collectors.toSet());
        for (int seed = 0; seed < 50; seed++) {
            List<Long> ids = QuestionDraw.draw(pool, 30, new Random(seed));
            assertEquals(30, ids.size());
            assertEquals(30, new HashSet<>(ids).size());
            assertTrue(poolIds.containsAll(ids));
        }
    }

    @Test
    void everyStudentGetsTheSameMixOfDifficultiesAndTypes() {
        List<Questions> pool = new ArrayList<>();
        pool.addAll(many(70, "EASY", QuestionType.MCQ, "Arrays"));
        pool.addAll(many(30, "EASY", QuestionType.TRUE_FALSE, "Lists"));
        pool.addAll(many(50, "MEDIUM", QuestionType.MCQ, "Trees"));
        pool.addAll(many(10, "MEDIUM", QuestionType.MATCHING, "Graphs"));
        pool.addAll(many(40, "HARD", QuestionType.MCQ, "Graphs"));
        Map<Long, Questions> byId = pool.stream().collect(Collectors.toMap(Questions::getQuesId, x -> x));

        Map<String, Long> firstDifficulty = null, firstType = null;
        for (int seed = 0; seed < 200; seed++) {
            List<Long> ids = QuestionDraw.draw(pool, 30, new Random(seed));
            Map<String, Long> difficulty = countBy(ids, byId, Questions::getDifficulty);
            Map<String, Long> type = countBy(ids, byId, x -> x.getQuestionType().name());
            // In proportion to the pool: 100 easy, 60 medium, 40 hard of 200 → 15, 9, 6 of 30
            assertEquals(Map.of("EASY", 15L, "MEDIUM", 9L, "HARD", 6L), difficulty);
            if (firstDifficulty == null) { firstDifficulty = difficulty; firstType = type; }
            assertEquals(firstDifficulty, difficulty, "same difficulty mix for every student");
            assertEquals(firstType, type, "same type mix for every student");
        }
    }

    @Test
    void untaggedQuestionsFormTheirOwnShare() {
        List<Questions> pool = new ArrayList<>();
        pool.addAll(many(50, null, QuestionType.MCQ, null));
        pool.addAll(many(50, "HARD", QuestionType.MCQ, null));
        Map<Long, Questions> byId = pool.stream().collect(Collectors.toMap(Questions::getQuesId, x -> x));
        List<Long> ids = QuestionDraw.draw(pool, 10, new Random(3));
        assertEquals(Map.of("", 5L, "HARD", 5L), countBy(ids, byId, x -> x.getDifficulty() == null ? "" : x.getDifficulty()));
    }

    @Test
    void topicsAreCoveredInProportionAndTiesGoToDifferentTopicsAcrossStudents() {
        List<Questions> pool = new ArrayList<>();
        for (String topic : List.of("A", "B", "C", "D", "E", "F", "G")) pool.addAll(many(10, "EASY", QuestionType.MCQ, topic));
        Map<Long, Questions> byId = pool.stream().collect(Collectors.toMap(Questions::getQuesId, x -> x));

        Map<String, Long> seenWithExtra = new TreeMap<>();
        for (int seed = 0; seed < 300; seed++) {
            // 30 of 70 across 7 equal topics: 4 each, and the 2 left over go to random topics
            Map<String, Long> byTopic = countBy(QuestionDraw.draw(pool, 30, new Random(seed)), byId, Questions::getTopic);
            assertEquals(7, byTopic.size());
            byTopic.values().forEach(c -> assertTrue(c == 4 || c == 5));
            assertEquals(2, byTopic.values().stream().filter(c -> c == 5).count());
            byTopic.forEach((t, c) -> { if (c == 5) seenWithExtra.merge(t, 1L, Long::sum); });
        }
        assertEquals(7, seenWithExtra.size(), "every topic sometimes gets the extra question");
    }

    @Test
    void topicsMatchIgnoringCaseAndSpaces() {
        List<Questions> pool = new ArrayList<>();
        pool.addAll(many(5, null, QuestionType.MCQ, "Sorting"));
        pool.addAll(many(5, null, QuestionType.MCQ, " sorting "));
        pool.addAll(many(10, null, QuestionType.MCQ, "Hashing"));
        Map<Long, Questions> byId = pool.stream().collect(Collectors.toMap(Questions::getQuesId, x -> x));
        for (int seed = 0; seed < 50; seed++) {
            Map<String, Long> byTopic = countBy(QuestionDraw.draw(pool, 10, new Random(seed)), byId,
                    x -> x.getTopic().trim().toLowerCase());
            assertEquals(Map.of("hashing", 5L, "sorting", 5L), byTopic);
        }
    }

    @Test
    void neverShortAndNeverOverAnyGroupForManyRandomPools() {
        Random r = new Random(42);
        String[] diffs = {null, "EASY", "MEDIUM", "HARD"};
        QuestionType[] types = QuestionType.values();
        for (int round = 0; round < 500; round++) {
            List<Questions> pool = new ArrayList<>();
            int size = 1 + r.nextInt(120);
            for (int i = 0; i < size; i++)
                pool.add(q(diffs[r.nextInt(diffs.length)], types[r.nextInt(types.length)], "T" + r.nextInt(6)));
            int n = 1 + r.nextInt(size + 5);
            List<Long> ids = QuestionDraw.draw(pool, n, new Random(round));
            assertEquals(Math.min(n, size), ids.size());
            assertEquals(ids.size(), new HashSet<>(ids).size());
        }
    }

    @Test
    void askingForTheWholePoolOrMoreGivesEveryQuestionShuffled() {
        List<Questions> pool = many(12, "EASY", QuestionType.MCQ, "x");
        List<Long> ids = QuestionDraw.draw(pool, 50, new Random(1));
        assertEquals(12, ids.size());
        assertEquals(pool.stream().map(Questions::getQuesId).collect(Collectors.toSet()), new HashSet<>(ids));
    }

    @Test
    void handlesEmptyNullDuplicateAndIdlessInput() {
        assertTrue(QuestionDraw.draw(null, 5, new Random(1)).isEmpty());
        assertTrue(QuestionDraw.draw(List.of(), 5, new Random(1)).isEmpty());
        assertTrue(QuestionDraw.draw(many(5, null, QuestionType.MCQ, null), 0, new Random(1)).isEmpty());

        Questions a = q(null, QuestionType.MCQ, null);
        Questions noId = q(null, QuestionType.MCQ, null);
        noId.setQuesId(null);
        List<Questions> pool = new ArrayList<>(Arrays.asList(a, a, noId, null));
        assertEquals(List.of(a.getQuesId()), QuestionDraw.draw(pool, 3, new Random(1)));
    }

    @Test
    void drawSizeIsNumberOfQuestionsOrTheWholePool() {
        Quiz quiz = new Quiz();
        quiz.setNumberOfQuestions(30);
        assertEquals(30, QuestionDraw.size(quiz));
        quiz.setNumberOfQuestions(null);
        assertEquals(Integer.MAX_VALUE, QuestionDraw.size(quiz));
        quiz.setNumberOfQuestions(0);
        assertEquals(Integer.MAX_VALUE, QuestionDraw.size(quiz));
    }

    @Test
    void storedDrawRoundTripsAndSurvivesDamage() {
        assertEquals("5,3,9", QuestionDraw.join(List.of(5L, 3L, 9L)));
        assertEquals(List.of(5L, 3L, 9L), QuestionDraw.parse("5,3,9"));
        assertEquals(List.of(5L, 9L), QuestionDraw.parse(" 5 ,x,,9,5"));
        assertNull(QuestionDraw.parse(null));
        assertNull(QuestionDraw.parse("  "));
        assertNull(QuestionDraw.parse("x,y"));
    }

    @Test
    void tagsAreTidiedAndBadDifficultiesRejected() {
        Questions x = q(" medium ", QuestionType.MCQ, "  Binary   trees ");
        QuestionDraw.cleanTags(x);
        assertEquals("MEDIUM", x.getDifficulty());
        assertEquals("Binary trees", x.getTopic());

        Questions blank = q("", QuestionType.MCQ, "   ");
        QuestionDraw.cleanTags(blank);
        assertNull(blank.getDifficulty());
        assertNull(blank.getTopic());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QuestionDraw.cleanTags(q("Tough", QuestionType.MCQ, null)));
        assertTrue(e.getMessage().contains("Easy, Medium or Hard"));
        assertThrows(IllegalArgumentException.class, () -> QuestionDraw.cleanTags(q(null, QuestionType.MCQ, "x".repeat(121))));
    }
}
