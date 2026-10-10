package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.service.FakeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maintenance mode: who may sign in, new attempts refused, and what the public is told. */
class MaintenanceServiceTest {

    final Map<String, String> store = new HashMap<>();
    MaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        maintenance = new MaintenanceService();
        ReflectionTestUtils.setField(maintenance, "settings", FakeSettings.create(store));
    }

    @Test
    void offByDefaultEveryoneSignsIn() {
        assertThat(maintenance.isOn()).isFalse();
        for (Role r : Role.values()) assertThat(maintenance.allowsSignIn(r)).isTrue();
        assertThatCode(maintenance::assertNewAttemptsAllowed).doesNotThrowAnyException();
        assertThat(maintenance.publicStatus()).containsEntry("enabled", false).doesNotContainKey("message");
    }

    @Test
    void onlyDevelopersAndAllowedSuperAdminsSignInWhileOn() {
        maintenance.update(Map.of("enabled", true, "message", "Upgrading to v2", "allowAdmins", true));

        assertThat(maintenance.allowsSignIn(Role.DEVELOPER)).isTrue();
        assertThat(maintenance.allowsSignIn(Role.SUPER_ADMIN)).isTrue();
        assertThat(maintenance.allowsSignIn(Role.ADMIN)).isFalse();
        assertThat(maintenance.allowsSignIn(Role.LECTURER)).isFalse();
        assertThat(maintenance.allowsSignIn(Role.NORMAL)).isFalse();

        maintenance.update(Map.of("allowAdmins", false));
        assertThat(maintenance.allowsSignIn(Role.SUPER_ADMIN)).isFalse();
        assertThat(maintenance.allowsSignIn(Role.DEVELOPER)).isTrue();
    }

    @Test
    void refusesNewAttemptsWith503AndTheMessage() {
        maintenance.update(Map.of("enabled", true, "message", "Back at 6pm"));
        assertThatThrownBy(maintenance::assertNewAttemptsAllowed)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException r = (ResponseStatusException) e;
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(r.getReason()).contains("Back at 6pm");
                });
    }

    @Test
    void recordsWhenItStartedAndClearsItWhenSwitchedOff() {
        maintenance.update(Map.of("enabled", true));
        assertThat(maintenance.status().get("since")).isNotNull();
        assertThat(maintenance.publicStatus()).containsEntry("message", MaintenanceService.DEFAULT_MESSAGE);

        maintenance.update(Map.of("enabled", false));
        assertThat(maintenance.status().get("since")).isNull();
        assertThat(maintenance.isOn()).isFalse();
    }

    @Test
    void validatesTheNotice() {
        assertThatThrownBy(() -> maintenance.update(Map.of("message", "x".repeat(301))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> maintenance.update(Map.of("until", "tomorrow-ish")))
                .isInstanceOf(IllegalArgumentException.class);
        maintenance.update(Map.of("until", "2026-10-11T18:00"));
        assertThat(maintenance.status().get("until")).isEqualTo("2026-10-11T18:00");
    }
}
