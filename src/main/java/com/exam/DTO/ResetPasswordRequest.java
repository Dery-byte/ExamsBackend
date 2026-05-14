package com.exam.DTO;


import lombok.Data;

@Data
public class ResetPasswordRequest {

    private String token;
    private String newPassword;
}
