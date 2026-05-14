package com.exam.model.exam;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;

//@Entity
//@Table
public class GradeResponse {
    private String evaluation;
    public GradeResponse(String evaluation) {
        this.evaluation = evaluation;
    }

    // Getters and Setters


    public String getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(String evaluation) {
        this.evaluation = evaluation;
    }
}