package com.exam.helper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Section B question numbers are grouped by their leading label and number:
 * "Q1a" and "Q1b(i)" belong to "Q1", "Q10a" to "Q10" (never "Q1"), and "2b" to "2".
 * Case and spaces are ignored ("q 1a" is "Q1"). A number with no digits is its own group,
 * and a missing one falls into "OTHER".
 * <p>
 * The frontend applies the same rule (src/utils/theoryGroups.ts); keep the two in step.
 */
public final class TheoryGroups {

    private static final Pattern SPACES = Pattern.compile("(?U)\\s+|\\uFEFF");
    private static final Pattern LEAD   = Pattern.compile("^[A-Z]*[0-9]+");

    private TheoryGroups() {}

    public static String key(String quesNo) {
        String s = quesNo == null ? "" : SPACES.matcher(quesNo).replaceAll("").toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return "OTHER";
        Matcher m = LEAD.matcher(s);
        return m.find() ? m.group() : s;
    }

    public static boolean sameGroup(String quesNo, String groupKey) {
        return key(quesNo).equals(key(groupKey));
    }
}
