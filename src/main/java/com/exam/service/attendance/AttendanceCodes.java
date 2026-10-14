package com.exam.service.attendance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Check-in codes. Each meeting has a random secret; the code for a time step is an HMAC of
 * (check number, step), cut to 5 digits, like an authenticator app. Nothing is stored per code and
 * the secret never leaves the server, so a code can't be worked out ahead of time.
 * <p>
 * Steps count from the moment the check opened. A code is accepted during its own step, plus a few
 * seconds into the next one so a student who is mid-typing when it changes isn't refused.
 */
public final class AttendanceCodes {

    private AttendanceCodes() {}

    public static final int DIGITS = 5;
    private static final int MODULO = 100_000;
    /** How far back an old code is still recognised as "expired" (rather than simply wrong). */
    private static final int EXPIRED_LOOKBACK_STEPS = 40;

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Match { VALID, EXPIRED, WRONG }

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Step number at {@code elapsedSeconds} after the check opened. */
    public static long step(long elapsedSeconds, int codeSeconds) {
        return Math.max(0, elapsedSeconds) / Math.max(1, codeSeconds);
    }

    /** Seconds until the code shown at {@code elapsedSeconds} is replaced. */
    public static int secondsLeft(long elapsedSeconds, int codeSeconds) {
        int period = Math.max(1, codeSeconds);
        return (int) (period - Math.floorMod(Math.max(0, elapsedSeconds), period));
    }

    public static String code(String secret, int check, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((check + ":" + step).getBytes(StandardCharsets.UTF_8));
            int offset = h[h.length - 1] & 0x0f;
            int bin = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % MODULO);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Checks a typed code against the check that opened {@code elapsedSeconds} ago.
     * VALID: the current code, or the previous one within {@code graceSeconds} of being replaced.
     * EXPIRED: an older code of this check. WRONG: anything else.
     */
    public static Match match(String secret, int check, long elapsedSeconds, int codeSeconds, int graceSeconds, String typed) {
        String t = typed == null ? "" : typed.replaceAll("\\s", "");
        if (t.length() != DIGITS || !t.chars().allMatch(Character::isDigit)) return Match.WRONG;
        long now = step(elapsedSeconds, codeSeconds);
        if (equal(code(secret, check, now), t)) return Match.VALID;
        long intoStep = Math.floorMod(Math.max(0, elapsedSeconds), Math.max(1, codeSeconds));
        if (now > 0 && intoStep < graceSeconds && equal(code(secret, check, now - 1), t)) return Match.VALID;
        for (long s = now - 1; s >= Math.max(0, now - EXPIRED_LOOKBACK_STEPS); s--) {
            if (equal(code(secret, check, s), t)) return Match.EXPIRED;
        }
        return Match.WRONG;
    }

    private static boolean equal(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 hex of the browser's device id; only the hash is stored. */
    public static String hashDevice(String deviceId) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
