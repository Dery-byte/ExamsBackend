package com.exam.service.attendance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Students' personal QR codes for scanned meetings.
 * <p>
 * Once the student's phone passes the phone and location checks, it receives a key that belongs to
 * that student in that meeting only (an HMAC of the meeting's secret). The phone then draws a new
 * code every {@link #STEP_SECONDS} seconds without asking the server:
 * {@code A1.<sessionId>.<studentId>.<step>.<mac>}, where mac = HMAC(key, "sessionId.studentId.step"),
 * cut to {@link #MAC_CHARS} base64url characters. A code is accepted for its own step and the ones
 * either side of it, so a screenshot is useless within about 20 seconds.
 */
public final class AttendanceQr {

    private AttendanceQr() {}

    public static final String PREFIX = "A1";
    public static final int STEP_SECONDS = 10;
    public static final int MAC_CHARS = 16;

    public record Parsed(long sessionId, long studentId, long step, String mac) {}

    /** The student's key for this meeting (raw bytes). */
    public static byte[] studentKey(String sessionSecret, long studentId) {
        return hmac(sessionSecret.getBytes(StandardCharsets.UTF_8), "qr:" + studentId);
    }

    public static String encodeKey(byte[] key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    public static long step(long epochMillis) { return Math.floorDiv(epochMillis, STEP_SECONDS * 1000L); }

    public static String mac(byte[] key, long sessionId, long studentId, long step) {
        byte[] h = hmac(key, sessionId + "." + studentId + "." + step);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, MAC_CHARS);
    }

    public static String token(byte[] key, long sessionId, long studentId, long step) {
        return PREFIX + "." + sessionId + "." + studentId + "." + step + "." + mac(key, sessionId, studentId, step);
    }

    /** Null when the text isn't one of our codes at all. */
    public static Parsed parse(String text) {
        if (text == null) return null;
        String[] p = text.trim().split("\\.");
        if (p.length != 5 || !PREFIX.equals(p[0]) || p[4].length() != MAC_CHARS) return null;
        try {
            return new Parsed(Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), p[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public enum Check { VALID, EXPIRED, FORGED }

    public static Check verify(String sessionSecret, Parsed t, long nowMillis) {
        String expected = mac(studentKey(sessionSecret, t.studentId()), t.sessionId(), t.studentId(), t.step());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), t.mac().getBytes(StandardCharsets.US_ASCII))) return Check.FORGED;
        return Math.abs(step(nowMillis) - t.step()) <= 1 ? Check.VALID : Check.EXPIRED;
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
