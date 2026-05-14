package com.exam.model.exam;
import com.exam.helper.StringListConverter;
import com.exam.model.User;
import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "answers")
public class Answer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long answerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)   // Link to User
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tq_id", nullable = false)     // Link to TheoryQuestions
    private TheoryQuestions theoryQuestion;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)     // Link to Quiz
    private Quiz quiz;

    // ✅ ADD THIS - Link to Report
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private Report report;

    @Column(nullable = false)
    private String  quesNo;




//    @Column(columnDefinition = "LONGTEXT")
//    private String studentAnswer;

//    @Column(nullable = false, columnDefinition = "TEXT")
@Column(nullable = false, length =70000000) // Large length value
private String studentAnswer;


    private double score;

    private double maxMarks;

    private String feedback;

    /**
     * Store keyMissed as JSON string in DB,
     * mapped to List<String> in Java
     */
    @Convert(converter = StringListConverter.class)
    private List<String> keyMissed;

    // === Getters & Setters ===


    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }

    public String getQuesNo() {
        return quesNo;
    }

    public void setQuesNo(String quesNo) {
        this.quesNo = quesNo;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public Long getAnswerId() {
        return answerId;
    }

    public void setAnswerId(Long answerId) {
        this.answerId = answerId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public TheoryQuestions getTheoryQuestion() {
        return theoryQuestion;
    }

    public void setTheoryQuestion(TheoryQuestions theoryQuestion) {
        this.theoryQuestion = theoryQuestion;
    }

    public String getStudentAnswer() {
        return studentAnswer;
    }

    public void setStudentAnswer(String studentAnswer) {
        this.studentAnswer = studentAnswer;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getMaxMarks() {
        return maxMarks;
    }

    public void setMaxMarks(double maxMarks) {
        this.maxMarks = maxMarks;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public List<String> getKeyMissed() {
        return keyMissed;
    }

    public void setKeyMissed(List<String> keyMissed) {
        this.keyMissed = keyMissed;
    }
}
