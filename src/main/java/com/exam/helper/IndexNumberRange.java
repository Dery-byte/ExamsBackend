package com.exam.helper;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An inclusive range of student index numbers (usernames), e.g. PS/ICT/17/0001 to PS/ICT/17/0009.
 * Each index number is read as a prefix ("PS/ICT/17/") followed by a trailing serial number
 * ("0001"). A student is in range when their prefix matches the range's prefix and their serial
 * lies between the two ends. Matching ignores case and surrounding spaces, and serials compare
 * numerically, so "0010" and "10" are the same.
 */
public final class IndexNumberRange {

    private static final Pattern PREFIX_AND_SERIAL = Pattern.compile("^(.*?)(\\d+)$");

    private record Parts(String prefix, BigInteger serial) {}

    private IndexNumberRange() {}

    /** Trimmed, upper-cased value; null when blank, meaning "no limit". */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static Parts parse(String raw) {
        String s = normalize(raw);
        if (s == null) return null;
        Matcher m = PREFIX_AND_SERIAL.matcher(s);
        return m.matches() ? new Parts(m.group(1), new BigInteger(m.group(2))) : null;
    }

    /** Null when (start, end) is a usable range or both are blank (no limit); otherwise why it isn't. */
    public static String validationError(String start, String end) {
        String s = normalize(start), e = normalize(end);
        if (s == null && e == null) return null;
        if (s == null || e == null) return "Enter both the first and the last index number of the range.";
        Parts ps = parse(s), pe = parse(e);
        if (ps == null) return "Index number \"" + s + "\" must end in a number, e.g. PS/ICT/17/0001.";
        if (pe == null) return "Index number \"" + e + "\" must end in a number, e.g. PS/ICT/17/0009.";
        if (!ps.prefix().equals(pe.prefix())) {
            return "Both index numbers must start the same way (\"" + ps.prefix() + "\" vs \"" + pe.prefix() + "\").";
        }
        if (ps.serial().compareTo(pe.serial()) > 0) {
            return "The first index number (" + s + ") comes after the last one (" + e + ").";
        }
        return null;
    }

    /** True when there is no range (either end blank) or the index number falls inside it. */
    public static boolean contains(String start, String end, String indexNumber) {
        Parts ps = parse(start), pe = parse(end);
        if (ps == null || pe == null) return true;
        Parts p = parse(indexNumber);
        return p != null
                && p.prefix().equals(ps.prefix())
                && p.serial().compareTo(ps.serial()) >= 0
                && p.serial().compareTo(pe.serial()) <= 0;
    }
}
