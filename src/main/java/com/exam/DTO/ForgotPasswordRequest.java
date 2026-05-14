package com.exam.DTO;

import java.util.List;

public class ForgotPasswordRequest {
    private List<String> recipient;

    public List<String> getRecipient() {
        return recipient;
    }

    public void setRecipient(List<String> recipient) {
        this.recipient = recipient;
    }
}