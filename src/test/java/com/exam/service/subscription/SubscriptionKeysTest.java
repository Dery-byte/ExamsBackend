package com.exam.service.subscription;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionKeysTest {

    private final TestKeys vendor = new TestKeys();

    @Test
    void readsAKeySignedByTheVendor() {
        String raw = vendor.issue("UCC", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 31), 14, 7);
        // Line breaks from copying out of an email don't matter
        SubscriptionKey k = vendor.verifier().parse(raw.substring(0, 20) + "\n " + raw.substring(20));
        assertThat(k.institutionId()).isEqualTo("UCC");
        assertThat(k.paidUntil()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(k.graceDays()).isEqualTo(14);
        assertThat(k.restrictedDays()).isEqualTo(7);
    }

    /** Made by subscription-tool with the real vendor key: the tool and the portal agree on the format. */
    @Test
    void acceptsAKeyFromTheVendorTool() {
        SubscriptionKey k = new SubscriptionKeys().parse("EPSUB1.eyJ2IjoxLCJpZCI6Ijg1MzNmOTNhLTY2MDgtNDNmMS05OWY0LTg3MGUxNmIwMGVlOSIsImluc3QiOiJURVNUIiwibmFtZSI6IkZvcm1hdCBjaGVjayIsInBsYW4iOiJzdGFuZGFyZCIsImlzc3VlZCI6IjIwMjYtMTAtMTEiLCJwYWlkVW50aWwiOiIyMDI3LTAxLTMxIiwiZ3JhY2VEYXlzIjoxNCwicmVzdHJpY3RlZERheXMiOjd9.C1_uGWP6w7sk9iV4rNVc68VTnLXF7563KUarwJqSS5yNIteKJcsYCSD9hsmU0sdFjNP02vb8nWbwn6JPErtIDg");
        assertThat(k.institutionId()).isEqualTo("TEST");
        assertThat(k.paidUntil()).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    /** Made with the vendor portal's online key: the portal trusts both vendor keys. */
    @Test
    void acceptsAKeySignedByTheVendorPortalsOnlineKey() {
        SubscriptionKey k = new SubscriptionKeys().parse("EPSUB1.eyJ2IjoxLCJpZCI6IjMzMjUyZjRmLWVlOTEtNDAzZS04OTY2LTNmNTE4MDY2ZmI3YSIsImluc3QiOiJPTkxJTkUiLCJuYW1lIjoiT25saW5lIGtleSBjaGVjayIsInBsYW4iOiJzdGFuZGFyZCIsImlzc3VlZCI6IjIwMjYtMTAtMTEiLCJwYWlkVW50aWwiOiIyMDI3LTAzLTMxIiwiZ3JhY2VEYXlzIjoxNCwicmVzdHJpY3RlZERheXMiOjcsInNlcSI6MTc5MTcxNjk5NjMwOX0.hk1Xf5vM5q4ghMVOj2_xsEX_HTU1KRNVDFarGNxpiBjAGLL73DTTMWndw0N2G91X1v2azikVwlmDuHNqB0zwCw");
        assertThat(k.institutionId()).isEqualTo("ONLINE");
        assertThat(k.seq()).isPositive();
    }

    @Test
    void readsSequenceAndSuspensionAndOrdersKeysBySequence() {
        SubscriptionKey older = vendor.verifier().parse(vendor.issue("UCC", LocalDate.of(2027, 1, 31), 1000, null));
        SubscriptionKey suspends = vendor.verifier().parse(vendor.issue("UCC", LocalDate.of(2027, 1, 31), 2000, "Contract ended"));
        assertThat(older.suspended()).isFalse();
        assertThat(older.note()).isNull();
        assertThat(suspends.suspended()).isTrue();
        assertThat(suspends.note()).isEqualTo("Contract ended");
        assertThat(suspends.isNewerThan(older)).isTrue();
        assertThat(older.isNewerThan(suspends)).isFalse();
        // A key without a sequence (made before Phase 2) is ordered by its issue date
        SubscriptionKey legacy = vendor.verifier().parse(vendor.issue("UCC", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 31), 14, 7));
        assertThat(older.isNewerThan(legacy)).isTrue();
    }

    @Test
    void refusesAKeyWhoseDatesWereEdited() {
        String raw = vendor.issue("UCC", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 31), 14, 7);
        String[] parts = raw.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("2027-01-31", "2099-01-31");
        String forged = parts[0] + "." + TestKeys.b64(json.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThatThrownBy(() -> vendor.verifier().parse(forged)).hasMessageContaining("signature is not valid");
    }

    @Test
    void refusesAKeySignedBySomeoneElse() {
        String other = new TestKeys().issue("UCC", LocalDate.of(2026, 10, 1), LocalDate.of(2099, 1, 31), 14, 7);
        assertThatThrownBy(() -> vendor.verifier().parse(other)).hasMessageContaining("signature is not valid");
        // Nor does the built-in production key accept test keys
        assertThatThrownBy(() -> new SubscriptionKeys().parse(other)).hasMessageContaining("signature is not valid");
    }

    @Test
    void explainsWhatIsWrongWithRubbish() {
        assertThatThrownBy(() -> vendor.verifier().parse("hello")).hasMessageContaining("not a subscription key");
        assertThatThrownBy(() -> vendor.verifier().parse("EPSUB1.abc.def")).hasMessageContaining("signature is not valid");
        assertThatThrownBy(() -> vendor.verifier().parse(vendor.sign("{\"v\":1,\"inst\":\"X\"}"))).hasMessageContaining("missing");
        assertThatThrownBy(() -> vendor.verifier().parse(vendor.issue("UCC", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 500, 7)))
                .hasMessageContaining("out of range");
    }
}
