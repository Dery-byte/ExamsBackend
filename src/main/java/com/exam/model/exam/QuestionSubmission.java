package com.exam.model.exam;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@RequiredArgsConstructor

@Setter
@Getter
public class QuestionSubmission {

    private String quizId;
    private String tqid;
    private String questionNumber;
    private String question;
    private String studentAnswer;
    private double maxMarks;
    private String criteria;

    // Constructors
//    public QuestionSubmission() {}
//
//    public QuestionSubmission(String questionNumber, String question,
//                              String studentAnswer, int maxMarks, String criteria) {
//        this.questionNumber = questionNumber;
//        this.question = question;
//        this.studentAnswer = studentAnswer;
//        this.maxMarks = maxMarks;
//        this.criteria = criteria;
//    }

}