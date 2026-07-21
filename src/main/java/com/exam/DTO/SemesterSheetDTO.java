package com.exam.DTO;

import java.math.BigDecimal;
import java.util.List;

public class SemesterSheetDTO {

    private Long id;
    private Long programId;
    private String programName;
    private String level;
    private Integer semester;
    private String status;
    private Long classTeacherId;
    private String classTeacherName;
    private boolean restrictLecturerToAssignedCourses;
    private int enrolledStudentCount;
    private Long courseId;
    private String courseName;
    private List<SectionDTO> sections;
    private List<StudentMarkDTO> studentMarks;

    public static class SectionDTO {
        private Long id;
        private String sectionName;
        private BigDecimal maxScore;
        private boolean deletable = true;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSectionName() { return sectionName; }
        public void setSectionName(String sectionName) { this.sectionName = sectionName; }
        public BigDecimal getMaxScore() { return maxScore; }
        public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
        public boolean isDeletable() { return deletable; }
        public void setDeletable(boolean deletable) { this.deletable = deletable; }
    }

    public static class StudentMarkDTO {
        private Long studentId;
        private String studentName;
        private String username;
        private List<CourseMarkDTO> courseMarks;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }
        public String getStudentName() { return studentName; }
        public void setStudentName(String studentName) { this.studentName = studentName; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public List<CourseMarkDTO> getCourseMarks() { return courseMarks; }
        public void setCourseMarks(List<CourseMarkDTO> courseMarks) { this.courseMarks = courseMarks; }
    }

    public static class CourseMarkDTO {
        private Long courseMarkId;
        private Long courseId;
        private String courseTitle;
        private String courseCode;
        private BigDecimal totalScore;
        private String grade;
        private List<SectionMarkDTO> sectionMarks;

        public Long getCourseMarkId() { return courseMarkId; }
        public void setCourseMarkId(Long courseMarkId) { this.courseMarkId = courseMarkId; }
        public Long getCourseId() { return courseId; }
        public void setCourseId(Long courseId) { this.courseId = courseId; }
        public String getCourseTitle() { return courseTitle; }
        public void setCourseTitle(String courseTitle) { this.courseTitle = courseTitle; }
        public String getCourseCode() { return courseCode; }
        public void setCourseCode(String courseCode) { this.courseCode = courseCode; }
        public BigDecimal getTotalScore() { return totalScore; }
        public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
        public List<SectionMarkDTO> getSectionMarks() { return sectionMarks; }
        public void setSectionMarks(List<SectionMarkDTO> sectionMarks) { this.sectionMarks = sectionMarks; }
    }

    public static class SectionMarkDTO {
        private Long sectionMarkId;
        private Long sectionId;
        private BigDecimal scoreObtained;

        public Long getSectionMarkId() { return sectionMarkId; }
        public void setSectionMarkId(Long sectionMarkId) { this.sectionMarkId = sectionMarkId; }
        public Long getSectionId() { return sectionId; }
        public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
        public BigDecimal getScoreObtained() { return scoreObtained; }
        public void setScoreObtained(BigDecimal scoreObtained) { this.scoreObtained = scoreObtained; }
    }

    // Getters and Setters for SemesterSheetDTO

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProgramId() { return programId; }
    public void setProgramId(Long programId) { this.programId = programId; }
    public String getProgramName() { return programName; }
    public void setProgramName(String programName) { this.programName = programName; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getClassTeacherId() { return classTeacherId; }
    public void setClassTeacherId(Long classTeacherId) { this.classTeacherId = classTeacherId; }
    public String getClassTeacherName() { return classTeacherName; }
    public void setClassTeacherName(String classTeacherName) { this.classTeacherName = classTeacherName; }
    public boolean isRestrictLecturerToAssignedCourses() { return restrictLecturerToAssignedCourses; }
    public void setRestrictLecturerToAssignedCourses(boolean restrictLecturerToAssignedCourses) { this.restrictLecturerToAssignedCourses = restrictLecturerToAssignedCourses; }
    public int getEnrolledStudentCount() { return enrolledStudentCount; }
    public void setEnrolledStudentCount(int enrolledStudentCount) { this.enrolledStudentCount = enrolledStudentCount; }
    public List<SectionDTO> getSections() { return sections; }
    public void setSections(List<SectionDTO> sections) { this.sections = sections; }
    public List<StudentMarkDTO> getStudentMarks() { return studentMarks; }
    public void setStudentMarks(List<StudentMarkDTO> studentMarks) { this.studentMarks = studentMarks; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }
}
