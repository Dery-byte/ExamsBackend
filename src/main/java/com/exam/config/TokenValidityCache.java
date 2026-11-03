package com.exam.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in tokens the database recently confirmed as neither expired nor revoked, so a student's
 * exam page (a save every few seconds) is not checked against the token table on every request.
 * Only "valid" answers are kept, for at most {@link #TTL_MS}. A token updated or deleted through JPA
 * (logout, a new sign-in, an account disabled) is forgotten at once by {@link com.exam.token.Token};
 * anything else (another server instance, a hand edit in the database) takes effect within the TTL.
 * The JWT's own signature and expiry are still checked on every request.
 */
public final class TokenValidityCache {

    static final long TTL_MS = 30_000;
    private static final int MAX_ENTRIES = 20_000;
    private static final Map<String, Long> VALID_UNTIL = new ConcurrentHashMap<>();

    private TokenValidityCache() {}

    public static boolean isKnownValid(String token) {
        Long until = VALID_UNTIL.get(token);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        VALID_UNTIL.remove(token, until);
        return false;
    }

    public static void remember(String token) {
        if (VALID_UNTIL.size() >= MAX_ENTRIES) VALID_UNTIL.clear();   // crude bound; entries refill on the next request
        VALID_UNTIL.put(token, System.currentTimeMillis() + TTL_MS);
    }

    public static void forget(String token) {
        if (token != null) VALID_UNTIL.remove(token);
    }

    static void clear() {
        VALID_UNTIL.clear();
    }
}
