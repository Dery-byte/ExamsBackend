package com.exam.service;

import com.exam.DTO.SemesterSheetDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Renders the transcript and report-card templates end to end, so template expression errors fail here. */
class AcademicPdfRenderTest {

    private PdfReportService pdf;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        pdf = new PdfReportService();
        ReflectionTestUtils.setField(pdf, "templateEngine", engine);
    }

    private static boolean isPdf(byte[] b) {
        return b.length > 1000 && new String(b, 0, 5).equals("%PDF-");
    }

    @Test
    void rendersTranscript() throws Exception {
        Map<String, Object> course = new LinkedHashMap<>();
        course.put("courseCode", "CS101"); course.put("courseTitle", "Intro & Basics"); course.put("creditUnits", 3);
        course.put("score", new BigDecimal("72.5")); course.put("grade", "B"); course.put("gradePoint", new BigDecimal("3.0"));
        course.put("passed", true); course.put("attempt", 2);
        Map<String, Object> sem = new LinkedHashMap<>();
        sem.put("session", "2025/2026"); sem.put("level", "100"); sem.put("semester", 1);
        sem.put("courses", List.of(course)); sem.put("creditUnits", 3); sem.put("creditPoints", new BigDecimal("9.0"));
        sem.put("gpa", new BigDecimal("3.00")); sem.put("cgpa", new BigDecimal("3.00"));

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("studentName", "Ama Mensah"); t.put("username", "STD001"); t.put("program", "BSc CS");
        t.put("department", "Computer Science"); t.put("currentLevel", 200);
        t.put("semesters", List.of(sem)); t.put("cgpa", new BigDecimal("3.00")); t.put("maxGradePoint", new BigDecimal("4.0"));
        t.put("totalCreditUnits", 3); t.put("creditsEarned", 3); t.put("degreeClass", "Second Class (Upper Division)");
        t.put("outstanding", List.of(Map.of("courseCode", "CS201"), Map.of("courseCode", "CS202")));

        assertThat(isPdf(pdf.generateTranscriptPdf(t, "STD001"))).isTrue();
    }

    @Test
    void rendersEmptyTranscript() throws Exception {
        // Same keys AcademicRecordService#transcript always sets, with the nulls a new student has
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("studentName", "New Student"); t.put("username", "STD002");
        t.put("program", null); t.put("department", null); t.put("currentLevel", null);
        t.put("semesters", List.of()); t.put("outstanding", List.of());
        t.put("totalCreditUnits", 0); t.put("creditsEarned", 0);
        t.put("cgpa", null); t.put("maxGradePoint", new BigDecimal("4.0")); t.put("degreeClass", null);
        assertThat(isPdf(pdf.generateTranscriptPdf(t, "STD002"))).isTrue();
    }

    @Test
    void rendersReportCardsWithGpa() throws Exception {
        SemesterSheetDTO.SectionDTO sec = new SemesterSheetDTO.SectionDTO();
        sec.setId(1L); sec.setSectionName("Exam"); sec.setMaxScore(new BigDecimal("100"));
        SemesterSheetDTO.SectionMarkDTO sm = new SemesterSheetDTO.SectionMarkDTO();
        sm.setSectionId(1L); sm.setScoreObtained(new BigDecimal("40"));
        SemesterSheetDTO.CourseMarkDTO cm = new SemesterSheetDTO.CourseMarkDTO();
        cm.setCourseCode("CS101"); cm.setCourseTitle("Intro"); cm.setTotalScore(new BigDecimal("40"));
        cm.setGrade("F"); cm.setGradePoint(BigDecimal.ZERO); cm.setCreditUnits(3); cm.setSectionMarks(List.of(sm));

        Map<String, Object> report = new HashMap<>();
        report.put("studentName", "Ama"); report.put("username", "STD001"); report.put("programName", "BSc CS");
        report.put("level", "100"); report.put("semester", 1); report.put("sessionName", "2025/2026");
        report.put("sections", new ArrayList<>(List.of(sec))); report.put("courseMarks", List.of(cm));
        report.put("generatedDate", "27 Sep 2026");
        report.put("creditUnits", 3); report.put("gpa", new BigDecimal("0.00")); report.put("cgpa", new BigDecimal("0.00"));

        assertThat(isPdf(pdf.generateSemesterReportCardPdf(report, "STD001"))).isTrue();
        assertThat(isPdf(pdf.generateCombinedSemesterReportCardPdf(List.of(report), "STD001"))).isTrue();
    }
}
