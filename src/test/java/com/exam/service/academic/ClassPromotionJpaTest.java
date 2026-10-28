package com.exam.service.academic;

import com.exam.DTO.DepartmentDTO;
import com.exam.DTO.ProgramDTO;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.SystemMode;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.DepartmentService;
import com.exam.service.ProgramService;
import com.exam.service.admin.AccountService;
import com.exam.service.features.FeatureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * School promotion on H2: a class moves to its next class (first term, its only level), students short
 * of the promotion rules stay unless the Super Admin overrides, a final class completes school
 * (accounts deactivated), and HODs only promote in their own section.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:classpromotion;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({ClassPromotionService.class, ProgramService.class, DepartmentService.class})
class ClassPromotionJpaTest {

    @Autowired ClassPromotionService promotion;
    @Autowired ProgramService programs;
    @Autowired DepartmentService departments;
    @Autowired UserRepository users;
    @Autowired ProgramRepository programRepository;
    @Autowired DepartmentRepository departmentRepository;
    @MockBean AcademicRecordService records;
    @MockBean AccountService accounts;
    @MockBean FeatureService features;

    User sa, primaryHead, jhsHead;
    Program p5, p6, jhs1, jhs3;
    /** Students the promotion rules hold back. */
    final Set<Long> failing = new HashSet<>();

    @BeforeEach
    void setUp() {
        SystemMode.setCurrent(SystemMode.BASIC);
        Long primary = departments.createDepartment(DepartmentDTO.builder().name("Primary School").code("PRI").durationYears(6).build()).getId();
        Long jhs = departments.createDepartment(DepartmentDTO.builder().name("Junior High School").code("JHS").durationYears(3).build()).getId();
        p5 = cls(primary, "Primary 5", 5);
        p6 = cls(primary, "Primary 6", 6);
        jhs1 = cls(jhs, "JHS 1", 1);
        jhs3 = cls(jhs, "JHS 3", 3);
        sa = user("sa", Role.SUPER_ADMIN, null, null);
        primaryHead = user("hpri", Role.ADMIN, departmentRepository.findById(primary).orElseThrow(), null);
        jhsHead = user("hjhs", Role.ADMIN, departmentRepository.findById(jhs).orElseThrow(), null);

        when(records.eligibility(any())).thenAnswer(i -> {
            User s = i.getArgument(0);
            boolean ok = !failing.contains(s.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getId());
            m.put("name", s.getFirstname());
            m.put("eligible", ok);
            m.put("reasons", ok ? List.of() : List.of("2 outstanding carry-overs (limit 0)"));
            return m;
        });
    }

    @AfterEach
    void backToUniversity() { SystemMode.setCurrent(SystemMode.UNIVERSITY); }

    private Program cls(Long section, String name, int year) {
        ProgramDTO d = programs.createProgram(ProgramDTO.builder().name(name).code(name.replace(" ", "")).departmentId(section)
                .sectionYear(year).semestersPerLevel(Map.of(1, 3)).build());
        return programRepository.findById(d.getId()).orElseThrow();
    }

    private User user(String username, Role role, Department dept, Program program) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setDepartment(dept);
        if (role == Role.NORMAL) { u.setProgram(program); u.setCurrentLevel(1); u.setCurrentSemester(3); }
        return users.save(u);
    }

    private User reload(User u) { return users.findById(u.getId()).orElseThrow(); }

    @Test
    void aClassMovesToItsNextClassAndThoseShortOfTheRulesStay() {
        User ama = user("ama", Role.NORMAL, null, p5);
        User kofi = user("kofi", Role.NORMAL, null, p5);
        failing.add(kofi.getId());

        Map<String, Object> preview = promotion.preview(sa, p5.getId());
        assertThat(preview.get("targetName")).isEqualTo("Primary 6");
        assertThat(preview.get("eligible")).isEqualTo(1L);

        Map<String, Object> res = promotion.promoteClass(primaryHead, p5.getId(), null, true);   // a HOD's override is ignored
        assertThat(res.get("count")).isEqualTo(1);
        assertThat((List<?>) res.get("heldBack")).hasSize(1);
        assertThat(reload(ama).getProgram().getId()).isEqualTo(p6.getId());
        assertThat(reload(ama).getCurrentLevel()).isEqualTo(1);
        assertThat(reload(ama).getCurrentSemester()).isEqualTo(1);
        assertThat(reload(kofi).getProgram().getId()).isEqualTo(p5.getId());

        promotion.promoteClass(sa, p5.getId(), List.of(kofi.getId()), true);   // the Super Admin may override
        assertThat(reload(kofi).getProgram().getId()).isEqualTo(p6.getId());
    }

    @Test
    void theLastYearOfASectionGoesToTheNextSectionAndAFinalClassCompletesSchool() {
        User esi = user("esi", Role.NORMAL, null, p6);
        User yaw = user("yaw", Role.NORMAL, null, jhs3);

        promotion.promoteClass(sa, p6.getId(), null, false);
        assertThat(reload(esi).getProgram().getId()).isEqualTo(jhs1.getId());

        Map<String, Object> res = promotion.promoteClass(sa, jhs3.getId(), null, false);
        assertThat(res.get("leaves")).isEqualTo(true);
        verify(accounts).deactivate(eq(sa), eq(yaw.getId()), eq(ClassPromotionService.LEFT_REASON));
    }

    @Test
    void headsOnlyPromoteInTheirSectionAndOnlyTheSuperAdminPicksAClass() {
        User ama = user("ama", Role.NORMAL, null, p6);
        assertThatThrownBy(() -> promotion.promoteClass(jhsHead, p6.getId(), null, false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> promotion.moveStudent(primaryHead, ama.getId(), p5.getId(), false)).isInstanceOf(AccessDeniedException.class);

        // HOD promotion switched off by the Super Admin
        doThrow(new AccessDeniedException("off")).when(features).require(any(), eq(primaryHead));
        assertThatThrownBy(() -> promotion.preview(primaryHead, p6.getId())).isInstanceOf(AccessDeniedException.class);

        // The Super Admin can move a student back a class without the promotion rules
        failing.add(ama.getId());
        promotion.moveStudent(sa, ama.getId(), p5.getId(), false);
        assertThat(reload(ama).getProgram().getId()).isEqualTo(p5.getId());
        // …but a forward move is refused unless overridden
        assertThatThrownBy(() -> promotion.moveStudent(sa, ama.getId(), null, false)).isInstanceOf(ClassPromotionService.PromotionRefused.class);
        promotion.moveStudent(sa, ama.getId(), null, true);
        assertThat(reload(ama).getProgram().getId()).isEqualTo(p6.getId());
    }
}
