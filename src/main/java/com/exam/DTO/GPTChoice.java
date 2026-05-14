package com.exam.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GPTChoice {
    private Integer index;
    private GPTMessage message;

    @JsonProperty("finish_reason")
    private String finishReason;

    // Getters and Setters
    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public GPTMessage getMessage() {
        return message;
    }

    public void setMessage(GPTMessage message) {
        this.message = message;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }
}
