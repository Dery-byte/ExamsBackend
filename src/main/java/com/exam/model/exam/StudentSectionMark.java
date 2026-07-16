package com.exam.model.exam;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "student_section_mark")
public class StudentSectionMark {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_course_mark_id", nullable = false)
    @JsonIgnore
    private StudentCourseMark studentCourseMark;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "section_id", nullable = false)
    private MarkSheetSection section;

    @Column(precision = 10, scale = 2)
    private BigDecimal scoreObtained;

    public StudentSectionMark() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public StudentCourseMark getStudentCourseMark() {
        return studentCourseMark;
    }

    public void setStudentCourseMark(StudentCourseMark studentCourseMark) {
        this.studentCourseMark = studentCourseMark;
    }

    public MarkSheetSection getSection() {
        return section;
    }

    public void setSection(MarkSheetSection section) {
        this.section = section;
    }

    public BigDecimal getScoreObtained() {
        return scoreObtained;
    }

    public void setScoreObtained(BigDecimal scoreObtained) {
        this.scoreObtained = scoreObtained;
    }
}
