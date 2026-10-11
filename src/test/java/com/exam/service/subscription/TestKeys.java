package com.exam.service.subscription;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/** A throw-away vendor key pair for tests, signing keys exactly like subscription-tool does. */
final class TestKeys {

    final KeyPair pair;

    TestKeys() {
        try { pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    SubscriptionKeys verifier() { return new SubscriptionKeys(List.of(pair.getPublic())); }

    String issue(String inst, LocalDate issued, LocalDate paidUntil, int grace, int restricted) {
        return sign("{\"v\":1,\"id\":\"" + inst + "-" + issued + "-" + paidUntil + "\",\"inst\":\"" + inst
                + "\",\"name\":\"Test Institution\",\"plan\":\"standard\",\"issued\":\"" + issued
                + "\",\"paidUntil\":\"" + paidUntil + "\",\"graceDays\":" + grace + ",\"restrictedDays\":" + restricted + "}");
    }

    /** As the vendor portal issues them: with a sequence number, and optionally suspending the portal. */
    String issue(String inst, LocalDate paidUntil, long seq, String suspendNote) {
        return sign("{\"v\":1,\"id\":\"" + inst + "-" + seq + "\",\"inst\":\"" + inst
                + "\",\"name\":\"Test Institution\",\"plan\":\"standard\",\"issued\":\"2026-10-11"
                + "\",\"paidUntil\":\"" + paidUntil + "\",\"graceDays\":14,\"restrictedDays\":7,\"seq\":" + seq
                + (suspendNote == null ? "" : ",\"suspended\":true,\"note\":\"" + suspendNote + "\"") + "}");
    }

    String sign(String json) {
        try {
            String signed = SubscriptionKeys.PREFIX + "." + b64(json.getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
