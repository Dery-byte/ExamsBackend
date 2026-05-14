package com.exam.DTO;

import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ReportDTO {
    private Long id;
    private BigDecimal marks;
    private BigDecimal marksB;
    private BigDecimal maxScoreSectionB;
    private String progress;
    private Double percentage;
    private String grade;
    private LocalDateTime submissionDate;
    private String evaluationMethod;

    // Only Quiz and Category
    private QuizDTO quiz;

    // Nested DTOs
    public static class QuizDTO {
        private Long qId;
        private String title;
        private String description;
        private Double maxMarks;
        private String quizTime;
        private Integer numberOfQuestions;
        private boolean active;
        private String status;
        private String quizType;
        private CategoryDTO category;

        // Constructors, Getters, Setters
        public QuizDTO(Quiz quiz) {
            this.qId = quiz.getqId();
            this.title = quiz.getTitle();
            this.description = quiz.getDescription();
            this.maxMarks = quiz.getMaxMarks();
            this.quizTime = quiz.getQuizTime();
            this.numberOfQuestions = quiz.getNumberOfQuestions();
            this.active = quiz.isActive();
            this.status = quiz.getStatus().toString();
            this.quizType = quiz.getQuizType().toString();
            this.category = quiz.getCategory() != null ? new CategoryDTO(quiz.getCategory()) : null;
        }

        // Getters and Setters
        public Long getqId() { return qId; }
        public void setqId(Long qId) { this.qId = qId; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Double getMaxMarks() {
            return maxMarks;
        }

        public void setMaxMarks(Double maxMarks) {
            this.maxMarks = maxMarks;
        }

        public String getQuizTime() { return quizTime; }
        public void setQuizTime(String quizTime) { this.quizTime = quizTime; }

        public Integer getNumberOfQuestions() {
            return numberOfQuestions;
        }

        public void setNumberOfQuestions(Integer numberOfQuestions) {
            this.numberOfQuestions = numberOfQuestions;
        }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getQuizType() { return quizType; }
        public void setQuizType(String quizType) { this.quizType = quizType; }

        public CategoryDTO getCategory() { return category; }
        public void setCategory(CategoryDTO category) { this.category = category; }
    }

    public static class CategoryDTO {
        private Long cid;
        private String level;
        private String title;
        private String description;
        private String courseCode;

        public CategoryDTO(Category category) {
            this.cid = category.getCid();
            this.level = category.getLevel();
            this.title = category.getTitle();
            this.description = category.getDescription();
            this.courseCode = category.getCourseCode();
        }

        // Getters and Setters
        public Long getCid() { return cid; }
        public void setCid(Long cid) { this.cid = cid; }

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getCourseCode() { return courseCode; }
        public void setCourseCode(String courseCode) { this.courseCode = courseCode; }
    }

    // Main DTO Constructor
    public ReportDTO(Report report) {
        this.id = report.getId();
        this.marks = report.getMarks();
        this.marksB = report.getMarksB();
        this.maxScoreSectionB = report.getMaxScoreSectionB();
        this.progress = report.getProgress();
        this.percentage = report.getPercentage();
        this.grade = report.getGrade();
        this.submissionDate = report.getSubmissionDate();
        this.evaluationMethod = report.getEvaluationMethod();
        this.quiz = report.getQuiz() != null ? new QuizDTO(report.getQuiz()) : null;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BigDecimal getMarks() { return marks; }
    public void setMarks(BigDecimal marks) { this.marks = marks; }

    public BigDecimal getMarksB() { return marksB; }
    public void setMarksB(BigDecimal marksB) { this.marksB = marksB; }

    public BigDecimal getMaxScoreSectionB() { return maxScoreSectionB; }
    public void setMaxScoreSectionB(BigDecimal maxScoreSectionB) { this.maxScoreSectionB = maxScoreSectionB; }

    public String getProgress() { return progress; }
    public void setProgress(String progress) { this.progress = progress; }

    public Double getPercentage() { return percentage; }
    public void setPercentage(Double percentage) { this.percentage = percentage; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public LocalDateTime getSubmissionDate() { return submissionDate; }
    public void setSubmissionDate(LocalDateTime submissionDate) { this.submissionDate = submissionDate; }

    public String getEvaluationMethod() { return evaluationMethod; }
    public void setEvaluationMethod(String evaluationMethod) { this.evaluationMethod = evaluationMethod; }

    public QuizDTO getQuiz() { return quiz; }
    public void setQuiz(QuizDTO quiz) { this.quiz = quiz; }
}