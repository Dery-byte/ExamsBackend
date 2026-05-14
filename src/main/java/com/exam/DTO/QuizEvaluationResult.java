package com.exam.DTO;

public class QuizEvaluationResult {

    private double marksGot;
    private int correctAnswers;
    private int attempted;
    private double maxMarks;

    // constructors
    public QuizEvaluationResult(double marksGot, int correctAnswers, int attempted, double maxMarks) {
        this.marksGot = marksGot;
        this.correctAnswers = correctAnswers;
        this.attempted = attempted;
        this.maxMarks = maxMarks;
    }

    // getters & setters
}
