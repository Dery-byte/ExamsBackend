package com.exam.model.exam;

import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity(name="report")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(precision = 10, scale = 1)
    private BigDecimal marks;

    private BigDecimal marksB;

    private BigDecimal maxScoreSectionB;

    @Column(name = "progress")
    private String progress = "Completed";

    // ✅ NEW: Additional fields for better tracking
    @Column(name = "percentage")
    private Double percentage;

    @Column(name = "grade", length = 5)
    private String grade;

    @Column(name = "submission_date")
    private LocalDateTime submissionDate;

    @Column(name = "evaluation_method", length = 20)
    private String evaluationMethod;

    // ✅ RELATIONSHIPS

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    // ✅ NEW: One Report has Many Answers
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore  // Prevent circular reference in JSON serialization
    private List<Answer> answers = new ArrayList<>();



    // ========== Constructors ==========

    public Report() {
        this.submissionDate = LocalDateTime.now();
        this.progress = "Completed";
    }

    public Report(Long id, BigDecimal marks, BigDecimal marksB, Quiz quiz, User user, String progress) {
        this();
        this.id = id;
        this.marks = marks;
        this.marksB = marksB;
        this.quiz = quiz;
        this.user = user;
        this.progress = progress;
        calculatePercentageAndGrade();
    }

    // ========== Helper Methods ==========

    public void calculatePercentageAndGrade() {
        if (marksB != null && marksB.doubleValue() > 0) {
            this.percentage = (marksB.doubleValue() / maxScoreSectionB.doubleValue()) * 100.0;
            this.grade = calculateGrade(this.percentage);
        }
    }

    private String calculateGrade(Double percentage) {
        if (percentage >= 90) return "A+";
        if (percentage >= 80) return "A";
        if (percentage >= 70) return "B";
        if (percentage >= 60) return "C";
        if (percentage >= 50) return "D";
        return "F";
    }

    /**
     * Helper method to add answer to report
     */
    public void addAnswer(Answer answer) {
        answers.add(answer);
        answer.setReport(this);
    }

    /**
     * Helper method to remove answer from report
     */
    public void removeAnswer(Answer answer) {
        answers.remove(answer);
        answer.setReport(null);
    }

    // ========== Getters and Setters ==========

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getMarks() {
        return marks;
    }

    public void setMarks(BigDecimal marks) {
        this.marks = marks;
    }

    public BigDecimal getMarksB() {
        return marksB;
    }

    public void setMarksB(BigDecimal marksB) {
        this.marksB = marksB;
    }

    public String getProgress() {
        return progress;
    }

    public void setProgress(String progress) {
        this.progress = progress;
    }

    public Double getPercentage() {
        return percentage;
    }

    public void setPercentage(Double percentage) {
        this.percentage = percentage;
    }

    public BigDecimal getMaxScoreSectionB() {
        return maxScoreSectionB;
    }

    public void setMaxScoreSectionB(BigDecimal maxScoreSectionB) {
        this.maxScoreSectionB = maxScoreSectionB;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDateTime getSubmissionDate() {
        return submissionDate;
    }

    public void setSubmissionDate(LocalDateTime submissionDate) {
        this.submissionDate = submissionDate;
    }

    public String getEvaluationMethod() {
        return evaluationMethod;
    }

    public void setEvaluationMethod(String evaluationMethod) {
        this.evaluationMethod = evaluationMethod;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public List<Answer> getAnswers() {
        return answers;
    }

    public void setAnswers(List<Answer> answers) {
        this.answers = answers;
    }
}




















