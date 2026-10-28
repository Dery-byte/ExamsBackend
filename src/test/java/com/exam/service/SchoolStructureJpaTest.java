package com.exam.service;

import com.exam.DTO.DepartmentDTO;
import com.exam.DTO.ProgramDTO;
import com.exam.model.academic.SchoolWords;
import com.exam.model.academic.SystemMode;
import com.exam.repository.ProgramRepository;
import com.exam.service.reports.ReportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schools on H2: departments are sections kept in order, each running some years; programmes are
 * one-year classes in a year of their section, and each class promotes into the next year's class
 * (same stream), the one chosen, or out of the school. Universities keep programmes with several
 * levels and nothing is numbered for them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:schoolstructure;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({ProgramService.class, DepartmentService.class})
class SchoolStructureJpaTest {

    @Autowired ProgramService programs;
    @Autowired DepartmentService departments;
    @Autowired ProgramRepository programRepository;

    @BeforeEach
    void basicSchool() { SystemMode.setCurrent(SystemMode.BASIC); }

    @AfterEach
    void backToUniversity() { SystemMode.setCurrent(SystemMode.UNIVERSITY); }

    private Long section(String name, String code) { return section(name, code, null); }

    private Long section(String name, String code, Integer years) {
        return departments.createDepartment(DepartmentDTO.builder().name(name).code(code).durationYears(years).build()).getId();
    }

    private ProgramDTO cls(Long section, String name, String code) { return cls(section, name, code, null); }

    private ProgramDTO cls(Long section, String name, String code, Integer year) {
        return programs.createProgram(ProgramDTO.builder().name(name).code(code).departmentId(section).sectionYear(year)
                .durationYears(4).semestersPerLevel(Map.of(1, 3)).build());
    }

    private ProgramDTO find(String name) {
        return programs.getAllPrograms().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sectionsAreNumberedInTheOrderTheyAreAddedAndCanBeReordered() {
        Long jhs = section("Junior High School", "JHS");
        Long primary = section("Primary School", "PRI");
        assertThat(departments.getAllDepartments()).extracting(DepartmentDTO::getName)
                .containsExactly("Junior High School", "Primary School");

        departments.reorder(List.of(primary, jhs));
        assertThat(departments.getAllDepartments()).extracting(DepartmentDTO::getName)
                .containsExactly("Primary School", "Junior High School");
        assertThat(departments.getAllDepartments()).extracting(DepartmentDTO::getSortOrder).containsExactly(1, 2);
    }

    @Test
    void aClassIsOneYearAndPromotesIntoTheNextClassAcrossSections() {
        Long primary = section("Primary School", "PRI");
        Long jhs = section("Junior High School", "JHS");
        cls(jhs, "JHS 1", "JHS1");
        cls(primary, "Primary 5", "P5");
        cls(primary, "Primary 6", "P6");
        cls(jhs, "JHS 2", "JHS2");

        ProgramDTO p5 = find("Primary 5");
        assertThat(p5.getDurationYears()).isEqualTo(1);
        assertThat(p5.getConfiguredLevels()).containsExactly(1);
        assertThat(p5.getSemestersPerLevel()).containsEntry(1, 3);

        // Section order first, then the order the classes were added in
        assertThat(programs.getAllPrograms()).extracting(ProgramDTO::getName)
                .containsExactly("Primary 5", "Primary 6", "JHS 1", "JHS 2");
        assertThat(p5.getPromotesToName()).isEqualTo("Primary 6");
        assertThat(find("Primary 6").getPromotesToName()).isEqualTo("JHS 1");   // last of a section → first of the next
        assertThat(find("JHS 2").getPromotesToId()).isNull();                      // last class: they leave
    }

    @Test
    void theChosenNextClassTheFinalClassAndDisabledClassesAreRespected() {
        Long primary = section("Primary School", "PRI");
        Long jhs = section("Junior High School", "JHS");
        ProgramDTO p6 = cls(primary, "Primary 6", "P6");
        ProgramDTO j1 = cls(jhs, "JHS 1", "JHS1");
        ProgramDTO j1b = cls(jhs, "JHS 1B", "JHS1B");

        // A disabled class is skipped by "next in order"
        programs.toggleProgram(j1.getId());
        assertThat(find("Primary 6").getPromotesToName()).isEqualTo("JHS 1B");

        // Chosen next class wins, even a disabled one
        programs.updateProgram(p6.getId(), ProgramDTO.builder().nextProgramId(j1.getId()).build());
        assertThat(find("Primary 6").getPromotesToName()).isEqualTo("JHS 1");
        assertThat(find("Primary 6").getNextProgramId()).isEqualTo(j1.getId());

        // A final class sends its students out of the school
        programs.updateProgram(p6.getId(), ProgramDTO.builder().finalClass(true).build());
        assertThat(find("Primary 6").getPromotesToId()).isNull();
        assertThat(find("Primary 6").getFinalClass()).isTrue();

        // Deleting the chosen class falls back to "next in order"
        programs.updateProgram(p6.getId(), ProgramDTO.builder().nextProgramId(j1b.getId()).build());
        programs.deleteProgram(j1b.getId());
        assertThat(programRepository.findById(p6.getId()).orElseThrow().getNextProgramId()).isNull();

        assertThatThrownBy(() -> programs.updateProgram(p6.getId(), ProgramDTO.builder().nextProgramId(p6.getId()).build()))
                .hasMessageContaining("itself");
    }

    @Test
    void classesCanBeReorderedAndNamesAreUniqueWithinASection() {
        Long primary = section("Primary School", "PRI");
        Long jhs = section("Junior High School", "JHS");
        // Streams of one year: their order is the Super Admin's
        ProgramDTO a = cls(primary, "Primary 1 B", "P1B", 1);
        ProgramDTO b = cls(primary, "Primary 1 A", "P1A", 1);

        programs.reorder(List.of(b.getId(), a.getId()));
        assertThat(programs.getProgramsByDepartment(primary)).extracting(ProgramDTO::getName).containsExactly("Primary 1 A", "Primary 1 B");

        assertThatThrownBy(() -> cls(primary, " primary 1 a ", "X")).hasMessageContaining("already a class called");
        assertThat(cls(jhs, "Primary 1 A", "X").getId()).isNotNull();   // another section may use the name
    }

    @Test
    void sectionsRunSomeYearsAndClassesPromoteYearByYearKeepingTheirStream() {
        Long primary = section("Primary School", "PRI", 6);
        Long jhs = section("Junior High School", "JHS", 3);
        assertThat(departments.getAllDepartments()).extracting(DepartmentDTO::getDurationYears).containsExactly(6, 3);

        cls(jhs, "JHS 2 Gold", "J2G", 2);
        cls(jhs, "JHS 1 Gold", "J1G", 1);
        cls(jhs, "JHS 1 Silver", "J1S", 1);
        cls(jhs, "JHS 2 Silver", "J2S", 2);
        cls(primary, "Primary 6", "P6", 6);
        cls(primary, "Primary 5", "P5", 5);
        ProgramDTO j3 = cls(jhs, "JHS 3", "J3");   // no year given: the year after the section's highest

        assertThat(j3.getSectionYear()).isEqualTo(3);
        assertThat(j3.getSectionYears()).isEqualTo(3);
        // Years order the classes, whatever order they were added in
        assertThat(programs.getAllPrograms()).extracting(ProgramDTO::getName)
                .containsExactly("Primary 5", "Primary 6", "JHS 1 Gold", "JHS 1 Silver", "JHS 2 Gold", "JHS 2 Silver", "JHS 3");
        assertThat(find("Primary 5").getPromotesToName()).isEqualTo("Primary 6");
        assertThat(find("Primary 6").getPromotesToName()).isEqualTo("JHS 1 Gold");     // last year → first year of the next section
        assertThat(find("JHS 1 Gold").getPromotesToName()).isEqualTo("JHS 2 Gold");    // not its fellow stream JHS 1 Silver
        assertThat(find("JHS 1 Silver").getPromotesToName()).isEqualTo("JHS 2 Silver");
        assertThat(find("JHS 2 Silver").getPromotesToName()).isEqualTo("JHS 3");       // streams join in one class
        assertThat(find("JHS 3").getPromotesToId()).isNull();

        assertThatThrownBy(() -> cls(jhs, "JHS 4", "J4", 4)).hasMessageContaining("years 1 to 3");
        assertThatThrownBy(() -> departments.updateDepartment(jhs, DepartmentDTO.builder().durationYears(2).build()))
                .hasMessageContaining("has classes in year 3");
        assertThat(departments.updateDepartment(primary, DepartmentDTO.builder().durationYears(7).build()).getDurationYears()).isEqualTo(7);
        assertThat(ProgramService.streamOf("SHS 1 Science A")).isEqualTo("science a");
    }

    @Test
    void universitiesKeepTheirYearsAndNothingIsNumbered() {
        SystemMode.setCurrent(SystemMode.UNIVERSITY);
        Long cs = section("Computer Science", "CS");
        ProgramDTO bsc = cls(cs, "BSc Computer Science", "BCS");
        assertThat(bsc.getDurationYears()).isEqualTo(4);
        assertThat(bsc.getConfiguredLevels()).containsExactly(100, 200, 300, 400);
        assertThat(bsc.getPromotesToId()).isNull();
        assertThat(bsc.getSortOrder()).isNull();
        assertThat(departments.getAllDepartments().get(0).getSortOrder()).isNull();
        assertThat(departments.getAllDepartments().get(0).getDurationYears()).isNull();
    }

    @Test
    void reportsUseSchoolWordsAndDropALevelColumnThatOnlySaysOne() {
        ReportResult r = new ReportResult("x", "Department comparison", "Fees for each programme and year");
        r.scope("Department: Primary School").note("Owed per programme, year and semester.");
        r.stat("Programmes with students", 3);
        ReportResult.Table one = r.table("a", "By programme and year").text("department", "Department").text("programme", "Class").col("level", "Year", ReportResult.INT);
        one.add("Primary School", "Primary 1", 1);
        one.add("Primary School", "Primary 2", null);
        ReportResult.Table two = r.table("b", "Old programmes").col("level", "Year", ReportResult.INT);
        two.add(2);

        r.forSchools();
        assertThat(r.getTitle()).isEqualTo("Section comparison");
        assertThat(r.getDescription()).isEqualTo("Fees for each class");
        assertThat(r.getScope()).containsExactly("Section: Primary School");
        assertThat(r.getNotes()).containsExactly("Owed per class and semester.");
        assertThat(r.getSummary().get(0).label()).isEqualTo("Classes with students");
        assertThat(one.getTitle()).isEqualTo("By class");
        assertThat(one.getColumns()).extracting(ReportResult.Column::label).containsExactly("Section", "Class");
        assertThat(two.getColumns()).extracting(ReportResult.Column::key).containsExactly("level");   // a real level stays

        SystemMode.setCurrent(SystemMode.UNIVERSITY);
        assertThat(SchoolWords.apply("Department and programme")).isEqualTo("Department and programme");
    }
}
