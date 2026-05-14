package com.exam.DTO;

import java.util.List;

public class GPTResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<GPTChoice> choices;
    private GPTUsage usage;

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<GPTChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<GPTChoice> choices) {
        this.choices = choices;
    }

    public GPTUsage getUsage() {
        return usage;
    }

    public void setUsage(GPTUsage usage) {
        this.usage = usage;
    }

}