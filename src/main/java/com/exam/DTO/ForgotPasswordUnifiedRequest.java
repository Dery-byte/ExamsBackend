package com.exam.DTO;

import lombok.Data;

/**
 * Unified forgot-password DTO.
 * Either `phone` or `email` must be supplied by the client.
 */
@Data
public class ForgotPasswordUnifiedRequest {
    private String phone;   // used when recovering via SMS
    private String email;   // used when recovering via email
}
