package com.exam.DTO;


import lombok.Data;

@Data
public class TheoryProgressDTO {
    private String quesNo;
    private String givenAnswer;
    public TheoryProgressDTO() {}
    public TheoryProgressDTO(String quesNo, String givenAnswer) {
        this.quesNo = quesNo;
        this.givenAnswer = givenAnswer;
    }
    public String getQuesNo() {
        return quesNo;
    }

    public void setQuesNo(String quesNo) {
        this.quesNo = quesNo;
    }

    public String getGivenAnswer() {
        return givenAnswer;
    }

    public void setGivenAnswer(String givenAnswer) {
        this.givenAnswer = givenAnswer;
    }
}