package com.exam.model.exam;

import jakarta.persistence.*;


@Entity
@Table(name = "theory_questions")
public class TheoryQuestions {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long TqId;

    @Column(nullable = false)
    private String  quesNo;

//    # MAKE SURE THE THEORY IS ONLY UPLOADABLE NOTHING ELSE.
    @Column(nullable = false, length = 5000)
    private String question;

    @Transient
    private String answer;
    @Column(nullable = false)
    private String marks;


    @Column(nullable = false, length = 5000)
    private String evaluationCriteria;



    // NEW FIELD: Mark question as compulsory
    @Column(nullable = false)
    private Boolean isCompulsory = false;

    @ManyToOne(cascade = CascadeType.ALL)
//    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    public TheoryQuestions() {
    }
    public TheoryQuestions(Long tqId,String  quesNo, String question, String marks,String evaluationCriteria,String answer,Boolean isCompulsory, Quiz quiz) {
        TqId = tqId;
        this.question = question;
        this.marks = marks;
        this.evaluationCriteria = evaluationCriteria;
        this.quiz = quiz;
        this.answer = answer;
        this.isCompulsory = isCompulsory != null ? isCompulsory : false;
        this.quesNo = quesNo;
    }


    public String getEvaluationCriteria() {
        return evaluationCriteria;
    }

    public void setEvaluationCriteria(String evaluationCriteria) {
        this.evaluationCriteria = evaluationCriteria;
    }

    public String getQuesNo() {
        return quesNo;
    }

    public void setQuesNo(String quesNo) {
        this.quesNo = quesNo;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Long getTqId() {
        return TqId;
    }

    public void setTqId(Long tqId) {
        TqId = tqId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getMarks() {
        return marks;
    }

    public void setMarks(String marks) {
        this.marks = marks;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    // NEW GETTER AND SETTER
    public Boolean getIsCompulsory() {
        return isCompulsory;
    }

    public void setIsCompulsory(Boolean isCompulsory) {
        this.isCompulsory = isCompulsory;
    }


}
