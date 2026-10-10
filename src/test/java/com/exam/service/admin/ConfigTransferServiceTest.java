package com.exam.service.admin;

import com.exam.model.academic.SystemMode;
import com.exam.service.FakeSettings;
import com.exam.service.academic.InstitutionService;
import com.exam.service.academic.ThemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Settings export / import: what travels, what never does, preview before apply. */
class ConfigTransferServiceTest {

    final Map<String, String> store = new HashMap<>();
    ConfigTransferService transfer;
    InstitutionService institution;

    @BeforeEach
    void setUp() {
        institution = mock(InstitutionService.class);
        when(institution.name()).thenReturn("Accra Academy");
        when(institution.shortName()).thenReturn("AA");
        when(institution.mode()).thenReturn(SystemMode.SHS);
        when(institution.logo()).thenReturn(Optional.empty());
        transfer = new ConfigTransferService();
        ReflectionTestUtils.setField(transfer, "settings", FakeSettings.create(store));
        ReflectionTestUtils.setField(transfer, "institution", institution);
    }

    @Test
    void exportLeavesOutStateAndSecrets() {
        store.put("INSTITUTION_NAME", "Accra Academy");
        store.put(ThemeService.BRAND, "#15803d");
        store.put(ThemeService.VERSION, "123");
        store.put(MaintenanceService.ON, "true");
        store.put("SETUP_EMAIL_VERIFIED_AT", "2026-10-10T10:00");
        store.put("PAYSTACK_SECRET_KEY", "sk_live_xxx");
        store.put("GRADING_SCALE_SEEDED", "true");

        @SuppressWarnings("unchecked")
        Map<String, String> s = (Map<String, String>) transfer.export(false).get("settings");
        assertThat(s).containsOnlyKeys("INSTITUTION_NAME", ThemeService.BRAND);
    }

    @Test
    void previewChangesNothingAndApplyWrites() {
        store.put("INSTITUTION_NAME", "Old name");
        store.put("REPORT_SHOW_POSITION", "true");
        Map<String, Object> file = file(Map.of("INSTITUTION_NAME", "New name", "REPORT_SHOW_POSITION", "true", ThemeService.BRAND, "#15803d"));

        Map<String, Object> preview = transfer.importConfig(file, false);
        assertThat((List<?>) preview.get("changes")).hasSize(2);
        assertThat(preview.get("unchanged")).isEqualTo(1);
        assertThat(store).containsEntry("INSTITUTION_NAME", "Old name").doesNotContainKey(ThemeService.BRAND);

        transfer.importConfig(file, true);
        assertThat(store).containsEntry("INSTITUTION_NAME", "New name").containsEntry(ThemeService.BRAND, "#15803d");
        assertThat(store).containsKey(ThemeService.VERSION);   // a theme change counts as choosing colours
    }

    @Test
    void skipsStateSecretsAndInvalidValues() {
        Map<String, Object> file = file(Map.of(
                MaintenanceService.ON, "true", "STRIPE_API_KEY", "x", "SYSTEM_MODE", "SPACESHIP",
                ThemeService.SIDEBAR, "blue", "bad key!", "1"));
        Map<String, Object> r = transfer.importConfig(file, true);
        assertThat((List<?>) r.get("changes")).isEmpty();
        assertThat((List<?>) r.get("skipped")).hasSize(5);
        assertThat(store).isEmpty();
    }

    @Test
    void refusesFilesThatAreNotSettingsExports() {
        assertThatThrownBy(() -> transfer.importConfig(Map.of("format", "something-else"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer.importConfig(Map.of("format", ConfigTransferService.FORMAT, "formatVersion", 99, "settings", Map.of()), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logoTravelsWhenIncluded() {
        Map<String, Object> f = new HashMap<>(file(Map.of()));
        f.put("logo", Map.of("contentType", "image/png", "data", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        Map<String, Object> r = transfer.importConfig(f, true);
        assertThat(r.get("logo")).isEqualTo(true);
        verify(institution).saveLogo(any(), org.mockito.ArgumentMatchers.eq("image/png"));
    }

    private static Map<String, Object> file(Map<String, String> settings) {
        return Map.of("format", ConfigTransferService.FORMAT, "formatVersion", 1, "settings", settings);
    }
}
