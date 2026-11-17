package com.exam.service.materials;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Short-lived signed download links, so a phone can open a file in a new tab (or the PDF viewer can
 * fetch it) without the sign-in token in the address. A link names the file, the person it was made
 * for and when it stops working (5 minutes). The key lives in memory: a server restart ends old links.
 */
@Component
public class MaterialLinks {

    public static final long LIFETIME_SECONDS = 300;

    private final byte[] key = new byte[32];

    public MaterialLinks() {
        new SecureRandom().nextBytes(key);
    }

    /** "/api/materials/download/{id}?u=..&e=..&i=..&s=.." (relative to the API root). */
    public String fileLink(String fileId, long userId, boolean inline) {
        long exp = Instant.now().getEpochSecond() + LIFETIME_SECONDS;
        String i = inline ? "1" : "0";
        return "/materials/download/" + fileId + "?u=" + userId + "&e=" + exp + "&i=" + i
                + "&s=" + sign("f|" + fileId + "|" + userId + "|" + exp + "|" + i);
    }

    public boolean fileLinkValid(String fileId, long userId, long exp, String inline, String sig) {
        return valid("f|" + fileId + "|" + userId + "|" + exp + "|" + inline, exp, sig);
    }

    /** The PDF view of a Word / PowerPoint file (OfficePreview). */
    public String previewLink(String fileId, long userId) {
        long exp = Instant.now().getEpochSecond() + LIFETIME_SECONDS;
        return "/materials/preview/" + fileId + "?u=" + userId + "&e=" + exp + "&s=" + sign("p|" + fileId + "|" + userId + "|" + exp);
    }

    public boolean previewLinkValid(String fileId, long userId, long exp, String sig) {
        return valid("p|" + fileId + "|" + userId + "|" + exp, exp, sig);
    }

    /** A week's files as one ZIP. */
    public String zipLink(long spaceId, int week, long userId) {
        long exp = Instant.now().getEpochSecond() + LIFETIME_SECONDS;
        return "/materials/zip/" + spaceId + "/" + week + "?u=" + userId + "&e=" + exp
                + "&s=" + sign("z|" + spaceId + "|" + week + "|" + userId + "|" + exp);
    }

    public boolean zipLinkValid(long spaceId, int week, long userId, long exp, String sig) {
        return valid("z|" + spaceId + "|" + week + "|" + userId + "|" + exp, exp, sig);
    }

    private boolean valid(String payload, long exp, String sig) {
        if (sig == null || exp < Instant.now().getEpochSecond()) return false;
        return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
