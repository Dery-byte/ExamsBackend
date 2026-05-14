package com.exam.DTO;

public class GPTMessage {
    private String role; // "system", "user", or "assistant"
    private String content;

    // Constructors
    public GPTMessage() {}

    public GPTMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // Getters and Setters
    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}