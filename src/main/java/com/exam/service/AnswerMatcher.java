package com.exam.service;

import com.exam.model.exam.QuestionType;

import java.util.*;

/**
 * One rule for deciding whether an objective answer is fully correct, shared by official marking,
 * the student's result view and the staff review screens so they can never disagree.
 * <ul>
 *   <li>MCQ / TRUE_FALSE: the selected options equal the correct options (order ignored).</li>
 *   <li>FILL_BLANK: the typed text matches any accepted answer, ignoring case, extra spaces and a trailing full stop.</li>
 *   <li>NUMERIC: the typed number is within the tolerance of the correct value ("3,5" is read as 3.5).</li>
 * </ul>
 * MATCHING is scored pair by pair by the callers.
 */
public final class AnswerMatcher {

    private AnswerMatcher() {}

    public static boolean isTyped(QuestionType t) {
        return t == QuestionType.FILL_BLANK || t == QuestionType.NUMERIC;
    }

    public static boolean isCorrect(QuestionType type, String[] correct, Double tolerance, List<String> given) {
        if (correct == null || given == null) return false;
        QuestionType t = type == null ? QuestionType.MCQ : type;
        String typed = firstNonBlank(given);
        switch (t) {
            case FILL_BLANK:
                if (typed == null) return false;
                String g = normalise(typed);
                return Arrays.stream(correct).filter(Objects::nonNull).anyMatch(a -> normalise(a).equals(g));
            case NUMERIC:
                Double value = parseNumber(typed);
                Double target = correct.length > 0 ? parseNumber(correct[0]) : null;
                if (value == null || target == null) return false;
                double tol = tolerance == null ? 0 : Math.abs(tolerance);
                return Math.abs(value - target) <= tol + 1e-9;
            default:
                List<String> a = new ArrayList<>(Arrays.asList(correct));
                List<String> b = new ArrayList<>(given);
                a.removeIf(Objects::isNull);
                b.removeIf(Objects::isNull);
                Collections.sort(a);
                Collections.sort(b);
                return a.equals(b);
        }
    }

    /** Checks a FILL_BLANK / NUMERIC question's answer key before it is saved. */
    public static void validateTyped(QuestionType type, String[] accepted, Double tolerance) {
        String[] clean = cleanAccepted(accepted);
        if (clean.length == 0)
            throw new IllegalArgumentException(type == QuestionType.NUMERIC ? "Enter the correct number." : "Enter at least one accepted answer.");
        if (type == QuestionType.NUMERIC) {
            if (parseNumber(clean[0]) == null) throw new IllegalArgumentException("The correct answer must be a number.");
            if (tolerance != null && tolerance < 0) throw new IllegalArgumentException("Tolerance can't be negative.");
        }
    }

    /** Trims answers and drops blanks and duplicates (keeping the first spelling). */
    public static String[] cleanAccepted(String[] accepted) {
        if (accepted == null) return new String[0];
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String a : accepted) {
            if (a == null || a.isBlank()) continue;
            out.putIfAbsent(normalise(a), a.trim());
        }
        return out.values().toArray(new String[0]);
    }

    static String normalise(String s) {
        String t = s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    public static Double parseNumber(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replace(" ", "");
        if (t.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d+)?")) t = t.replace(",", "");   // 1,000 / 12,500.5 → thousands separators
        else if (t.contains(",") && !t.contains(".")) t = t.replace(',', '.');      // 3,5 → decimal comma
        else t = t.replace(",", "");
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    private static String firstNonBlank(List<String> given) {
        return given.stream().filter(x -> x != null && !x.isBlank()).findFirst().orElse(null);
    }
}
