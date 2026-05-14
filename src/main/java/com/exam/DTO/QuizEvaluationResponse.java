package com.exam.DTO;

import com.exam.model.exam.QuestionEvaluationResult;

import java.util.List;

public class QuizEvaluationResponse {

    private Long reportId;
    private Long userId;
    private String username;
    private Long quizId;
    private List<QuestionEvaluationResult> results;

    private Summary summary;

    public static class Summary {
        private double totalScore;
        private double totalMaxMarks;
        private double percentage;
        private String grade;
        private int questionsAnswered;
        private int answersSaved;

        // Getters and Setters
        public double getTotalScore() { return totalScore; }
        public void setTotalScore(double totalScore) { this.totalScore = totalScore; }
        public double getTotalMaxMarks() { return totalMaxMarks; }
        public void setTotalMaxMarks(double totalMaxMarks) { this.totalMaxMarks = totalMaxMarks; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
        public int getQuestionsAnswered() { return questionsAnswered; }
        public void setQuestionsAnswered(int questionsAnswered) { this.questionsAnswered = questionsAnswered; }
        public int getAnswersSaved() { return answersSaved; }
        public void setAnswersSaved(int answersSaved) { this.answersSaved = answersSaved; }
    }

    // Getters and Setters
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getQuizId() { return quizId; }
    public void setQuizId(Long quizId) { this.quizId = quizId; }
    public List<QuestionEvaluationResult> getResults() { return results; }
    public void setResults(List<QuestionEvaluationResult> results) { this.results = results; }
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }
}
