package com.exam.service.academic;

import com.exam.service.FakeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The developer's colour theme: saving, validation, reset, and recolouring PDFs and emails. */
class ThemeServiceTest {

    final Map<String, String> store = new HashMap<>();
    ThemeService theme;

    @BeforeEach
    void setUp() {
        theme = new ThemeService();
        ReflectionTestUtils.setField(theme, "settings", FakeSettings.create(store));
    }

    @Test
    void defaultsUntilTheDeveloperPicksColours() {
        Map<String, Object> t = theme.theme();
        assertThat(t.get("brand")).isEqualTo(ThemeService.DEFAULT_BRAND);
        assertThat(t.get("sidebar")).isEqualTo(ThemeService.DEFAULT_SIDEBAR);
        assertThat(t.get("accent")).isEqualTo(ThemeService.DEFAULT_ACCENT);
        assertThat(t.get("customised")).isEqualTo(false);
        // Documents keep their original print colours
        String html = "<h1 style=\"color:#1a2744\">x</h1>";
        assertThat(theme.recolor(html)).isEqualTo(html);
    }

    @Test
    void savesLowercaseColoursAndTreatsBlankAccentAsAuto() {
        Map<String, Object> t = theme.update(Map.of("brand", "#9F1239", "sidebar", "#2B0F17", "accent", "", "preset", "maroon"));
        assertThat(t.get("brand")).isEqualTo("#9f1239");
        assertThat(t.get("sidebar")).isEqualTo("#2b0f17");
        assertThat(t.get("accent")).isNull();
        assertThat(t.get("preset")).isEqualTo("maroon");
        assertThat(t.get("customised")).isEqualTo(true);
        assertThat((Long) t.get("version")).isPositive();
    }

    @Test
    void refusesAnythingThatIsNotAHexColour() {
        assertThatThrownBy(() -> theme.update(Map.of("brand", "red", "sidebar", "#000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> theme.update(Map.of("brand", "#000000", "sidebar", "#00000;}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> theme.update(Map.of("brand", "#000000", "sidebar", "#000000", "preset", "<script>")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store).isEmpty();
    }

    @Test
    void resetGoesBackToTheBuiltInColours() {
        theme.update(Map.of("brand", "#15803d", "sidebar", "#14281d"));
        Map<String, Object> t = theme.reset();
        assertThat(t.get("brand")).isEqualTo(ThemeService.DEFAULT_BRAND);
        assertThat(t.get("customised")).isEqualTo(false);
        assertThat(store).isEmpty();
    }

    @Test
    void recolorsDocumentsAndKeepsHeadingsReadableOnAPaleBrand() {
        theme.update(Map.of("brand", "#f5c400", "sidebar", "#16213e"));   // pale yellow
        String out = theme.recolor("<div style=\"color:#4A4A9C;border:1px solid #1a2744;background:#edf0fb\">x</div>");

        assertThat(out).doesNotContainIgnoringCase("#4a4a9c").doesNotContain("#1a2744").doesNotContain("#edf0fb");
        String text = out.replaceAll("(?s).*color:(#[0-9a-f]{6}).*", "$1");
        // Headings and labels get the brand shade that reads on white (and white reads on it)
        assertThat(ThemeService.contrast(text, "#ffffff")).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    void colourMathMatchesTheBrowser() {
        // Same numbers utils/theme.ts produces for the default brand (index.css defaults)
        assertThat(ThemeService.mix("#5156be", "#000000", 0.55)).isEqualTo("#242755");
        assertThat(ThemeService.mix("#5156be", "#ffffff", 0.75)).isEqualTo("#d4d5ef");
        assertThat(ThemeService.mix("#5156be", "#ffffff", 0.92)).isEqualTo("#f1f1fa");
        assertThat(ThemeService.readableOn("#5156be")).isEqualTo("#ffffff");
        assertThat(ThemeService.readableOn("#f5c400")).isEqualTo("#111827");
    }
}
