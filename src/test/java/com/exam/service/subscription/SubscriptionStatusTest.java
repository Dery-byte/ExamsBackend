package com.exam.service.subscription;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.exam.service.subscription.SubscriptionState.*;
import static com.exam.service.subscription.SubscriptionStatus.Source.KEY;
import static com.exam.service.subscription.SubscriptionStatus.Source.NO_KEY;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionStatusTest {

    private static final LocalDate PAID = LocalDate.of(2027, 1, 31);
    private static final SubscriptionKey KEY_14_7 = key(14, 7);

    private static SubscriptionKey key(int grace, int restricted) {
        return new SubscriptionKey("id", "UCC", "Uni", "standard", LocalDate.of(2026, 10, 1), PAID, grace, restricted, 0, false, null);
    }

    private static SubscriptionStatus on(LocalDate day) {
        return SubscriptionStatus.evaluate(KEY_14_7, KEY, null, LocalDate.of(2026, 1, 1), day, null, null, null);
    }

    @Test
    void movesThroughEachStageOnTheRightDay() {
        assertThat(on(PAID).state()).isEqualTo(ACTIVE);
        assertThat(on(PAID.plusDays(1)).state()).isEqualTo(GRACE);
        assertThat(on(PAID.plusDays(14)).state()).isEqualTo(GRACE);
        assertThat(on(PAID.plusDays(15)).state()).isEqualTo(RESTRICTED);
        assertThat(on(PAID.plusDays(21)).state()).isEqualTo(RESTRICTED);
        assertThat(on(PAID.plusDays(22)).state()).isEqualTo(SUSPENDED);
    }

    @Test
    void saysWhatComesNextAndWhen() {
        SubscriptionStatus s = on(PAID.minusDays(9));
        assertThat(s.nextChange()).isEqualTo(PAID.plusDays(1));
        assertThat(s.nextState()).isEqualTo(GRACE);
        assertThat(s.daysLeft()).isEqualTo(10);

        SubscriptionStatus g = on(PAID.plusDays(10));
        assertThat(g.nextState()).isEqualTo(RESTRICTED);
        assertThat(g.daysLeft()).isEqualTo(5);

        assertThat(on(PAID.plusDays(30)).nextChange()).isNull();
    }

    @Test
    void zeroGraceGoesStraightToReadOnly() {
        SubscriptionStatus s = SubscriptionStatus.evaluate(key(0, 7), KEY, null, LocalDate.of(2026, 1, 1),
                PAID.plusDays(1), null, null, null);
        assertThat(s.state()).isEqualTo(RESTRICTED);
        assertThat(SubscriptionStatus.evaluate(key(0, 7), KEY, null, LocalDate.of(2026, 1, 1), PAID, null, null, null).nextState())
                .isEqualTo(RESTRICTED);
    }

    @Test
    void extensionKeepsGraceButIsCappedAtThirtyDays() {
        LocalDate day = PAID.plusDays(20);
        SubscriptionStatus ext = SubscriptionStatus.evaluate(KEY_14_7, KEY, null, LocalDate.of(2026, 1, 1), day,
                PAID.plusDays(25), null, null);
        assertThat(ext.state()).isEqualTo(GRACE);
        assertThat(ext.graceEnd()).isEqualTo(PAID.plusDays(25));

        SubscriptionStatus huge = SubscriptionStatus.evaluate(KEY_14_7, KEY, null, LocalDate.of(2026, 1, 1), day,
                PAID.plusYears(5), null, null);
        assertThat(huge.extendedUntil()).isEqualTo(PAID.plusDays(14 + 30));
    }

    @Test
    void manualSuspensionWinsEvenWhenPaid() {
        SubscriptionStatus s = SubscriptionStatus.evaluate(KEY_14_7, KEY, null, LocalDate.of(2026, 1, 1), PAID.minusDays(100),
                null, "Contract ended", LocalDateTime.of(2026, 10, 1, 9, 0));
        assertThat(s.state()).isEqualTo(SUSPENDED);
        assertThat(s.manuallySuspended()).isTrue();
        assertThat(s.nextChange()).isNull();
    }

    @Test
    void aSuspendingKeyFromTheVendorSuspendsEvenWhenPaid() {
        SubscriptionKey k = new SubscriptionKey("id", "UCC", "Uni", "standard", LocalDate.of(2026, 10, 1), PAID, 14, 7, 5, true, "Contract ended");
        SubscriptionStatus s = SubscriptionStatus.evaluate(k, KEY, null, LocalDate.of(2026, 1, 1), PAID.minusDays(100), null, null, null);
        assertThat(s.state()).isEqualTo(SUSPENDED);
        assertThat(s.suspendedByProvider()).isTrue();
        assertThat(s.manuallySuspended()).isFalse();
        assertThat(s.nextChange()).isNull();
    }

    @Test
    void withoutAKeyThereAreThirtyDaysFromFirstStart() {
        LocalDate first = LocalDate.of(2026, 10, 11);
        assertThat(SubscriptionStatus.evaluate(null, NO_KEY, null, first, first, null, null, null).state()).isEqualTo(GRACE);
        assertThat(SubscriptionStatus.evaluate(null, NO_KEY, null, first, first.plusDays(29), null, null, null).state()).isEqualTo(GRACE);
        assertThat(SubscriptionStatus.evaluate(null, NO_KEY, null, first, first.plusDays(30), null, null, null).state()).isEqualTo(RESTRICTED);
        assertThat(SubscriptionStatus.evaluate(null, NO_KEY, null, first, first.plusDays(37), null, null, null).state()).isEqualTo(SUSPENDED);
    }
}
