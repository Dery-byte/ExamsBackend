package com.exam.model.exam;

import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "student_course_mark")
public class StudentCourseMark {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_sheet_id", nullable = false)
    @JsonIgnore
    private SemesterSheet semesterSheet;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "course_id", nullable = false)
    private Category course;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalScore;

    @Column(length = 5)
    private String grade;

    @OneToMany(mappedBy = "studentCourseMark", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StudentSectionMark> sectionMarks = new ArrayList<>();

    public StudentCourseMark() {
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

    public User getStudent() {
        return student;
    }

    public void setStudent(User student) {
        this.student = student;
    }

    public Category getCourse() {
        return course;
    }

    public void setCourse(Category course) {
        this.course = course;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public List<StudentSectionMark> getSectionMarks() {
        return sectionMarks;
    }

    public void setSectionMarks(List<StudentSectionMark> sectionMarks) {
        this.sectionMarks = sectionMarks;
    }
}
