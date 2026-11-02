package com.exam.helper;

import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The random draw for a question-pool quiz ({@link Quiz#isQuestionPool()}): each attempt is given
 * Number of Questions objective questions out of the quiz's larger pool.
 *
 * The draw is balanced so it mirrors the pool, level by level:
 *  1. difficulty: every student gets the same number of easy, medium, hard and untagged questions;
 *  2. question type, within each difficulty: the same number of each type for everyone;
 *  3. topic, within those: in proportion to the pool, a tie for the last place going to a random topic.
 * Each level uses largest-remainder shares, which never ask a group for more questions than it has.
 */
public final class QuestionDraw {

    private QuestionDraw() {}

    /** The difficulty values a quiz question may carry (null = untagged). */
    public static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");

    /** The largest pool a quiz may hold, so one upload cannot make every draw slow. */
    public static final int MAX_POOL = 2000;

    private static final List<Function<Questions, String>> LEVELS = List.of(
            q -> q.getDifficulty() == null ? "" : q.getDifficulty(),
            q -> (q.getQuestionType() == null ? QuestionType.MCQ : q.getQuestionType()).name(),
            q -> q.getTopic() == null ? "" : q.getTopic().trim().toLowerCase(Locale.ROOT));

    /** How many questions each attempt gets: Number of Questions, or the whole pool when it is not set. */
    public static int size(Quiz quiz) {
        Integer n = quiz.getNumberOfQuestions();
        return n == null || n < 1 ? Integer.MAX_VALUE : n;
    }

    /**
     * Picks n of the pool's questions and returns their ids in a random order (the order the student
     * sees). Asking for at least the pool's size returns the whole pool, shuffled.
     */
    public static List<Long> draw(Collection<Questions> pool, int n, Random rnd) {
        Map<Long, Questions> unique = new TreeMap<>();
        if (pool != null) for (Questions q : pool) if (q != null && q.getQuesId() != null) unique.putIfAbsent(q.getQuesId(), q);
        List<Questions> all = new ArrayList<>(unique.values());

        List<Questions> chosen = new ArrayList<>();
        if (n >= all.size()) {
            chosen.addAll(all);
        } else if (n > 0) {
            pick(all, n, 0, rnd, chosen);
            // Shares never exceed a group's size, so this is only a guard: never hand out fewer than n
            if (chosen.size() < n) {
                Set<Questions> taken = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                taken.addAll(chosen);
                List<Questions> rest = new ArrayList<>(all.stream().filter(q -> !taken.contains(q)).toList());
                Collections.shuffle(rest, rnd);
                chosen.addAll(rest.subList(0, Math.min(n - chosen.size(), rest.size())));
            }
        }
        List<Long> ids = new ArrayList<>(chosen.stream().map(Questions::getQuesId).toList());
        Collections.shuffle(ids, rnd);
        return ids;
    }

    private static void pick(List<Questions> qs, int n, int level, Random rnd, List<Questions> out) {
        if (n <= 0 || qs.isEmpty()) return;
        if (level == LEVELS.size()) {
            List<Questions> copy = new ArrayList<>(qs);
            Collections.shuffle(copy, rnd);
            out.addAll(copy.subList(0, Math.min(n, copy.size())));
            return;
        }
        Map<String, List<Questions>> groups = qs.stream()
                .collect(Collectors.groupingBy(LEVELS.get(level), TreeMap::new, Collectors.toList()));
        // Difficulty and type shares are the same for every student; only topic ties are random
        boolean randomTies = level == LEVELS.size() - 1;
        Map<String, Integer> shares = allocate(n, groups, randomTies ? rnd : null);
        groups.forEach((key, group) -> pick(group, shares.getOrDefault(key, 0), level + 1, rnd, out));
    }

    /**
     * Largest remainder: each group gets the whole part of its fair share of n, and the questions left
     * over go one each to the groups with the largest remainders. Ties go to the larger group, then the
     * first key, or (tieBreak given) to a random one of the tied groups.
     */
    static Map<String, Integer> allocate(int n, Map<String, List<Questions>> groups, Random tieBreak) {
        long total = groups.values().stream().mapToLong(List::size).sum();
        Map<String, Integer> shares = new LinkedHashMap<>();
        if (total == 0 || n <= 0) return shares;
        List<String> keys = new ArrayList<>(groups.keySet());
        if (tieBreak != null) Collections.shuffle(keys, tieBreak);

        int given = 0;
        for (String k : keys) {
            int share = (int) ((long) n * groups.get(k).size() / total);
            shares.put(k, share);
            given += share;
        }
        Comparator<String> byRemainder = Comparator.comparingLong((String k) -> (long) n * groups.get(k).size() % total).reversed();
        if (tieBreak == null) byRemainder = byRemainder.thenComparing(Comparator.comparingInt((String k) -> groups.get(k).size()).reversed());
        List<String> order = new ArrayList<>(keys);
        order.sort(byRemainder);   // stable: equal remainders keep key (or shuffled) order
        for (int i = 0; i < n - given && i < order.size(); i++) shares.merge(order.get(i), 1, Integer::sum);
        return shares;
    }

    // ── Storing a draw on the attempt ────────────────────────────────────────

    public static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** The stored ids in order; null when nothing has been drawn. Unreadable entries are skipped. */
    public static List<Long> parse(String stored) {
        if (stored == null || stored.isBlank()) return null;
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : stored.split(",")) {
            try {
                ids.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited or damaged entry: drop it rather than fail the exam
            }
        }
        return ids.isEmpty() ? null : new ArrayList<>(ids);
    }

    // ── Topic and difficulty tags ────────────────────────────────────────────

    /** Trims the topic (blank = none) and turns the difficulty into EASY / MEDIUM / HARD; 400 if either is unusable. */
    public static void cleanTags(Questions q) {
        String topic = q.getTopic() == null ? null : q.getTopic().trim().replaceAll("\\s+", " ");
        if (topic != null && topic.isEmpty()) topic = null;
        if (topic != null && topic.length() > 120)
            throw new IllegalArgumentException("A topic can be at most 120 characters long (\"" + topic.substring(0, 40) + "…\").");
        q.setTopic(topic);
        q.setDifficulty(difficulty(q.getDifficulty()));
    }

    /** EASY / MEDIUM / HARD from any capitalisation, null for blank; 400 for anything else. */
    public static String difficulty(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String d = raw.trim().toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(d))
            throw new IllegalArgumentException("Difficulty must be Easy, Medium or Hard, not \"" + raw.trim() + "\".");
        return d;
    }
}
