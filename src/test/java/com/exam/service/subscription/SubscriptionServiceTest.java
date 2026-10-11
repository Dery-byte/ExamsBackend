package com.exam.service.subscription;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.SubscriptionEventRepository;
import com.exam.service.FakeSettings;
import com.exam.service.subscription.SubscriptionService.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.HashMap;
import java.util.Map;

import static com.exam.service.subscription.SubscriptionService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    private final TestKeys vendor = new TestKeys();
    private final Map<String, String> store = new HashMap<>();
    private final QuizAttemptRepository attempts = mock(QuizAttemptRepository.class);
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService();
        ReflectionTestUtils.setField(service, "settings", FakeSettings.create(store));
        ReflectionTestUtils.setField(service, "keys", vendor.verifier());
        ReflectionTestUtils.setField(service, "events", mock(SubscriptionEventRepository.class));
        ReflectionTestUtils.setField(service, "attempts", attempts);
        at(TODAY.atTime(10, 0));
    }

    private void at(LocalDateTime t) {
        service.setClock(Clock.fixed(t.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    private String key(LocalDate issued, LocalDate paidUntil) {
        return vendor.issue("UCC", issued, paidUntil, 14, 7);
    }

    private static User user(long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    @Test
    void aFreshDeploymentStartsItsThirtyDaysToday() {
        assertThat(service.refresh().state()).isEqualTo(SubscriptionState.GRACE);
        assertThat(store.get(FIRST_SEEN)).isEqualTo(TODAY.toString());
        assertThat(service.status().graceEnd()).isEqualTo(TODAY.plusDays(29));
    }

    @Test
    void installingAKeyMakesItActiveAndClearsAnOldExtension() {
        store.put(EXTENDED_UNTIL, TODAY.plusDays(5).toString());
        service.installKey(key(TODAY, TODAY.plusMonths(3)), "dev");
        SubscriptionStatus s = service.status();
        assertThat(s.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(s.source()).isEqualTo(SubscriptionStatus.Source.KEY);
        assertThat(store).doesNotContainKey(EXTENDED_UNTIL);
    }

    @Test
    void anOlderKeyCannotReplaceANewerOne() {
        service.installKey(key(TODAY, TODAY.plusMonths(3)), "dev");
        assertThatThrownBy(() -> service.installKey(key(TODAY.minusDays(30), TODAY.plusYears(1)), "dev"))
                .hasMessageContaining("older than the one installed");
    }

    @Test
    void aKeyForAnotherInstitutionIsRefusedWhenThePortalIsBound() {
        ReflectionTestUtils.setField(service, "boundInstitution", "KNUST");
        assertThatThrownBy(() -> service.installKey(key(TODAY, TODAY.plusMonths(3)), "dev")).hasMessageContaining("is for 'UCC'");
        // A key edited into the database directly is ignored too
        store.put(KEY, key(TODAY, TODAY.plusMonths(3)));
        assertThat(service.refresh().source()).isEqualTo(SubscriptionStatus.Source.WRONG_INSTITUTION);
    }

    @Test
    void aTamperedKeyInTheDatabaseIsIgnored() {
        store.put(KEY, key(TODAY, TODAY.plusMonths(3)).replace("EPSUB1.", "EPSUB1.x"));
        SubscriptionStatus s = service.refresh();
        assertThat(s.source()).isEqualTo(SubscriptionStatus.Source.INVALID_KEY);
        assertThat(s.keyProblem()).isNotBlank();
    }

    @Test
    void turningTheClockBackDoesNotWinBackDays() {
        service.installKey(key(TODAY.minusMonths(2), TODAY.minusDays(30)), "dev");   // suspended by now
        assertThat(service.refresh().state()).isEqualTo(SubscriptionState.SUSPENDED);
        at(TODAY.minusMonths(1).atTime(10, 0));
        assertThat(service.refresh().state()).isEqualTo(SubscriptionState.SUSPENDED);
    }

    @Test
    void readOnlyAndSuspendedAccessByRole() {
        // Read-only: everyone can look, nobody can change
        service.installKey(key(TODAY.minusMonths(2), TODAY.minusDays(16)), "dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.RESTRICTED);
        assertThat(service.accessFor(user(1, Role.LECTURER))).isEqualTo(Access.READ_ONLY);
        assertThat(service.accessFor(user(2, Role.NORMAL))).isEqualTo(Access.READ_ONLY);
        assertThat(service.accessFor(user(3, Role.DEVELOPER))).isEqualTo(Access.FULL);
        assertThatThrownBy(service::assertNewAttemptsAllowed).isInstanceOf(ResponseStatusException.class);
        assertThat(service.allowsSelfSignUp()).isFalse();

        // Suspended: only the Super Admin (read-only) and the developer
        service.suspend("Unpaid", "dev");
        assertThat(service.accessFor(user(4, Role.SUPER_ADMIN))).isEqualTo(Access.READ_ONLY);
        assertThat(service.accessFor(user(5, Role.ADMIN))).isEqualTo(Access.BLOCKED);
        assertThat(service.accessFor(user(6, Role.LECTURER))).isEqualTo(Access.BLOCKED);
        assertThat(service.allowsSignIn(user(5, Role.ADMIN))).isFalse();
        assertThat(service.allowsSignIn(user(4, Role.SUPER_ADMIN))).isTrue();
    }

    @Test
    void aStudentInTheMiddleOfAnExamIsNeverStopped() {
        service.suspend("Unpaid", "dev");
        when(attempts.existsByUser_IdAndStatus(eq(7L), eq(AttemptStatus.IN_PROGRESS))).thenReturn(true);
        assertThat(service.accessFor(user(7, Role.NORMAL))).isEqualTo(Access.FULL);
        assertThat(service.allowsSignIn(user(7, Role.NORMAL))).isTrue();
        // One who just submitted still gets their marking calls through
        when(attempts.existsByUser_IdAndStatusAndSubmittedAtAfter(eq(8L), eq(AttemptStatus.SUBMITTED), any())).thenReturn(true);
        assertThat(service.accessFor(user(8, Role.NORMAL))).isEqualTo(Access.FULL);
        assertThat(service.accessFor(user(9, Role.NORMAL))).isEqualTo(Access.BLOCKED);
    }

    @Test
    void theDeveloperChoosesWhetherAdminsSeeTheNotice() {
        service.installKey(key(TODAY.minusMonths(2), TODAY.minusDays(3)), "dev");   // in grace
        User admin = user(1, Role.ADMIN), sa = user(2, Role.SUPER_ADMIN), lecturer = user(3, Role.LECTURER);

        assertThat(service.notice(sa)).containsEntry("show", true).containsEntry("state", "GRACE");
        assertThat(service.notice(admin)).containsEntry("show", false).doesNotContainKey("state");

        service.updateSettings(Map.of("audience", "ADMINS"), "dev");
        assertThat(service.notice(admin)).containsEntry("show", true).containsEntry("state", "GRACE");
        assertThat(service.notice(lecturer)).containsEntry("show", false).containsEntry("access", "FULL");

        assertThatThrownBy(() -> service.updateSettings(Map.of("audience", "EVERYONE"), "dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noNoticeWhilePaidUpUntilTheLastTwoWeeks() {
        service.installKey(key(TODAY, TODAY.plusDays(20)), "dev");
        assertThat(service.notice(user(1, Role.SUPER_ADMIN))).containsEntry("show", false);
        service.installKey(key(TODAY.plusDays(1), TODAY.plusDays(10)), "dev");
        assertThat(service.notice(user(1, Role.SUPER_ADMIN))).containsEntry("show", true).containsEntry("daysLeft", 11L);
    }

    @Test
    void anExtensionMustStayWithinThirtyDaysOfTheKeysGrace() {
        LocalDate paid = TODAY.minusDays(20);   // grace ended 6 days ago: read-only
        service.installKey(key(TODAY.minusMonths(2), paid), "dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.RESTRICTED);

        assertThatThrownBy(() -> service.extend(paid.plusDays(14 + 31), "Promised Friday", "dev")).hasMessageContaining("at the latest");
        assertThatThrownBy(() -> service.extend(TODAY.plusDays(5), " ", "dev")).hasMessageContaining("reason");

        service.extend(TODAY.plusDays(5), "Promised Friday", "dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.GRACE);
        service.clearExtension("dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.RESTRICTED);
    }

    @Test
    void resumingLiftsOnlyAManualSuspension() {
        service.installKey(key(TODAY, TODAY.plusMonths(3)), "dev");
        assertThatThrownBy(() -> service.resume("dev")).hasMessageContaining("not suspended");
        service.suspend("Contract dispute", "dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.SUSPENDED);
        service.resume("dev");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.ACTIVE);
    }
}
