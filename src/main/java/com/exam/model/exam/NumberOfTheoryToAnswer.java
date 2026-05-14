package com.exam.model.exam;

import jakarta.persistence.*;

@Entity
@Table(name = "NumberOfTheoryToAnswer")
public class NumberOfTheoryToAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long AnswerTheoryId;
    @Column(nullable = false)
    private Integer TotalQuestToAnswer;

    @Column(nullable = false)
    private Integer TimeAllowed;

    public NumberOfTheoryToAnswer() {
    }

    public NumberOfTheoryToAnswer(Long answerTheoryId, Integer timeAllowed ,Integer totalQuestToAnswer,Quiz quiz) {
        AnswerTheoryId = answerTheoryId;
        TotalQuestToAnswer = totalQuestToAnswer;
        TimeAllowed = timeAllowed;
        this.quiz = quiz;
    }

    @ManyToOne(cascade = CascadeType.REMOVE)
    @JoinColumn(name = "quiz_q_id")
    private Quiz quiz;

    public Integer getTimeAllowed() {
        return TimeAllowed;
    }

    public void setTimeAllowed(Integer timeAllowed) {
        TimeAllowed = timeAllowed;
    }

    public Long getAnswerTheoryId() {
        return AnswerTheoryId;
    }

    public void setAnswerTheoryId(Long answerTheoryId) {
        AnswerTheoryId = answerTheoryId;
    }

    public Integer getTotalQuestToAnswer() {
        return TotalQuestToAnswer;
    }

    public void setTotalQuestToAnswer(Integer totalQuestToAnswer) {
        TotalQuestToAnswer = totalQuestToAnswer;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }
}
