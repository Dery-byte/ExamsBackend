package com.exam.service;

import com.exam.model.academic.GradingPreset;
import com.exam.repository.DegreeClassRepository;
import com.exam.repository.GradeBandRepository;
import com.exam.repository.GradingPresetRepository;
import com.exam.repository.StudentCourseMarkRepository;
import com.exam.service.academic.GradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Built-in Ghanaian presets and Super Admin saved presets. */
class GradingPresetTest {

    private GradingService grading;
    private final List<com.exam.model.academic.GradeBand> savedBands = new ArrayList<>();
    private final Map<Long, GradingPreset> presets = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GradeBandRepository bands = mock(GradeBandRepository.class);
        when(bands.saveAll(anyList())).thenAnswer(i -> { savedBands.clear(); savedBands.addAll(i.getArgument(0)); return i.getArgument(0); });
        when(bands.findAllByOrderByMinScoreDesc()).thenAnswer(i -> savedBands.stream()
                .sorted(Comparator.comparing(com.exam.model.academic.GradeBand::getMinScore).reversed()).toList());
        DegreeClassRepository classes = mock(DegreeClassRepository.class);
        when(classes.findAllByOrderByMinCgpaDesc()).thenReturn(List.of());

        GradingPresetRepository presetRepo = mock(GradingPresetRepository.class);
        when(presetRepo.save(any())).thenAnswer(i -> { GradingPreset g = i.getArgument(0); g.setId((long) presets.size() + 1); presets.put(g.getId(), g); return g; });
        when(presetRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(presets.get(i.<Long>getArgument(0))));
        when(presetRepo.findAllByOrderByNameAsc()).thenAnswer(i -> new ArrayList<>(presets.values()));
        doAnswer(i -> presets.remove(i.<GradingPreset>getArgument(0).getId())).when(presetRepo).delete(any());
        when(presetRepo.existsByNameIgnoreCase(anyString())).thenAnswer(i ->
                presets.values().stream().anyMatch(g -> g.getName().equalsIgnoreCase(i.getArgument(0))));

        grading = new GradingService();
        ReflectionTestUtils.setField(grading, "bandRepository", bands);
        ReflectionTestUtils.setField(grading, "classRepository", classes);
        ReflectionTestUtils.setField(grading, "presetRepository", presetRepo);
        ReflectionTestUtils.setField(grading, "studentCourseMarkRepository", mock(StudentCourseMarkRepository.class));
        ReflectionTestUtils.setField(grading, "systemSettingService", mock(SystemSettingService.class));
    }

    @SuppressWarnings("unchecked")
    private void load(String key) {
        Map<String, Object> p = grading.preset(key);
        grading.update((List<GradingService.BandRow>) p.get("bands"), (List<GradingService.ClassRow>) p.get("classes"), null, null, null);
        ReflectionTestUtils.setField(grading, "cachedBands", null);
    }

    private String letter(double score) { return grading.gradeFor(BigDecimal.valueOf(score)).getLetter(); }

    @Test
    void ghanaianPresetsAreValidAndGradeCorrectly() {
        load("GH_SHS");
        assertThat(letter(75)).isEqualTo("A1");
        assertThat(letter(74.9)).isEqualTo("B2");
        assertThat(letter(50)).isEqualTo("C6");
        assertThat(letter(39)).isEqualTo("F9");
        assertThat(grading.gradeFor(BigDecimal.valueOf(39)).isPassing()).isFalse();
        assertThat(grading.gradeFor(BigDecimal.valueOf(40)).isPassing()).isTrue();   // E8 is a pass
        assertThat(grading.gradeFor(BigDecimal.valueOf(80)).getGradePoint()).isEqualByComparingTo("8");

        load("GH_JHS");
        assertThat(letter(80)).isEqualTo("1");
        assertThat(letter(57)).isEqualTo("4");
        assertThat(letter(34)).isEqualTo("9");

        load("GH_PRIMARY");
        assertThat(letter(68)).isEqualTo("P");
        assertThat(letter(67)).isEqualTo("AP");
        assertThat(letter(39)).isEqualTo("B");
    }

    @Test
    void schoolPresetsHaveNoClassesOfDegree() {
        assertThat((List<?>) grading.preset("GH_SHS").get("classes")).isEmpty();
        assertThat(grading.classFor(new BigDecimal("7.5"))).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void superAdminCanSaveLoadAndDeleteOwnPresets() {
        List<GradingService.BandRow> bands = (List<GradingService.BandRow>) grading.preset("GH_SHS").get("bands");
        grading.savePreset("Our SHS scale", "Adapted for mock exams", bands, List.of(), "Super Admin");

        Map<String, Object> custom = grading.presets().stream().filter(p -> !(Boolean) p.get("builtIn")).findFirst().orElseThrow();
        assertThat(custom.get("label")).isEqualTo("Our SHS scale");
        assertThat((List<?>) grading.preset((String) custom.get("key")).get("bands")).hasSize(9);

        assertThatThrownBy(() -> grading.savePreset("our shs scale", null, bands, List.of(), "x")).hasMessageContaining("already exists");
        assertThatThrownBy(() -> grading.savePreset("No zero", null,
                List.of(new GradingService.BandRow("A", BigDecimal.TEN, BigDecimal.ONE, null, true)), List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);

        grading.deletePreset((Long) custom.get("id"));
        assertThat(grading.presets().stream().filter(p -> !(Boolean) p.get("builtIn"))).isEmpty();
    }
}
