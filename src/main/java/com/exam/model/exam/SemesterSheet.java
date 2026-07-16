package com.exam.model.exam;

import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "semester_sheet")
public class SemesterSheet {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    private String level;

    private Integer semester;

    /** DRAFT, SUBMITTED, PUBLISHED */
    private String status = "DRAFT";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "class_teacher_id")
    private User classTeacher;

    private boolean restrictLecturerToAssignedCourses = true;

    @OneToMany(mappedBy = "semesterSheet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MarkSheetSection> sections = new ArrayList<>();

    @OneToMany(mappedBy = "semesterSheet", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<StudentCourseMark> courseMarks = new ArrayList<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "semester_sheet_courses",
            joinColumns = @JoinColumn(name = "sheet_id"),
            inverseJoinColumns = @JoinColumn(name = "course_id")
    )
    private List<Category> courses = new ArrayList<>();

    public SemesterSheet() {
    }

    public List<Category> getCourses() {
        return courses;
    }

    public void setCourses(List<Category> courses) {
        this.courses = courses;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Program getProgram() {
        return program;
    }

    public void setProgram(Program program) {
        this.program = program;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Integer getSemester() {
        return semester;
    }

    public void setSemester(Integer semester) {
        this.semester = semester;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public User getClassTeacher() {
        return classTeacher;
    }

    public void setClassTeacher(User classTeacher) {
        this.classTeacher = classTeacher;
    }

    public boolean isRestrictLecturerToAssignedCourses() {
        return restrictLecturerToAssignedCourses;
    }

    public void setRestrictLecturerToAssignedCourses(boolean restrictLecturerToAssignedCourses) {
        this.restrictLecturerToAssignedCourses = restrictLecturerToAssignedCourses;
    }

    public List<MarkSheetSection> getSections() {
        return sections;
    }

    public void setSections(List<MarkSheetSection> sections) {
        this.sections = sections;
    }

    public List<StudentCourseMark> getCourseMarks() {
        return courseMarks;
    }

    public void setCourseMarks(List<StudentCourseMark> courseMarks) {
        this.courseMarks = courseMarks;
    }
}
