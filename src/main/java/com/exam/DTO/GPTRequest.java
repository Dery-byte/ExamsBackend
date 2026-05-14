package com.exam.DTO;

// ============== REQUEST MODELS ==============

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class GPTRequest {
    private String model;
    private List<GPTMessage> messages;
    private Double temperature;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    // Constructors
    public GPTRequest() {}

    public GPTRequest(String model, List<GPTMessage> messages) {
        this.model = model;
        this.messages = messages;
    }

    // Getters and Setters
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<GPTMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<GPTMessage> messages) {
        this.messages = messages;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}
