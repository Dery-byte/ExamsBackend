package com.exam.service;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.features.DepartmentFeatureSetting;
import com.exam.model.features.Feature;
import com.exam.repository.DepartmentFeatureSettingRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.service.features.FeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeatureServiceTest {

    private final Map<String, String> settings = new HashMap<>();
    private final Map<String, DepartmentFeatureSetting> deptSettings = new HashMap<>();
    private FeatureService features;
    private Department cs, maths;
    private User superAdmin, hodCs, csStudent, mathsStudent;

    @BeforeEach
    void setUp() {
        SystemSettingService system = mock(SystemSettingService.class);
        when(system.getBooleanSetting(anyString(), anyBoolean())).thenAnswer(i ->
                settings.containsKey(i.<String>getArgument(0)) ? Boolean.parseBoolean(settings.get(i.<String>getArgument(0))) : i.<Boolean>getArgument(1));
        doAnswer(i -> settings.put(i.getArgument(0), i.getArgument(1))).when(system).updateSetting(anyString(), anyString());

        DepartmentFeatureSettingRepository repo = mock(DepartmentFeatureSettingRepository.class);
        when(repo.findByDepartment_IdAndFeatureKey(anyLong(), anyString())).thenAnswer(i ->
                Optional.ofNullable(deptSettings.get(i.getArgument(0) + ":" + i.getArgument(1))));
        when(repo.save(any())).thenAnswer(i -> {
            DepartmentFeatureSetting s = i.getArgument(0);
            deptSettings.put(s.getDepartment().getId() + ":" + s.getFeatureKey(), s);
            return s;
        });
        doAnswer(i -> {
            DepartmentFeatureSetting s = i.getArgument(0);
            deptSettings.remove(s.getDepartment().getId() + ":" + s.getFeatureKey());
            return null;
        }).when(repo).delete(any());

        cs = dept(1L); maths = dept(2L);
        DepartmentRepository depts = mock(DepartmentRepository.class);
        when(depts.findById(1L)).thenReturn(Optional.of(cs));
        when(depts.findById(2L)).thenReturn(Optional.of(maths));

        features = new FeatureService();
        ReflectionTestUtils.setField(features, "systemSettingService", system);
        ReflectionTestUtils.setField(features, "departmentSettings", repo);
        ReflectionTestUtils.setField(features, "departmentRepository", depts);

        superAdmin = user(Role.SUPER_ADMIN, null);
        hodCs = user(Role.ADMIN, cs);
        csStudent = student(cs);
        mathsStudent = student(maths);
    }

    private static Department dept(Long id) { Department d = new Department(); d.setId(id); d.setName("D" + id); return d; }

    private static User user(Role role, Department d) { User u = new User(); u.setId((long) (Math.random() * 1e9)); u.setRole(role); u.setDepartment(d); return u; }

    /** Students belong to their program's department. */
    private static User student(Department d) {
        Program p = new Program(); p.setDepartment(d);
        User u = user(Role.NORMAL, null); u.setProgram(p); return u;
    }

    @Test
    void everythingIsOnByDefault() {
        for (Feature f : Feature.values()) assertThat(features.isOnFor(f, csStudent)).as(f.name()).isTrue();
    }

    @Test
    void systemSwitchOffBeatsEveryDepartment() {
        features.setForDepartment(superAdmin, "REMARK_REQUESTS", 1L, true);
        features.setSystemWide(superAdmin, "REMARK_REQUESTS", false);

        assertThat(features.isOnFor(Feature.REMARK_REQUESTS, csStudent)).isFalse();
        assertThat(features.isOnFor(Feature.REMARK_REQUESTS, mathsStudent)).isFalse();
        assertThat(features.isOnFor(Feature.REMARK_REQUESTS, superAdmin)).isTrue();   // never restricted
    }

    @Test
    void hodSwitchesOnlyTheirOwnDepartment() {
        features.setForDepartment(hodCs, "STUDENT_TIMETABLE", 1L, false);

        assertThat(features.isOnFor(Feature.STUDENT_TIMETABLE, csStudent)).isFalse();
        assertThat(features.isOnFor(Feature.STUDENT_TIMETABLE, mathsStudent)).isTrue();
        assertThatThrownBy(() -> features.require(Feature.STUDENT_TIMETABLE, csStudent))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("your department");

        features.setForDepartment(hodCs, "STUDENT_TIMETABLE", 1L, null);           // back to following the system
        assertThat(features.isOnFor(Feature.STUDENT_TIMETABLE, csStudent)).isTrue();
    }

    @Test
    void hodCannotReachOtherDepartmentsOrSystemSwitches() {
        assertThatThrownBy(() -> features.setForDepartment(hodCs, "STUDENT_TIMETABLE", 2L, false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> features.setSystemWide(hodCs, "STUDENT_TIMETABLE", false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> features.setForDepartment(hodCs, "HOD_ANALYTICS", 1L, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hodCannotOverrideWhatTheSuperAdminSwitchedOff() {
        features.setSystemWide(superAdmin, "QUESTION_BANK", false);
        assertThatThrownBy(() -> features.setForDepartment(hodCs, "QUESTION_BANK", 1L, true))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Super Admin");
    }

    @Test
    void systemFeaturesIgnoreDepartments() {
        features.setSystemWide(superAdmin, "HOD_ANALYTICS", false);
        assertThat(features.isOnFor(Feature.HOD_ANALYTICS, hodCs)).isFalse();
        assertThatThrownBy(() -> features.require(Feature.HOD_ANALYTICS, hodCs)).hasMessageContaining("Super Admin");
    }
}
