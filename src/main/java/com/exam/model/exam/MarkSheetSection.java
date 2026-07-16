package com.exam.model.exam;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "mark_sheet_section")
public class MarkSheetSection {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_sheet_id", nullable = false)
    @JsonIgnore
    private SemesterSheet semesterSheet;

    private String sectionName;

    @Column(precision = 10, scale = 2)
    private BigDecimal maxScore;

    public MarkSheetSection() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SemesterSheet getSemesterSheet() {
        return semesterSheet;
    }

    public void setSemesterSheet(SemesterSheet semesterSheet) {
        this.semesterSheet = semesterSheet;
    }

    public String getSectionName() {
        return sectionName;
    }

    public void setSectionName(String sectionName) {
        this.sectionName = sectionName;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(BigDecimal maxScore) {
        this.maxScore = maxScore;
    }
}
