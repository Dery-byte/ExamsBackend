package com.exam.service.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/**
 * Reads and checks subscription keys: {@code EPSUB1.<base64url JSON>.<base64url Ed25519 signature>}.
 * Keys are made with the vendor's private key (subscription-tool/SubscriptionKeyTool.java, never deployed);
 * only the public half is built in here. It is deliberately not configurable: whoever could change it
 * could sign their own keys.
 */
@Component
public class SubscriptionKeys {

    public static final String PREFIX = "EPSUB1";

    /** Vendor public keys (X.509, base64). A new key pair can be added here before the old one is retired. */
    private static final List<String> PUBLIC_KEYS = List.of(
            // Offline key (subscription-tool/keys): kept off every server, for emergencies and manual keys
            "MCowBQYDK2VwAyEAgAxYXLa7jKL0wPkSoH97jKNMHsTrgqWoEsxd0M5FwdA=",
            // Online key held by the vendor portal, which renews keys automatically
            "MCowBQYDK2VwAyEA9yf4+feQce5RMdVz/PGx0EV9+B5MPYGjb0V3b4ekLHw="
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<PublicKey> trusted;

    @Autowired
    public SubscriptionKeys() {
        this(PUBLIC_KEYS.stream().map(SubscriptionKeys::decodePublicKey).toList());
    }

    /** For tests: trust other keys. */
    public SubscriptionKeys(List<PublicKey> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    /** The key's contents, or an {@link IllegalArgumentException} saying in plain words what is wrong with it. */
    public SubscriptionKey parse(String raw) {
        String key = raw == null ? "" : raw.replaceAll("\\s+", "");
        String[] parts = key.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0]))
            throw new IllegalArgumentException("This is not a subscription key. Paste the whole key, starting with " + PREFIX + ".");
        if (!signedByVendor(parts[0] + "." + parts[1], parts[2]))
            throw new IllegalArgumentException("This key's signature is not valid. It was changed or not issued by the provider.");
        try {
            JsonNode n = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (n.path("v").asInt() != 1) throw new IllegalArgumentException("This key needs a newer version of the portal.");
            SubscriptionKey k = new SubscriptionKey(
                    text(n, "id"), text(n, "inst"), text(n, "name"), n.path("plan").asText("standard"),
                    LocalDate.parse(text(n, "issued")), LocalDate.parse(text(n, "paidUntil")),
                    n.path("graceDays").asInt(14), n.path("restrictedDays").asInt(7),
                    n.path("seq").asLong(0), n.path("suspended").asBoolean(false),
                    n.hasNonNull("note") && !n.path("note").asText().isBlank() ? n.path("note").asText() : null);
            if (k.graceDays() < 0 || k.graceDays() > 90 || k.restrictedDays() < 0 || k.restrictedDays() > 60)
                throw new IllegalArgumentException("This key's grace periods are out of range.");
            return k;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("This key could not be read.");
        }
    }

    private boolean signedByVendor(String signed, String signature) {
        byte[] sig;
        try { sig = Base64.getUrlDecoder().decode(signature); } catch (IllegalArgumentException e) { return false; }
        for (PublicKey pk : trusted) {
            try {
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(pk);
                v.update(signed.getBytes(StandardCharsets.US_ASCII));
                if (v.verify(sig)) return true;
            } catch (Exception ignored) {
                // a malformed signature is simply not valid
            }
        }
        return false;
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("This key is missing its " + field + ".");
        return v;
    }

    static PublicKey decodePublicKey(String b64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("Bad built-in subscription public key", e);
        }
    }
}
