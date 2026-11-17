package com.exam.service.materials;

import java.util.*;

/**
 * Text similarity between students' work for one assignment, and chatbot phrases left in it.
 * <p>
 * Each student's text is cut into overlapping five-word phrases; two pieces of work are compared by the
 * share of the shorter one's phrases that also appear in the other (so copying a section of a long essay
 * still shows). Phrases that come from the questions or instructions are left out first, since students
 * quote the questions. Very short work (fewer than 40 phrases) is never compared: there is too little to go on.
 * Pure Java, no database: callers give it the texts.
 */
public final class SimilarityCheck {

    public static final int SHINGLE = 5;
    public static final int MIN_SHINGLES = 40;
    /** Shown on the class list at or above this share. */
    public static final int FLAG_PERCENT = 40;

    private SimilarityCheck() {}

    public record Text(Long studentId, String name, String text) {}
    public record Match(Long studentId, String name, int percent, int shared) {}
    public record Passage(String text) {}

    /** Words in lower case, letters and digits only, for comparing. */
    static List<String> words(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        List<String> out = new ArrayList<>(raw.length);
        for (String w : raw) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static long hash(List<String> w, int from) {
        long h = 0xcbf29ce484222325L;                // FNV-1a over the five words
        for (int i = from; i < from + SHINGLE; i++) {
            String s = w.get(i);
            for (int k = 0; k < s.length(); k++) { h ^= s.charAt(k); h *= 0x100000001b3L; }
            h ^= ' '; h *= 0x100000001b3L;
        }
        return h;
    }

    static Set<Long> shingles(List<String> w) {
        Set<Long> s = new HashSet<>();
        for (int i = 0; i + SHINGLE <= w.size(); i++) s.add(hash(w, i));
        return s;
    }

    /** For every student, their closest classmates (highest first, those at 20% or more). */
    public static Map<Long, List<Match>> compare(List<Text> texts, String questionText) {
        Set<Long> common = shingles(words(questionText));
        Map<Long, Set<Long>> sets = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Text t : texts) {
            Set<Long> s = shingles(words(t.text()));
            s.removeAll(common);
            if (s.size() >= MIN_SHINGLES) { sets.put(t.studentId(), s); names.put(t.studentId(), t.name()); }
        }
        Map<Long, List<Match>> out = new HashMap<>();
        List<Long> ids = new ArrayList<>(sets.keySet());
        for (int i = 0; i < ids.size(); i++) {
            Set<Long> a = sets.get(ids.get(i));
            for (int j = i + 1; j < ids.size(); j++) {
                Set<Long> b = sets.get(ids.get(j));
                Set<Long> small = a.size() <= b.size() ? a : b, big = small == a ? b : a;
                int shared = 0;
                for (Long x : small) if (big.contains(x)) shared++;
                int percent = (int) Math.round(100.0 * shared / small.size());
                if (percent < 20) continue;
                out.computeIfAbsent(ids.get(i), k -> new ArrayList<>()).add(new Match(ids.get(j), names.get(ids.get(j)), percent, shared));
                out.computeIfAbsent(ids.get(j), k -> new ArrayList<>()).add(new Match(ids.get(i), names.get(ids.get(i)), percent, shared));
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt(Match::percent).reversed()));
        return out;
    }

    /**
     * The longest stretches of a's text that also appear in b (question wording left out), in a's own
     * words, at most {@code max} of them, each cut to about 400 characters.
     */
    public static List<Passage> sharedPassages(String a, String b, String questionText, int max) {
        List<String> wa = words(a);
        Set<Long> sb = shingles(words(b));
        sb.removeAll(shingles(words(questionText)));
        boolean[] hit = new boolean[wa.size()];
        for (int i = 0; i + SHINGLE <= wa.size(); i++)
            if (sb.contains(hash(wa, i))) for (int k = i; k < i + SHINGLE; k++) hit[k] = true;
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= wa.size(); i++) {
            boolean on = i < wa.size() && hit[i];
            if (on && start < 0) start = i;
            if (!on && start >= 0) { runs.add(new int[]{start, i}); start = -1; }
        }
        runs.sort((x, y) -> (y[1] - y[0]) - (x[1] - x[0]));
        List<Passage> out = new ArrayList<>();
        for (int[] r : runs.subList(0, Math.min(max, runs.size()))) {
            String t = String.join(" ", wa.subList(r[0], r[1]));
            out.add(new Passage(t.length() > 400 ? t.substring(0, 400) + "…" : t));
        }
        return out;
    }

    /** Phrases chatbots write that students sometimes paste in by mistake (each found phrase once). */
    private static final List<String> CHATBOT = List.of(
            "as an ai language model", "as an ai developed by", "as a large language model", "i am an ai", "i'm an ai",
            "as of my last knowledge update", "as of my knowledge cutoff", "my training data", "i cannot browse the internet",
            "regenerate response", "certainly! here", "certainly! below", "sure! here's", "sure, here's", "here's a revised",
            "i hope this helps", "feel free to ask", "let me know if you need");

    public static List<String> chatbotPhrases(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        List<String> found = new ArrayList<>();
        for (String p : CHATBOT) if (t.contains(p)) found.add(p);
        return found;
    }
}
